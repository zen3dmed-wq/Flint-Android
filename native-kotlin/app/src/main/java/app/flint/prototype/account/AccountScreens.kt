package app.flint.prototype.account

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import app.flint.prototype.BuildConfig
import app.flint.prototype.ui.FlintStyle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Each Qt popup remains a separate screen; no purchase/support tabs in settings. */
class AccountScreens(private val activity: Activity, private val api: FlintAccount,
                     private val scope: CoroutineScope, private val changed: suspend (Boolean) -> Unit) {
    private val s = FlintStyle(activity)
    private val panels = mutableListOf<FlintStyle.Panel>()
    private val polls = mutableMapOf<FlintStyle.Panel, Job>()
    private fun panel(title: String, wide: Boolean = false) = s.panel(title, wide,
        maxWidth = if (wide) 690 else if (title in setOf("Купить / продлить подписку", "Поддержка", "Мои подписки", "Пригласить друга")) 560 else 520,
        logo = title in setOf("Аккаунт Flint", "Вход во Flint")).also { p ->
        panels.add(p); p.dialog.setOnDismissListener { polls.remove(p)?.cancel(); panels.remove(p) }
    }
    fun close() { polls.values.toList().forEach { it.cancel() }; polls.clear(); panels.toList().forEach { it.dialog.dismiss() }; panels.clear() }
    private fun task(p: FlintStyle.Panel, action: suspend () -> Unit) {
        if (p.busy) return
        p.busy = true; p.message.text = "Загрузка…"
        scope.launch {
            try { action(); if (p.message.text == "Загрузка…") p.message.text = "" }
            catch (e: CancellationException) { throw e }
            catch (e: ApiError) { p.error(e.message.orEmpty()) }
            catch (_: Exception) { p.error("Не удалось выполнить действие. Повторите попытку.") }
            finally { p.busy = false }
        }
    }
    fun requireAccount(next: () -> Unit): Boolean {
        if (api.loggedIn) return true
        login(next); return false
    }
    fun login(after: () -> Unit = {}) {
        val p = panel("Вход во Flint")
        s.add(p.body, s.label("Windows • Android • TV", 11f, color = s.muted))
        val email = s.field("Электронная почта").apply { inputType = 33 }
        val password = s.field("Пароль", true)
        s.add(p.body, email, 50); s.add(p.body, password, 50)
        fun submit(register: Boolean) {
            if (email.text.isBlank() || password.text.isBlank()) { p.error("Укажите почту и пароль."); return }
            if (register && password.text.length !in 8..128) { p.error("Пароль должен содержать от 8 до 128 символов."); return }
            task(p) {
                api.login(email.text.toString(), password.text.toString(), register)
                password.text.clear(); changed(true); p.dialog.dismiss(); after()
            }
        }
        s.add(p.body, s.primary("Войти") { submit(false) }, 48)
        s.add(p.body, s.button("Войти через Telegram") { p.dialog.dismiss(); telegram(false, after) }, 48)
        s.add(p.body, s.button("Создать аккаунт") { submit(true) }, 48)
        val links = api.config.optJSONObject("links") ?: JSONObject()
        listOf("userAgreement" to "Условия использования", "privacyPolicy" to "Политика конфиденциальности").forEach { (key, title) ->
            if (links.string(key).isNotBlank()) s.add(p.body, s.button(title) { openLink(links.string(key), p) }, 44)
        }
    }
    fun identity() {
        if (!requireAccount { identity() }) return
        val p = panel("Аккаунт Flint")
        task(p) {
            api.refresh(); changed(false)
            s.add(p.body, s.label("Windows • Android • TV", 11f, color = s.muted))
            s.add(p.body, s.label(api.title, 18f, true))
            val active = api.subscriptions.any { it.string("status") == "active" }
            s.add(p.body, s.label(if (active) "Подписка активна" else "Нет активной подписки", color = if (active) s.mint else s.muted))
            val sessions = s.label("", color = s.muted); s.add(p.body, sessions)
            s.add(p.body, s.button("Способы входа · почта и Telegram") { p.dialog.dismiss(); loginMethods() }, 48)
            if (api.me.optBoolean("trialAvailable")) s.add(p.body, s.primary("Попробовать бесплатно") { task(p) {
                api.request("POST", "/subscriptions/trial", JSONObject()); api.refresh(); changed(true); p.dialog.dismiss()
            } }, 48)
            s.add(p.body, s.button("Выйти") { confirm("Выйти из Flint?", "Сохранённые вручную серверы останутся.") {
                task(p) { try { api.logout() } finally { changed(true); p.dialog.dismiss() } }
            } }, 48)
            s.closeButton(p)
            runCatching { objects(api.request("GET", "/me/sessions").data, "items").size }.getOrNull()?.let { sessions.text = "Сеансов входа: $it" }
        }
    }
    private fun loginMethods() {
        val p = panel("Способы входа")
        task(p) { api.refresh(); changed(false); drawLoginMethods(p) }
    }
    private fun drawLoginMethods(p: FlintStyle.Panel) {
        p.body.removeAllViews()
        s.add(p.body, s.label(api.title, 19f, true))
        s.add(p.body, s.label("Подписка принадлежит этому аккаунту. Почта и Telegram — способы входа в один аккаунт.", color = s.muted))
        s.add(p.body, s.label("Почта: " + api.me.string("email").ifBlank { "не привязана" }))
        val telegram = api.me.optJSONObject("telegram")
        s.add(p.body, s.label("Telegram: " + (telegram?.string("username")?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: if (telegram?.string("id")?.isNotBlank() == true) "привязан" else "не привязан")))
        if (api.me.string("email").isBlank()) {
            val email = s.field("Электронная почта"); val password = s.field("Пароль для входа по почте", true)
            s.add(p.body, email, 50); s.add(p.body, password, 50)
            s.add(p.body, s.button("Привязать почту") { task(p) {
                api.request("POST", "/me/email-login", JSONObject().put("email", email.text.toString().trim()).put("password", password.text.toString()))
                password.text.clear(); api.refresh(); changed(false); drawLoginMethods(p)
            } }, 48)
        }
        if (telegram == null || telegram.length() == 0) s.add(p.body, s.button("Привязать мой Telegram") { p.dialog.dismiss(); telegram(true) { loginMethods() } }, 48)
        s.add(p.body, s.button("Обновить аккаунт") { task(p) { api.refresh(); changed(false); drawLoginMethods(p) } }, 48)
    }
    fun telegram(link: Boolean = false, after: () -> Unit = {}) {
        val p = panel(if (link) "Привязать Telegram" else if (BuildConfig.IS_TV) "Добавить телевизор" else "Войти через Telegram")
        val key = if (link) "telegramLink" else "telegramLogin"
        val job = scope.launch {
            try {
                p.message.text = "Подготовка входа…"
                var pending = api.draft(key)
                if (pending == null || epoch(pending.string("expiresAt")) < System.currentTimeMillis() + 5000) {
                    val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                    val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                    pending = api.request("POST", if (link) "/me/telegram/bot/start" else "/auth/telegram/bot/start",
                        if (link) JSONObject() else JSONObject().put("codeChallenge", challenge).put("device", api.device()), link).data
                    pending.put("verifier", verifier); api.saveDraft(key, pending)
                }
                val loginId = pending.string("loginId"); val url = pending.string("botUrl")
                require(loginId.isNotBlank() && url.startsWith("https://t.me/"))
                s.add(p.body, s.label(if (BuildConfig.IS_TV) "Сканируйте QR телефоном и подтвердите вход. Подписка загрузится на телевизоре автоматически." else "Подтвердите вход в Telegram и вернитесь во Flint.", color = s.muted))
                addQr(p, url)
                if (!BuildConfig.IS_TV) s.add(p.body, s.button("Открыть Telegram") { openTelegram(url, p) }, 50)
                p.message.text = "Ожидаем подтверждения…"
                while (p.dialog.isShowing && epoch(pending.string("expiresAt")) > System.currentTimeMillis()) {
                    delay(2000)
                    val body = JSONObject().put("loginId", loginId)
                    if (!link) body.put("codeVerifier", pending.string("verifier"))
                    val reply = api.request("POST", if (link) "/me/telegram/bot/complete" else "/auth/telegram/bot/complete", body, link)
                    if (reply.status == 202) continue
                    if (link) api.refresh() else api.acceptTelegram(reply.data)
                    api.saveDraft(key, null); changed(true); p.dialog.dismiss(); after(); return@launch
                }
                if (p.dialog.isShowing) p.error("Время входа истекло. Закройте окно и начните снова.")
            } catch (e: CancellationException) { throw e }
            catch (e: ApiError) { if (e.status == 410) api.saveDraft(key, null); p.error(e.message.orEmpty()) }
            catch (_: Exception) { p.error("Не удалось начать вход. Повторите попытку.") }
        }
        p.dialog.setOnDismissListener { job.cancel(); panels.remove(p) }
    }
    fun subscriptions() {
        if (!requireAccount { subscriptions() }) return
        val p = panel("Мои подписки")
        s.buttons(p.footer, s.button("Обновить") { p.dialog.dismiss(); subscriptions() }, s.button("Закрыть") { p.dialog.dismiss() })
        fun draw() {
            p.body.removeAllViews()
            s.add(p.body, s.label("Выберите подписку для подключения. У каждой свой список серверов и трафик.", color = s.muted))
            api.subscriptions.forEach { sub ->
                val id = sub.string("id")
                val active = sub.string("status").equals("active", true) &&
                    (sub.string("expiresAt").isBlank() || epoch(sub.string("expiresAt")) > System.currentTimeMillis()) &&
                    sub.optJSONObject("traffic")?.optBoolean("limitReached") != true
                val card = s.column().apply {
                    background = s.surface(if (id == api.selectedId) 0xFF57E4B0.toInt() else s.card)
                    setPadding(s.dp(14), s.dp(12), s.dp(14), s.dp(12))
                    isFocusable = true; isFocusableInTouchMode = BuildConfig.IS_TV
                    isEnabled = active; alpha = if (active) 1f else .55f
                    contentDescription = subTitle(sub) + if (id == api.selectedId) ", выбрана" else ""
                    setOnClickListener { if (active) task(p) { api.selectedId = id; changed(true); p.dialog.dismiss() } }
                }
                s.add(card, s.label(subTitle(sub) + (if (id == api.selectedId) " · выбрана" else ""), 17f, true,
                    if (id == api.selectedId) 0xFF052A20.toInt() else s.ink), gap = 0)
                val bar = FrameLayout(activity).apply { background = s.shape(0xFF77818E.toInt(), android.graphics.Color.TRANSPARENT, 8); clipToOutline = true }
                val fill = View(activity).apply { background = s.shape(0xFF008CFF.toInt(), android.graphics.Color.TRANSPARENT, 8) }
                bar.addView(fill, FrameLayout.LayoutParams(0, -1))
                bar.addView(s.label(traffic(sub), 12f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                bar.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                    val width = ((right - left) * fraction(sub)).toInt()
                    if (fill.layoutParams.width != width) fill.layoutParams = FrameLayout.LayoutParams(width, -1)
                }
                s.add(card, bar, 22); s.add(card, s.label(expiry(sub), 12f, color = if (id == api.selectedId) 0xFF164C3C.toInt() else s.muted))
                if (!active) s.add(card, s.label("Подписка неактивна", 12f, color = s.muted), gap = 6)
                s.add(p.body, card)
            }
            if (api.subscriptions.isEmpty()) s.add(p.body, s.label("У вас пока нет подписок", color = s.muted))
        }
        draw(); task(p) { api.refresh(); changed(false); draw() }
    }
    fun purchase() {
        if (!requireAccount { purchase() }) return
        val p = panel("Купить / продлить подписку")
        val saved = api.draft("orderDraft")
        if (saved != null) { order(p, saved); return }
        task(p) {
            api.publicConfig()
            if (!api.config.optBoolean("purchasesEnabled", true)) { p.error("Покупка временно недоступна. Попробуйте позже."); return@task }
            val plans = objects(api.request("GET", "/plans").data, "items")
            val methods = objects(api.request("GET", "/payment-methods").data, "items")
            if (plans.isEmpty() || methods.isEmpty()) { p.error("Сейчас нет доступных тарифов или способов оплаты."); return@task }
            s.add(p.body, s.label("Выберите тариф", 20f, true, s.mint))
            s.add(p.body, s.label("Покупка для: ${api.title}", 12f, color = s.muted))
            var planId = ""; var provider = methods.first().string("id")
            val planButtons = mutableListOf<Pair<String, LinearLayout>>()
            plans.chunked(2).forEach { pair ->
                val row = s.row()
                pair.forEachIndexed { index, plan ->
                    val price = plan.optJSONObject("price") ?: JSONObject()
                    val available = plan.string("availableUntil").isBlank() || epoch(plan.string("availableUntil")) > System.currentTimeMillis()
                    val card = s.column().apply {
                        background = s.surface(); setPadding(s.dp(12), s.dp(12), s.dp(12), s.dp(12))
                        isFocusable = true; isFocusableInTouchMode = BuildConfig.IS_TV; isClickable = true; isEnabled = available
                        alpha = if (available) 1f else .55f
                    }
                    s.add(card, s.label(plan.string("name"), 14f, true), gap = 0)
                    s.add(card, s.label("${price.string("amount")} ${price.string("currency")}", 20f, true), gap = 5)
                    s.add(card, s.label(benefit(plan, plans), 11f, color = s.mint), gap = 5)
                    val detail = planDetails(plan)
                    if (detail.isNotBlank()) s.add(card, s.label(detail, 11f, color = s.muted), gap = 5)
                    card.setOnClickListener {
                        planId = plan.string("id")
                        planButtons.forEach { (id, b) ->
                            val selected = id == planId
                            b.background = s.surface(if (selected) 0xFF57E4B0.toInt() else s.card)
                            for (i in 0 until b.childCount) (b.getChildAt(i) as? TextView)?.setTextColor(if (selected) 0xFF052A20.toInt() else if (i == 2) s.mint else if (i > 2) s.muted else s.ink)
                        }
                    }
                    row.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index == 0 && pair.size > 1) marginEnd = s.dp(10) })
                    planButtons.add(plan.string("id") to card)
                }
                row.minimumHeight = s.dp(124); s.add(p.body, row)
            }
            planButtons.firstOrNull { it.second.isEnabled }?.second?.performClick()
            s.add(p.body, s.label("Способ оплаты", 12f, color = s.muted))
            val choice = s.button(methods.first().string("title") + "  ⌄") {}
            choice.setOnClickListener {
                val select = panel("Способ оплаты")
                methods.forEach { method -> s.add(select.body, s.button(method.string("title")) {
                    provider = method.string("id"); choice.text = method.string("title") + "  ⌄"; select.dialog.dismiss()
                }, 48) }
            }
            s.add(p.body, choice, 46)
            val pay = s.button("Перейти к оплате") {
                if (planId.isBlank()) { p.error("Выберите доступный тариф."); return@button }
                val draft = JSONObject().put("key", UUID.randomUUID().toString()).put("body", JSONObject().put("planId", planId).put("provider", provider))
                api.saveDraft("orderDraft", draft); order(p, draft)
            }
            pay.background = s.surface(s.mint); pay.setTextColor(0xFF052A20.toInt()); s.add(p.body, pay, 48)
        }
    }
    private fun order(p: FlintStyle.Panel, draft: JSONObject) {
        task(p) {
            p.body.removeAllViews()
            s.add(p.body, s.label("Проверяем ваш заказ. Повторная попытка использует тот же заказ и не создаёт новую покупку.", color = s.muted))
            s.add(p.body, s.button("Повторить проверку заказа") { order(p, draft) }, 48)
            var id = draft.string("orderId")
            var data = if (id.isBlank()) api.request("POST", "/orders", draft.getJSONObject("body"), idempotencyKey = draft.string("key")).data
                else api.request("GET", "/orders/${Uri.encode(id)}").data
            id = data.string("id"); require(id.isNotBlank()); draft.put("orderId", id); api.saveDraft("orderDraft", draft)
            fun draw(value: JSONObject) {
                p.body.removeAllViews()
                val status = value.string("status").lowercase()
                s.add(p.body, s.label("Заказ: " + when(status) { "paid", "completed", "succeeded" -> "Оплачен"; "cancelled", "canceled" -> "Отменён"; "expired" -> "Истёк"; else -> "Ожидает оплаты" }, 20f, true))
                if (status in setOf("paid", "completed", "succeeded", "cancelled", "canceled", "expired", "failed")) {
                    api.saveDraft("orderDraft", null)
                    s.add(p.body, s.button("Мои подписки") { p.dialog.dismiss(); subscriptions() }, 48)
                    return
                }
                val payment = value.optJSONObject("payment") ?: JSONObject()
                s.add(p.body, s.label(payment.string("displayAmount"), 18f, true))
                s.add(p.body, s.button("Открыть оплату") { openLink(payment.string("url"), p) }, 48)
                s.add(p.body, s.button("Проверить оплату") { task(p) {
                    data = api.request("GET", "/orders/${Uri.encode(id)}").data; draw(data)
                    if (data.string("status").lowercase() in setOf("paid", "completed", "succeeded")) { api.refresh(); changed(true) }
                } }, 48)
                s.add(p.body, s.button("Обновить ссылку оплаты") { task(p) {
                    val updated = api.request("POST", "/orders/${Uri.encode(id)}/payment-link", JSONObject()).data
                    data = if (updated.has("payment")) updated else api.request("GET", "/orders/${Uri.encode(id)}").data; draw(data)
                } }, 48)
                s.add(p.body, s.button("Отменить заказ") { confirm("Отменить заказ?", "Подписка не будет оплачена этим заказом.") { task(p) {
                    api.request("POST", "/orders/${Uri.encode(id)}/cancel", JSONObject()); api.saveDraft("orderDraft", null); p.dialog.dismiss()
                } } }, 48)
            }
            draw(data)
            if (data.string("status").lowercase() in setOf("paid", "completed", "succeeded")) { api.refresh(); changed(true) }
            if (data.string("status").lowercase() !in setOf("paid", "completed", "succeeded", "cancelled", "canceled", "expired", "failed")) {
                polls.remove(p)?.cancel()
                polls[p] = scope.launch {
                    while (isActive && p.dialog.isShowing && api.draft("orderDraft")?.string("orderId") == id) {
                        delay(5000)
                        if (p.busy) continue
                        p.busy = true
                        try {
                            val updated = api.request("GET", "/orders/${Uri.encode(id)}").data
                            if (updated.string("status") != data.string("status")) { data = updated; draw(data) }
                            if (data.string("status").lowercase() in setOf("paid", "completed", "succeeded")) {
                                api.refresh(); changed(true); break
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* The explicit check stays available during a temporary outage. */ }
                        finally { p.busy = false }
                    }
                }
            }
        }
    }
    fun friends() {
        if (!requireAccount { friends() }) return
        val p = panel("Пригласить друга")
        task(p) {
            val data = api.request("GET", "/referrals").data
            val code = data.string("code")
            val template = api.config.optJSONObject("flintIntegration")?.string("referralUrlTemplate").orEmpty()
            val website = if ("{code}" in template) template.replace("{code}", Uri.encode(code)) else ""
            val referralUrl = listOf(website, data.string("inviteUrl"), data.string("referralUrl"), data.string("link")).firstOrNull {
                val u = Uri.parse(it); u.scheme == "https" && u.host !in setOf("t.me", "telegram.me")
            }
            s.add(p.body, s.label("Ваш код: $code", 22f, true, s.mint))
            s.add(p.body, s.label("Приглашено друзей: ${data.optInt("invitedCount")}\nБонусных дней: ${data.optInt("bonusDays")}"))
            s.add(p.body, s.label("Друг может зарегистрироваться по почте и указать этот код. Telegram необязателен.", color = s.muted))
            val terms = data.optJSONObject("terms")
            if (terms != null) s.add(p.body, s.label("Бонус ${terms.optDouble("bonusPercent")}% при покупке от ${terms.optInt("minPurchaseDays")} дней. Максимум ${terms.optInt("maxBonusDays")} бонусных дней.", 12f, color = s.muted))
            val invitation = referralUrl ?: "Приглашаю во Flint! Зарегистрируйтесь в приложении по почте, затем откройте Настройки → Пригласить друга и введите код: $code"
            s.buttons(p.body,
                s.button(if (referralUrl != null) "Копировать ссылку" else "Копировать") { copy(invitation); p.message.text = "Скопировано" },
                s.button("Поделиться") { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invitation), "Пригласить друга")) })
            if (data.isNull("referrer") || data.optJSONObject("referrer")?.length() == 0) {
                val field = s.field("Код пригласившего друга"); s.add(p.body, field, 50)
                s.add(p.body, s.button("Применить код") { task(p) {
                    api.request("POST", "/referrals/apply", JSONObject().put("code", field.text.toString().trim())); p.dialog.dismiss(); friends()
                } }, 48)
            }
        }
    }
    fun devices(subscriptionId: String = api.selectedId) {
        if (!requireAccount { devices() }) return
        val p = panel("Устройства")
        s.add(p.footer, s.button("Обновить список") { p.dialog.dismiss(); devices(subscriptionId) }, 48)
        task(p) {
            api.refresh(); changed(false)
            val sub = api.subscriptions.find { it.string("id") == subscriptionId } ?: api.selected
            if (api.subscriptions.size > 1) s.add(p.body, s.button(sub?.let(::subTitle).orEmpty() + "  ⌄") {
                val choose = panel("Подписка для управления устройствами")
                api.subscriptions.forEach { candidate -> s.add(choose.body, s.button(subTitle(candidate)) {
                    choose.dialog.dismiss(); p.dialog.dismiss(); devices(candidate.string("id"))
                }, 48) }
            }, 48)
            if (sub != null) {
                val sid = sub.string("id")
                try {
                    val doc = api.request("GET", "/subscriptions/${Uri.encode(sid)}/devices").data
                    val canManage = api.me.string("id").isNotBlank() && doc.optBoolean("canManageDevices") && doc.string("ownerUserId") == api.me.string("id") && doc.string("subscriptionId") == sid
                    s.add(p.body, s.label(if (canManage) "Только вы, как владелец подписки, можете отключать устройства." else "Управление доступно только владельцу основной подписки.", color = s.muted))
                    if (canManage) {
                        s.add(p.body, s.label("УСТРОЙСТВА ПОДПИСКИ", 11f, color = s.muted))
                        val items = objects(doc, "items").filter { it.string("id").isNotBlank() }.distinctBy { it.string("id") }
                        if (items.isEmpty()) s.add(p.body, s.label("Устройства пока не добавлены"))
                        items.forEach { device ->
                            val card = s.column().apply { background = s.shape(); setPadding(s.dp(14), s.dp(14), s.dp(14), s.dp(14)) }
                            val name = device.string("name").ifBlank { device.string("model").ifBlank { "Устройство" } }
                            s.add(card, s.label(name + if (device.optBoolean("isCurrent")) " · это устройство" else "", 15f, true), gap = 0)
                            s.add(card, s.label(if (device.optBoolean("revoked")) "Доступ отключён" else if (device.isNull("online")) "Статус VPN неизвестен" else if (device.optBoolean("online")) "VPN подключён" else "VPN не подключён", color = s.mint))
                            s.add(card, s.label("Последняя активность: " + date(device.string("lastSeenAt")).ifBlank { "Нет данных" }, 12f, color = s.muted))
                            if (!device.optBoolean("revoked")) s.add(card, s.button("Отключить доступ") {
                                confirm("Отключить «$name»?", "Устройство потеряет доступ к VPN по этой подписке. Остальные устройства продолжат работать.") { task(p) {
                                    val result = api.request("DELETE", "/subscriptions/${Uri.encode(sid)}/devices/${Uri.encode(device.string("id"))}")
                                    if (result.status != 204) throw ApiError(result.status, "not_confirmed", "Сервер ещё не подтвердил отключение. Обновите список.")
                                    if (device.optBoolean("isCurrent")) activity.startService(Intent(activity, app.flint.prototype.vpn.FlintVpnService::class.java).setAction(app.flint.prototype.vpn.VpnContract.ACTION_DISCONNECT))
                                    p.dialog.dismiss(); devices(sid)
                                } }
                            }, 44)
                            s.add(p.body, card)
                        }
                    }
                } catch (e: ApiError) {
                    s.add(p.body, s.label(when (e.status) {
                        403 -> "Управлять устройствами может только владелец основной подписки."
                        404,405,501 -> "Вход и подписка работают. Отключение VPN на отдельном устройстве пока недоступно на сервере Flint. Ниже можно управлять входами в свой аккаунт."
                        else -> "Не удалось загрузить устройства. Повторите обновление списка."
                    }, color = s.muted))
                }
                s.add(p.body, s.button("Добавить устройство по QR") { shareSubscription(sub) }.apply {
                    isEnabled = sub.string("status") == "active" && sub.string("subscriptionUrl").isNotBlank()
                }, 50)
            }
            s.add(p.body, s.label("Сеансы входа в аккаунт", 18f, true))
            s.add(p.body, s.label("Входы собраны по системе устройства. Откройте группу, чтобы завершить ненужный вход. Это список входов в аккаунт, а не активных VPN-подключений.", 12f, color = s.muted))
            val sessions = objects(api.request("GET", "/me/sessions").data, "items").filter { it.string("id").isNotBlank() }.distinctBy { it.string("id") }
            val groups = sessions.groupBy { if (it.string("deviceId").isNotBlank()) "device:" + it.string("deviceId") else "platform:" + it.string("platform").lowercase() }
                .values.sortedByDescending { group -> group.any { it.optBoolean("isCurrent") } }
            groups.forEach { group ->
                val first = group.firstOrNull { it.optBoolean("isCurrent") } ?: group.first()
                val known = first.string("deviceId").isNotBlank()
                val name = if (known) first.string("model").ifBlank { first.string("platform") } else when(first.string("platform").lowercase()) {
                    "android" -> "Android"; "android-tv" -> "Android TV"; "windows" -> "Windows"; "ios" -> "iPhone и iPad"; else -> "Другие устройства"
                }
                val card = s.column().apply { background = s.shape(0xFF102635.toInt()); setPadding(s.dp(14), s.dp(14), s.dp(14), s.dp(14)) }
                s.add(card, s.label("Входы: $name", 15f, true), gap = 0)
                s.add(card, s.label("Сеансов: ${group.size}" + (if (group.any { it.optBoolean("isCurrent") }) " · здесь текущий вход" else ""), 12f, color = s.muted))
                if (!known && group.size > 1) s.add(card, s.label("Здесь могут быть входы с разных устройств.", 12f, color = s.muted))
                val details = s.column().apply { visibility = View.GONE }
                val expand = s.button("Показать входы") {}
                expand.setOnClickListener { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE; expand.text = if (details.visibility == View.VISIBLE) "Свернуть входы" else "Показать входы" }
                s.add(card, expand, 46)
                group.sortedWith(compareByDescending<JSONObject> { it.optBoolean("isCurrent") }.thenByDescending { it.string("lastActiveAt") }).forEach { row ->
                    s.add(details, s.label(row.string("model").ifBlank { row.string("platform") }, 14f, true))
                    s.add(details, s.label("Flint " + row.string("appVersion") + if (row.optBoolean("isCurrent")) " · текущий вход" else "", 13f))
                    s.add(details, s.label("Последняя активность: " + date(row.string("lastActiveAt")).ifBlank { "Нет данных" }, 12f, color = s.muted))
                    if (!row.optBoolean("isCurrent")) s.add(details, s.button("Завершить вход") {
                        confirm("Завершить этот вход?", "На устройстве потребуется снова войти в аккаунт. Уже выданный VPN-ключ продолжит работать. Текущий вход и остальные сеансы сохранятся.") { task(p) {
                            val fresh = objects(api.request("GET", "/me/sessions").data, "items").find { it.string("id") == row.string("id") }
                            check(fresh != null && !fresh.optBoolean("isCurrent"))
                            val result = api.request("DELETE", "/me/sessions/${Uri.encode(row.string("id"))}")
                            if (result.status != 204) throw ApiError(result.status, "not_confirmed", "Сервер ещё не подтвердил завершение входа.")
                            p.dialog.dismiss(); devices(subscriptionId)
                        } }
                    }, 46)
                }
                s.add(card, details); s.add(p.body, card)
            }
        }
    }
    fun shareSubscription(sub: JSONObject) {
        val url = sub.string("subscriptionUrl")
        val p = panel("Добавить устройство")
        if (url.isBlank()) { p.error("У этой подписки пока нет ссылки подключения."); return }
        s.add(p.body, s.label("Отсканируйте QR в приложении Flint на другом устройстве.", color = s.muted))
        addQr(p, url)
        s.add(p.body, s.label("QR содержит секретную ссылку подписки. Не отправляйте его посторонним. Это общий ключ: устройства с ним нельзя отключить по отдельности.", 12f, color = s.muted))
    }
    fun support() {
        if (!requireAccount { support() }) return
        val p = panel("Поддержка")
        val capability = api.config.optJSONObject("flintIntegration")
        var available = capability?.let { !it.has("supportEnabled") || it.optBoolean("supportEnabled") } ?: true
        val warning = s.label("", 13f, color = s.muted); s.add(p.body, warning)
        val field = s.field("Опишите проблему").apply {
            setSingleLine(false); minLines = 4; gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(api.draft("supportDraft")?.optJSONObject("body")?.string("text")
                ?: api.draft("supportInput")?.string("text").orEmpty())
        }
        s.add(p.body, field, 140)
        val tickets = s.column()
        val send = s.primary("Отправить в поддержку") {}
        val refresh = s.button("Обновить ответы") {}
        fun unavailable() {
            available = false; send.isEnabled = false; refresh.isEnabled = false
            warning.text = "Доставка обращений ещё не подключена. Администратор сможет включить её через API и админку."
        }
        fun drawTickets(items: List<JSONObject>) {
            tickets.removeAllViews()
            items.forEach { ticket ->
                val card = s.column().apply { background = s.shape(0xFF102635.toInt()); setPadding(s.dp(14), s.dp(12), s.dp(14), s.dp(12)) }
                s.add(card, s.label("Обращение " + ticket.string("id").take(10), 14f, true, s.mint), gap = 0)
                objects(ticket, "messages").forEach { message ->
                    val support = message.string("author") == "support"
                    s.add(card, s.label((if (support) "Поддержка: " else "Вы: ") + message.string("text"), 14f,
                        color = if (support) s.mint else s.ink))
                }
                s.add(tickets, card)
            }
        }
        suspend fun reload() {
            try { drawTickets(objects(api.request("GET", "/support/tickets").data, "items")) }
            catch (e: ApiError) { if (e.status in setOf(404,405,501)) unavailable() else throw e }
        }
        s.add(p.body, send, 48); s.add(p.body, refresh, 48); s.add(p.body, tickets)
        send.setOnClickListener {
            if (p.busy || !available) return@setOnClickListener
            val text = field.text.toString().trim()
            if (text.isEmpty()) { p.error("Опишите проблему"); return@setOnClickListener }
            val pending = api.draft("supportDraft")
            if (pending != null && pending.optJSONObject("body")?.string("text") != text) {
                field.setText(pending.optJSONObject("body")?.string("text"))
                p.error("Повторите отправку сохранённого сообщения, чтобы проверить его доставку."); return@setOnClickListener
            }
            task(p) {
                field.isEnabled = false; send.isEnabled = false
                try {
                    val draft = pending ?: JSONObject().put("key", UUID.randomUUID().toString()).put("body",
                        JSONObject().put("text", text).put("platform", if (BuildConfig.IS_TV) "android-tv" else "android"))
                    api.saveDraft("supportDraft", draft)
                    api.request("POST", "/support/tickets", draft.getJSONObject("body"), idempotencyKey = draft.string("key"))
                    api.saveDraft("supportDraft", null); api.saveDraft("supportInput", null); field.text.clear()
                    p.message.text = "Обращение сохранено. Ответ появится здесь."; reload()
                } finally { field.isEnabled = true; send.isEnabled = available }
            }
        }
        refresh.setOnClickListener { task(p) { reload() } }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(t: CharSequence?, st: Int, b: Int, c: Int) {
                api.saveDraft("supportInput", t?.takeIf { it.isNotBlank() }?.let { JSONObject().put("text", it.toString()) })
            }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        if (available) task(p) { reload() } else unavailable()
    }
    private fun confirm(title: String, message: String, action: () -> Unit) {
        val p = panel(title); s.add(p.body, s.label(message, color = s.muted))
        s.add(p.body, s.button("Подтвердить") { p.dialog.dismiss(); action() }, 48)
        s.add(p.body, s.button("Отмена") { p.dialog.dismiss() }, 48)
    }
    private fun addQr(p: FlintStyle.Panel, value: String) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 3, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565)
        val pixels = IntArray(640 * 640) { i -> if (matrix[i % 640, i / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 640)
        s.add(p.body, ImageView(activity).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "QR-код для добавления устройства" }, 280)
    }
    private fun openTelegram(url: String, p: FlintStyle.Panel) {
        val u = Uri.parse(url); val bot = u.pathSegments.firstOrNull().orEmpty()
        if (u.host != "t.me" || !bot.matches(Regex("[A-Za-z0-9_]{4,64}"))) { openLink(url, p); return }
        val deep = Uri.Builder().scheme("tg").authority("resolve").appendQueryParameter("domain", bot)
        u.getQueryParameter("start")?.let { deep.appendQueryParameter("start", it) }
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, deep.build())) } catch (_: Exception) { openLink(url, p) }
    }
    fun openLink(url: String, p: FlintStyle.Panel) {
        val u = Uri.parse(url)
        if (u.scheme != "https" || u.host.isNullOrBlank() || u.userInfo != null) { p.error("Сервер не предоставил безопасную ссылку."); return }
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { p.error("Не удалось открыть браузер на этом устройстве.") }
    }
    private fun copy(text: String) { activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flint", text)) }
    companion object {
        fun epoch(text: String): Long = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0)
        fun date(text: String): String = runCatching { DateTimeFormatter.ofPattern("dd MMMM yyyy HH:mm", Locale.forLanguageTag("ru-RU")).withZone(ZoneId.systemDefault()).format(Instant.parse(text)) }.getOrDefault("")
        fun subTitle(sub: JSONObject) = sub.optJSONObject("plan")?.string("name")?.ifBlank { null } ?: "Подписка Flint"
        fun traffic(sub: JSONObject): String {
            val t = sub.optJSONObject("traffic") ?: return "Нет данных"
            fun gb(v: Double) = String.format(Locale.forLanguageTag("ru-RU"), "%.1f GB", v / 1_000_000_000.0)
            val used = if (t.string("updatedAt").isNotBlank()) gb(t.optDouble("usedBytes", 0.0)) else "—"
            return "$used / ${if (t.isNull("limitBytes")) "∞" else gb(t.optDouble("limitBytes"))}"
        }
        fun fraction(sub: JSONObject): Float = sub.optJSONObject("traffic")?.let {
            if (it.string("updatedAt").isNotBlank() && it.optDouble("limitBytes", 0.0) > 0) (it.optDouble("usedBytes", 0.0) / it.optDouble("limitBytes")).toFloat().coerceIn(0f, 1f) else 0f
        } ?: 0f
        fun expiry(sub: JSONObject) = (if (sub.string("status") == "expired") "Истекла: " else "Активна до: ") + date(sub.string("expiresAt"))
        fun planDetails(plan: JSONObject): String {
            val parts = mutableListOf<String>()
            if (plan.optBoolean("isPersonal")) parts.add("Для вас")
            plan.optJSONObject("traffic")?.let { traffic ->
                if (traffic.has("limitBytes")) parts.add(if (traffic.isNull("limitBytes")) "Безлимит" else String.format(Locale.forLanguageTag("ru-RU"), "%.0f GB", traffic.optDouble("limitBytes") / 1_000_000_000))
            }
            if (plan.string("availableUntil").isNotBlank()) parts.add("До " + date(plan.string("availableUntil")))
            return parts.joinToString(" · ")
        }
        fun benefit(plan: JSONObject, all: List<JSONObject>): String {
            val days = plan.optInt("durationDays"); val price = plan.optJSONObject("price") ?: return ""
            val amount = price.optDouble("amount", 0.0); val currency = price.string("currency")
            var percent = if (!plan.isNull("savingsPercent")) plan.optInt("savingsPercent") else 0
            if (plan.isNull("savingsPercent") && plan.isNull("savings")) {
                val base = all.filter { it.optInt("durationDays") > 0 && it.optJSONObject("price")?.string("currency") == currency }.minByOrNull { it.optInt("durationDays") }
                if (base != null && days > base.optInt("durationDays")) {
                    val perDay = base.getJSONObject("price").optDouble("amount") / base.optInt("durationDays")
                    if (perDay > 0) percent = kotlin.math.floor(100 * (1 - amount / days / perDay)).toInt()
                }
            }
            val money = plan.optJSONObject("savings")
            val saving = if (percent in 1..100) "Выгода $percent% · "
                else if (plan.isNull("savingsPercent") && money?.string("currency") == currency && money.optDouble("amount", 0.0) > 0) "Выгода ${money.string("amount")} $currency · " else ""
            return saving + if (days > 0) "${(amount * 30 / days).toInt()} $currency / 30 дней" else ""
        }
    }
}
