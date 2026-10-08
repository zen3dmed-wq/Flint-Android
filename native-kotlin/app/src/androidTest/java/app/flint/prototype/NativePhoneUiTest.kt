package app.flint.prototype

import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flint.prototype.testing.UiHarnessActivity
import app.flint.prototype.ui.FlintPhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePhoneUiTest {
    @Test fun importFileActionIsReachableAndDoesNotStartVpn() {
        UiTestSupport.launch(tv = false).use { scenario ->
            scenario.onActivity { activity ->
                val add = UiTestSupport.findText(activity.flintView, "QR-код") as Button
                UiTestSupport.assertOnScreen(add)
                val connect = UiTestSupport.findText(activity.flintView, "Подключиться") as Button
                UiTestSupport.assertOnScreen(connect)
                assertTrue(add.performClick())
            }
            UiTestSupport.clickAccessibilityText("Открыть файл")
            assertEquals(listOf("file"), UiHarnessActivity.events.toList())
            UiTestSupport.screenshot("phone-disconnected-fixture", false, FlintPhase.DISCONNECTED)
        }
    }

    @Test fun pendingConnectionCanBeCancelledWithoutShowingSuccess() {
        UiTestSupport.launch(tv = false, phase = FlintPhase.CONNECTING).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(UiTestSupport.findText(activity.flintView, "Вы защищены"))
                val cancel = UiTestSupport.findText(activity.flintView, "Отменить подключение") as Button
                UiTestSupport.assertOnScreen(cancel)
                assertTrue(cancel.isEnabled)
                cancel.performClick()
                assertEquals(listOf("connectToggle"), UiHarnessActivity.events.toList())
                assertEquals(FlintPhase.CONNECTING, activity.uiState.phase)
            }
            UiTestSupport.screenshot("phone-connecting-fixture", false, FlintPhase.CONNECTING)
        }
    }

    @Test fun returningToConnectedScreenPreservesStatusWithoutTogglingVpn() {
        UiTestSupport.launch(tv = false, phase = FlintPhase.CONNECTED).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNotNull(UiTestSupport.findText(activity.flintView, "Вы защищены"))
                val disconnect = UiTestSupport.findText(activity.flintView, "Отключить VPN") as Button
                UiTestSupport.assertOnScreen(disconnect)
                assertTrue(disconnect.isEnabled)
                assertEquals(FlintPhase.CONNECTED, activity.uiState.phase)
                assertTrue("Rendering and recreation must not invoke a VPN toggle", UiHarnessActivity.events.isEmpty())
            }
            UiTestSupport.screenshot("phone-connected-fixture", false, FlintPhase.CONNECTED)
        }
    }

    @Test fun deniedConnectionHasErrorStateAndRetryInsteadOfConnectedLabel() {
        UiTestSupport.launch(tv = false).use { scenario ->
            scenario.onActivity { activity ->
                activity.showState(activity.uiState.copy(phase = FlintPhase.ERROR,
                    message = "Разрешение на VPN не выдано. Можно повторить подключение."))
                assertNull(UiTestSupport.findText(activity.flintView, "Вы защищены"))
                assertNotNull(UiTestSupport.findText(activity.flintView, "Вы не защищены"))
                val retry = UiTestSupport.findText(activity.flintView, "Подключиться") as Button
                assertTrue(retry.isEnabled)
                retry.performClick()
                assertEquals(listOf("connectToggle"), UiHarnessActivity.events.toList())
                assertEquals("Only the service may confirm successful connection", FlintPhase.ERROR, activity.uiState.phase)
            }
            UiTestSupport.screenshot("phone-error-fixture", false, FlintPhase.ERROR)
        }
    }
}
