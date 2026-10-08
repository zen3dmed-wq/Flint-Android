package app.flint.prototype

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flint.prototype.testing.AccountHarnessActivity
import app.flint.prototype.ui.FlintPhase
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountUiTest {
    @Test fun productScreensHaveRealNativeContentWithoutProductionWrites() {
        for ((screen, labels) in listOf(
            "purchase" to arrayOf("Выберите тариф", "1 Месяц", "3 Месяца", "6 Месяцев", "12 Месяцев"),
            "subscriptions" to arrayOf("Мои подписки", "199,6 GB / 1000,0 GB"),
            "friends" to arrayOf("Пригласить друга", "FLINT-TEST"),
            "devices" to arrayOf("Устройства", "Добавить устройство по QR", "Сеансы входа в аккаунт")
        )) {
            ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext, AccountHarnessActivity::class.java).putExtra("screen", screen)).use {
                UiTestSupport.awaitWindowContaining(*labels)
                UiTestSupport.screenshot("account-$screen-fixture", false, FlintPhase.DISCONNECTED)
            }
        }
    }
}
