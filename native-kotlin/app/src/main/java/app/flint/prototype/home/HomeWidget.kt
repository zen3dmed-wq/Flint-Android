package app.flint.prototype.home

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.*
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.widget.RemoteViews
import app.flint.prototype.BuildConfig
import app.flint.prototype.R
import app.flint.prototype.ui.FlintStyle
import app.flint.prototype.vpn.FlintVpnService
import app.flint.prototype.vpn.VpnContract
import java.io.File

class HomeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refreshLive(context) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) refreshLive(context)
    }
    private fun refreshLive(context: Context) {
        refresh(context, stored(context)) // Also replaces legacy Activity PendingIntents after an upgrade.
        val pending = goAsync()
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        var bound = false; var done = false
        lateinit var connection: ServiceConnection
        fun finish(state: String?) {
            if (done) return
            done = true; handler.removeCallbacksAndMessages(null)
            if (state != null) refresh(app, state)
            if (bound) runCatching { app.unbindService(connection) }
            pending?.finish()
        }
        val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == VpnContract.STATUS) finish(msg.data.getString(VpnContract.STATE))
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (done) return
                runCatching { Messenger(service).send(Message.obtain(null, VpnContract.REQUEST_STATUS).apply { replyTo = replies }) }.onFailure { finish(null) }
            }
            override fun onServiceDisconnected(name: ComponentName?) { finish("disconnected") }
        }
        bound = runCatching { app.bindService(Intent(app, FlintVpnService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) finish(null) else handler.postDelayed({ finish(null) }, 4000)
    }
    companion object {
        fun stored(context: Context) = runCatching { File(context.filesDir, "widget-state").readText() }.getOrDefault("disconnected")
        fun icon(context: Context, state: String, adaptive: Boolean = false): Bitmap {
            val color = Color.parseColor(when (state) { "connected" -> "#4AE6A3"; "connecting", "disconnecting" -> "#F1C75B"; "error" -> "#EF626B"; else -> "#82909E" })
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.color = color }
            if (adaptive) canvas.drawColor(color) else canvas.drawRoundRect(RectF(0f, 0f, 192f, 192f), 42f, 42f, paint)
            val source = BitmapFactory.decodeResource(context.resources, R.drawable.flint_emblem)
            val inset = if (adaptive) 40f else 12f
            canvas.save(); canvas.clipPath(Path().apply { addCircle(96f, 96f, 96f-inset, Path.Direction.CW) })
            canvas.drawBitmap(source, Rect((source.width*.075).toInt(), (source.height*.075).toInt(), (source.width*.925).toInt(), (source.height*.925).toInt()), RectF(inset, inset, 192-inset, 192-inset), paint)
            canvas.restore(); source.recycle(); return bitmap
        }
        fun shortcut(context: Context, state: String) = ShortcutInfo.Builder(context, "flint-vpn-toggle-v2")
            .setShortLabel("VPN").setLongLabel("Включить / выключить Flint VPN")
            .setIcon(Icon.createWithAdaptiveBitmap(icon(context, state, true)))
            .setIntent(Intent(context, ToggleActivity::class.java).setAction("app.flint.TOGGLE")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            .build()
        fun toggleIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(context, 41,
            Intent(context, FlintVpnService::class.java).setAction(VpnContract.ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun refresh(context: Context, state: String) {
            if (BuildConfig.IS_TV) return
            runCatching {
                File(context.filesDir, "widget-state").writeText(state)
                val views = RemoteViews(context.packageName, R.layout.flint_widget)
                views.setImageViewBitmap(R.id.flint_widget_toggle, icon(context, state))
                views.setOnClickPendingIntent(R.id.flint_widget_toggle, toggleIntent(context))
                views.setContentDescription(R.id.flint_widget_toggle, if (state == "connected") "Flint. Отключить VPN" else "Flint. Подключить VPN")
                val manager = AppWidgetManager.getInstance(context); val component = ComponentName(context, HomeWidget::class.java)
                manager.updateAppWidget(manager.getAppWidgetIds(component), views)
                if (Build.VERSION.SDK_INT >= 35) manager.setWidgetPreview(component, android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, views)
                context.getSystemService(ShortcutManager::class.java)?.let { shortcuts ->
                    shortcuts.updateShortcuts(listOf(shortcut(context, state)))
                    if (shortcuts.dynamicShortcuts.isEmpty()) shortcuts.addDynamicShortcuts(listOf(shortcut(context, state)))
                }
            }
        }
        fun setup(activity: Activity) {
            val s = FlintStyle(activity); val p = s.panel("Кнопка Flint")
            s.add(p.body, s.label("Включайте и выключайте VPN одним нажатием с рабочего стола.", color = s.muted))
            val help = "Удерживайте пустое место рабочего стола → Виджеты → Flint VPN. В ColorOS проверьте раздел обычных виджетов Android. Если запрос не появляется, добавьте иконку VPN и проверьте разрешение добавлять значки."
            if (!File(activity.filesDir, "last-vpn-config.json").isFile)
                s.add(p.body, s.label("Сначала подключитесь к выбранному серверу в Flint и разрешите VPN. Кнопка запомнит последнее подключение.", color = s.muted))
            s.add(p.body, s.button("Добавить виджет 1×1") {
                val m = AppWidgetManager.getInstance(activity)
                val accepted = runCatching { m.isRequestPinAppWidgetSupported && m.requestPinAppWidget(ComponentName(activity, HomeWidget::class.java), null, null) }.getOrDefault(false)
                p.message.text = if (accepted) "Подтвердите добавление в окне рабочего стола. Если окно не появилось: $help" else help
            }, 50)
            s.add(p.body, s.button("Добавить иконку VPN") {
                val m = activity.getSystemService(ShortcutManager::class.java)
                val accepted = runCatching { m.isRequestPinShortcutSupported && m.requestPinShortcut(shortcut(activity, stored(activity)), null) }.getOrDefault(false)
                p.message.text = if (accepted) "Подтвердите добавление иконки. Её цвет отражает состояние VPN." else "Удерживайте значок Flint → VPN и перетащите кнопку на экран."
            }, 50)
            s.add(p.body, s.button("Как добавить вручную") { p.message.text = help }, 50)
            s.add(p.body, s.button("Настройки рабочего стола") { runCatching { activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.onFailure { p.message.text = help } }, 50)
            refresh(activity, stored(activity))
        }
    }
}
