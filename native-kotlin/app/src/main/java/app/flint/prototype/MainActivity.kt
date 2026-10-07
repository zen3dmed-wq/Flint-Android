package app.flint.prototype

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.net.VpnService
import android.os.*
import android.text.InputType
import android.widget.EditText
import app.flint.prototype.data.ProfileStore
import app.flint.prototype.data.QrImageDecoder
import app.flint.prototype.imports.*
import app.flint.prototype.ui.*
import app.flint.prototype.vpn.FlintVpnService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

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
            state = state.copy(phase = phase,
                message = info.getString("message").orEmpty(),
                ruDirect = if (phase == FlintPhase.CONNECTED || phase == FlintPhase.CONNECTING)
                    info.getBoolean("ruDirect", state.ruDirect) else state.ruDirect,
                serverLabel = info.getString("serverName").orEmpty().ifBlank { selectedLabel() })
            render()
            true
        } else false
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            sendMessage(1)
            sendMessage(3)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            state = state.copy(phase = FlintPhase.DISCONNECTED, busy = false,
                message = "Связь с VPN-службой прервана. Подключитесь снова.")
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
        state = state.copy(selectedServerId = prefs.getString("selected", null))
        pendingFile = savedInstanceState?.getString("pendingFile")
        home = FlintHomeView(this, BuildConfig.IS_TV, this)
        setContentView(home)
        scope.launch {
            try {
                profiles = withContext(Dispatchers.IO) { profilesStore.load() }
                render()
                if (profiles.isNotEmpty()) checkServers()
            } catch (_: Exception) { report("Не удалось прочитать сохранённые профили. Повторите импорт.") }
        }
        render()
    }

    override fun onStart() { super.onStart(); resumed = true; bindVpn() }
    override fun onStop() { resumed = false; unbindVpn(); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingFile?.let { outState.putString("pendingFile", it) }
        super.onSaveInstanceState(outState)
    }

    private fun bindVpn() {
        if (!binding) binding = bindService(Intent(this, FlintVpnService::class.java), connection, BIND_AUTO_CREATE)
    }
    private fun unbindVpn() {
        if (binding) { sendMessage(2); unbindService(connection) }
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
        if (!::home.isInitialized) return
        state = state.copy(hasProfile = profiles.isNotEmpty(), servers = profiles.map { p ->
            FlintServerUi(p.id, p.name, ping[p.id]?.first, ping[p.id]?.second)
        })
        home.render(state)
    }
    private fun report(message: String, error: Boolean = false) {
        state = state.copy(message = message, busy = false,
            phase = if (error && state.phase != FlintPhase.CONNECTED) FlintPhase.ERROR else state.phase)
        render()
    }

    override fun onConnectToggle() {
        if (state.phase == FlintPhase.CONNECTED || state.phase == FlintPhase.CONNECTING || state.busy) {
            generation++
            operation?.cancel()
            pendingFile?.let { File(filesDir, "vpn-configs/$it").delete() }
            pendingFile = null
            state = state.copy(busy = false)
            if (!sendMessage(4)) startService(Intent(this, FlintVpnService::class.java)
                .setAction("app.flint.prototype.vpn.DISCONNECT"))
            render()
        } else connectSelected()
    }

    private fun connectSelected() {
        if (profiles.isEmpty()) { onImportText(); return }
        val request = ++generation
        operation?.cancel()
        state = state.copy(busy = true, message = "Подготовка подключения…")
        render()
        operation = scope.launch {
            try {
                val selected = state.selectedServerId
                val ru = state.ruDirect
                val profile = profiles.find { it.id == selected } ?: chooseAutomatic()
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
                    file.writeText(wrapper.toString())
                    file.name
                }
                if (request != generation) { File(filesDir, "vpn-configs/$filename").delete(); return@launch }
                pendingFile = filename
                state = state.copy(serverLabel = profile.name)
                val permission = VpnService.prepare(this@MainActivity)
                if (permission != null) startActivityForResult(permission, REQUEST_VPN)
                else startPrepared()
            } catch (_: CancellationException) {
                // A newer selection or explicit disconnect owns the outcome.
            } catch (e: ImportException) { report(e.message ?: "Ошибка профиля", true) }
            catch (_: Exception) { report("Не удалось подготовить VPN. Повторите импорт подписки.", true) }
        }
    }

    private fun startPrepared() {
        val file = pendingFile ?: return
        if (VpnService.prepare(this) != null) {
            pendingFile = null
            File(filesDir, "vpn-configs/$file").delete()
            state = state.copy(phase = FlintPhase.DISCONNECTED)
            report("Разрешение VPN не предоставлено")
            return
        }
        try {
            startForegroundService(Intent(this, FlintVpnService::class.java)
                .setAction("app.flint.prototype.vpn.CONNECT").putExtra("config_file", file))
            pendingFile = null
            state = state.copy(busy = false, message = "Запуск VPN…")
            render()
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } catch (_: Exception) {
            File(filesDir, "vpn-configs/$file").delete()
            pendingFile = null
            report("Android не разрешил запустить VPN-службу. Откройте приложение и повторите.", true)
        }
    }

    override fun onSelectServer(id: String?) {
        if (id != null && profiles.none { it.id == id }) return
        prefs.edit().putString("selected", id).apply()
        state = state.copy(selectedServerId = id, serverLabel = profiles.find { it.id == id }?.name ?: "Автоматически")
        val reconnect = state.phase == FlintPhase.CONNECTED || state.phase == FlintPhase.CONNECTING || state.busy
        render()
        if (reconnect) connectSelected()
    }
    override fun onRuDirectChanged(enabled: Boolean) {
        state = state.copy(ruDirect = enabled)
        render()
        if (state.phase == FlintPhase.CONNECTED || state.phase == FlintPhase.CONNECTING || state.busy) connectSelected()
    }

    override fun onImportClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) report("Буфер обмена пуст. Скопируйте ссылку подписки.") else importText(text)
    }
    override fun onImportText() {
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
            if (resultCode == RESULT_OK && pendingFile != null) startPrepared()
            else {
                pendingFile?.let { File(filesDir, "vpn-configs/$it").delete() }
                pendingFile = null
                state = state.copy(phase = FlintPhase.DISCONNECTED)
                report("Подключение отменено: разрешение VPN не выдано.")
            }
        } else if (resultCode == RESULT_OK && requestCode in setOf(REQUEST_QR, REQUEST_FILE)) {
            val uri = data?.data ?: return
            state = state.copy(busy = true, message = "Чтение файла…")
            render()
            scope.launch {
                try {
                    val input = withContext(Dispatchers.IO) {
                        if (requestCode == REQUEST_QR) QrImageDecoder.decode(this@MainActivity, uri)
                        else readImportFile(uri)
                    }
                    importText(input)
                } catch (e: ImportException) { report(e.message ?: "Не удалось прочитать файл") }
                catch (_: Exception) { report("Не удалось прочитать файл или QR-код. Выберите другое изображение.") }
            }
        }
    }

    private fun readImportFile(uri: Uri): String = contentResolver.openInputStream(uri)?.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > SubscriptionParser.MAX_BYTES) throw ImportException("Файл слишком большой")
            out.write(buffer, 0, n)
        }
        out.toString("UTF-8")
    } ?: throw ImportException("Не удалось открыть файл")

    private fun importText(input: String) {
        if (input.isBlank()) { report("Введите ссылку подписки"); return }
        state = state.copy(busy = true, message = "Загрузка подписки…")
        render()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { SubscriptionClient().import(input) }
                profiles = withContext(Dispatchers.IO) { profilesStore.merge(result.profiles) }
                report("Серверов в списке: ${profiles.size}. " + result.warnings.joinToString(" "))
                checkServers()
            } catch (_: CancellationException) { }
            catch (e: ImportException) { report(e.message ?: "Не удалось импортировать подписку") }
            catch (_: Exception) { report("Не удалось сохранить подписку") }
        }
    }

    private suspend fun measure(profile: ServerProfile): Pair<Long?, Boolean> = withContext(Dispatchers.IO) {
        try {
            val start = SystemClock.elapsedRealtime()
            Socket().use { it.connect(InetSocketAddress(profile.host, profile.port), 2000) }
            Pair(SystemClock.elapsedRealtime() - start, true)
        } catch (_: Exception) { Pair(null, false) }
    }
    private suspend fun chooseAutomatic(): ServerProfile {
        // TCP responsiveness is only a selection hint, not proof of a successful VPN handshake.
        val checks = coroutineScope { profiles.map { profile -> async { profile to measure(profile) } }.awaitAll() }
        checks.forEach { ping[it.first.id] = it.second }
        return checks.filter { it.second.second }.minByOrNull { it.second.first ?: Long.MAX_VALUE }?.first ?: profiles.first()
    }
    private fun checkServers() {
        if (profiles.size > 200) return
        scope.launch {
            profiles.chunked(8).forEach { group ->
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
