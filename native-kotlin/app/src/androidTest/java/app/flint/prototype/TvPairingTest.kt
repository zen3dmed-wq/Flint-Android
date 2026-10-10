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

class TvPairingTest {
    @Test fun tvQrPhoneApprovalTransfersProfilesAndStartsWithoutTelegramOrTvLogin() {
        val inst=UiTestSupport.instrumentation
        val context=inst.targetContext
        PairingFixture.reset()
        context.getSharedPreferences("flint-account",0).edit().clear().commit()
        context.getSharedPreferences("flint-token-vault",0).edit().clear().commit()
        try {
            ActivityScenario.launch<PairingHarnessActivity>(Intent(context,PairingHarnessActivity::class.java)).use {
                UiTestSupport.awaitWindowContaining("Ожидаем подтверждения на телефоне")
                val bitmap=inst.uiAutomation.takeScreenshot()!!
                val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val qr=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))),
                    mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)).text
                assertTrue(qr.startsWith("flint://pair?"));assertFalse(qr.contains("t.me"))
                // ActivityScenario clears the target task. Model the phone in a
                // separate task so launching it does not destroy the fake TV.
                ActivityScenario.launch<PairingHarnessActivity>(Intent(context,PairingHarnessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("qr",qr)).use {
                    UiTestSupport.awaitWindowContaining("Тестовый телевизор","Передать · Тестовая подписка")
                    UiTestSupport.clickAccessibilityText("Передать · Тестовая подписка")
                    UiTestSupport.awaitWindowContaining("Настройки отправлены.")
                    val deadline=SystemClock.uptimeMillis()+10000
                    while((PairingFixture.connected.get()==0 || !PairingFixture.acknowledged) && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
                    assertEquals(1,PairingFixture.saved.get());assertEquals(1,PairingFixture.connected.get());assertTrue(PairingFixture.acknowledged)
                    assertTrue(ProfileStore(context).load().any {it.id==PairingFixture.profile.id})
                    assertTrue(ProfileStore(context).sources().contains(PairingFixture.source))
                    assertTrue(PairingFixture.requests.none {it.first.contains("telegram")})
                    for((path,authenticated) in PairingFixture.requests) {
                        if(path in setOf("/devices/pairing/start","/devices/pairing/complete","/devices/pairing/ack")) assertFalse(path,authenticated)
                        if(path in setOf("/devices/pairing/inspect","/devices/pairing/approve")) assertTrue(path,authenticated)
                    }
                }
            }
        } finally {
            ProfileStore(context).merge(emptyList(),PairingFixture.source)
            context.getSharedPreferences("flint-account",0).edit().clear().commit()
            context.getSharedPreferences("flint-token-vault",0).edit().clear().commit()
        }
    }
    @Test fun missingServerMethodExplainsDependencyWithoutOpeningTelegram() {
        PairingFixture.reset()
        ActivityScenario.launch<PairingHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext,PairingHarnessActivity::class.java).putExtra("missing",true)).use {
            UiTestSupport.awaitWindowContaining("Добавление ТВ без Telegram ещё не включено на сервере Flint.")
            assertEquals(listOf("/devices/pairing/start"),PairingFixture.requests.map {it.first})
            assertEquals(0,PairingFixture.connected.get())
        }
    }
    @Test fun closingQrDuringAcknowledgementDoesNotStartVpn() {
        val inst=UiTestSupport.instrumentation
        val context=inst.targetContext
        PairingFixture.reset();PairingFixture.holdAck=true
        try {
            ActivityScenario.launch<PairingHarnessActivity>(Intent(context,PairingHarnessActivity::class.java)).use { scenario ->
                UiTestSupport.awaitWindowContaining("Ожидаем подтверждения на телефоне")
                val bitmap=inst.uiAutomation.takeScreenshot()!!
                val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val qr=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))),
                    mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)).text
                val target=PairingProtocol.parse(qr)
                PairingFixture.envelope=PairingProtocol.seal(target.id,target.key,PairingProtocol.payload(
                    listOf(PairingFixture.profile),PairingFixture.source,"Тестовая подписка",listOf("gosuslugi.ru","yandex.ru"),true,null,PairingFixture.profile.id))
                val deadline=SystemClock.uptimeMillis()+10000
                while(!PairingFixture.ackStarted && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
                assertTrue(PairingFixture.ackStarted);assertEquals(1,PairingFixture.saved.get())
                scenario.onActivity {it.closePairing()}
                inst.waitForIdleSync()
                assertEquals(0,PairingFixture.connected.get());assertFalse(PairingFixture.acknowledged)
            }
        } finally {
            PairingFixture.holdAck=false
            ProfileStore(context).merge(emptyList(),PairingFixture.source)
        }
    }
}
