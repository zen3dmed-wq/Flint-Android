package app.flint.prototype

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import app.flint.prototype.data.ProfileStore
import app.flint.prototype.pairing.PairingProtocol
import app.flint.prototype.testing.PairingFixture
import app.flint.prototype.testing.PairingHarnessActivity
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test

class SharePairingTest {
    @Test fun existingDevicesQrLetsRecipientConnectWithoutLoginOrTelegram() {
        val inst=UiTestSupport.instrumentation;val context=inst.targetContext
        PairingFixture.reset()
        context.getSharedPreferences("flint-account",0).edit().clear().commit()
        context.getSharedPreferences("flint-token-vault",0).edit().clear().commit()
        try {
            ActivityScenario.launch<PairingHarnessActivity>(Intent(context,PairingHarnessActivity::class.java).putExtra("owner",true)).use {
                UiTestSupport.awaitWindowContaining("Добавить устройство по QR")
                UiTestSupport.clickAccessibilityText("Добавить устройство по QR")
                UiTestSupport.awaitWindowContaining("Покажите QR другому человеку.")
                val bitmap=inst.uiAutomation.takeScreenshot()!!
                val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val qr=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))),
                    mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)).text
                assertTrue(qr.startsWith("https://flintmain.ru/connect/"));assertTrue(PairingProtocol.parse(qr).shared)
                val logins=PairingFixture.requests.count {it.first=="/auth/login"}
                ActivityScenario.launch<PairingHarnessActivity>(Intent(context,PairingHarnessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("qr",qr)).use {
                    UiTestSupport.awaitWindowContaining("Получить и подключиться")
                    UiTestSupport.clickAccessibilityText("Получить и подключиться")
                    val deadline=SystemClock.uptimeMillis()+10000
                    while(PairingFixture.connected.get()==0&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
                    assertEquals(1,PairingFixture.saved.get());assertEquals(1,PairingFixture.connected.get())
                    assertEquals(logins,PairingFixture.requests.count {it.first=="/auth/login"})
                    assertTrue(PairingFixture.requests.none {it.first.contains("telegram")})
                    assertFalse(PairingFixture.requests.first {it.first=="/devices/pairing/share/claim"}.second)
                }
            }
        } finally {
            ProfileStore(context).merge(emptyList(),PairingFixture.source)
            context.getSharedPreferences("flint-account",0).edit().clear().commit()
            context.getSharedPreferences("flint-token-vault",0).edit().clear().commit()
        }
    }
}
