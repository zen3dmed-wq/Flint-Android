package app.flint.prototype.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.flint.prototype.account.FlintAccount
import app.flint.prototype.imports.ServerProfile
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/** Owned by the service so navigation, HOME and a closed Activity cannot disable balancing. */
object AutomaticMonitor {
    fun start(context: Context, scope: CoroutineScope, config: JSONObject, switch: (JSONObject) -> Unit): Job? {
        if (!config.optBoolean("flintAutomatic")) return null
        val candidates = config.optJSONArray("flintCandidates") ?: return null
        val profiles = (0 until candidates.length()).mapNotNull { i -> runCatching {
            val c = candidates.getJSONObject(i); ServerProfile(c.getString("id"), c.getString("name"), c.getString("host"), c.getInt("port"), "vless", c.getJSONObject("outbound").toString())
        }.getOrNull() }
        return scope.launch {
            val balance = ServerBalance.State(); val connectedAt = System.currentTimeMillis()
            val cm = context.getSystemService(ConnectivityManager::class.java)
            while (isActive) {
                delay(30_000)
                val underlying = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.let { c -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true } ?: continue
                val document = withContext(Dispatchers.IO) {
                    var c: HttpURLConnection? = null
                    try {
                        c = underlying.openConnection(URL(FlintAccount.BASE + "/locations")) as HttpURLConnection
                        c.connectTimeout = 4000; c.readTimeout = 4000; c.instanceFollowRedirects = false
                        if (c.responseCode != 200) JSONObject() else c.inputStream.use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 1_000_000); out.write(buffer, 0, n) }; JSONObject(out.toString("UTF-8")) }
                    } catch (_: Exception) { JSONObject() } finally { c?.disconnect() }
                }
                val now = System.currentTimeMillis()
                val health = mutableMapOf<String, ServerBalance.Health>()
                for (p in profiles.take(30)) {
                    val latency = withContext(Dispatchers.IO) {
                        try {
                            val address = underlying.getAllByName(p.host).first()
                            val start = System.nanoTime()
                            underlying.socketFactory.createSocket().use { it.connect(InetSocketAddress(address, p.port), 1200) }
                            maxOf(1, (System.nanoTime() - start) / 1_000_000)
                        } catch (_: Exception) { null }
                    }
                    val load = ServerBalance.load(document, p, now)
                    health[p.id] = ServerBalance.Health(latency != null, latency, now, load?.first, load?.second ?: 0)
                }
                val active = config.optString("flintServerId")
                if (!VpnReachability.verify(context, config)) health[active]?.let { health[active] = it.copy(available = false, checkedAt = now) }
                val id = balance.choose(profiles, health, active, true, connectedAt, now) ?: continue
                val candidate = (0 until candidates.length()).map { candidates.getJSONObject(it) }.first { it.optString("id") == id }
                switch(switchConfig(config, candidate)); return@launch
            }
        }
    }
    fun switchConfig(config: JSONObject, candidate: JSONObject): JSONObject {
        val next = JSONObject(config.toString()).put("hostName", candidate.getString("host"))
            .put("description", candidate.getString("name")).put("flintServerId", candidate.getString("id"))
        val native = JSONObject(next.getJSONObject("xray_config_data").getString("config"))
        val outbounds = native.getJSONArray("outbounds")
        val index = (0 until outbounds.length()).firstOrNull { outbounds.getJSONObject(it).optString("protocol") in setOf("vless", "vmess", "trojan", "shadowsocks") } ?: error("No VPN outbound")
        val replacement = JSONObject(candidate.getJSONObject("outbound").toString())
        val tag = outbounds.getJSONObject(index).optString("tag")
        if (tag.isNotBlank()) replacement.put("tag", tag)
        outbounds.put(index, replacement)
        next.put("xray_config_data", JSONObject().put("config", native.toString()))
        return next
    }
}
