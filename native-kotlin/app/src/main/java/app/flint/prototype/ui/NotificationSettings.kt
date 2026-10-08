package app.flint.prototype.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.flint.prototype.vpn.VpnNotifications

object NotificationSettings {
    const val REQUEST = 14
    fun requestOnce(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences("flint-notification-access", Activity.MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        runCatching { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST) }
    }

    fun show(activity: Activity) {
        val s = FlintStyle(activity); val p = s.panel("Значок VPN")
        val manager = activity.getSystemService(NotificationManager::class.java)
        val channelId = VpnNotifications.ensureChannel(activity)
        val channel = manager.getNotificationChannel(channelId)
        val missingPermission = Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val description = when {
            !VpnNotifications.canPost(activity) -> "Уведомления Flint отключены в Android. Разрешите их, чтобы видеть собачку в верхней строке при работающем VPN."
            channel?.importance == NotificationManager.IMPORTANCE_NONE -> "Уведомление VPN отключено. Включите его в настройках Android."
            channel != null && channel.importance < NotificationManager.IMPORTANCE_DEFAULT -> "Для VPN выбран тихий или свёрнутый режим. Android может скрывать его значок сверху. В настройках уведомления включите обычный показ; звук можно оставить выключенным."
            else -> "Показ уведомления VPN включён. Если собачки сверху нет, включите «Значки уведомлений» в настройках строки состояния телефона. Название пункта зависит от устройства."
        }
        s.add(p.body, android.widget.ImageView(activity).apply { setImageResource(app.flint.prototype.R.drawable.ic_vpn_status) }, 40)
        s.add(p.body, s.label(description, color = s.muted))
        if (missingPermission && activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            s.add(p.body, s.button("Разрешить уведомления") {
                p.dialog.dismiss()
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
            }, 48)
        }
        s.add(p.body, s.button("Открыть настройки Android") {
            val intent = if (!VpnNotifications.canPost(activity)) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            try { activity.startActivity(intent); p.dialog.dismiss() }
            catch (_: Exception) {
                try { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))); p.dialog.dismiss() }
                catch (_: Exception) { p.error("Откройте настройки телефона → Приложения → Flint → Уведомления.") }
            }
        }, 48)
        s.closeButton(p)
    }
}
