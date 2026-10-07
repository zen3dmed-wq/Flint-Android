package org.amnezia.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.vpn.util.Log

class FlintWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)
    private fun refresh(context: Context) {
        val pending = goAsync()
        scope.launch { try { refreshStored(context.applicationContext) } finally { pending.finish() } }
    }
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var previewPublished = false
        fun schedule(context: Context) { if (FlintBuild.DISTRIBUTION == "tv") return; scope.launch { refreshStored(context.applicationContext) } }
        private suspend fun refreshStored(context: Context) {
            try { withTimeout(7000) { mutex.withLock { updateAll(context, VpnStateStore.getVpnState()) } } }
            catch (e: Exception) { Log.w("FlintWidget", "Home update: ${e.javaClass.simpleName}") }
        }
        fun model(context: Context, state: VpnState): FlintWidgetModel {
            val running = state.vpnProto?.let { AmneziaVpnService.isRunning(context, it.processName) } ?: false
            return flintWidgetModel(state.protocolState.name, running, state.vpnProto != null && state.serverName != null)
        }
        fun updateAll(context: Context, state: VpnState) {
            if (FlintBuild.DISTRIBUTION == "tv") return
            val model = model(context, state)
            val colour = flintHomeColour(model.action, state.flintError)
            // Pinned shortcuts must update even when no widget exists.
            FlintHomeShortcut.update(context, colour)
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, FlintWidgetProvider::class.java)
            val views = RemoteViews(context.packageName, R.layout.flint_widget)
            views.setImageViewBitmap(R.id.flint_widget_toggle, FlintHomeIcon.bitmap(context, colour))
            if (Build.VERSION.SDK_INT >= 35 && !previewPublished) {
                runCatching { previewPublished = manager.setWidgetPreview(component, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, views) }
            }
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val toggle = PendingIntent.getActivity(context, 81021,
                Intent(context, FlintWidgetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setContentDescription(R.id.flint_widget_toggle, "Flint. ${model.title}. ${model.button}")
            views.setBoolean(R.id.flint_widget_toggle, "setEnabled", model.action != FlintWidgetAction.WAIT)
            views.setOnClickPendingIntent(R.id.flint_widget_toggle, toggle)
            manager.updateAppWidget(ids, views)
        }
    }
}
