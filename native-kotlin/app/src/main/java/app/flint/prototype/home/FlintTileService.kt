package app.flint.prototype.home

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.*
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.flint.prototype.BuildConfig
import app.flint.prototype.MainActivity
import app.flint.prototype.R
import app.flint.prototype.ui.FlintStyle
import app.flint.prototype.vpn.FlintVpnService
import app.flint.prototype.vpn.VpnContract
import java.io.File

/** Standard listening mode: subscribe to the real VPN process while the shade is visible. */
class FlintTileService : TileService() {
    private var listening = false
    private var bound = false
    private var vpn: Messenger? = null
    private var liveState: String? = null
    private var lastClick = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == VpnContract.STATUS && listening) {
            liveState = message.data.getString(VpnContract.STATE)
            render()
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!listening) { release(); return }
            vpn = Messenger(binder)
            runCatching { vpn?.send(Message.obtain(null, VpnContract.REGISTER).apply { replyTo = replies }) }
                .onFailure { render("Откройте Flint") }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpn = null; liveState = null; render("Откройте Flint")
        }
        override fun onNullBinding(name: ComponentName?) { release(); render("Откройте Flint") }
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = true; liveState = null; render()
        if (!bound) {
            bound = runCatching { bindService(Intent(this, FlintVpnService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) render("Откройте Flint")
        } else runCatching { vpn?.send(Message.obtain(null, VpnContract.REQUEST_STATUS).apply { replyTo = replies }) }
        handler.postDelayed({ if (listening && liveState == null) render("Откройте Flint") }, 4000)
    }

    override fun onStopListening() { release(); super.onStopListening() }
    override fun onTileRemoved() { release(); super.onTileRemoved() }
    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() {
        listening = false; handler.removeCallbacksAndMessages(null)
        runCatching { vpn?.send(Message.obtain(null, VpnContract.UNREGISTER).apply { replyTo = replies }) }
        vpn = null
        if (bound) runCatching { unbindService(connection) }
        bound = false
    }

    private fun render(problem: String? = null) {
        val tile = qsTile ?: return
        val phase = liveState
        val subtitle = problem ?: when (phase) {
            "connected" -> "Подключён"
            "connecting" -> "Подключается…"
            "disconnecting" -> "Отключается…"
            "error" -> "Ошибка подключения"
            "disconnected" -> "Выключен"
            else -> "Проверяем VPN…"
        }
        tile.label = "Flint VPN"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_status)
        tile.subtitle = subtitle
        tile.stateDescription = subtitle
        tile.contentDescription = "Flint VPN. $subtitle"
        tile.state = when {
            problem != null -> Tile.STATE_INACTIVE
            phase == "connected" -> Tile.STATE_ACTIVE
            phase == null || phase == "disconnecting" -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        runCatching { tile.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        val now = SystemClock.elapsedRealtime()
        if (now - lastClick < 600) return
        lastClick = now
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        try {
            val running = liveState in setOf("connected", "connecting")
            if (!running && !File(filesDir, "last-vpn-config.json").isFile) {
                Toast.makeText(this, "Выберите сервер и подключитесь во Flint один раз.", Toast.LENGTH_LONG).show()
                open(Intent(this, MainActivity::class.java))
            } else if (!running && VpnService.prepare(this) != null) {
                open(Intent(this, ToggleActivity::class.java))
            } else {
                // The service decides whether to start/stop using its own current state.
                HomeWidget.toggleIntent(this).send()
            }
        } catch (_: Exception) {
            render("Откройте Flint")
            Toast.makeText(this, "Не удалось переключить VPN. Откройте Flint и попробуйте снова.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 87, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(intent)
    }

    companion object {
        fun setup(activity: Activity) {
            if (BuildConfig.IS_TV) return
            val s = FlintStyle(activity); val p = s.panel("Кнопка в шторке")
            val help = "Откройте шторку быстрых настроек → Изменить (карандаш) → найдите Flint VPN и перетащите кнопку к активным."
            s.add(p.body, s.label("Включайте и выключайте VPN, не открывая приложение. Подсветка кнопки показывает подключение.", color = s.muted))
            s.add(p.body, s.label(help, color = s.muted))
            if (!File(activity.filesDir, "last-vpn-config.json").isFile)
                s.add(p.body, s.label("Сначала подключитесь к выбранному серверу во Flint и разрешите VPN.", color = s.muted))
            if (Build.VERSION.SDK_INT >= 33) {
                val add = s.primary("Добавить в шторку") {}
                add.setOnClickListener {
                    add.isEnabled = false
                    p.message.text = "Подтвердите добавление в системном окне. Если окно не появилось, воспользуйтесь инструкцией выше."
                    try {
                        val manager = activity.getSystemService(StatusBarManager::class.java)
                        manager.requestAddTileService(ComponentName(activity, FlintTileService::class.java), "Flint VPN",
                            Icon.createWithResource(activity, R.drawable.ic_vpn_status), activity.mainExecutor) { result ->
                            add.isEnabled = true
                            p.message.text = when (result) {
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Flint VPN добавлен в шторку."
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Flint VPN уже есть в шторке. При необходимости переместите его через «Изменить»."
                                else -> "Кнопка не добавлена. Можно добавить её вручную по инструкции выше."
                            }
                        }
                    } catch (_: Exception) { add.isEnabled = true; p.message.text = help }
                }
                s.add(p.body, add, 50)
            }
        }
    }
}
