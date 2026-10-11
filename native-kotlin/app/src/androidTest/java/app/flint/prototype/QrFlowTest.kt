package app.flint.prototype

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flint.prototype.ui.FlintPhase
import app.flint.prototype.ui.FlintQrCaptureActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrFlowTest {
    @Test fun cameraStaysPortraitWithSquareQrCropAndRejectsBarcodes() {
        val inst = UiTestSupport.instrumentation
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.CAMERA)
        try {
        ActivityScenario.launch<FlintQrCaptureActivity>(Intent(inst.targetContext, FlintQrCaptureActivity::class.java)).use { scenario ->
            for (rotation in listOf(android.app.UiAutomation.ROTATION_FREEZE_90, android.app.UiAutomation.ROTATION_FREEZE_0)) {
                assertTrue(inst.uiAutomation.setRotation(rotation))
                inst.waitForIdleSync()
                SystemClock.sleep(500)
                val expected = android.content.res.Configuration.ORIENTATION_PORTRAIT
                var frame: Rect? = null
                val deadline = SystemClock.uptimeMillis() + 10000
                while (frame == null && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val scanner = activity.findViewById<com.journeyapps.barcodescanner.BarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_surface)
                        frame = if (activity.resources.configuration.orientation == expected) scanner.framingRect else null
                    }
                    SystemClock.sleep(100)
                }
                assertNotNull("Camera preview must establish its decode crop", frame)
                assertEquals(frame!!.width(), frame!!.height())
                assertTrue(frame!!.width() > 100)
                scenario.onActivity { activity ->
                    assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
                    assertEquals(expected, activity.resources.configuration.orientation)
                    val barcode = activity.findViewById<com.journeyapps.barcodescanner.BarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_surface)
                    val scanner = barcode.parent as DecoratedBarcodeView
                    fun decode(format: BarcodeFormat, text: String): String? {
                        val bits = MultiFormatWriter().encode(text, format, 600, 600)
                        val pixels = IntArray(600 * 600) { if (bits[it % 600, it / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                        return scanner.decoderFactory.createDecoder(emptyMap<com.google.zxing.DecodeHintType, Any>()).decode(RGBLuminanceSource(600, 600, pixels))?.text
                    }
                    assertEquals("https://example.invalid/sub/qr", decode(BarcodeFormat.QR_CODE, "https://example.invalid/sub/qr"))
                    assertNull(decode(BarcodeFormat.CODE_128, "123456789012"))
                }

            }
            UiTestSupport.screenshot("qr-camera-fixture", false, FlintPhase.DISCONNECTED)
            UiTestSupport.awaitWindowContaining("Сканировать QR-код", "Закрыть")
            UiTestSupport.clickAccessibilityText("Закрыть")
        }
        } finally { inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
    }
}
