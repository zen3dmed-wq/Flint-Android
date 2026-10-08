package app.flint.prototype.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import app.flint.prototype.imports.ServerProfile
import app.flint.prototype.imports.XrayConfigBuilder
import go.Seq
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.vpn.protocol.xray.libXray.*
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Isolated UI-process core. Never changes TUN or stops the service-process engine. */
object ProfileProbe {
    private val lock = Mutex()
    data class Result(val latency: Long?, val fingerprint: String?, val available: Boolean)
    suspend fun check(context: Context, profile: ServerProfile, initialize: Boolean = false): Result = lock.withLock {
        runInterruptible(Dispatchers.IO) {
            Seq.setContext(context.applicationContext)
            LibXray.initLogger(object : Logger {
                override fun warning(s: String) {}
                override fun error(s: String) {}
                override fun write(msg: ByteArray): Long = msg.size.toLong()
            })
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.let { c ->
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true }
            val controller = DialerController { fd ->
                try { if (network != null) ParcelFileDescriptor.fromFd(fd.toInt()).use { network.bindSocket(it.fileDescriptor) }; network != null }
                catch (_: Exception) { false }
            }
            LibXray.registerDialerController(controller); LibXray.registerListenerController(controller)
            val original = profile.outbound().optJSONObject("streamSettings")
            val tlsKey = if (original?.optString("security") == "reality") "realitySettings" else "tlsSettings"
            val first = original?.optJSONObject(tlsKey)?.optString("fingerprint").orEmpty()
            val variants = (listOf(first) + if (initialize && original?.optString("security") in setOf("reality", "tls")) listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "randomized") else emptyList()).distinct()
            val directory = context.getDir("profile-probes", Context.MODE_PRIVATE)
            for (fp in variants) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val config = JSONObject(XrayConfigBuilder.build(profile, port, false))
                val outbounds = config.getJSONArray("outbounds")
                val outbound = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.first { it.optString("protocol") == profile.protocol }
                outbound.optJSONObject("streamSettings")?.optJSONObject(tlsKey)?.let { if (fp.isNotBlank()) it.put("fingerprint", fp) }
                config.put("log", JSONObject().put("loglevel", "none"))
                val file = File.createTempFile("probe-", ".json", directory)
                var connection: HttpsURLConnection? = null
                try {
                    // Resolve endpoint on the physical network even while VPN is active.
                    val address = network?.getAllByName(profile.host)?.firstOrNull()?.hostAddress
                    if (address != null) outbound.optJSONObject("settings")?.let { settings ->
                        for (key in listOf("vnext", "servers")) settings.optJSONArray(key)?.optJSONObject(0)?.put("address", address)
                    }
                    file.writeText(config.toString()); LibXray.initXray(directory.absolutePath)
                    if (!LibXray.runXray(directory.absolutePath, file.absolutePath, 64L * 1024 * 1024).isNullOrBlank()) continue
                    val start = System.nanoTime()
                    for (url in listOf("https://cp.cloudflare.com/generate_204", "https://www.gstatic.com/generate_204")) {
                        try {
                            connection = URL(url).openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) as HttpsURLConnection
                            connection.connectTimeout = 2500; connection.readTimeout = 2500; connection.instanceFollowRedirects = false
                            if (connection.responseCode == 204) return@runInterruptible Result(maxOf(1, (System.nanoTime() - start) / 1_000_000), fp.takeIf { it.isNotBlank() }, true)
                        } catch (_: java.io.IOException) { } finally { connection?.disconnect(); connection = null }
                    }
                } finally { connection?.disconnect(); runCatching { LibXray.stopXray() }; file.delete() }
            }
            Result(null, null, false)
        }
    }
    fun withFingerprint(profile: ServerProfile, fingerprint: String?): ServerProfile {
        if (fingerprint.isNullOrBlank()) return profile
        val outbound = profile.outbound()
        val stream = outbound.optJSONObject("streamSettings") ?: return profile
        val key = if (stream.optString("security") == "reality") "realitySettings" else "tlsSettings"
        stream.optJSONObject(key)?.put("fingerprint", fingerprint)
        val original = profile.originalConfigJson?.let { JSONObject(it).apply { val list = getJSONArray("outbounds"); val index = (0 until list.length()).first { list.getJSONObject(it).optString("protocol") == profile.protocol }; list.put(index, outbound) }.toString() }
        return ServerProfile(profile.id, profile.name, profile.host, profile.port, profile.protocol, outbound.toString(), original)
    }
}
