package app.flint.prototype

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flint.prototype.testing.AccountHarnessActivity
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
    @Test fun mainQrMenuSharesChosenSubscriptionWithoutChangingActiveVpn() {
        val context = UiTestSupport.instrumentation.targetContext
        ActivityScenario.launch<AccountHarnessActivity>(Intent(context, AccountHarnessActivity::class.java)
            .putExtra("screen", "home").putExtra("qrSubscriptions", true)).use {
            // Wait for fixture login, which occurs asynchronously.
            val deadline = SystemClock.uptimeMillis() + 8000
            while (AccountHarnessActivity.requests.none { it == "GET /config" } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            UiTestSupport.clickAccessibilityText("QR-код")
            UiTestSupport.awaitWindowContaining("Поделиться через QR-код", "Сканировать QR-код", "QR-код на картинке")
            UiTestSupport.clickAccessibilityText("Поделиться через QR-код")
            UiTestSupport.awaitWindowContaining("12 Месяцев · используется сейчас", "Вторая подписка")
            UiTestSupport.clickAccessibilityText("Вторая подписка")
            UiTestSupport.awaitWindowContaining("Копировать ссылку", "Вторая подписка")
            UiTestSupport.screenshot("qr-share-fixture", false, FlintPhase.DISCONNECTED)
            val shot = UiTestSupport.instrumentation.uiAutomation.takeScreenshot()!!
            val image = java.io.File(context.cacheDir, "qr-share-fixture.png")
            try {
                image.outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                assertEquals("https://example.invalid/sub/second", app.flint.prototype.data.QrImageDecoder.decode(context, android.net.Uri.fromFile(image)))
            } finally { image.delete(); shot.recycle() }
            assertEquals("fixture-sub", context.getSharedPreferences("flint-account", 0).getString("selected", ""))
            assertFalse(AccountHarnessActivity.requests.any { it.contains("/devices") || it.contains("/select") })
            UiTestSupport.clickAccessibilityText("Копировать ссылку")
            it.onActivity { activity ->
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertEquals("https://example.invalid/sub/second", clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
        }
    }

    @Test fun cameraHasSquareQrCropInBothOrientationsAndRejectsBarcodes() {
        val inst = UiTestSupport.instrumentation
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch<FlintQrCaptureActivity>(Intent(inst.targetContext, FlintQrCaptureActivity::class.java)).use { scenario ->
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                scenario.onActivity { it.requestedOrientation = orientation }
                var frame: Rect? = null
                val deadline = SystemClock.uptimeMillis() + 10000
                while (frame == null && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val scanner = activity.findViewById<com.journeyapps.barcodescanner.BarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_surface)
                        frame = scanner.framingRect
                    }
                    SystemClock.sleep(100)
                }
                assertNotNull("Camera preview must establish its decode crop", frame)
                assertEquals(frame!!.width(), frame!!.height())
                assertTrue(frame!!.width() > 100)
                scenario.onActivity { activity ->
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
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            UiTestSupport.awaitWindowContaining("Сканировать QR-код", "Закрыть")
            UiTestSupport.screenshot("qr-camera-fixture", false, FlintPhase.DISCONNECTED)
            UiTestSupport.clickAccessibilityText("Закрыть")
        }
    }
}
