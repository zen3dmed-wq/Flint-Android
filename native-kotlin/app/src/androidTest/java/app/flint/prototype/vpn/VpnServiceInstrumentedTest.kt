package app.flint.prototype.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.flint.prototype.imports.ServerProfile
import app.flint.prototype.imports.XrayConfigBuilder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real cross-process IPC; optional test traverses TUN and native core. */
@RunWith(AndroidJUnit4::class)
class VpnServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val bindings = mutableListOf<Binding>()

    @After fun stopFixtureVpn() {
        // Only the separately installed prototype's service/app-op is modified.
        runCatching {
            val live = bindings.lastOrNull { !it.closed } ?: bind()
            live.send(VpnContract.DISCONNECT)
            live.await { it.getString(VpnContract.STATE) == "disconnected" }
        }
        bindings.forEach { it.close() }
        shell("appops set ${context.packageName} ACTIVATE_VPN default")
    }

    @Test fun bindingAndRebindingDoNotClaimAConnection() {
        shell("appops set ${context.packageName} ACTIVATE_VPN deny")
        val first = bind()
        assertEquals("disconnected", first.await().getString(VpnContract.STATE))
        first.send(VpnContract.REQUEST_STATUS)
        assertEquals("disconnected", first.await().getString(VpnContract.STATE))
        first.close()
        val second = bind()
        assertEquals("disconnected", second.await().getString(VpnContract.STATE))
    }

    @Test fun noVpnPermissionNeverReportsConnected() {
        shell("appops set ${context.packageName} ACTIVATE_VPN deny")
        assertNotNull("Test precondition: prototype must not be prepared", VpnService.prepare(context))
        foregroundActivity()
        val binding = bind()
        binding.await()
        connect(fixtureConfig())
        val terminal = binding.await { it.getString(VpnContract.STATE) == "error" }
        assertEquals("error", terminal.getString(VpnContract.STATE))
        assertFalse(binding.states.contains("connected"))
        assertNotNull(VpnService.prepare(context))
    }

    @Test fun nativeVpnCarriesTrafficAndSurvivesActivityUnbind() {
        assumeTrue("CI loopback fixture must be explicitly enabled",
            InstrumentationRegistry.getArguments().getString("flintLocalVpnTest") == "true")
        foregroundActivity()
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        assertNull("Granting only prototype VPN app-op must prepare service", VpnService.prepare(context))
        val first = bind()
        first.await()
        connect(fixtureConfig())
        val connected = first.await { it.getString(VpnContract.STATE) == "connected" }
        assertEquals("fixture-local", connected.getString(VpnContract.SERVER_ID))
        assertFalse(connected.getBoolean(VpnContract.RU_DIRECT))
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())
        assertDnsThroughTunnel()
        first.close()

        // Force Activity background and unbind every visible client, then create
        // a fresh client. No status is synthesized from persistent preferences.
        shell("input keyevent KEYCODE_HOME")
        val second = bind()
        assertEquals("connected", second.await().getString(VpnContract.STATE))
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())

        // Exercise the real bundled expanded catalog through the private file.
        // This confirms local TUN/core startup, not reachability of Russian sites.
        // 198.18.0.1 can match private direct rules, so do not use the HTTP marker
        // while this catalog is active; restore ordinary VPN routing below.
        foregroundActivity()
        verifyReopenedMainScreen()
        val russian = fixtureConfig(ruDirect = true).put("flintServerId", "fixture-russian-routing")
        val expanded = JSONObject(russian.getJSONObject("xray_config_data").getString("config"))
        val expandedRules = expanded.getJSONObject("routing").getJSONArray("rules")
        assertTrue("Use the shipped domain catalog, not a miniature fixture", expandedRules.getJSONObject(0).getJSONArray("domain").length() > 100)
        assertTrue("Use the shipped IP catalog, not a miniature fixture", expandedRules.getJSONObject(1).getJSONArray("ip").length() > 100)
        connect(russian)
        val russianStarted = second.await {
            it.getString(VpnContract.STATE) == "connected" && it.getString(VpnContract.SERVER_ID) == "fixture-russian-routing"
        }
        assertTrue(russianStarted.getBoolean(VpnContract.RU_DIRECT))
        assertDnsThroughTunnel()

        // A real switch back from the large routing profile must also stop/start
        // safely, and forwarding is verified again through the VLESS fixture.
        connect(fixtureConfig().put("flintServerId", "fixture-switched"))
        val switched = second.await {
            it.getString(VpnContract.STATE) == "connected" && it.getString(VpnContract.SERVER_ID) == "fixture-switched"
        }
        assertEquals("fixture-switched", switched.getString(VpnContract.SERVER_ID))
        assertFalse(switched.getBoolean(VpnContract.RU_DIRECT))
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())
        val publicKey = InstrumentationRegistry.getArguments().getString("flintRealityPublicKey").orEmpty()
        assertTrue("Reality fixture key is required", publicKey.isNotBlank())
        val reality = fixtureConfig()
        val realConfig = JSONObject(reality.getJSONObject("xray_config_data").getString("config"))
        val realOutbound = realConfig.getJSONArray("outbounds").getJSONObject(0)
        realOutbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("port", 18444)
        realOutbound.put("streamSettings", JSONObject().put("network", "tcp").put("security", "reality")
            .put("realitySettings", JSONObject().put("serverName", "localhost").put("publicKey", publicKey).put("shortId", "1234abcd").put("fingerprint", "chrome")))
        reality.getJSONObject("xray_config_data").put("config", realConfig.toString())
        reality.put("flintServerId", "fixture-reality")
        connect(reality)
        second.await { it.getString(VpnContract.STATE) == "connected" && it.getString(VpnContract.SERVER_ID) == "fixture-reality" }
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())
        assertDnsThroughTunnel()
        second.send(VpnContract.DISCONNECT)
        assertEquals("disconnected", second.await { it.getString(VpnContract.STATE) == "disconnected" }.getString(VpnContract.STATE))
    }

    @Test fun unreachableServerNeverBecomesConnected() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("flintLocalVpnTest") == "true")
        foregroundActivity()
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        val binding = bind(); binding.await()
        val config = fixtureConfig()
        val native = JSONObject(config.getJSONObject("xray_config_data").getString("config"))
        native.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings")
            .getJSONArray("vnext").getJSONObject(0).put("port", 18449)
        config.getJSONObject("xray_config_data").put("config", native.toString())
        connect(config)
        val failed = binding.await { it.getString(VpnContract.STATE) == "error" }
        assertTrue(failed.getString(VpnContract.MESSAGE).orEmpty().contains("DATA_PATH"))
        assertFalse("A local TUN without working forwarding is not connected", binding.states.contains("connected"))
    }

    @Test fun huskyNotificationPersistsWithRealVpnAndMainHidden() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("flintLocalVpnTest") == "true")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
        foregroundActivity()
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        val binding = bind(); binding.await(); connect(fixtureConfig())
        binding.await { it.getString(VpnContract.STATE) == "connected" }
        shell("input keyevent KEYCODE_HOME")
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val deadline = SystemClock.uptimeMillis() + 5000
        var notice: android.app.Notification? = null
        while (SystemClock.uptimeMillis() < deadline) {
            notice = manager.activeNotifications.firstOrNull { it.id == VpnNotifications.ID }?.notification
            if (notice?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.contains("VPN включён") == true) break
            SystemClock.sleep(50)
        }
        val posted = requireNotNull(notice)
        assertEquals(app.flint.prototype.R.drawable.ic_vpn_status, posted.smallIcon.resId)
        assertEquals("FLINT", posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        assertTrue(posted.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val channel = manager.getNotificationChannel(posted.channelId)
        assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound); assertFalse(channel.shouldVibrate())
        assertEquals(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE, posted.foregroundServiceBehavior)
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())
        val directory = File(context.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        File(directory, "vpn-status-icon-real.png").outputStream().use {
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        foregroundActivity(); binding.send(VpnContract.REFRESH_NOTIFICATION)
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())
    }

    @Test fun blockedNotificationsNeverDisconnectWorkingTunnel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("flintLocalVpnTest") == "true")
        shell("appops set ${context.packageName} POST_NOTIFICATION ignore")
        try {
            assertFalse(VpnNotifications.canPost(context))
            foregroundActivity(); shell("appops set ${context.packageName} ACTIVATE_VPN allow")
            val binding = bind(); binding.await(); connect(fixtureConfig())
            binding.await { it.getString(VpnContract.STATE) == "connected" }
            binding.send(VpnContract.REFRESH_NOTIFICATION)
            assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())
        } finally { shell("appops set ${context.packageName} POST_NOTIFICATION allow") }
    }

    @Test fun widgetAndShortcutToggleWithoutForegroundingMainActivity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("flintLocalVpnTest") == "true")
        foregroundActivity()
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        val binding = bind(); binding.await()
        connect(fixtureConfig())
        binding.await { it.getString(VpnContract.STATE) == "connected" }
        shell("input keyevent KEYCODE_HOME")
        val toggle = app.flint.prototype.home.HomeWidget.toggleIntent(context)
        assertTrue("Widget click must go to a service, not an Activity", toggle.isForegroundService)
        fun assertMainHidden() {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse("Home widget must never bring MainActivity to the foreground",
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .any { it.javaClass.name == "app.flint.prototype.MainActivity" })
            }
        }
        fun awaitColor(state: String) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline && app.flint.prototype.home.HomeWidget.stored(context) != state) SystemClock.sleep(50)
            assertEquals("Launcher color must reflect the actual service", state, app.flint.prototype.home.HomeWidget.stored(context))
        }
        toggle.send()
        binding.await { it.getString(VpnContract.STATE) == "disconnected" }
        awaitColor("disconnected"); assertMainHidden()
        toggle.send()
        binding.await { it.getString(VpnContract.STATE) == "connected" }
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel()); awaitColor("connected"); assertMainHidden()
        // Existing and newly pinned shortcuts both target this isolated trampoline.
        context.startActivity(app.flint.prototype.home.HomeWidget.shortcut(context, "connected").intent)
        binding.await { it.getString(VpnContract.STATE) == "disconnected" }
        awaitColor("disconnected"); assertMainHidden()
    }

    private fun foregroundActivity() {
        context.startActivity(Intent().setClassName(context.packageName, "app.flint.prototype.MainActivity").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        instrumentation.waitForIdleSync()
    }

    private fun verifyReopenedMainScreen() {
        val deadline = SystemClock.uptimeMillis() + 5000
        var verified = false
        while (SystemClock.uptimeMillis() < deadline) {
            var mainResumed = false
            instrumentation.runOnMainSync {
                mainResumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it.javaClass.name == "app.flint.prototype.MainActivity" }
            }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (mainResumed && root?.packageName?.toString() == context.packageName) {
                val disconnect = root.findAccessibilityNodeInfosByText("Отключить VPN")
                    .any { (it.text?.toString() == "Отключить VPN" || it.contentDescription?.toString() == "Отключить VPN") && it.isVisibleToUser && it.isEnabled && it.isClickable }
                val protected = root.findAccessibilityNodeInfosByText("Вы защищены")
                    .any { it.text?.toString() == "Вы защищены" && it.isVisibleToUser }
                if (disconnect && protected) {
                    verified = true
                    break
                }
            }
            SystemClock.sleep(100)
        }
        assertTrue("Real MainActivity must restore connected service state within five seconds after HOME/reopen", verified)
        // Confirm the same real connection still carries packets at capture time.
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Screenshot of reopened MainActivity must be available", image)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "vpn-main-connected-real.png").outputStream().use { output ->
            assertTrue(image!!.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        File(directory, "vpn-main-connected-real.json").writeText(JSONObject()
            .put("syntheticUiState", false)
            .put("vpnStarted", true)
            .put("localFixtureTun", true)
            .put("vpnPathVerified", "Android TUN -> SOCKS -> VLESS -> loopback HTTP fixture")
            .put("activity", "app.flint.prototype.MainActivity")
            .put("foregroundActivityVerified", true)
            .put("returnedAfterHome", true)
            .put("accessibilityTexts", JSONArray(listOf("Отключить VPN", "Вы защищены")))
            .put("phase", "CONNECTED")
            .put("serverId", "fixture-local")
            .put("ruDirect", false)
            .put("widthPx", image!!.width)
            .put("heightPx", image.height)
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .toString(2))
        image.recycle()
    }

    private fun connect(config: JSONObject) {
        val directory = File(context.filesDir, VpnContract.CONFIG_DIRECTORY).apply { mkdirs() }
        val file = File(directory, UUID.randomUUID().toString() + ".json")
        file.writeText(config.toString())
        ContextCompat.startForegroundService(context, Intent(context, FlintVpnService::class.java)
            .setAction(VpnContract.ACTION_CONNECT).putExtra(VpnContract.EXTRA_CONFIG_FILE, file.name))
    }

    private fun bind(): Binding = Binding().also { binding ->
        bindings.add(binding)
        assertTrue(context.bindService(Intent(context, FlintVpnService::class.java), binding, Context.BIND_AUTO_CREATE))
        assertTrue("VPN service bind timed out", binding.bound.await(10, TimeUnit.SECONDS))
    }

    private fun throughTunnel(): String {
        var failure: Exception? = null
        repeat(5) {
            val connection = URL("http://198.18.0.1:18080/android/tun-marker").openConnection() as HttpURLConnection
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            try {
                assertEquals(200, connection.responseCode)
                return connection.inputStream.bufferedReader().use { it.readText() }
            } catch (error: Exception) { failure = error }
            finally { connection.disconnect() }
        }
        throw AssertionError("Native VPN failed to carry fixture traffic", failure)
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private inner class Binding : ServiceConnection {
        val bound = CountDownLatch(1)
        val queue = LinkedBlockingQueue<Bundle>()
        val states = java.util.concurrent.CopyOnWriteArrayList<String>()
        var service: Messenger? = null
        var closed = false
        private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what == VpnContract.STATUS) {
                    states.add(message.data.getString(VpnContract.STATE).orEmpty())
                    queue.offer(Bundle(message.data))
                }
            }
        })
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            send(VpnContract.REGISTER)
            bound.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
        fun send(command: Int) { service?.send(Message.obtain(null, command).apply { replyTo = replies }) }
        fun await(predicate: (Bundle) -> Boolean = { true }): Bundle {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
            while (System.nanoTime() < deadline) {
                val next = queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (predicate(next)) return next
            }
            throw AssertionError("Service status timed out; observed states: $states")
        }
        fun close() {
            if (closed) return
            runCatching { send(VpnContract.UNREGISTER) }
            context.unbindService(this)
            closed = true
        }
    }

    private fun fixtureConfig(ruDirect: Boolean = false): JSONObject {
        val user = JSONObject().put("id", "11111111-1111-4111-8111-111111111111").put("encryption", "none")
        val endpoint = JSONObject().put("address", "10.0.2.2").put("port", 18443)
            .put("users", JSONArray().put(user))
        val outbound = JSONObject().put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(endpoint)))
            .put("streamSettings", JSONObject().put("network", "tcp").put("security", "none"))
        var native = JSONObject().put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", JSONArray().put(JSONObject().put("protocol", "socks").put("listen", "127.0.0.1")
                .put("port", 10808).put("settings", JSONObject().put("udp", true))))
            .put("outbounds", JSONArray().put(outbound))
        if (ruDirect) {
            val profile = ServerProfile("fixture-local", "Local test fixture", "10.0.2.2", 18443, "vless", outbound.toString())
            val catalog = context.assets.open("flint-routing-catalog.json").bufferedReader().use { it.readText() }
            native = JSONObject(XrayConfigBuilder.build(profile, ruDirect = true, routingCatalogJson = catalog))
        }
        return JSONObject().put("protocol", "xray").put("hostName", "10.0.2.2")
            .put("flintTestProbeUrl", "http://93.184.215.14:18080/android/verify")
            .put("dns1", "1.1.1.1").put("dns2", "1.0.0.1").put("mtu", "1500")
            .put("description", "Local test fixture").put("flintServerId", "fixture-local")
            .put("flintRussianAppsDirect", ruDirect)
            .put("xray_config_data", JSONObject().put("config", native.toString()))
    }

    private fun assertDnsThroughTunnel() {
        val name = "flint-${System.nanoTime()}.example"
        val addresses = java.net.InetAddress.getAllByName(name)
        assertTrue("Android DNS must travel through TUN, SOCKS UDP and VLESS", addresses.any { it.hostAddress == "93.184.215.14" })
        val c = URL("http://$name:18080/android/dns-and-http").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        try { assertEquals("FLINT_VPN_TUNNEL_OK", c.inputStream.bufferedReader().use { it.readText() }) }
        finally { c.disconnect() }
    }
}
