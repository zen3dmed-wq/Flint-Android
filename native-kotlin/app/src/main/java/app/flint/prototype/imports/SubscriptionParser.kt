package app.flint.prototype.imports

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

/** Pure JVM parser shared by clipboard, QR and HTTP imports. No network access. */
object SubscriptionParser {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val schemes = setOf("vless", "trojan", "vmess", "ss")
    private val protocols = setOf("vless", "trojan", "vmess", "shadowsocks")

    fun parse(raw: String): ImportResult {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail("Подписка слишком большая")
        val text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) fail("Подписка пуста")
        if (text.startsWith("{") || text.startsWith("[")) return parseJson(text)
        val candidates = mutableListOf(text)
        decodeBase64OrNull(text)?.takeIf { it != text }?.let { candidates.add(it.trim()) }
        for (candidate in candidates) {
            if (candidate.startsWith("{") || candidate.startsWith("[")) return parseJson(candidate)
            // The fragment is a display name and may contain raw Unicode and spaces.
            // Split a line only at another URI, never at words in a server name.
            val entries = candidate.split(Regex("[\\r\\n]+|[\\t ]+(?=[a-zA-Z][a-zA-Z0-9+.-]*://)"))
                .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
            if (entries.none { it.substringBefore("://").lowercase(Locale.ROOT) in schemes }) continue
            val profiles = mutableListOf<ServerProfile>()
            val warnings = mutableListOf<String>()
            for ((index, entry) in entries.withIndex()) {
                if (!entry.contains("://")) continue
                try {
                    profiles.add(parseUri(entry))
                } catch (error: ImportException) {
                    warnings.add("Строка ${index + 1}: ${error.message}")
                } catch (_: Exception) {
                    warnings.add("Строка ${index + 1}: повреждённый профиль")
                }
            }
            if (profiles.isEmpty()) fail(warnings.firstOrNull() ?: "В подписке нет поддерживаемых серверов")
            return ImportResult(disambiguate(profiles), warnings)
        }
        fail("Нужна ссылка VLESS, Trojan, VMess, Shadowsocks или подписка с такими ссылками")
    }

    private fun disambiguate(profiles: List<ServerProfile>): List<ServerProfile> {
        val counts = profiles.groupingBy { it.name }.eachCount()
        val ordinals = mutableMapOf<String, Int>()
        return profiles.map { profile ->
            if ((counts[profile.name] ?: 0) <= 1) profile else {
                val ordinal = (ordinals[profile.name] ?: 0) + 1
                ordinals[profile.name] = ordinal
                ServerProfile(profile.id, "${profile.name} · $ordinal", profile.host, profile.port,
                    profile.protocol, profile.outboundJson, profile.originalConfigJson)
            }
        }
    }

    private fun parseUri(raw: String): ServerProfile {
        val scheme = raw.substringBefore("://").lowercase(Locale.ROOT)
        if (scheme !in schemes) fail("Этот протокол пока не поддерживается в прототипе")
        if (scheme == "vmess") return parseVmess(raw)
        if (scheme == "ss") return parseShadowsocks(raw)
        val parts = splitUri(raw)
        val endpoint = endpoint(parts.authority.substringAfterLast('@'))
        val credential = percent(parts.authority.substringBeforeLast('@', ""))
        if (credential.isEmpty()) fail("В профиле отсутствуют данные доступа")
        val query = parts.query
        val outbound = JSONObject().put("protocol", scheme).put("tag", "proxy")
        if (scheme == "vless") {
            val user = JSONObject().put("id", credential).put("encryption", query["encryption"] ?: "none")
            query["flow"]?.takeIf(String::isNotEmpty)?.let { user.put("flow", it) }
            outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", endpoint.first).put("port", endpoint.second)
                    .put("users", JSONArray().put(user)))))
        } else {
            outbound.put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", endpoint.first).put("port", endpoint.second).put("password", credential))))
        }
        outbound.put("streamSettings", stream(query, if (scheme == "trojan") "tls" else "none", endpoint.first))
        return profile(raw, parts.name, endpoint, scheme, outbound)
    }

    private fun parseVmess(raw: String): ServerProfile {
        val payload = raw.substringAfter("://").substringBefore('#')
        val source = decodeBase64OrNull(payload) ?: fail("Повреждённая ссылка VMess")
        val obj = try { JSONObject(source) } catch (_: Exception) { fail("Повреждённый JSON VMess") }
        val address = obj.optString("add")
        val port = obj.optString("port").toIntOrNull() ?: fail("В VMess отсутствует порт")
        validateEndpoint(address, port)
        val id = obj.optString("id").takeIf(String::isNotBlank) ?: fail("В VMess отсутствуют данные доступа")
        val query = mutableMapOf("type" to obj.optString("net", "tcp"), "security" to obj.optString("tls", "none"))
        for (key in listOf("host", "path", "sni", "alpn", "fp", "allowInsecure", "serviceName")) {
            obj.optString(key).takeIf(String::isNotEmpty)?.let { query[key] = it }
        }
        if (query["type"] == "grpc" && query["serviceName"].isNullOrEmpty()) query["serviceName"] = obj.optString("path")
        obj.optString("type").takeIf { it.isNotBlank() && it != "none" }?.let { query["headerType"] = it }
        val user = JSONObject().put("id", id).put("alterId", obj.optString("aid", "0").toIntOrNull() ?: 0)
            .put("security", obj.optString("scy", "auto").ifBlank { "auto" })
        val outbound = JSONObject().put("protocol", "vmess").put("tag", "proxy")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", address).put("port", port).put("users", JSONArray().put(user)))))
            .put("streamSettings", stream(query, "none", address))
        return profile(raw, obj.optString("ps"), address to port, "vmess", outbound)
    }

    private fun parseShadowsocks(raw: String): ServerProfile {
        val parts = splitUri(raw)
        if (!parts.query["plugin"].isNullOrBlank()) fail("Shadowsocks с отдельным плагином пока не поддерживается")
        val authority = if (parts.authority.contains('@')) parts.authority else
            decodeBase64OrNull(parts.authority) ?: fail("Повреждённая ссылка Shadowsocks")
        val endpoint = endpoint(authority.substringAfterLast('@', ""))
        val encoded = authority.substringBeforeLast('@', "")
        val decoded = percent(encoded)
        val auth = if (decoded.contains(':')) decoded else decodeBase64OrNull(decoded)
            ?: fail("Повреждённые данные доступа Shadowsocks")
        val method = auth.substringBefore(':')
        val password = auth.substringAfter(':', "")
        if (method.isBlank() || password.isEmpty()) fail("В Shadowsocks отсутствуют данные доступа")
        val outbound = JSONObject().put("protocol", "shadowsocks").put("tag", "proxy")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                .put("address", endpoint.first).put("port", endpoint.second).put("method", method).put("password", password))))
        return profile(raw, parts.name, endpoint, "shadowsocks", outbound)
    }

    private fun stream(q: Map<String, String>, defaultSecurity: String, host: String): JSONObject {
        val network = q["type"].orEmpty().ifBlank { "tcp" }
        val security = q["security"].orEmpty().ifBlank { defaultSecurity }
        if (network !in setOf("tcp", "raw", "ws", "grpc", "http", "h2", "kcp", "quic", "xhttp", "splithttp", "httpupgrade"))
            fail("Транспорт профиля пока не поддерживается")
        if (security !in setOf("none", "tls", "reality")) fail("Режим защиты профиля пока не поддерживается")
        val result = JSONObject().put("network", network).put("security", security)
        fun setIf(obj: JSONObject, key: String, source: String = key) { q[source]?.let { obj.put(key, it) } }
        when (network) {
            "tcp", "raw" -> if (q["headerType"] == "http") {
                val request = JSONObject().put("path", array(q["path"] ?: "/"))
                q["host"]?.let { request.put("headers", JSONObject().put("Host", array(it))) }
                result.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", request)))
            }
            "ws" -> {
                val settings = JSONObject().put("path", q["path"] ?: "/")
                q["host"]?.let { settings.put("headers", JSONObject().put("Host", it)) }
                q["ed"]?.toIntOrNull()?.let { settings.put("maxEarlyData", it) }
                setIf(settings, "earlyDataHeaderName", "eh")
                result.put("wsSettings", settings)
            }
            "grpc" -> {
                val settings = JSONObject().put("serviceName", q["serviceName"] ?: "")
                    .put("multiMode", q["mode"] == "multi")
                setIf(settings, "authority")
                result.put("grpcSettings", settings)
            }
            "http", "h2" -> {
                val settings = JSONObject().put("path", q["path"] ?: "/")
                q["host"]?.let { settings.put("host", array(it)) }
                result.put("httpSettings", settings)
            }
            "kcp" -> {
                val settings = JSONObject().put("header", JSONObject().put("type", q["headerType"] ?: "none"))
                setIf(settings, "seed")
                result.put("kcpSettings", settings)
            }
            "quic" -> result.put("quicSettings", JSONObject().put("security", q["quicSecurity"] ?: "none")
                .put("key", q["key"] ?: "").put("header", JSONObject().put("type", q["headerType"] ?: "none")))
            "httpupgrade" -> result.put("httpupgradeSettings", JSONObject().put("path", q["path"] ?: "/")
                .put("host", q["host"] ?: ""))
            "xhttp", "splithttp" -> {
                val settings = JSONObject().put("path", q["path"] ?: "/").put("mode", q["mode"] ?: "auto")
                setIf(settings, "host")
                q["extra"]?.takeIf(String::isNotEmpty)?.let {
                    try { settings.put("extra", JSONObject(it)) } catch (_: Exception) { fail("Повреждены параметры XHTTP") }
                }
                result.put("xhttpSettings", settings)
            }
        }
        if (security != "none") {
            val settings = JSONObject().put("serverName", q["sni"] ?: q["peer"] ?: host)
            q["fp"]?.takeIf(String::isNotBlank)?.let { settings.put("fingerprint", it) }
            q["alpn"]?.takeIf(String::isNotBlank)?.let { settings.put("alpn", array(it)) }
            if (security == "reality") {
                val publicKey = q["pbk"] ?: q["publicKey"] ?: fail("В Reality отсутствует открытый ключ сервера")
                if (publicKey.isBlank()) fail("В Reality отсутствует открытый ключ сервера")
                settings.put("publicKey", publicKey).put("shortId", q["sid"] ?: "")
                if (!settings.has("fingerprint")) settings.put("fingerprint", "chrome")
                (q["spiderX"] ?: q["spx"])?.let { settings.put("spiderX", it) }
                q["pqv"]?.let { settings.put("mldsa65Verify", it) }
                result.put("realitySettings", settings)
            } else {
                val insecure = listOf("allowInsecure", "allowInsecureCertificate", "allowInsecureHostname", "insecure")
                    .any { q[it]?.lowercase(Locale.ROOT) in setOf("1", "true", "yes", "y") }
                settings.put("allowInsecure", insecure)
                result.put("tlsSettings", settings)
            }
        }
        q["tfo"]?.let { result.put("sockopt", JSONObject().put("tcpFastOpen", it.lowercase(Locale.ROOT) in setOf("1", "true", "yes", "y"))) }
        return result
    }

    private fun parseJson(text: String): ImportResult {
        val list = try {
            if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        } catch (_: Exception) { fail("Некорректный JSON профиля Xray") }
        val profiles = mutableListOf<ServerProfile>()
        val warnings = mutableListOf<String>()
        for (index in 0 until list.length()) {
            try {
                val obj = list.optJSONObject(index) ?: fail("Нужен объект конфигурации Xray")
                val outbounds = obj.optJSONArray("outbounds")
                val outbound = if (outbounds == null) obj else (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
                    .firstOrNull { it.optString("protocol") in protocols } ?: fail("В JSON нет поддерживаемого VPN-сервера")
                val protocol = outbound.optString("protocol")
                if (protocol !in protocols) fail("Этот протокол JSON пока не поддерживается")
                val settings = outbound.optJSONObject("settings") ?: fail("В JSON отсутствуют параметры сервера")
                val server = (settings.optJSONArray("vnext") ?: settings.optJSONArray("servers"))?.optJSONObject(0)
                    ?: fail("В JSON отсутствует адрес сервера")
                val host = server.optString("address")
                val port = server.optInt("port", -1)
                validateEndpoint(host, port)
                val title = obj.optString("remarks").ifBlank { obj.optString("name") }.ifBlank { host }
                profiles.add(ServerProfile(ServerProfile.stableId(obj.toString()), title, host, port, protocol,
                    outbound.toString(), if (outbounds != null) obj.toString() else null))
            } catch (error: ImportException) {
                warnings.add("Профиль ${index + 1}: ${error.message}")
            }
        }
        if (profiles.isEmpty()) fail(warnings.firstOrNull() ?: "JSON не содержит серверов")
        return ImportResult(disambiguate(profiles.distinctBy { it.id }), warnings)
    }

    private data class UriParts(val authority: String, val query: Map<String, String>, val name: String)
    private fun splitUri(raw: String): UriParts {
        val body = raw.substringAfter("://")
        val fragment = body.substringAfter('#', "")
        val main = body.substringBefore('#')
        val query = linkedMapOf<String, String>()
        for (entry in main.substringAfter('?', "").split('&')) {
            if (entry.isBlank()) continue
            val key = percent(entry.substringBefore('='))
            val value = percent(entry.substringAfter('=', ""))
            if (query.containsKey(key) && query[key] != value) fail("Повторяющиеся параметры ссылки")
            query[key] = value
        }
        return UriParts(main.substringBefore('?').trimEnd('/'), query, percent(fragment).trim())
    }

    private fun endpoint(authority: String): Pair<String, Int> {
        val host = if (authority.startsWith('[')) authority.substringAfter('[').substringBefore(']') else authority.substringBeforeLast(':', "")
        val rawPort = if (authority.startsWith('[')) authority.substringAfter("]:", "") else authority.substringAfterLast(':', "")
        val port = rawPort.toIntOrNull() ?: fail("В профиле отсутствует корректный порт")
        validateEndpoint(host, port)
        return host to port
    }

    private fun validateEndpoint(host: String, port: Int) {
        if (host.isBlank() || host.any { it.isWhitespace() || it in "/@?#\\" } || port !in 1..65535)
            fail("В профиле некорректный адрес или порт сервера")
    }

    private fun profile(source: String, title: String, endpoint: Pair<String, Int>, protocol: String, outbound: JSONObject) =
        ServerProfile(ServerProfile.stableId(source), title.ifBlank { endpoint.first }, endpoint.first, endpoint.second, protocol, outbound.toString())

    private fun percent(value: String): String = try { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
        catch (_: Exception) { fail("Некорректное кодирование ссылки") }
    private fun array(csv: String) = JSONArray(csv.split(',').map(String::trim).filter(String::isNotEmpty))
    private fun decodeBase64OrNull(raw: String): String? {
        val value = raw.filterNot(Char::isWhitespace)
        if (value.isEmpty() || value.any { !(it.isLetterOrDigit() || it in "+/=_-") }) return null
        return try { Base64.getDecoder().decode(value.replace('-', '+').replace('_', '/')).toString(Charsets.UTF_8) }
        catch (_: IllegalArgumentException) { null }
    }
    private fun fail(message: String): Nothing = throw ImportException(message)
}
