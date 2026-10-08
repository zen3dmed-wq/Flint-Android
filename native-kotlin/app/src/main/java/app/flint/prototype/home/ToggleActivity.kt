package app.flint.prototype.home

import android.app.Activity
import android.content.*
import android.net.VpnService
import android.os.*
import app.flint.prototype.MainActivity
import app.flint.prototype.vpn.FlintVpnService
import app.flint.prototype.vpn.VpnContract
import java.io.File
import java.util.UUID

/** A transparent trampoline queries the service; a stale colored icon never decides the action. */
class ToggleActivity : Activity() {
    private var bound = false
    private var remote: Messenger? = null
    private var handled = false
    private var permissionPending = false
    private val handler = Handler(Looper.getMainLooper())
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == VpnContract.STATUS && !handled) {
            handled = true
            when (message.data.getString(VpnContract.STATE)) {
                "connected", "connecting", "disconnecting" -> { remote?.send(Message.obtain(null, VpnContract.DISCONNECT)); finish() }
                else -> {
                    val permission = VpnService.prepare(this)
                    if (permission == null) startLast() else { permissionPending = true; startActivityForResult(permission, 1) }
                }
            }
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (handled || isFinishing || isDestroyed) return
            if (binder == null) { openMain(); return }
            remote = Messenger(binder); remote?.send(Message.obtain(null, VpnContract.REQUEST_STATUS).apply { replyTo = receiver })
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        permissionPending = saved?.getBoolean("permissionPending") == true
        handled = permissionPending
        bound = bindService(Intent(this, FlintVpnService::class.java), connection, BIND_AUTO_CREATE)
        handler.postDelayed({ if (!handled) openMain() }, 3000)
    }
    private fun startLast() {
        val previous = File(filesDir, "last-vpn-config.json")
        if (!previous.isFile) { openMain(); return }
        try {
            val dir = File(filesDir, VpnContract.CONFIG_DIRECTORY).apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString() + ".json"); previous.copyTo(file)
            startForegroundService(Intent(this, FlintVpnService::class.java).setAction(VpnContract.ACTION_CONNECT).putExtra(VpnContract.EXTRA_CONFIG_FILE, file.name))
        } catch (_: Exception) { openMain(); return }
        finish()
    }
    private fun openMain() { handled = true; startActivity(Intent(this, MainActivity::class.java)); finish() }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("permissionPending", permissionPending); super.onSaveInstanceState(out) }
    @Deprecated("Platform activity") override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        permissionPending = false
        if (code == 1 && result == RESULT_OK) startLast() else finish()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if (bound) unbindService(connection); super.onDestroy() }
}
