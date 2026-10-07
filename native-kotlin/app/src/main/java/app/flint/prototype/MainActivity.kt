package app.flint.prototype

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.net.VpnService
import android.net.DnsResolver
import android.net.InetAddresses
import android.os.*
import android.text.InputType
import android.widget.EditText
import app.flint.prototype.data.ProfileStore
import app.flint.prototype.data.QrImageDecoder
import app.flint.prototype.imports.*
import app.flint.prototype.ui.*
import app.flint.prototype.vpn.FlintVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import kotlin.coroutines.resume

/** The service owns the tunnel. Activity navigation never stops it. */
class MainActivity : Activity(), FlintUiCallbacks {
    private lateinit var home: FlintHomeView
    private lateinit var profilesStore: ProfileStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var profiles = emptyList<ServerProfile>()
    private var state = FlintUiState()
    private val ping = mutableMapOf<String, Pair<Long?, Boolean>>()
    private var remote: Messenger? = null
    private var binding = false
    private var resumed = false
    private var operation: Job? = null
    private var importJob: Job? = null
    private var probeJob: Job? = null
    private var loadingProfiles = true
    private val profilesReady = CompletableDeferred<Unit>()
    private var importing = false
    private var preparing = false
    private var awaitingService = false
    private var permissionInFlight = false
    private var confirmedPhase = FlintPhase.DISCONNECTED
    private var confirmedServerName = ""
    private var confirmedRuDirect = true
    private val probeSlots = Semaphore(8)
    private var generation = 0
    private var pendingFile: String? = null
    private val prefs by lazy { getSharedPreferences("prototype-ui", MODE_PRIVATE) }
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == 100) {
            val info = msg.data
            val phase = when (info.getString("state")) {
                "connected" -> FlintPhase.CONNECTED
                "connecting", "disconnecting" -> FlintPhase.CONNECTING
                "error" -> FlintPhase.ERROR
                else -> FlintPhase.DISCONNECTED
            }
            confirmedPhase = phase
            confirmedServerName = info.getString("serverName").orEmpty()
            confirmedRuDirect = info.getBoolean("ruDirect", true)
            if (awaitingService && phase != FlintPhase.DISCONNECTED) awaitingService = false
            val localPreparation = preparing || pendingFile != null || awaitingService
            state = state.copy(phase = if (localPreparation) FlintPhase.CONNECTING else phase,
                message = if (localPreparation) state.message else info.getString("message").orEmpty(),
                ruDirect = if (!localPreparation && (phase == FlintPhase.CONNECTED || phase == FlintPhase.CONNECTING))
                    info.getBoolean("ruDirect", state.ruDirect) else state.ruDirect,
                serverLabel = if (localPreparation) state.serverLabel else info.getString("serverName").orEmpty().ifBlank { selectedLabel() })
            render()
            true
        } else false
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            remote = Messenger(service)
            sendMessage(1)
            sendMessage(3)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            confirmedPhase = FlintPhase.DISCONNECTED
            awaitingService = false
            state = state.copy(phase = if (preparing || pendingFile != null) FlintPhase.CONNECTING else FlintPhase.DISCONNECTED,
                message = if (preparing || pendingFile != null) state.message else "Связь с VPN-службой прервана. Подключитесь снова.")
            render()
        }
        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
            unbindVpn()
            if (resumed) bindVpn()
        }
        override fun onNullBinding(name: ComponentName?) { onServiceDisconnected(name) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        profilesStore = ProfileStore(this)
        state = state.copy(selectedServerId = prefs.getString("selected", null),
            ruDirect = savedInstanceState?.getBoolean("ruDirect", true) ?: true)
        pendingFile = savedInstanceState?.getString("pendingFile")?.takeIf { validPendingFile(it)?.isFile == true }
        permissionInFlight = pendingFile != null && savedInstanceState?.getBoolean("permissionInFlight", false) == true
        if (pendingFile != null) state = state.copy(phase = FlintPhase.CONNECTING, message = "Ожидаем разрешения VPN…")
        home = FlintHomeView(this, BuildConfig.IS_TV, this)
        setContentView(home)
        scope.launch {
            try {
                profiles = withContext(Dispatchers.IO) { profilesStore.load() }
                render()
                if (profiles.isNotEmpty() && savedInstanceState?.getBoolean("restartPreparation", false) == true)
                    connectSelected()
                else if (profiles.isNotEmpty()) checkServers()
            } catch (_: CancellationException) { }
            catch (error: ImportException) { report(error.message ?: "Не удалось прочитать сохранённые профили") }
            catch (_: Exception) { report("Не удалось прочитать сохранённые профили. Данные не изменены.") }
            finally { loadingProfiles = false; profilesReady.complete(Unit); render() }
        }
        render()
    }

    override fun onStart() { super.onStart(); resumed = true; bindVpn() }
    override fun onStop() { resumed = false; unbindVpn(); super.onStop() }
    override fun onDestroy() {
        scope.cancel()
        if (isFinishing) clearPendingFile()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingFile?.let { outState.putString("pendingFile", it) }
        outState.putBoolean("permissionInFlight", permissionInFlight)
        outState.putBoolean("restartPreparation", preparing && pendingFile == null)
        outState.putBoolean("ruDirect", state.ruDirect)
        super.onSaveInstanceState(outState)
    }

    private fun bindVpn() {
        if (!binding) try {
            binding = bindService(Intent(this, FlintVpnService::class.java), connection, BIND_AUTO_CREATE)
        } catch (_: Exception) { report("Не удалось получить состояние VPN-службы") }
    }
    private fun unbindVpn() {
        if (binding) {
            sendMessage(2)
            try { unbindService(connection) } catch (_: IllegalArgumentException) { }
        }
        binding = false
        remote = null
    }
    private fun sendMessage(what: Int): Boolean {
        val messenger = remote ?: return false
        return try {
            messenger.send(Message.obtain(null, what).apply { replyTo = receiver })
            true
        } catch (_: RemoteException) { remote = null; false }
    }

    private fun selectedLabel(): String = profiles.find { it.id == state.selectedServerId }?.name ?: "Автоматически"
    private fun render() {
        if (!::home.isInitialized || isDestroyed) return
        state = state.copy(busy = loadingProfiles || importing || preparing || pendingFile != null || awaitingService,
            hasProfile = profiles.isNotEmpty(), servers = profiles.map { p ->
            FlintServerUi(p.id, p.name, ping[p.id]?.first, ping[p.id]?.second)
        })
        home.render(state)
    }
    private fun report(message: String, error: Boolean = false) {
        state = state.copy(message = message,
            phase = if (error && state.phase != FlintPhase.CONNECTED) FlintPhase.ERROR else state.phase)
        render()
    }

    private fun preparationFailed(message: String) {
        clearPendingFile()
        preparing = false
        awaitingService = false
        permissionInFlight = false
        val oldTunnelActive = confirmedPhase == FlintPhase.CONNECTED || confirmedPhase == FlintPhase.CONNECTING
        state = state.copy(phase = if (oldTunnelActive) confirmedPhase else FlintPhase.ERROR,
            serverLabel = if (oldTunnelActive) confirmedServerName.ifBlank { selectedLabel() } else selectedLabel(),
            ruDirect = if (oldTunnelActive) confirmedRuDirect else state.ruDirect)
        report(message)
    }

    override fun onConnectToggle() {
        if (state.phase == FlintPhase.CONNECTED || state.phase == FlintPhase.CONNECTING || preparing || pendingFile != null || awaitingService) {
            generation++
            operation?.cancel()
            clearPendingFile()
            permissionInFlight = false
            preparing = false
            awaitingService = false
            state = state.copy(message = "Отключение…")
            if (!sendMessage(4)) try {
                startService(Intent(this, FlintVpnService::class.java).setAction("app.flint.prototype.vpn.DISCONNECT"))
            } catch (_: Exception) { report("Не удалось отправить команду отключения. Используйте уведомление Flint.") }
            render()
        } else if (loadingProfiles || importing) report("Дождитесь загрузки профилей")
        else connectSelected()
    }

    private fun connectSelected() {
        if (profiles.isEmpty()) { onImportText(); return }
        val request = ++generation
        operation?.cancel()
        probeJob?.cancel()
        clearPendingFile()
        preparing = true
        awaitingService = false
        val availableProfiles = profiles.toList()
        val selected = state.selectedServerId
        val ru = state.ruDirect
        state = state.copy(phase = FlintPhase.CONNECTING, message = "Подготовка подключения…")
        render()
        operation = scope.launch {
            var staged: File? = null
            var handedToPending = false
            try {
                val profile = availableProfiles.find { it.id == selected } ?: chooseAutomatic(availableProfiles)
                val filename = withContext(Dispatchers.IO) {
                    val catalog = assets.open("flint-routing-catalog.json").bufferedReader().use { it.readText() }
                    val wrapper = JSONObject().put("protocol", "xray").put("hostName", profile.host)
                        .put("dns1", "1.1.1.1").put("dns2", "1.0.0.1").put("mtu", "1500")
                        .put("description", profile.name).put("flintServerId", profile.id)
                        .put("flintRussianAppsDirect", ru)
                        .put("xray_config_data", JSONObject().put("config", XrayConfigBuilder.build(
                            profile, ruDirect = ru, routingCatalogJson = catalog)))
                    val dir = File(filesDir, "vpn-configs").apply { mkdirs() }
                    val file = File(dir, "${UUID.randomUUID()}.json")
                    staged = file
                    file.writeText(wrapper.toString())
                    file.name
                }
                if (request != generation) return@launch
                pendingFile = filename
                handedToPending = true
                state = state.copy(serverLabel = profile.name)
                val permission = VpnService.prepare(this@MainActivity)
                if (permission != null) {
                    if (!permissionInFlight) {
                        permissionInFlight = true
                        startActivityForResult(permission, REQUEST_VPN)
                    }
                }
                else startPrepared()
            } catch (_: CancellationException) {
                // A newer selection or explicit disconnect owns the outcome.
            } catch (e: ImportException) {
                if (request == generation) preparationFailed(e.message ?: "Ошибка профиля")
            } catch (_: Exception) {
                if (request == generation) preparationFailed("Не удалось подготовить VPN. Повторите импорт подписки.")
            } finally {
                if (!handedToPending) staged?.delete()
                if (request == generation) { preparing = false; render() }
            }
        }
    }

    private fun validPendingFile(name: String): File? =
        if (name.matches(Regex("[A-Za-z0-9_-]{1,100}\\.json"))) File(filesDir, "vpn-configs/$name") else null

    private fun clearPendingFile() {
        pendingFile?.let { validPendingFile(it)?.delete() }
        pendingFile = null
    }

    private fun startPrepared() {
        val file = pendingFile ?: return
        if (validPendingFile(file)?.isFile != true) {
            preparationFailed("Подготовленный профиль больше недоступен. Нажмите «Подключиться» снова.")
            return
        }
        if (VpnService.prepare(this) != null) {
            clearPendingFile()
            preparing = false
            state = state.copy(phase = FlintPhase.DISCONNECTED)
            report("Разрешение VPN не предоставлено")
            return
        }
        try {
            startForegroundService(Intent(this, FlintVpnService::class.java)
                .setAction("app.flint.prototype.vpn.CONNECT").putExtra("config_file", file))
            pendingFile = null
            permissionInFlight = false
            preparing = false
            awaitingService = true
            state = state.copy(phase = FlintPhase.CONNECTING, message = "Запуск VPN…")
            render()
        } catch (_: Exception) {
            preparationFailed("Android не разрешил запустить VPN-службу. Откройте приложение и повторите.")
            return
        }
        // Notification permission is independent of VPN startup and must not retract its file.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) try {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } catch (_: Exception) { /* The foreground service remains the tunnel owner. */ }
    }

    override fun onSelectServer(id: String?) {
        if (id != null && profiles.none { it.id == id }) return
        prefs.edit().putString("selected", id).apply()
        state = state.copy(selectedServerId = id, serverLabel = profiles.find { it.id == id }?.name ?: "Автоматически")
        val reconnect = state.phase == FlintPhase.CONNECTED || state.phase == FlintPhase.CONNECTING || preparing || awaitingService || pendingFile != null
        render()
        if (reconnect) connectSelected()
    }
    override fun onRuDirectChanged(enabled: Boolean) {
        state = state.copy(ruDirect = enabled)
        render()
        if (state.phase == FlintPhase.CONNECTED || state.phase == FlintPhase.CONNECTING || preparing || awaitingService || pendingFile != null) connectSelected()
    }

    override fun onImportClipboard() {
        if (loadingProfiles || importing) { report("Дождитесь завершения импорта"); return }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = try { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() }
            catch (_: Exception) { report("Не удалось прочитать буфер обмена"); return }
        if (text.isNullOrBlank()) report("Буфер обмена пуст. Скопируйте ссылку подписки.") else importText(text)
    }
    override fun onImportText() {
        if (loadingProfiles || importing) { report("Дождитесь завершения импорта"); return }
        val field = EditText(this).apply {
            hint = "Ссылка подписки или конфигурация"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(32, 24, 32, 24)
            maxLines = 5
        }
        AlertDialog.Builder(this).setTitle("Добавить подписку").setView(field)
            .setNegativeButton("Отмена", null).setPositiveButton("Добавить") { _, _ -> importText(field.text.toString()) }.show()
    }
    override fun onImportQrImage() = pick("image/*", REQUEST_QR)
    override fun onImportFile() = pick("*/*", REQUEST_FILE)
    private fun pick(mime: String, code: Int) {
        if (loadingProfiles || importing) { report("Дождитесь завершения импорта"); return }
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = mime; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, code) }
        catch (_: ActivityNotFoundException) { report("На устройстве нет выбора файлов. Добавьте ссылку через поле ввода.") }
    }

    @Deprecated("Legacy Activity result callback is intentionally used with platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (!permissionInFlight) return
            permissionInFlight = false
            if (resultCode == RESULT_OK && pendingFile != null) startPrepared()
            else {
                generation++
                operation?.cancel()
                clearPendingFile()
                preparing = false
                awaitingService = false
                state = state.copy(phase = FlintPhase.DISCONNECTED)
                report("Подключение отменено: разрешение VPN не выдано.")
            }
        } else if (resultCode == RESULT_OK && requestCode in setOf(REQUEST_QR, REQUEST_FILE)) {
            val uri = data?.data ?: return
            launchImport("Чтение файла…") {
                if (requestCode == REQUEST_QR) QrImageDecoder.decode(this@MainActivity, uri)
                else readImportFile(uri)
            }
        }
    }

    private fun readImportFile(uri: Uri): String = contentResolver.openInputStream(uri)?.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw ImportException("Импорт отменён")
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > SubscriptionParser.MAX_BYTES) throw ImportException("Файл слишком большой")
            out.write(buffer, 0, n)
        }
        out.toString("UTF-8")
    } ?: throw ImportException("Не удалось открыть файл")

    private fun importText(input: String) {
        if (input.isBlank()) { report("Введите ссылку подписки"); return }
        launchImport("Загрузка подписки…") { input }
    }

    private fun launchImport(message: String, readInput: () -> String) {
        if (importing) { report("Дождитесь завершения импорта"); return }
        importing = true
        state = state.copy(message = message)
        render()
        importJob = scope.launch {
            try {
                // A document result can arrive immediately after Activity recreation.
                profilesReady.await()
                val result = runInterruptible(Dispatchers.IO) { SubscriptionClient().import(readInput()) }
                profiles = withContext(Dispatchers.IO) { profilesStore.merge(result.profiles) }
                if (state.selectedServerId != null && profiles.none { it.id == state.selectedServerId }) {
                    state = state.copy(selectedServerId = null)
                    prefs.edit().remove("selected").apply()
                }
                val warningSummary = result.warnings.take(2).joinToString(" ") +
                    if (result.warnings.size > 2) " Ещё ошибок импорта: ${result.warnings.size - 2}." else ""
                report("Серверов в списке: ${profiles.size}. $warningSummary")
                checkServers()
            } catch (_: CancellationException) { }
            catch (e: ImportException) { report(e.message ?: "Не удалось импортировать подписку") }
            catch (_: Exception) { report("Не удалось сохранить подписку") }
            finally { importing = false; render() }
        }
    }

    private suspend fun resolveForProbe(host: String): List<InetAddress> {
        try { return listOf(InetAddresses.parseNumericAddress(host)) } catch (_: IllegalArgumentException) { }
        return withTimeoutOrNull(1500) {
            suspendCancellableCoroutine<List<InetAddress>> { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                try {
                    DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, mainExecutor, cancellation,
                        object : DnsResolver.Callback<List<InetAddress>> {
                            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                                if (continuation.isActive) continuation.resume(if (rcode == 0) answer else emptyList())
                            }
                            override fun onError(error: DnsResolver.DnsException) {
                                if (continuation.isActive) continuation.resume(emptyList())
                            }
                        })
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            }
        } ?: emptyList()
    }

    private suspend fun measure(profile: ServerProfile): Pair<Long?, Boolean> = probeSlots.withPermit {
        try {
            val addresses = resolveForProbe(profile.host).take(2)
            if (addresses.isEmpty()) return@withPermit Pair(null, false)
            withContext(Dispatchers.IO) {
                for (address in addresses) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val start = SystemClock.elapsedRealtime()
                        Socket().use { it.connect(InetSocketAddress(address, profile.port), 1000) }
                        return@withContext Pair(SystemClock.elapsedRealtime() - start, true)
                    } catch (_: java.io.IOException) { }
                }
                Pair(null, false)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { Pair(null, false) }
    }

    private suspend fun chooseAutomatic(candidates: List<ServerProfile>): ServerProfile {
        // TCP responsiveness is only a selection hint, not proof of a successful VPN handshake.
        val checks = mutableListOf<Pair<ServerProfile, Pair<Long?, Boolean>>>()
        withTimeoutOrNull(6000) {
            candidates.take(200).chunked(8).forEach { group ->
                checks.addAll(coroutineScope { group.map { profile -> async { profile to measure(profile) } }.awaitAll() })
            }
        }
        checks.forEach { ping[it.first.id] = it.second }
        return checks.filter { it.second.second }.minByOrNull { it.second.first ?: Long.MAX_VALUE }?.first
            ?: candidates.firstOrNull { ping[it.id]?.second == true } ?: candidates.first()
    }
    private fun checkServers() {
        probeJob?.cancel()
        val candidates = profiles.take(200)
        probeJob = scope.launch {
            candidates.chunked(8).forEach { group ->
                val checks = coroutineScope { group.map { p -> async { p.id to measure(p) } }.awaitAll() }
                checks.forEach { ping[it.first] = it.second }
                render()
            }
        }
    }
    companion object {
        private const val REQUEST_VPN = 11
        private const val REQUEST_QR = 12
        private const val REQUEST_FILE = 13
        private const val REQUEST_NOTIFICATIONS = 14
    }
}
