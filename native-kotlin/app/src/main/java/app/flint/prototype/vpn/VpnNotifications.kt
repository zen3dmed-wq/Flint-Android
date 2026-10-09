package app.flint.prototype.vpn

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.flint.prototype.R

/** Same visible, soundless service notification as Qt; user settings always win. */
object VpnNotifications {
    const val ID = 8125
    const val CHANNEL = "flint-vpn-status"
    val legacyChannels = listOf("flint-native-vpn", "org.amnezia.vpn.notifications")

    internal fun preserveLegacy(importance: Int, userSetImportance: Boolean, userSetSound: Boolean) =
        importance == NotificationManager.IMPORTANCE_NONE || userSetImportance || userSetSound

    fun ensureChannel(context: Context, currentId: String = CHANNEL,
                      legacyIds: List<String> = legacyChannels): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(currentId) != null) return currentId
        val previous = legacyIds.mapNotNull(manager::getNotificationChannel)
        // Never route around an explicit block or a user's channel preferences.
        val retained = previous.firstOrNull { it.importance == NotificationManager.IMPORTANCE_NONE }
            ?: previous.firstOrNull { preserveLegacy(it.importance, it.hasUserSetImportance(), it.hasUserSetSound()) }
        if (retained != null) return retained.id
        manager.createNotificationChannel(NotificationChannel(currentId, "Состояние VPN", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Значок Flint и состояние подключения VPN"
            setShowBadge(false); setSound(null, null); enableVibration(false); enableLights(false)
        })
        return currentId
    }

    fun canPost(context: Context) =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun canPostOnChannel(context: Context, channelId: String): Boolean {
        if (!canPost(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE &&
            (channel.group?.let(manager::getNotificationChannelGroup)?.isBlocked != true)
    }

    fun build(context: Context, channel: String, content: String): Notification {
        val stop = PendingIntent.getService(context, 1,
            Intent(context, FlintVpnService::class.java).setAction(VpnContract.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismissed = PendingIntent.getService(context, 2,
            Intent(context, FlintVpnService::class.java).setAction(VpnContract.ACTION_RESTORE_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_vpn_status).setContentTitle("FLINT")
            .setContentText(content).setOngoing(true).setAutoCancel(false).setShowWhen(false).setOnlyAlertOnce(true)
            .setDeleteIntent(dismissed)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))?.let { intent ->
            builder.setContentIntent(PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build().apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }
}
