package app.flint.prototype.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        first.close()

        // Force Activity background and unbind every visible client, then create
        // a fresh client. No status is synthesized from persistent preferences.
        shell("input keyevent KEYCODE_HOME")
        val second = bind()
        assertEquals("connected", second.await().getString(VpnContract.STATE))
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())

        // A real switch with an active tunnel must serialize stop/start safely.
        foregroundActivity()
        connect(fixtureConfig().put("flintServerId", "fixture-switched"))
        val switched = second.await {
            it.getString(VpnContract.STATE) == "connected" && it.getString(VpnContract.SERVER_ID) == "fixture-switched"
        }
        assertEquals("fixture-switched", switched.getString(VpnContract.SERVER_ID))
        assertEquals("FLINT_VPN_TUNNEL_OK", throughTunnel())
        second.send(VpnContract.DISCONNECT)
        assertEquals("disconnected", second.await { it.getString(VpnContract.STATE) == "disconnected" }.getString(VpnContract.STATE))
    }

    private fun foregroundActivity() {
        context.startActivity(Intent().setClassName(context.packageName, "app.flint.prototype.MainActivity").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        instrumentation.waitForIdleSync()
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
            val connection = URL("http://198.18.0.1:18080/").openConnection() as HttpURLConnection
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

    private fun fixtureConfig(): JSONObject {
        val user = JSONObject().put("id", "11111111-1111-4111-8111-111111111111").put("encryption", "none")
        val endpoint = JSONObject().put("address", "10.0.2.2").put("port", 18443)
            .put("users", JSONArray().put(user))
        val outbound = JSONObject().put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(endpoint)))
            .put("streamSettings", JSONObject().put("network", "tcp").put("security", "none"))
        val native = JSONObject().put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", JSONArray().put(JSONObject().put("protocol", "socks").put("listen", "127.0.0.1")
                .put("port", 10808).put("settings", JSONObject().put("udp", true))))
            .put("outbounds", JSONArray().put(outbound))
        return JSONObject().put("protocol", "xray").put("hostName", "10.0.2.2")
            .put("dns1", "1.1.1.1").put("dns2", "1.0.0.1").put("mtu", "1500")
            .put("description", "Local test fixture").put("flintServerId", "fixture-local")
            .put("flintRussianAppsDirect", false)
            .put("xray_config_data", JSONObject().put("config", native.toString()))
    }
}
