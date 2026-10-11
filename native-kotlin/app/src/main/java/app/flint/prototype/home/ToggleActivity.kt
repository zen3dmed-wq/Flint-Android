package app.flint.prototype.home

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import app.flint.prototype.vpn.FlintVpnService
import app.flint.prototype.vpn.VpnContract

/** A shortcut needs an Activity; its empty task affinity keeps the main task hidden.
 * The actual widget sends a foreground-service PendingIntent without any Activity. */
class ToggleActivity : Activity() {
    private var permissionPending = false
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        overridePendingTransition(0, 0)
        permissionPending = saved?.getBoolean("permissionPending") == true
        if (permissionPending) return
        val permission = VpnService.prepare(this)
        if (permission != null) {
            permissionPending = true
            startActivityForResult(permission, 1)
        } else toggle()
    }
    private fun toggle() {
        try {
            startForegroundService(Intent(this, FlintVpnService::class.java).setAction(VpnContract.ACTION_TOGGLE))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось запустить VPN. Проверьте разрешение фоновой работы Flint.", Toast.LENGTH_LONG).show()
        }
        close()
    }
    private fun close() { if (isTaskRoot) finishAndRemoveTask() else finish(); overridePendingTransition(0, 0) }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("permissionPending", permissionPending); super.onSaveInstanceState(out) }
    @Deprecated("Platform activity") override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        permissionPending = false
        if (code == 1 && result == RESULT_OK) toggle() else close()
    }
}
