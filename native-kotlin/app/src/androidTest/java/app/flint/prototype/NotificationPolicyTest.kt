package app.flint.prototype

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.flint.prototype.vpn.VpnNotifications
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationPolicyTest {
    @Test fun qtPriorityMigrationPreservesBlockedAndUserConfiguredChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefix = "notification-test-" + UUID.randomUUID()
        val old = "$prefix-old"; val next = "$prefix-new"; val blocked = "$prefix-blocked"
        try {
            manager.createNotificationChannel(NotificationChannel(old, "Fixture", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            assertEquals(next, VpnNotifications.ensureChannel(context, next, listOf(old)))
            val channel = manager.getNotificationChannel(next)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
            assertNull(channel.sound); assertFalse(channel.shouldVibrate()); assertFalse(channel.canShowBadge())
            assertEquals(next, VpnNotifications.ensureChannel(context, next, listOf(old)))
            manager.createNotificationChannel(NotificationChannel(blocked, "Blocked fixture", NotificationManager.IMPORTANCE_NONE))
            assertEquals(blocked, VpnNotifications.ensureChannel(context, "$prefix-unused", listOf(blocked)))
            assertNull(manager.getNotificationChannel("$prefix-unused"))
            assertTrue(VpnNotifications.preserveLegacy(NotificationManager.IMPORTANCE_LOW, true, false))
            assertTrue(VpnNotifications.preserveLegacy(NotificationManager.IMPORTANCE_LOW, false, true))
            assertTrue(VpnNotifications.preserveLegacy(NotificationManager.IMPORTANCE_NONE, false, false))
            assertFalse(VpnNotifications.preserveLegacy(NotificationManager.IMPORTANCE_LOW, false, false))
        } finally { listOf(old,next,blocked,"$prefix-unused").forEach(manager::deleteNotificationChannel) }
    }
}
