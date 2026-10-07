package org.amnezia.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.SystemClock
import org.amnezia.vpn.util.Log

object FlintHomeShortcut {
    const val ID = "flint-vpn-toggle-live"
    private var lastColour: FlintHomeColour? = null
    private var lastUpdate = 0L
    fun info(context: Context, colour: FlintHomeColour): ShortcutInfo = ShortcutInfo.Builder(context, ID)
        .setActivity(ComponentName(context, AmneziaActivity::class.java))
        .setShortLabel(colour.label).setLongLabel("Flint — включить / выключить VPN")
        .setIcon(Icon.createWithAdaptiveBitmap(FlintHomeIcon.bitmap(context, colour, true)))
        .setIntent(Intent(context, FlintWidgetActivity::class.java).setAction("app.flint.vpn.TOGGLE_FROM_HOME")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        .setRank(0).build()

    /** Called on IO, including when no AppWidget is installed. Never block VPN state storage. */
    @Synchronized fun update(context: Context, colour: FlintHomeColour) {
        if (lastColour == colour && SystemClock.elapsedRealtime()-lastUpdate < 300000) return
        try {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            if (manager.addDynamicShortcuts(listOf(info(context, colour)))) {
                lastColour = colour; lastUpdate = SystemClock.elapsedRealtime()
            }
        } catch (e: RuntimeException) { Log.w("FlintHome", "Shortcut update: ${e.javaClass.simpleName}") }
    }
}
