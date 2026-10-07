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
            scenario.onActivity { activity ->
                val metrics = activity.resources.displayMetrics
                assertTrue("TV test requires landscape width >= 960dp", metrics.widthPixels / metrics.density >= 960)
                assertTrue("TV test requires height >= 540dp", metrics.heightPixels / metrics.density >= 540)
                val add = UiTestSupport.findText(activity.flintView, "Добавить сервер") as Button
                UiTestSupport.assertOnScreen(add)
                assertTrue(add.requestFocus())
            }
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { assertEquals("Из буфера", (it.currentFocus as? Button)?.text?.toString()) }
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(listOf("clipboard"), UiHarnessActivity.events.toList())

            scenario.onActivity { activity ->
                val server = UiTestSupport.findText(activity.flintView, "Сервер\nАвтоматически") as Button
                UiTestSupport.assertOnScreen(server)
                assertTrue(server.requestFocus())
            }
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_CENTER)
            val picker = UiTestSupport.instrumentation.uiAutomation.rootInActiveWindow
            assertFalse("The measurement must identify the TCP probe", picker.findAccessibilityNodeInfosByText("TCP 23 мс").isEmpty())
            assertFalse("A failed port probe must not claim a failed VPN handshake", picker.findAccessibilityNodeInfosByText("Нет ответа TCP").isEmpty())
            UiTestSupport.screenshot("tv-server-picker-fixture", true, FlintPhase.CONNECTED)
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_DOWN)
            UiTestSupport.press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(listOf("clipboard", "server:server-a"), UiHarnessActivity.events.toList())
            scenario.onActivity { activity ->
                assertEquals("server-a", activity.uiState.selectedServerId)
                assertEquals(FlintPhase.CONNECTED, activity.uiState.phase)
                assertNotNull(UiTestSupport.findText(activity.flintView, "Отключить VPN"))
            }
            assertFalse("Changing location must not force a UI disconnect", UiHarnessActivity.events.contains("connectToggle"))
            UiTestSupport.screenshot("tv-connected-fixture", true, FlintPhase.CONNECTED)
        }
    }
}
