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
    private fun createSupport() {
        UiTestSupport.awaitWindowContaining("Создать обращение", "Помогите подключить телевизор")
        UiTestSupport.clickAccessibilityText("Создать обращение")
        UiTestSupport.awaitWindowContaining("Тема: Общий вопрос")
    }
    private fun writeSupport(text: String) {
        UiTestSupport.awaitWindowContaining("Отправить в поддержку")
        fun field(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if(node.isEditable && node.isVisibleToUser && node.isEnabled) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { field(it)?.let { f -> return f } }
            return null
        }
        // The button may enter the accessibility tree before the editor does.
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val editor = UiTestSupport.instrumentation.uiAutomation.rootInActiveWindow?.let { field(it) }
            if (editor != null && editor.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text) })) return
            android.os.SystemClock.sleep(50)
        }
        org.junit.Assert.fail("Support editor must accept text within 8 seconds")
    }
    @Test fun supportChatRepliesReadCloseAndRatingFollowV1Contract() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext,AccountHarnessActivity::class.java).putExtra("screen","support")).use {
            createSupport()
            writeSupport("Первая строка\nОписание проблемы"); UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Обращение №ticket-new", "Анна", "Здравствуйте! Уточните")
            org.junit.Assert.assertEquals("Общий вопрос",AccountHarnessActivity.supportFixture.bodies.first().getString("subject"))
            org.junit.Assert.assertEquals("Первая строка\nОписание проблемы",AccountHarnessActivity.supportFixture.bodies.first().getString("text"))
            writeSupport("Телефон Samsung"); UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Спасибо, проверяем подключение.")
            org.junit.Assert.assertTrue(AccountHarnessActivity.requests.any { it == "POST /support/tickets/ticket-new/messages" })
            org.junit.Assert.assertTrue(AccountHarnessActivity.requests.any { it.startsWith("GET /support/tickets/ticket-new?afterMessageId=") })
            val limit = android.os.SystemClock.uptimeMillis()+5000
            while(AccountHarnessActivity.requests.none { it == "POST /support/tickets/ticket-new/read" } && android.os.SystemClock.uptimeMillis()<limit) android.os.SystemClock.sleep(50)
            org.junit.Assert.assertTrue(AccountHarnessActivity.requests.any { it == "POST /support/tickets/ticket-new/read" })
            UiTestSupport.clickAccessibilityText("Закрыть обращение")
            UiTestSupport.awaitWindowContaining("Закрыть обращение?")
            UiTestSupport.clickAccessibilityText("Закрыть обращение")
            UiTestSupport.awaitWindowContaining("Оценить работу поддержки")
            UiTestSupport.clickAccessibilityText("Оценить работу поддержки")
            UiTestSupport.awaitWindowContaining("Отправить оценку")
            UiTestSupport.clickAccessibilityText("Отправить оценку")
            UiTestSupport.awaitWindowContaining("Спасибо за оценку")
        }
    }
    @Test fun supportRetryKeepsIdempotencyKeyAfterLostResponse() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext,AccountHarnessActivity::class.java).putExtra("screen","support").putExtra("supportTimeout",true)).use {
            createSupport()
            writeSupport("Повтор без дубликата"); UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Тестовый обрыв связи")
            UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Сообщение отправлено")
            val keys=AccountHarnessActivity.supportFixture.keys
            org.junit.Assert.assertEquals(2,keys.size);org.junit.Assert.assertEquals(keys[0],keys[1])
        }
    }
    @Test fun supportThreeOpenTicketsPreventFourthWithoutBlockingExistingChat() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext,AccountHarnessActivity::class.java).putExtra("screen","support").putExtra("supportOpenCount",3)).use {
            UiTestSupport.awaitWindowContaining("Открыто обращений: 3 из 3", "Открытый вопрос 1")
            UiTestSupport.clickAccessibilityText("Создать обращение")
            UiTestSupport.awaitWindowContaining("Открытый вопрос 1", "Открытый вопрос 2", "Открытый вопрос 3")
            org.junit.Assert.assertEquals(0,AccountHarnessActivity.requests.count { it == "POST /support/tickets" })
        }
    }
    @Test fun supportRateLimitHonorsRetryAfterAndKeepsDraft() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext,AccountHarnessActivity::class.java).putExtra("screen","support").putExtra("supportRateLimited",true)).use {
            createSupport()
            writeSupport("Проверка лимита");UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Лимит новых обращений")
            UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Слишком много запросов")
            org.junit.Assert.assertEquals(1,AccountHarnessActivity.requests.count { it == "POST /support/tickets" })
        }
    }
    @Test fun supportStopsPollingWhenActivityIsStopped() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext,AccountHarnessActivity::class.java).putExtra("screen","support")).use { scenario ->
            createSupport()
            writeSupport("Проверка паузы");UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Сообщение отправлено")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            android.os.SystemClock.sleep(1000)
            val before=AccountHarnessActivity.requests.count { it.startsWith("GET /support/tickets/ticket-new?") }
            android.os.SystemClock.sleep(13_000)
            org.junit.Assert.assertEquals(before,AccountHarnessActivity.requests.count { it.startsWith("GET /support/tickets/ticket-new?") })
        }
    }
    @Test fun productScreensHaveRealNativeContentWithoutProductionWrites() {
        for ((screen, labels) in listOf(
            "purchase" to arrayOf("Выберите тариф", "1 Месяц", "3 Месяца", "6 Месяцев", "12 Месяцев"),
            "subscriptions" to arrayOf("Мои подписки", "199,6 GB / 1000,0 GB"),
            "friends" to arrayOf("Пригласить друга", "FLINT-TEST"),
            "devices" to arrayOf("Устройства", "Добавить устройство по QR", "Сеансы входа в аккаунт")
            ,"support" to arrayOf("Поддержка", "Создать обращение", "Помогите подключить телевизор", "Закрыто")
            ,"settings" to arrayOf("Настройки Flint", "Добавить виджет на экран", "Закрыть")
            ,"identity" to arrayOf("Аккаунт Flint", "Подписка активна", "Способы входа · почта и Telegram")
            ,"routing" to arrayOf("Раздельное проксирование", "Добавить сайт", "zakupki.gov.ru")
        )) {
            ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext, AccountHarnessActivity::class.java).putExtra("screen", screen)).use {
                UiTestSupport.awaitWindowContaining(*labels)
                UiTestSupport.screenshot("account-$screen-fixture", false, FlintPhase.DISCONNECTED)
            }
        }
    }
    @Test fun routingOpensInstalledAppChoicesAndSavesWithoutSendingAccountData() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext, AccountHarnessActivity::class.java).putExtra("screen", "routing")).use {
            UiTestSupport.awaitWindowContaining("Раздельное проксирование", "Приложения напрямую")
            UiTestSupport.clickAccessibilityText("Приложения напрямую")
            UiTestSupport.awaitWindowContaining("Приложения напрямую", "Найдено:", "Сохранить")
            UiTestSupport.screenshot("direct-apps-fixture", false, FlintPhase.DISCONNECTED)
            UiTestSupport.clickAccessibilityText("Сохранить")
            UiTestSupport.awaitWindowContaining("Раздельное проксирование", "Сохранено")
            org.junit.Assert.assertFalse(AccountHarnessActivity.requests.any { it.contains("installed") || it.contains("routing") })
        }
    }

    @Test fun settingsAndInvitationFitAndStayCentered() {
        for (screen in listOf("settings", "friends")) {
            ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext, AccountHarnessActivity::class.java).putExtra("screen", screen)).use { scenario ->
                UiTestSupport.awaitWindowContaining(if (screen == "settings") "Настройки Flint" else "Применить код")
                UiTestSupport.instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    fun panel(view: android.view.View): android.view.View? {
                        if (view.tag == "flint-panel") return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) panel(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val p = android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull { panel(it) }.last()
                    val scroll = (p as android.view.ViewGroup).getChildAt(1) as android.widget.ScrollView
                    org.junit.Assert.assertFalse("$screen fits on this screen and must not scroll", scroll.canScrollVertically(1))
                    val bounds = android.graphics.Rect(); p.getGlobalVisibleRect(bounds)
                    org.junit.Assert.assertEquals(p.height, bounds.height())
                    val position = IntArray(2); p.getLocationOnScreen(position)
                    bounds.set(position[0], position[1], position[0] + p.width, position[1] + p.height)
                    val visible = android.graphics.Rect(); activity.window.decorView.getWindowVisibleDisplayFrame(visible)
                    org.junit.Assert.assertTrue("$screen centered: $bounds inside $visible", kotlin.math.abs(bounds.centerY() - visible.centerY()) < 24 * activity.resources.displayMetrics.density)
                }
            }
        }
    }
    @Test fun mainSupportOpensTicketListAndCreateOpensComposer() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext, AccountHarnessActivity::class.java).putExtra("screen", "home")).use {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while ("GET /config" !in AccountHarnessActivity.requests && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
            UiTestSupport.clickAccessibilityText("Поддержка")
            createSupport()
            val root = UiTestSupport.awaitWindowContaining("Отправить в поддержку", "Все обращения")
            fun editable(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.isEditable) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> editable(child)?.let { return it } }
                return null
            }
            val field = requireNotNull(editable(root)) { "Support must immediately show an editable message field" }
            org.junit.Assert.assertTrue(field.isEnabled)
            org.junit.Assert.assertEquals("Опишите проблему", field.hintText?.toString())
            org.junit.Assert.assertTrue(root.findAccessibilityNodeInfosByText("Госзакупки").isEmpty())
            org.junit.Assert.assertTrue(root.findAccessibilityNodeInfosByText("Написать оператору").isEmpty())
        }
    }
    @Test fun supportSendsExactTextAndDisplaysRepliesInline() {
        ActivityScenario.launch<AccountHarnessActivity>(Intent(UiTestSupport.instrumentation.targetContext, AccountHarnessActivity::class.java).putExtra("screen", "support")).use {
            createSupport()
            val root = UiTestSupport.awaitWindowContaining("Отправить в поддержку", "Все обращения", "Тема: Общий вопрос")
            fun input(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.isEditable) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { c -> input(c)?.let { return it } }
                return null
            }
            val field = requireNotNull(input(root))
            val args = android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Проверка формы поддержки") }
            org.junit.Assert.assertTrue(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            UiTestSupport.clickAccessibilityText("Отправить в поддержку")
            UiTestSupport.awaitWindowContaining("Сообщение отправлено", "Анна", "Здравствуйте! Уточните")
            org.junit.Assert.assertEquals(1, AccountHarnessActivity.requests.count { it == "POST /support/tickets" })
        }
    }
}
