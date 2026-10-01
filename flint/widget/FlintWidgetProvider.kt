package org.amnezia.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.amnezia.vpn.util.Log

class FlintWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)

    private fun refresh(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(7000) { updateAll(context, VpnStateStore.getVpnState()) }
            } catch (e: Exception) {
                Log.w("FlintWidget", "Widget refresh unavailable: ${e.javaClass.simpleName}")
            } finally { pending.finish() }
        }
    }

    companion object {
        fun model(context: Context, state: VpnState): FlintWidgetModel {
            val running = state.vpnProto?.let { AmneziaVpnService.isRunning(context, it.processName) } ?: false
            return flintWidgetModel(state.protocolState.name, running, state.vpnProto != null && state.serverName != null)
        }

        fun updateAll(context: Context, state: VpnState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FlintWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val model = model(context, state)
            val open = PendingIntent.getActivity(context, 81020,
                Intent(context, AmneziaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val toggle = PendingIntent.getActivity(context, 81021,
                Intent(context, FlintWidgetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(context.packageName, R.layout.flint_widget)
            views.setTextViewText(R.id.flint_widget_status, model.title)
            views.setTextViewText(R.id.flint_widget_toggle, model.button)
            views.setTextColor(R.id.flint_widget_status, Color.parseColor(if (model.action == FlintWidgetAction.DISCONNECT) "#4AE6A3" else "#C3D5DF"))
            views.setBoolean(R.id.flint_widget_toggle, "setEnabled", model.action != FlintWidgetAction.WAIT)
            views.setOnClickPendingIntent(R.id.flint_widget_toggle, toggle)
            views.setOnClickPendingIntent(R.id.flint_widget_header, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
