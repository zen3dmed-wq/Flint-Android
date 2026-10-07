package app.flint.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.flint.prototype.testing.UiHarnessActivity
import app.flint.prototype.ui.FlintPhase
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

internal object UiTestSupport {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun launch(tv: Boolean, phase: FlintPhase = FlintPhase.DISCONNECTED): ActivityScenario<UiHarnessActivity> {
        UiHarnessActivity.events.clear()
        return ActivityScenario.launch(Intent(instrumentation.targetContext, UiHarnessActivity::class.java)
            .putExtra("tv", tv).putExtra("phase", phase.name))
    }

    fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString() == text) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findText(root.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    fun assertOnScreen(view: View) {
        val rect = Rect()
        assertTrue("${view.contentDescription ?: (view as? TextView)?.text} must be visible", view.getGlobalVisibleRect(rect))
        assertEquals("The control must not be clipped horizontally", view.width, rect.width())
        assertEquals("The control must not be clipped vertically", view.height, rect.height())
        assertTrue("Control must have a usable height", rect.height() >= 32 * view.resources.displayMetrics.density)
    }

    fun press(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    fun clickAccessibilityText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val match = root?.findAccessibilityNodeInfosByText(text)?.firstOrNull {
                it.text?.toString() == text && it.isClickable
            }
            if (match != null) {
                assertTrue("$text should be clickable", match.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(100)
        }
        fail("No clickable accessibility node named $text")
    }

    fun screenshot(name: String, tv: Boolean, phase: FlintPhase) {
        instrumentation.waitForIdleSync()
        // Native image composition runs off the UI thread; let its first frame finish.
        instrumentation.uiAutomation.waitForIdle(750, 5000)
        val image = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Emulator screenshot must be available", image)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-evidence")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { output ->
            assertTrue(image!!.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        File(directory, "$name.json").writeText(JSONObject()
            .put("syntheticUiState", true)
            .put("vpnStarted", false)
            .put("tv", tv)
            .put("phase", phase.name)
            .put("widthPx", image!!.width)
            .put("heightPx", image.height)
            .put("callbackEvents", UiHarnessActivity.events.toList())
            .toString(2))
    }
}
