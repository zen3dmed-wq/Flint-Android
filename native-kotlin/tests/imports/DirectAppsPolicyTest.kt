package app.flint.prototype.imports

import app.flint.prototype.routing.DirectApps
import org.junit.Assert.*
import org.junit.Test

class DirectAppsPolicyTest {
    @Test fun russianAppsUseExactIdsAndRespectManualOverrides() {
        val auto = DirectApps.selected(true, true, emptyMap(), "app.flint.vpn")
        assertTrue(auto.containsAll(listOf("ru.rostel", "com.gpn.azs", "ru.yandex.yandexmaps")))
        assertFalse(auto.contains("ru.unknown.browser"))
        assertFalse(auto.contains("org.telegram.messenger"))
        val changed = DirectApps.selected(true, true, mapOf("ru.rostel" to false, "app.other.client" to true,
            "app.flint.vpn" to true, "invalid/package" to true), "app.flint.vpn")
        assertFalse(changed.contains("ru.rostel"))
        assertTrue(changed.contains("app.other.client"))
        assertFalse(changed.contains("app.flint.vpn"))
        assertFalse(changed.contains("invalid/package"))
    }
    @Test fun disabledRoutingAndManualModeDoNotLeakAutomaticAppExclusions() {
        val choices = mapOf("app.other.client" to true, "ru.rostel" to false)
        assertEquals(setOf("app.other.client"), DirectApps.selected(true, false, choices, "app.flint.vpn"))
        assertTrue(DirectApps.selected(false, true, choices, "app.flint.vpn").isEmpty())
        assertTrue(DirectApps.selected(false, false, choices, "app.flint.vpn").isEmpty())
    }
}
