package org.amnezia.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** An explicit immutable PendingIntent is the only external entry point. */
class FlintHomePinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PINNED) return
        val token = intent.getStringExtra("token") ?: return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("confirmed", token).apply()
    }

    companion object {
        const val ACTION_PINNED = "app.flint.vpn.HOME_ITEM_PINNED"
        const val PREFERENCES = "flint_home_pin"
    }
}
