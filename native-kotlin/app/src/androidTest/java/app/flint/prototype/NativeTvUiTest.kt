package app.flint.prototype

import android.view.KeyEvent
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flint.prototype.testing.UiHarnessActivity
import app.flint.prototype.ui.FlintPhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** CI runs this class after configuring a 1280x720, density-160 emulator viewport. */
@RunWith(AndroidJUnit4::class)
class NativeTvUiTest {
    @Test fun remoteNavigationCanImportAndChangeServerWhileConnected() {
        UiTestSupport.launch(tv = true, phase = FlintPhase.CONNECTED).use { scenario ->
            // The preceding phone tests can leave the emulator in touch mode.
            // Exercise a real remote key before asking for keyboard focus.
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { activity ->
                val metrics = activity.resources.displayMetrics
                assertTrue("TV test requires landscape width >= 960dp", metrics.widthPixels / metrics.density >= 960)
                assertTrue("TV test requires height >= 540dp", metrics.heightPixels / metrics.density >= 540)
                val add = UiTestSupport.findText(activity.flintView, "QR-код") as Button
                UiTestSupport.assertOnScreen(add)
                assertNotNull("The first remote key must establish a focused control", activity.currentFocus)
                assertTrue("TV actions must accept focus after a remote key", add.requestFocus())
            }
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            UiTestSupport.awaitFocusedText("Из буфера")
            scenario.onActivity { assertEquals("Из буфера", (it.currentFocus as? Button)?.text?.toString()) }
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_CENTER)
            UiTestSupport.awaitEvents(listOf("clipboard"))
            assertEquals(listOf("clipboard"), UiHarnessActivity.events.toList())

            scenario.onActivity { activity ->
                val server = UiTestSupport.findText(activity.flintView, "Автоматически")!!
                UiTestSupport.assertOnScreen(server)
                assertTrue(server.requestFocus())
            }
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_CENTER)
            val picker = UiTestSupport.awaitWindowContaining("23 мс", "Недоступен")
            assertFalse(picker.findAccessibilityNodeInfosByText("23 мс").isEmpty())
            assertFalse(picker.findAccessibilityNodeInfosByText("Недоступен").isEmpty())
            UiTestSupport.awaitFocusedText("Автоматически")
            UiTestSupport.screenshot("tv-server-picker-fixture", true, FlintPhase.CONNECTED)
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_DOWN)
            UiTestSupport.awaitFocusedText("Армения · тест A")
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_CENTER)
            UiTestSupport.awaitEvents(listOf("clipboard", "server:server-a"))
            assertEquals(listOf("clipboard", "server:server-a"), UiHarnessActivity.events.toList())
            scenario.onActivity { activity ->
                assertEquals("server-a", activity.uiState.selectedServerId)
                assertEquals(FlintPhase.CONNECTED, activity.uiState.phase)
                assertNotNull(UiTestSupport.findText(activity.flintView, "Отключить VPN"))
            }
            assertFalse("Changing location must not force a UI disconnect", UiHarnessActivity.events.contains("connectToggle"))
            UiTestSupport.awaitWindowContaining("Вы защищены", "Армения · тест A")
            UiTestSupport.screenshot("tv-connected-fixture", true, FlintPhase.CONNECTED)
        }
    }
}
