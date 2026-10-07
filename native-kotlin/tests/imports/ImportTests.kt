package app.flint.prototype.imports

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.util.Base64
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private fun encoded(value: String) = Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray())
private fun url(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
private const val ID = "00000000-0000-4000-8000-000000000001"
private const val BASE = "vless://$ID@vpn.example.test:443"
private const val REALITY = "$BASE?type=tcp&security=reality&pbk=examplePublicKey&sid=abcd&fp=firefox&sni=tls.example.test&flow=xtls-rprx-vision"

private fun fails(expected: String, operation: () -> Unit) {
    val error = try { operation(); null } catch (error: ImportException) { error }
    check(error != null && error.message.orEmpty().contains(expected)) { "Expected explicit error: $expected" }
}

fun main(args: Array<String>) {
    val checks = linkedMapOf<String, () -> Unit>()
    checks["same country keeps distinct endpoints and only exact duplicates disappear"] = {
        val first = "$BASE#Армения"
        val second = "vless://$ID@vpn2.example.test:8443#Армения"
        val third = "vless://$ID@us.example.test:443#США"
        val result = SubscriptionParser.parse("$first\n$second\n$third\n$first")
        check(result.profiles.size == 3)
        check(result.profiles.map { it.id }.toSet().size == 3)
        check(result.profiles[0].name == "Армения · 1")
        check(result.profiles[1].name == "Армения · 2")
        check(result.profiles[2].name == "США")
    }
    checks["standard and URL safe Base64 subscriptions preserve endpoints"] = {
        val text = "$REALITY#Первый\n${BASE.replace("vpn.example", "second.example")}#Второй"
        for (base64 in listOf(encoded(text), Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray()))) {
            check(SubscriptionParser.parse(base64).profiles.size == 2)
        }
    }
    checks["Reality credentials, SNI and percent encoded parameters are preserved"] = {
        val profile = SubscriptionParser.parse("$REALITY&spiderX=%2Fhello%2Bworld#Test%20%2B%20VPN").profiles.single()
        val outbound = profile.outbound()
        val security = outbound.getJSONObject("streamSettings").getJSONObject("realitySettings")
        check(security.getString("serverName") == "tls.example.test")
        check(security.getString("publicKey") == "examplePublicKey")
        check(security.getString("shortId") == "abcd")
        check(security.getString("fingerprint") == "firefox")
        check(security.getString("spiderX") == "/hello+world")
        check(outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0).getString("flow") == "xtls-rprx-vision")
        check(profile.name == "Test + VPN")
        check(!profile.toString().contains("examplePublicKey"))
        check(!profile.toString().contains(ID))
    }
    checks["IPv6 authority and plus in passwords"] = {
        val profile = SubscriptionParser.parse("trojan://password%2Bwith+plus@[2001:db8::7]:443#IPv6").profiles.single()
        check(profile.host == "2001:db8::7")
        check(profile.outbound().getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
            .getString("password") == "password+with+plus")
        check(profile.outbound().getJSONObject("streamSettings").getString("security") == "tls")
    }
    checks["WS, gRPC and XHTTP options are not discarded"] = {
        val ws = SubscriptionParser.parse("$BASE?type=ws&security=tls&path=%2Fapi%253F&host=cdn.example.test&alpn=h2,http%2F1.1").profiles.single().outbound().getJSONObject("streamSettings")
        check(ws.getJSONObject("wsSettings").getString("path") == "/api%3F")
        check(ws.getJSONObject("tlsSettings").getJSONArray("alpn").length() == 2)
        val grpc = SubscriptionParser.parse("$BASE?type=grpc&security=tls&serviceName=Service%2FOne&mode=multi&authority=grpc.example.test").profiles.single().outbound().getJSONObject("streamSettings").getJSONObject("grpcSettings")
        check(grpc.getString("serviceName") == "Service/One")
        check(grpc.getBoolean("multiMode"))
        check(grpc.getString("authority") == "grpc.example.test")
        val extra = "{\"noSSEHeader\":true,\"xmux\":{\"maxConcurrency\":\"16-32\"}}"
        val xhttp = SubscriptionParser.parse("$BASE?type=xhttp&security=tls&mode=packet-up&path=%2Fx&extra=${url(extra)}").profiles.single().outbound().getJSONObject("streamSettings").getJSONObject("xhttpSettings")
        check(xhttp.getJSONObject("extra").getBoolean("noSSEHeader"))
        check(xhttp.getString("mode") == "packet-up")
    }
    checks["VMess and both Shadowsocks link formats"] = {
        val vmess = JSONObject().put("add", "vmess.example.test").put("port", "8443").put("id", ID)
            .put("net", "ws").put("tls", "tls").put("host", "cdn.example.test").put("path", "/path").put("aid", "0").put("ps", "VMess")
        check(SubscriptionParser.parse("vmess://${encoded(vmess.toString())}").profiles.single().protocol == "vmess")
        val auth = "aes-128-gcm:password:with:colon"
        for (entry in listOf("ss://${encoded(auth)}@ss.example.test:8388#SS", "ss://${encoded("$auth@ss.example.test:8388")}#SS")) {
            val server = SubscriptionParser.parse(entry).profiles.single().outbound().getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
            check(server.getString("method") == "aes-128-gcm")
            check(server.getString("password") == "password:with:colon")
        }
    }
    checks["explicit partial import errors and no silent invented credentials"] = {
        val result = SubscriptionParser.parse("$BASE\nvless://missing-host\nhysteria2://password@example.test:443")
        check(result.profiles.size == 1 && result.warnings.size == 2)
        fails("ключ", { SubscriptionParser.parse("$BASE?security=reality") })
        fails("порт", { SubscriptionParser.parse("vless://$ID@example.test:70000") })
        fails("Транспорт", { SubscriptionParser.parse("$BASE?type=unknown") })
        fails("Повторяющиеся", { SubscriptionParser.parse("$BASE?security=tls&security=none") })
        fails("плагином", { SubscriptionParser.parse("ss://${encoded("aes-128-gcm:test")}@example.test:8388?plugin=v2ray-plugin") })
    }
    checks["JSON profiles keep extra outbounds and secure the local listener"] = {
        val outbound = SubscriptionParser.parse(BASE).profiles.single().outbound()
        val original = JSONObject().put("remarks", "JSON server")
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("protocol", "freedom").put("tag", "existing-direct")))
            .put("inbounds", JSONArray().put(JSONObject().put("listen", "0.0.0.0").put("port", 22).put("tag", "old-socks")))
            .put("log", JSONObject().put("access", "/arbitrary/path"))
            .put("api", JSONObject().put("tag", "admin"))
        val profile = SubscriptionParser.parse(original.toString()).profiles.single()
        check(profile.originalConfigJson != null)
        val config = JSONObject(XrayConfigBuilder.build(profile, socksPort = 10809, ruDirect = false))
        check(config.getJSONArray("outbounds").length() == 2)
        check(config.getJSONArray("inbounds").getJSONObject(0).getString("listen") == "127.0.0.1")
        check(config.getJSONArray("inbounds").getJSONObject(0).getInt("port") == 10809)
        check(config.getJSONArray("inbounds").getJSONObject(0).getString("tag") == "old-socks")
        check(!config.has("api") && !config.getJSONObject("log").has("access"))
        check(SubscriptionParser.parse("[$original,$original]").profiles.size == 1)
    }
    checks["Russian routing expands the catalog without geo dependencies"] = {
        val catalog = if (args.isNotEmpty()) File(args[0]).readText() else """{"geosite":{"category-ru":["domain:yandex.ru"],"tld-ru":["regexp:.*\\.ru$"]},"geoip":{"ru":["192.0.2.0/24"],"private":["10.0.0.0/8"]}}"""
        val profile = SubscriptionParser.parse(REALITY).profiles.single()
        val config = JSONObject(XrayConfigBuilder.build(profile, ruDirect = true, routingCatalogJson = catalog, customDomains = listOf("Example.RU")))
        val routing = config.getJSONObject("routing")
        check(routing.getString("domainStrategy") == "IPIfNonMatch")
        val rules = routing.getJSONArray("rules")
        check(rules.length() >= 2)
        check(rules.getJSONObject(0).getJSONArray("domain").toString().contains("domain:example.ru"))
        check(rules.getJSONObject(1).getJSONArray("ip").length() > 0)
        check(!config.toString().contains("geosite:"))
        check(!config.toString().contains("geoip:"))
        check(config.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing").getBoolean("routeOnly"))
        fails("каталог", { XrayConfigBuilder.build(profile) })
        fails("группа", { XrayConfigBuilder.build(profile, routingCatalogJson = "{}") })
        fails("сайта", { XrayConfigBuilder.normalizeDomain("https://example.ru/path") })
    }
    checks["HTTP redirect and bounded error reporting without credentials"] = {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/profiles") { exchange ->
            val bytes = encoded(BASE).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/profiles")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/gone") { exchange -> exchange.sendResponseHeaders(410, -1); exchange.close() }
        server.createContext("/gzip") { exchange ->
            val packed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(BASE.toByteArray()) } }.toByteArray()
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, packed.size.toLong())
            exchange.responseBody.use { it.write(packed) }
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(300)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.createContext("/loop") { exchange ->
            exchange.responseHeaders.add("Location", "/loop")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            check(SubscriptionClient().import("$base/redirect").profiles.size == 1)
            check(SubscriptionClient().import("$base/gzip").profiles.size == 1)
            fails("недоступна", { SubscriptionClient().import("$base/gone?private-token=redacted") })
            fails("перенаправлений", { SubscriptionClient().import("$base/loop") })
            fails("вовремя", { SubscriptionClient(readTimeoutMs = 100).import("$base/slow") })
            val error = try { SubscriptionClient().fetch("http://username:private-secret@127.0.0.1/private-secret"); null } catch (error: ImportException) { error }
            check(error != null && !error.toString().contains("private-secret"))
        } finally { server.stop(0) }
    }
    for ((name, operation) in checks) {
        operation()
        println("PASS: $name")
    }
    println("${checks.size} import/configuration checks passed")
}
