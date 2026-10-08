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
            ,"support" to arrayOf("Поддержка", "Отправить в поддержку", "Обновить ответы", "Обращение ticket-tes")
            ,"assist" to arrayOf("Flint Assist", "Написать оператору", "Госзакупки", "Закрыть")
            ,"identity" to arrayOf("Аккаунт Flint", "Подписка активна", "Способы входа · почта и Telegram")
            ,"routing" to arrayOf("Раздельное проксирование", "Добавить сайт", "zakupki.gov.ru")
        )) {
            ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext, AccountHarnessActivity::class.java).putExtra("screen", screen)).use {
                UiTestSupport.awaitWindowContaining(*labels)
                UiTestSupport.screenshot("account-$screen-fixture", false, FlintPhase.DISCONNECTED)
            }
        }
    }
    @Test fun supportSendsExactTextAndDisplaysRepliesInline() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext, AccountHarnessActivity::class.java).putExtra("screen", "support")).use {
            val root = UiTestSupport.awaitWindowContaining("Отправить в поддержку", "Обновить ответы", "Обращение ticket-tes")
            fun input(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.isEditable) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { c -> input(c)?.let { return it } }
                return null
            }
            val field = requireNotNull(input(root))
            val args = android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Проверка формы поддержки") }
            org.junit.Assert.assertTrue(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Обращение сохранено. Ответ появится здесь.")
            org.junit.Assert.assertEquals(1, AccountHarnessActivity.requests.count { it == "POST /support/tickets" })
        }
    }
}
