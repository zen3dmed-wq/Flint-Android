package app.flint.prototype.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.flint.prototype.imports.ServerProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.InetSocketAddress

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
                delay(15_000)
                val underlying = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.let { c -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true } ?: continue
                val active = config.optString("flintServerId")
                // A dead tunnel must not make optional API telemetry hold up recovery.
                val document = withTimeoutOrNull(2500) { app.flint.prototype.account.LocationClient.fetch(context) } ?: JSONObject()
                val slots = Semaphore(6)
                val health = coroutineScope { profiles.sortedBy { it.id != active }.take(30).map { p -> async { slots.withPermit {
                    val latency = withContext(Dispatchers.IO) {
                        try {
                            val address = underlying.getAllByName(p.host).first()
                            val start = System.nanoTime()
                            underlying.socketFactory.createSocket().use { it.connect(InetSocketAddress(address, p.port), 1200) }
                            maxOf(1, (System.nanoTime() - start) / 1_000_000)
                        } catch (_: Exception) { null }
                    }
                    val now = System.currentTimeMillis()
                    val load = ServerBalance.load(document, p, now)
                    p.id to ServerBalance.Health(latency != null, latency, now, load?.first, load?.second ?: 0)
                } } }.awaitAll().toMap().toMutableMap() }
                val now = System.currentTimeMillis()
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
