package app.flint.prototype.vpn

import app.flint.prototype.account.AccountScreens
import app.flint.prototype.account.objects
import app.flint.prototype.account.string
import app.flint.prototype.imports.ServerProfile
import org.json.JSONObject
import java.net.URI

/** Same thresholds, freshness and hysteresis as Qt flintBalance.h / flintTelemetry.h. */
object ServerBalance {
    data class Health(val available: Boolean?, val latency: Long?, val checkedAt: Long, val load: Int? = null, val loadAt: Long = 0)
    fun fresh(at: Long, now: Long) = at > 0 && now - at in -30_000..119_999
    fun score(h: Health?, now: Long): Double {
        if (h == null || h.available == null || !fresh(h.checkedAt, now)) return 10000.0
        if (!h.available) return 100000.0
        return maxOf(1L, h.latency ?: 1000).toDouble() + if (h.load != null && fresh(h.loadAt, now)) 12.0 * h.load else 0.0
    }
    fun load(document: JSONObject, profile: ServerProfile, now: Long): Pair<Int?, Long>? {
        val at = AccountScreens.epoch(document.string("loadUpdatedAt"))
        if (!fresh(at, now)) return null
        val matches = objects(document, "items").filter { item ->
            item.optJSONArray("addresses")?.let { a -> (0 until a.length()).any { matches(a.optString(it), profile.host, profile.port) } } == true
        }
        if (matches.size != 1) return null
        val v = matches.first()
        if (v.isNull("load")) return null
        val load = v.optInt("load", -1)
        return if (load in 0..100) load to at else null
    }
    fun matches(address: String, host: String, port: Int): Boolean {
        val raw = address.trim()
        if (raw.trim('[', ']').equals(host.trim('[', ']'), true)) return true
        return runCatching {
            val uri = URI(if (raw.contains("://")) raw else "tcp://$raw")
            uri.host?.trimEnd('.')?.equals(host.trimEnd('.'), true) == true && (uri.port == -1 || uri.port == port) &&
                uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/")
        }.getOrDefault(false)
    }
    class State {
        private var samples = 0; private var lastAt = 0L; private var reason = ""
        fun choose(profiles: List<ServerProfile>, health: Map<String, Health>, active: String, auto: Boolean, connectedAt: Long, now: Long): String? {
            if (!auto || now - connectedAt < 180_000 || connectedAt == 0L) { reset(); return null }
            val current = health[active] ?: return null
            val down = current.available == false && fresh(current.checkedAt, now)
            val loaded = current.load != null && current.load >= 85 && fresh(current.loadAt, now)
            if (!down && !loaded) { reset(); return null }
            val kind = if (down) "down" else "load"
            if (reason != kind) { reset(); reason = kind }
            val at = if (down) current.checkedAt else current.loadAt
            if (at <= lastAt) return null
            lastAt = at; samples++
            if (samples < 3) return null
            val node = profiles.find { it.id == active }
            val best = profiles.filter { p ->
                val h = health[p.id]
                p.id != active && p.host != node?.host && h != null && (down || (h.load != null && fresh(h.loadAt, now))) &&
                    (h.load == null || !fresh(h.loadAt, now) || h.load < 70) && score(h, now) + 200 < score(current, now)
            }.minByOrNull { score(health[it.id], now) }
            if (best != null) reset()
            return best?.id
        }
        private fun reset() { samples = 0; lastAt = 0; reason = "" }
    }
}
