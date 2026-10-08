package app.flint.prototype.imports

import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

object XrayConfigBuilder {
    /** routingCatalogJson is the existing Flint catalog, bundled as a local asset. */
    fun build(
        profile: ServerProfile,
        socksPort: Int = 10808,
        ruDirect: Boolean = true,
        routingCatalogJson: String? = null,
        customDomains: List<String> = emptyList(),
    ): String {
        require(socksPort in 1024..65535) { "Некорректный локальный порт" }
        val config = profile.originalConfigJson?.let(::JSONObject) ?: JSONObject()
            .put("outbounds", JSONArray().put(profile.outbound()))
        // The prototype bundles expanded routing rules, not arbitrary external .dat files.
        // Reject imported dependencies explicitly instead of changing their routing meaning.
        rejectExternalGeoRules(config)
        // Imported file paths and management listeners do not belong in an Android client.
        config.put("log", JSONObject().put("loglevel", "warning"))
        config.remove("api")
        config.remove("metrics")
        config.remove("stats")
        if (!config.has("dns")) config.put("dns", JSONObject().put("servers", JSONArray(listOf("1.1.1.1", "1.0.0.1"))))
        val oldInbound = config.optJSONArray("inbounds")?.optJSONObject(0)
        val inboundTag = oldInbound?.optString("tag")?.takeIf(String::isNotBlank) ?: "flint-socks"
        val inbound = JSONObject().put("tag", inboundTag).put("listen", "127.0.0.1")
            .put("port", socksPort).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic"))).put("routeOnly", true))
        config.put("inbounds", JSONArray().put(inbound))
        if (ruDirect) {
            val catalog = try { JSONObject(routingCatalogJson ?: throw ImportException("Не найден каталог правил Сайтов РФ")) }
                catch (error: ImportException) { throw error }
                catch (_: Exception) { throw ImportException("Повреждён каталог правил Сайтов РФ") }
            applyRussianRouting(config, catalog, customDomains)
        }
        val result = config.toString()
        if (result.toByteArray(Charsets.UTF_8).size > SubscriptionParser.MAX_BYTES)
            throw ImportException("Конфигурация VPN слишком большая")
        return result
    }

    private fun rejectExternalGeoRules(config: JSONObject) {
        fun external(value: String): Boolean = listOf("geosite:", "geoip:", "ext:", "ext-domain:", "ext-ip:")
            .any { value.trim().startsWith(it, ignoreCase = true) }
        fun check(value: Any?) {
            val requiresDatabase = when (value) {
                is String -> external(value)
                is JSONArray -> (0 until value.length()).any { external(value.optString(it)) }
                else -> false
            }
            if (requiresDatabase) throw ImportException(
                "Профиль Xray использует внешние базы geosite/geoip, которых нет в тестовой версии. " +
                    "Импортируйте ссылку VLESS, VMess, Trojan или Shadowsocks либо JSON с явными доменами и IP-адресами.")
        }
        val rules = config.optJSONObject("routing")?.optJSONArray("rules")
        if (rules != null) for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            for (key in listOf("domain", "ip", "source")) check(rule.opt(key))
        }
        val dns = config.optJSONObject("dns") ?: return
        val servers = dns.optJSONArray("servers")
        if (servers != null) for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            for (key in listOf("domains", "expectIPs", "unexpectedIPs")) check(server.opt(key))
        }
        dns.optJSONObject("hosts")?.let { hosts ->
            val names = hosts.keys()
            while (names.hasNext()) check(names.next())
        }
    }

    private fun applyRussianRouting(config: JSONObject, catalog: JSONObject, customDomains: List<String>) {
        val domains = linkedSetOf("domain:zakupki.gov.ru")
        val ips = linkedSetOf<String>()
        fun expand(kind: String, group: String, output: MutableSet<String>) {
            val entries = catalog.optJSONObject(kind)?.optJSONArray(group)
                ?: throw ImportException("В каталоге отсутствует группа $kind/$group")
            for (index in 0 until entries.length()) {
                val entry = entries.optString(index)
                if (entry.isBlank()) throw ImportException("В каталоге найдены некорректные правила")
                output.add(entry)
            }
        }
        expand("geosite", "category-ru", domains)
        expand("geosite", "tld-ru", domains)
        expand("geoip", "ru", ips)
        expand("geoip", "private", ips)
        customDomains.forEach { domains.add("domain:${normalizeDomain(it)}") }

        val outbounds = config.getJSONArray("outbounds")
        val existingTags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).optString("tag") }.toSet()
        var directTag = "flint-direct"
        while (directTag in existingTags) directTag += "-1"
        outbounds.put(JSONObject().put("tag", directTag).put("protocol", "freedom"))
        val routing = config.optJSONObject("routing") ?: JSONObject()
        val rules = JSONArray()
        if (domains.isNotEmpty()) rules.put(JSONObject().put("type", "field").put("domain", JSONArray(domains.toList())).put("outboundTag", directTag))
        if (ips.isNotEmpty()) rules.put(JSONObject().put("type", "field").put("ip", JSONArray(ips.toList())).put("outboundTag", directTag))
        val oldRules = routing.optJSONArray("rules") ?: JSONArray()
        for (index in 0 until oldRules.length()) rules.put(oldRules.get(index))
        config.put("routing", routing.put("rules", rules).put("domainStrategy", "IPIfNonMatch"))
    }

    fun normalizeDomain(input: String): String {
        val domain = input.trim().removePrefix("domain:").trimEnd('.').lowercase()
        val ascii = try { IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES) }
            catch (_: IllegalArgumentException) { throw ImportException("Некорректное имя сайта") }
        if (ascii.length !in 1..253 || !ascii.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) || ascii.contains(".."))
            throw ImportException("Некорректное имя сайта")
        return ascii
    }
}
