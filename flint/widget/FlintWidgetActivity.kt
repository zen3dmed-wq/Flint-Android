package org.amnezia.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** A visible user gesture entry point: no background activity trampoline. */
class FlintWidgetActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        overridePendingTransition(0, 0)
        val now = SystemClock.elapsedRealtime()
        if (now - lastAction < 1500) { finish(); return }
        lastAction = now
        scope.launch {
            try {
                val vpn = withTimeout(5000) { VpnStateStore.getVpnState() }
                // Re-read actual state at tap time; do not trust stale widget text.
                when (FlintWidgetProvider.model(applicationContext, vpn).action) {
                    FlintWidgetAction.DISCONNECT -> sendBroadcast(Intent(ACTION_DISCONNECT).setPackage(packageName))
                    FlintWidgetAction.CONNECT -> {
                        val proto = vpn.vpnProto!!
                        if (VpnService.prepare(applicationContext) != null) {
                            startActivity(Intent(this@FlintWidgetActivity, VpnRequestActivity::class.java).putExtra(EXTRA_PROTOCOL, proto))
                        } else {
                            ContextCompat.startForegroundService(this@FlintWidgetActivity,
                                Intent(this@FlintWidgetActivity, proto.serviceClass))
                        }
                    }
                    FlintWidgetAction.OPEN -> startActivity(Intent(this@FlintWidgetActivity, AmneziaActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                    FlintWidgetAction.WAIT -> Unit
                }
            } catch (e: Exception) {
                Toast.makeText(this@FlintWidgetActivity, "Откройте Flint, чтобы проверить подключение", Toast.LENGTH_LONG).show()
            } finally { finish() }
        }
    }
    override fun finish() { super.finish(); overridePendingTransition(0, 0) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { private var lastAction = -1500L }
}
