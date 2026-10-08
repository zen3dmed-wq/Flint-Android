package app.flint.prototype.vpn

import android.app.Notification
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.AtomicFile
import app.flint.prototype.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.xray.Xray
import org.json.JSONObject

/** Connection lifetime is owned here, never by a visible screen or its binding. */
class FlintVpnService : VpnService() {
    private val nativeDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Flint VPN engine")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val generation = AtomicLong(0)
    private val clients = mutableSetOf<Messenger>()
    private val native = Xray.instance
    private var stateJob: Job? = null
    private var monitorJob: Job? = null
    private val widgetUpdates = Channel<String>(Channel.CONFLATED)
    private val widgetLock = Mutex()
    private var lastWidgetState = ""
    private var forgetSaved = false
    private var nativeStarted = false
    private var destroyed = false
    private var foreground = false
    private var lastStartId = 0
    private var pendingGeneration: Long? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var snapshot = Snapshot()
    private val prefs by lazy { getSharedPreferences("flint-vpn-service", MODE_PRIVATE) }
    private val recoveryFile by lazy { AtomicFile(File(filesDir, "vpn-recovery.json")) }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            // The same-app binding is the only supported client of this service.
            if (msg.sendingUid != Process.myUid()) return
            when (msg.what) {
                VpnContract.REGISTER -> msg.replyTo?.let { clients.add(it); sendSnapshot(it) }
                VpnContract.UNREGISTER -> clients.remove(msg.replyTo)
                VpnContract.REQUEST_STATUS -> msg.replyTo?.let(::sendSnapshot)
                VpnContract.DISCONNECT -> requestStop()
                5 -> { forgetSaved = true; requestStop() }
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch { for (ignored in widgetUpdates) updateWidget() }
        scope.launch {
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Connect -> connect(command)
                        is Command.Stop -> disconnect(command.generation)
                    }
                } finally {
                    // Superseded queued requests may never reach readConfig().
                    // Their private handoff files must not accumulate on disk.
                    if (command is Command.Connect) discardTransient(command.fileName)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == VpnContract.ACTION_TOGGLE) {
            // A widget click is a foreground-service PendingIntent, not an Activity.
            // Query this live state; neither a cached color nor the UI decides.
            if (!startForegroundSafely(startId)) return START_NOT_STICKY
            if (snapshot.state in setOf("connected", "connecting") || prefs.getBoolean("desired", false)) {
                requestStop()
            } else if (snapshot.state != "disconnecting") {
                val request = generation.incrementAndGet()
                pendingGeneration = request
                publish(snapshot.copy(state = "connecting", generation = request))
                commands.trySend(Command.Connect(request, null, fromWidget = true))
                watchNativeDeadline(request, 30_000)
            }
            return START_STICKY
        }
        if (intent?.action == VpnContract.ACTION_DISCONNECT || intent?.action == "app.flint.prototype.vpn.FORGET") {
            if (intent.action == "app.flint.prototype.vpn.FORGET") forgetSaved = true
            requestStop()
            return START_NOT_STICKY
        }
        if (intent?.action == VpnContract.ACTION_CONNECT) {
            val request = generation.incrementAndGet()
            pendingGeneration = request
            val fileName = intent.getStringExtra(VpnContract.EXTRA_CONFIG_FILE)
            if (!startForegroundSafely(startId)) {
                discardTransient(fileName)
                return START_NOT_STICKY
            }
            if (commands.trySend(Command.Connect(request, fileName)).isFailure) {
                discardTransient(fileName)
                pendingGeneration = null
                leaveForeground()
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            watchNativeDeadline(request, 30_000)
        } else if (intent == null || intent.action == SERVICE_INTERFACE) {
            // Process recovery is permitted only for a connection the user left on.
            if (prefs.getBoolean("desired", false)) {
                val request = generation.incrementAndGet()
                pendingGeneration = request
                if (!startForegroundSafely(startId)) return START_NOT_STICKY
                commands.trySend(Command.Connect(request, null, recover = true))
                watchNativeDeadline(request, 30_000)
            } else {
                stopSelfResult(startId)
            }
        } else {
            stopSelfResult(startId)
        }
        return START_STICKY
    }

    private fun requestStop() {
        // Persist before enqueueing so a crash cannot revive a user-stopped tunnel.
        prefs.edit().putBoolean("desired", false).commit()
        val request = generation.incrementAndGet()
        pendingGeneration = request
        commands.trySend(Command.Stop(request))
        watchNativeDeadline(request, 8_000)
    }

    private fun watchNativeDeadline(request: Long, timeoutMs: Long) {
        mainHandler.postDelayed({
            if (generation.get() != request || pendingGeneration != request || destroyed) return@postDelayed
            prefs.edit().putBoolean("desired", false).commit()
            recoveryFile.delete()
            publish(snapshot.copy(state = "error", message = "VPN не ответил вовремя. Попробуйте подключиться снова."))
            pendingGeneration = null
            leaveForeground()
            stopSelfResult(lastStartId)
            // Let bound UI receive the error, then release a stuck native call.
            mainHandler.postDelayed({ if (generation.get() == request) terminateVpnProcess() }, 200)
        }, timeoutMs)
    }

    private suspend fun connect(command: Command.Connect) {
        if (command.generation != generation.get() || destroyed) return
        stateJob?.cancel()
        monitorJob?.cancel()
        stopNative()
        if (command.generation != generation.get() || destroyed) return
        publish(Snapshot(state = "connecting", generation = command.generation))
        var attemptedConfig: JSONObject? = null
        try {
            check(prepare(this) == null) { "permission" }
            val config = withContext(nativeDispatcher) { readConfig(command) }
            attemptedConfig = config
            require(config.optString("protocol").equals("xray", true)) { "protocol" }
            require(config.optJSONObject("xray_config_data")?.optString("config")?.isNotBlank() == true) { "config" }
            if (command.generation != generation.get() || destroyed) return
            val name = config.optString("description", "Сервер").take(120)
            val serverId = config.optString("flintServerId").take(128)
            publish(Snapshot("connecting", serverName = name, serverId = serverId,
                ruDirect = config.optBoolean("flintRuDirect", config.optBoolean("flintRussianAppsDirect", false)), generation = command.generation))
            // Original configuration is stored before resolution and is resolved anew
            // on process recovery. No account tokens or profiles go through Binder.
            withContext(nativeDispatcher) { saveRecovery(config.toString()) }
            if (command.generation != generation.get() || destroyed) return
            prefs.edit().putBoolean("desired", true).commit()
            val nativeState = MutableStateFlow(ProtocolState.CONNECTING)
            stateJob = scope.launch {
                nativeState.collect { current ->
                    if (command.generation == generation.get() && !destroyed) {
                        when (current) {
                            ProtocolState.CONNECTING, ProtocolState.RECONNECTING -> publish(snapshot.copy(state = "connecting"))
                            // startVpn return is authoritative for completed local startup.
                            ProtocolState.CONNECTED -> Unit
                            ProtocolState.DISCONNECTING -> publish(snapshot.copy(state = "disconnecting"))
                            ProtocolState.DISCONNECTED -> if (nativeStarted) {
                                requestStop()
                            }
                            ProtocolState.UNKNOWN -> Unit
                        }
                    }
                }
            }
            withContext(nativeDispatcher) {
                org.amnezia.vpn.util.Log.clear()
                native.initialize(applicationContext, nativeState) { _ ->
                    // Native errors may contain endpoints/credentials. Keep display safe.
                    scope.launch {
                        if (generation.get() == command.generation) {
                            prefs.edit().putBoolean("desired", false).commit()
                            requestStop()
                        }
                    }
                }
                // Mark ownership before calling native so every failure path cleans up.
                nativeStarted = true
                native.startVpn(config, Builder(), ::protect)
            }
            if (command.generation != generation.get() || destroyed) {
                stopNative()
                return
            }
            publish(snapshot.copy(state = "connecting", message = "Проверяем интернет через VPN…"))
            if (!VpnReachability.verify(this, config)) throw java.io.IOException("data_path")
            if (command.generation != generation.get() || destroyed) { stopNative(); return }
            publish(snapshot.copy(state = "connected", message = ""))
            config.remove("flintTried")
            pendingGeneration = null
            monitorJob = AutomaticMonitor.start(this, scope, config) { next ->
                if (generation.get() == command.generation) {
                    val dir = File(filesDir, VpnContract.CONFIG_DIRECTORY).apply { mkdirs() }
                    val file = File(dir, java.util.UUID.randomUUID().toString() + ".json")
                    file.writeText(next.toString())
                    val request = generation.incrementAndGet(); pendingGeneration = request
                    commands.trySend(Command.Connect(request, file.name)); watchNativeDeadline(request, 30_000)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            stopNative()
            if (command.generation == generation.get() && !destroyed) {
                val old = attemptedConfig
                if (old != null && old.optBoolean("flintAutomatic")) {
                    val tried = old.optJSONArray("flintTried") ?: org.json.JSONArray()
                    tried.put(old.optString("flintServerId"))
                    val ids = (0 until tried.length()).map { tried.optString(it) }.toSet()
                    val candidates = old.optJSONArray("flintCandidates") ?: org.json.JSONArray()
                    val next = (0 until candidates.length()).mapNotNull { candidates.optJSONObject(it) }.firstOrNull { it.optString("id") !in ids }
                    if (next != null && ids.size < 5) {
                        val config = AutomaticMonitor.switchConfig(old, next).put("flintTried", tried)
                        val dir = File(filesDir, VpnContract.CONFIG_DIRECTORY).apply { mkdirs() }
                        val file = File(dir, java.util.UUID.randomUUID().toString() + ".json")
                        withContext(Dispatchers.IO) { file.writeText(config.toString()) }
                        val request = generation.incrementAndGet(); pendingGeneration = request
                        publish(snapshot.copy(state = "connecting", message = "Пробуем другой сервер…", generation = request))
                        commands.trySend(Command.Connect(request, file.name)); watchNativeDeadline(request, 30_000)
                        return
                    }
                }
                prefs.edit().putBoolean("desired", false).commit()
                recoveryFile.delete()
                val message = if (prepare(this) != null) "Откройте Flint и разрешите подключение VPN один раз. Затем кнопка на экране будет работать самостоятельно."
                    else if (error.message == "widget_profile") "Сначала выберите сервер и подключитесь в Flint. После этого кнопка на экране сможет включать VPN самостоятельно."
                    else if (error.message == "data_path") "Туннель запущен, но интернет через сервер не отвечает. Выберите другую локацию или выполните автонастройку."
                    else "Не удалось запустить VPN. Проверьте профиль или выберите другой сервер."
                val stage = if (error.message == "data_path") "DATA_PATH" else "CORE_START"
                publish(snapshot.copy(state = "error", message = message + "\nДиагностика: $stage / ${org.amnezia.vpn.util.Log.lastIssue}"))
                updateWidget()
                if (command.generation != generation.get() || destroyed) return
                if (command.fromWidget) android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                pendingGeneration = null
                leaveForeground()
                stopSelfResult(lastStartId)
            }
        }
    }

    private suspend fun disconnect(request: Long) {
        stateJob?.cancel()
        monitorJob?.cancel()
        if (request == generation.get()) publish(snapshot.copy(state = "disconnecting", generation = request))
        stopNative()
        recoveryFile.delete()
        if (forgetSaved) {
            withContext(nativeDispatcher) { AtomicFile(File(filesDir, "last-vpn-config.json")).delete() }
            forgetSaved = false
        }
        if (request == generation.get() && !destroyed) {
            publish(Snapshot(generation = request))
            // Complete the launcher update before stopSelf can destroy :vpn.
            updateWidget()
            if (request != generation.get() || destroyed) return
            pendingGeneration = null
            leaveForeground()
            stopSelfResult(lastStartId)
        }
    }

    private suspend fun stopNative() = withContext(nativeDispatcher) {
        if (nativeStarted) {
            try { native.stopVpn() } catch (_: Throwable) { /* Process teardown is the final fallback. */ }
            nativeStarted = false
        }
    }

    private fun readConfig(command: Command.Connect): JSONObject {
        val raw = if (command.fromWidget) {
            val saved = AtomicFile(File(filesDir, "last-vpn-config.json"))
            check(saved.baseFile.isFile) { "widget_profile" }
            saved.openRead().use { input ->
                require(saved.baseFile.length() in 1..VpnContract.MAX_CONFIG_BYTES.toLong())
                input.readBytes().toString(Charsets.UTF_8)
            }
        } else if (command.recover) {
            require(prefs.getBoolean("desired", false))
            recoveryFile.openRead().use { input ->
                require(recoveryFile.baseFile.length() in 1..VpnContract.MAX_CONFIG_BYTES.toLong())
                input.readBytes().toString(Charsets.UTF_8)
            }
        } else {
            val name = requireNotNull(command.fileName)
            require(name.matches(Regex("[A-Za-z0-9_-]{1,100}\\.json")))
            val directory = File(filesDir, VpnContract.CONFIG_DIRECTORY).canonicalFile
            val file = File(directory, name).canonicalFile
            require(file.parentFile == directory && file.isFile)
            require(file.length() in 1..VpnContract.MAX_CONFIG_BYTES.toLong())
            try { file.readText(Charsets.UTF_8) } finally { file.delete() }
        }
        return JSONObject(raw)
    }

    private fun saveRecovery(text: String) {
        val stream = recoveryFile.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); recoveryFile.finishWrite(stream)
            val last = AtomicFile(File(filesDir, "last-vpn-config.json")); val out = last.startWrite()
            try { out.write(text.toByteArray()); last.finishWrite(out) } catch (e: Exception) { last.failWrite(out); throw e }
        }
        catch (error: Exception) { recoveryFile.failWrite(stream); throw error }
    }

    private fun discardTransient(name: String?) {
        if (name == null || !name.matches(Regex("[A-Za-z0-9_-]{1,100}\\.json"))) return
        runCatching {
            val directory = File(filesDir, VpnContract.CONFIG_DIRECTORY).canonicalFile
            val file = File(directory, name).canonicalFile
            if (file.parentFile == directory) file.delete()
        }
    }

    private fun publish(value: Snapshot) {
        snapshot = value.copy(timestamp = System.currentTimeMillis())
        clients.toList().forEach(::sendSnapshot)
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        if (lastWidgetState != value.state) { lastWidgetState = value.state; widgetUpdates.trySend(value.state) }
    }

    private suspend fun updateWidget() = widgetLock.withLock {
        val current = snapshot.state
        withContext(Dispatchers.IO) { app.flint.prototype.home.HomeWidget.refresh(this@FlintVpnService, current) }
    }

    private fun sendSnapshot(client: Messenger) {
        val value = snapshot
        val message = Message.obtain(null, VpnContract.STATUS).apply {
            data = Bundle().apply {
                putString(VpnContract.STATE, value.state)
                putString(VpnContract.MESSAGE, value.message)
                putString(VpnContract.SERVER_NAME, value.serverName)
                putString(VpnContract.SERVER_ID, value.serverId)
                putBoolean(VpnContract.RU_DIRECT, value.ruDirect)
                putLong(VpnContract.GENERATION, value.generation)
                putLong(VpnContract.TIMESTAMP, value.timestamp)
            }
        }
        try { client.send(message) } catch (_: Exception) { clients.remove(client) }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FLINT VPN", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }

    private fun notification(): Notification {
        val content = when (snapshot.state) {
            "connected" -> "VPN включён · ${snapshot.serverName}"
            "connecting" -> "Подключение…"
            "disconnecting" -> "Отключение…"
            "error" -> snapshot.message
            else -> "VPN выключен"
        }
        val stop = PendingIntent.getService(this, 1,
            Intent(this, FlintVpnService::class.java).setAction(VpnContract.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status).setContentTitle("FLINT")
            .setContentText(content).setOngoing(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
        (packageManager.getLaunchIntentForPackage(packageName)
            ?: packageManager.getLeanbackLaunchIntentForPackage(packageName))?.let { intent ->
            builder.setContentIntent(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }

    private fun showForeground() {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NOTIFICATION_ID, notification())
        foreground = true
    }

    private fun startForegroundSafely(startId: Int): Boolean = try {
        showForeground()
        true
    } catch (_: Exception) {
        prefs.edit().putBoolean("desired", false).commit()
        pendingGeneration = null
        publish(snapshot.copy(state = "error", message = "Не удалось запустить службу VPN. Откройте приложение и повторите подключение."))
        stopSelfResult(startId)
        false
    }

    private fun leaveForeground() {
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
    }

    override fun onRevoke() {
        scope.launch { requestStop() }
    }

    override fun onDestroy() {
        destroyed = true
        generation.incrementAndGet()
        stateJob?.cancel()
        commands.close()
        // Closing first prevents new producers from handing us files while the
        // queued requests are drained. The active request keeps its own finally.
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: break
            if (command is Command.Connect) discardTransient(command.fileName)
        }
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        // This service is intentionally isolated in :vpn. Terminating this process
        // releases native threads/TUN descriptors even if a native call is stuck.
        // Never call this from the UI process.
        super.onDestroy()
        terminateVpnProcess()
    }

    private fun terminateVpnProcess() {
        if (Application.getProcessName() == "$packageName:vpn") Process.killProcess(Process.myPid())
    }

    private sealed interface Command {
        data class Connect(val generation: Long, val fileName: String?, val recover: Boolean = false, val fromWidget: Boolean = false) : Command
        data class Stop(val generation: Long) : Command
    }
    private data class Snapshot(
        val state: String = "disconnected", val message: String = "",
        val serverName: String = "", val serverId: String = "",
        val ruDirect: Boolean = true,
        val generation: Long = 0, val timestamp: Long = System.currentTimeMillis()
    )
    companion object {
        private const val CHANNEL_ID = "flint-native-vpn"
        private const val NOTIFICATION_ID = 8125
    }
}
