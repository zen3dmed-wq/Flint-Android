package app.flint.prototype.imports

import app.flint.prototype.account.AccountScreens
import app.flint.prototype.vpn.ServerBalance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ParityPolicyTest {
    private fun server(id: String, host: String) = ServerProfile(id, id, host, 443, "vless", "{}")
    @Test fun balancingRequiresThreeFreshSamplesAndNeverOverridesManualChoice() {
        val profiles = listOf(server("a", "192.0.2.1"), server("b", "192.0.2.2"))
        val state = ServerBalance.State()
        val now = 500_000L
        fun health(at: Long) = mapOf("a" to ServerBalance.Health(true, 20, at, 95, at), "b" to ServerBalance.Health(true, 80, at, 20, at))
        assertNull(state.choose(profiles, health(now), "a", false, 1, now))
        assertNull(state.choose(profiles, health(now), "a", true, 1, now))
        assertNull(state.choose(profiles, health(now), "a", true, 1, now + 1))
        assertNull(state.choose(profiles, health(now+30_000), "a", true, 1, now+30_000))
        assertEquals("b", state.choose(profiles, health(now+60_000), "a", true, 1, now+60_000))
    }
    @Test fun unknownAndStaleLoadAreNotZeroPercent() {
        val p = server("a", "vpn.example.com")
        val doc = JSONObject().put("loadUpdatedAt", "2026-10-08T06:00:00Z").put("items", JSONArray().put(JSONObject().put("addresses", JSONArray().put("vpn.example.com:443")).put("load", 0)))
        val at = AccountScreens.epoch(doc.getString("loadUpdatedAt"))
        assertEquals(0, ServerBalance.load(doc, p, at+1000)?.first)
        assertNull(ServerBalance.load(doc, p, at+130_000))
        doc.getJSONArray("items").getJSONObject(0).put("load", JSONObject.NULL)
        assertNull(ServerBalance.load(doc, p, at+1000))
        assertFalse(ServerBalance.matches("vpn.example.com:444", p.host, p.port))
    }
    @Test fun priceBenefitDoesNotInventDiscountOverExplicitBackendZero() {
        val base = JSONObject("""{"id":"a","durationDays":30,"price":{"amount":120,"currency":"RUB"}}""")
        val year = JSONObject("""{"id":"b","durationDays":360,"price":{"amount":1200,"currency":"RUB"},"savingsPercent":0}""")
        assertFalse(AccountScreens.benefit(year, listOf(base,year)).contains("Выгода"))
        year.remove("savingsPercent")
        assertTrue(AccountScreens.benefit(year, listOf(base,year)).contains("Выгода"))
    }
    @Test fun rulesSupportManualSitesAndRejectMalformedAddresses() {
        assertEquals("yandex.ru", XrayConfigBuilder.normalizeSite("https://yandex.ru/maps/"))
        assertEquals("192.0.2.0/24", XrayConfigBuilder.normalizeSite("192.0.2.0/24"))
        assertThrows(ImportException::class.java) { XrayConfigBuilder.normalizeSite("192.0.2.1/999") }
        val p = SubscriptionParser.parse("vless://11111111-1111-4111-8111-111111111111@192.0.2.1:443?security=none#Test").profiles.single()
        val catalog = """{"geosite":{},"geoip":{}}"""
        val manual = """{"version":1,"geosite":[],"geoip":[],"domains":[],"ips":[]}"""
        val c = JSONObject(XrayConfigBuilder.build(p, ruDirect=true, routingCatalogJson=catalog, customDomains=listOf("yandex.ru","192.0.2.0/24"),routingPolicyJson=manual))
        val rules = c.getJSONObject("routing").getJSONArray("rules")
        assertEquals("domain:yandex.ru", rules.getJSONObject(0).getJSONArray("domain").getString(0))
        assertEquals("192.0.2.0/24", rules.getJSONObject(1).getJSONArray("ip").getString(0))
    }
}
