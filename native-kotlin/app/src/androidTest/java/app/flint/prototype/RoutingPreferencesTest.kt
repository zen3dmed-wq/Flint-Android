package app.flint.prototype

import androidx.test.platform.app.InstrumentationRegistry
import app.flint.prototype.ui.DirectSites
import app.flint.prototype.imports.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutingPreferencesTest {
    @Test fun qtStarterListMigratesOnceAndRespectsRemovalsAndCustomSites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("routing-migration-test", 0)
        prefs.edit().clear().putString("directSites", "[\"example.test\"]").commit()
        val first = DirectSites.read(prefs)
        assertEquals(21, first.size)
        assertTrue(first.containsAll(listOf("zakupki.gov.ru", "yandex.ru", "example.test")))
        DirectSites.save(prefs, first.filter { it != "yandex.ru" })
        assertFalse(DirectSites.read(prefs).contains("yandex.ru"))
        assertTrue(DirectSites.read(prefs).contains("example.test"))
        DirectSites.save(prefs, emptyList())
        assertTrue("Opening again must not re-add deliberately removed sites", DirectSites.read(prefs).isEmpty())
        prefs.edit().clear().commit()
    }
    @Test fun displayedAutomaticRulesUseTheSameExpandedCatalogAsVpn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = JSONObject(context.assets.open("flint-routing-catalog.json").bufferedReader().use { it.readText() })
        val defaults = XrayConfigBuilder.russianRules(catalog, emptyList(), null)
        assertTrue(defaults.first.size > 100)
        assertTrue(defaults.second.size > 100)
        assertTrue(defaults.first.contains("domain:zakupki.gov.ru"))
        val manual = """{"version":1,"geosite":[],"geoip":[],"domains":[],"ips":[]}"""
        val custom = XrayConfigBuilder.russianRules(catalog, listOf("example.test", "192.0.2.0/24"), manual)
        assertEquals(listOf("domain:example.test"), custom.first)
        assertEquals(listOf("192.0.2.0/24"), custom.second)
    }
}
