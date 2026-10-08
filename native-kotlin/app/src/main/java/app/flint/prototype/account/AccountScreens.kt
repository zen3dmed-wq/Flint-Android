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
    private fun panel(title: String, wide: Boolean = false) = s.panel(title, wide).also { p ->
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
        val p = panel("Аккаунт Flint")
        s.add(p.body, s.label("Один аккаунт на всех устройствах", color = s.muted))
        val email = s.field("Электронная почта").apply { inputType = 33 }
        val password = s.field("Пароль", true)
        val referral = s.field("Код приглашения — необязательно")
        s.add(p.body, email, 50); s.add(p.body, password, 50)
        var register = false
        referral.visibility = View.GONE; s.add(p.body, referral, 50)
        val submit = s.button("Войти") {
            if (email.text.isBlank() || password.text.isBlank()) { p.error("Укажите почту и пароль."); return@button }
            task(p) {
                api.login(email.text.toString(), password.text.toString(), register, referral.text.toString())
                password.text.clear(); changed(true); p.dialog.dismiss(); after()
            }
        }
        s.add(p.body, submit, 48)
        s.add(p.body, s.button("Войти через Telegram") { p.dialog.dismiss(); telegram(false, after) }, 48)
        s.add(p.body, s.button("Регистрация / вход по почте") {
            register = !register; submit.text = if (register) "Зарегистрироваться" else "Войти"
            referral.visibility = if (register) View.VISIBLE else View.GONE
        }, 48)
        val links = api.config.optJSONObject("links") ?: JSONObject()
        listOf("userAgreement" to "Условия использования", "privacyPolicy" to "Политика конфиденциальности").forEach { (key, title) ->
            if (links.string(key).isNotBlank()) s.add(p.body, s.button(title) { openLink(links.string(key), p) }, 44)
        }
    }
    fun identity() {
        if (!requireAccount { identity() }) return
        val p = panel("Аккаунт Flint")
        task(p) { api.refresh(); changed(false); drawIdentity(p) }
    }
    private fun drawIdentity(p: FlintStyle.Panel) {
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
                password.text.clear(); api.refresh(); changed(false); drawIdentity(p)
            } }, 48)
        }
        if (telegram == null || telegram.length() == 0) s.add(p.body, s.button("Привязать Telegram") { p.dialog.dismiss(); telegram(true) { identity() } }, 48)
        if (api.me.optBoolean("trialAvailable")) s.add(p.body, s.button("Попробовать бесплатно") { task(p) {
            api.request("POST", "/subscriptions/trial", JSONObject()); api.refresh(); changed(true); p.dialog.dismiss()
        } }, 48)
        s.add(p.body, s.button("Выйти из аккаунта") { confirm("Выйти из Flint?", "Сохранённые вручную серверы останутся.") {
            task(p) { try { api.logout() } finally { changed(true); p.dialog.dismiss() } }
        } }, 48)
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
        fun draw() {
            p.body.removeAllViews()
            api.subscriptions.forEach { sub ->
                val id = sub.string("id")
                val active = sub.string("status").equals("active", true) &&
                    (sub.string("expiresAt").isBlank() || epoch(sub.string("expiresAt")) > System.currentTimeMillis()) &&
                    sub.optJSONObject("traffic")?.optBoolean("limitReached") != true
                val card = s.column().apply {
                    background = s.surface(if (id == api.selectedId) 0xFF174A3F.toInt() else s.card)
                    setPadding(s.dp(14), s.dp(12), s.dp(14), s.dp(12))
                    isFocusable = true; isFocusableInTouchMode = BuildConfig.IS_TV
                    isEnabled = active; alpha = if (active) 1f else .55f
                    contentDescription = subTitle(sub) + if (id == api.selectedId) ", выбрана" else ""
                    setOnClickListener { if (active) task(p) { api.selectedId = id; changed(true); p.dialog.dismiss() } }
                }
                s.add(card, s.label((if (id == api.selectedId) "✓  " else "") + subTitle(sub), 17f, true), gap = 0)
                val bar = FrameLayout(activity).apply { background = s.shape(0xFF77818E.toInt(), android.graphics.Color.TRANSPARENT, 8); clipToOutline = true }
                val fill = View(activity).apply { background = s.shape(0xFF008CFF.toInt(), android.graphics.Color.TRANSPARENT, 8) }
                bar.addView(fill, FrameLayout.LayoutParams(0, -1))
                bar.addView(s.label(traffic(sub), 12f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                bar.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                    val width = ((right - left) * fraction(sub)).toInt()
                    if (fill.layoutParams.width != width) fill.layoutParams = FrameLayout.LayoutParams(width, -1)
                }
                s.add(card, bar, 22); s.add(card, s.label(expiry(sub), 12f, color = s.muted))
                if (!active) s.add(card, s.label("Подписка неактивна", 12f, color = s.muted), gap = 6)
                s.add(p.body, card)
            }
            if (api.subscriptions.isEmpty()) s.add(p.body, s.label("У вас пока нет подписок", color = s.muted))
            s.add(p.body, s.button("Купить / продлить подписку") { p.dialog.dismiss(); purchase() }, 48)
            s.add(p.body, s.button("Обновить список") { task(p) { api.refresh(); changed(false); draw() } }, 46)
        }
        draw(); task(p) { api.refresh(); changed(false); draw() }
    }
    fun purchase() {
        if (!requireAccount { purchase() }) return
        val p = panel("Купить / продлить подписку", true)
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
            val planButtons = mutableListOf<Pair<String, Button>>()
            plans.chunked(2).forEach { pair ->
                val row = s.row()
                pair.forEachIndexed { index, plan ->
                    val price = plan.optJSONObject("price") ?: JSONObject()
                    val available = plan.string("availableUntil").isBlank() || epoch(plan.string("availableUntil")) > System.currentTimeMillis()
                    val button = s.button("${plan.string("name")}\n${price.optDouble("amount", 0.0)} ${price.string("currency")}\n${plan.optInt("durationDays")} дней\n${benefit(plan, plans)}") {
                        planId = plan.string("id"); planButtons.forEach { (id, b) -> b.background = s.surface(if (id == planId) 0xFF174A3F.toInt() else s.card) }
                    }
                    button.isEnabled = available; button.textSize = 14f
                    row.addView(button, LinearLayout.LayoutParams(0, s.dp(134), 1f).apply { if (index == 0 && pair.size > 1) marginEnd = s.dp(10) })
                    planButtons.add(plan.string("id") to button)
                }
                s.add(p.body, row)
            }
            planButtons.firstOrNull { it.second.isEnabled }?.second?.performClick()
            s.add(p.body, s.label("Способ оплаты", 12f, color = s.muted))
            methods.forEach { method ->
                val b = RadioButton(activity).apply { text = method.string("title"); setTextColor(s.ink); buttonTintList = android.content.res.ColorStateList.valueOf(s.mint); isChecked = method.string("id") == provider }
                b.setOnClickListener {
                    provider = method.string("id")
                    for (i in 0 until p.body.childCount) (p.body.getChildAt(i) as? RadioButton)?.isChecked = p.body.getChildAt(i) === b
                }
                s.add(p.body, b, 44)
            }
            s.add(p.body, s.button("Перейти к оплате") {
                if (planId.isBlank()) { p.error("Выберите доступный тариф."); return@button }
                val draft = JSONObject().put("key", UUID.randomUUID().toString()).put("body", JSONObject().put("planId", planId).put("provider", provider))
                api.saveDraft("orderDraft", draft); order(p, draft)
            }, 48)
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
            val referralUrl = listOf(data.string("inviteUrl"), data.string("referralUrl"), data.string("link")).firstOrNull {
                val u = Uri.parse(it); u.scheme == "https" && u.host !in setOf("t.me", "telegram.me")
            }
            s.add(p.body, s.label("Ваш код: $code", 22f, true, s.mint))
            s.add(p.body, s.label("Приглашено друзей: ${data.optInt("invitedCount")}\nБонусных дней: ${data.optInt("bonusDays")}"))
            s.add(p.body, s.label("Друг может зарегистрироваться по почте и указать этот код. Telegram необязателен.", color = s.muted))
            val terms = data.optJSONObject("terms")
            if (terms != null) s.add(p.body, s.label("Бонус ${terms.optDouble("bonusPercent")}% при покупке от ${terms.optInt("minPurchaseDays")} дней. Максимум ${terms.optInt("maxBonusDays")} бонусных дней.", 12f, color = s.muted))
            val invitation = referralUrl ?: "Приглашаю во Flint! При регистрации по почте укажите код $code."
            s.add(p.body, s.button(if (referralUrl != null) "Скопировать ссылку" else "Скопировать приглашение") { copy(invitation); p.message.text = "Скопировано" }, 48)
            s.add(p.body, s.button("Поделиться") { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invitation), "Пригласить друга")) }, 48)
            if (data.isNull("referrer")) {
                val field = s.field("Код пригласившего друга"); s.add(p.body, field, 50)
                s.add(p.body, s.button("Применить код") { task(p) {
                    api.request("POST", "/referrals/apply", JSONObject().put("code", field.text.toString().trim())); p.dialog.dismiss(); friends()
                } }, 48)
            }
        }
    }
    fun devices() {
        if (!requireAccount { devices() }) return
        val p = panel("Устройства")
        task(p) {
            api.refresh(); changed(false)
            val sub = api.selected
            s.add(p.body, s.button(sub?.let(::subTitle) ?: "Выбрать подписку") { p.dialog.dismiss(); subscriptions() }, 48)
            if (sub != null) {
                val sid = sub.string("id")
                try {
                    val doc = api.request("GET", "/subscriptions/${Uri.encode(sid)}/devices").data
                    val canManage = doc.optBoolean("canManageDevices") && doc.string("ownerUserId") == api.me.string("id") && doc.string("subscriptionId") == sid
                    if (canManage) objects(doc, "items").distinctBy { it.string("id") }.forEach { device ->
                        s.add(p.body, s.label(device.string("name").ifBlank { device.string("model") }, 16f, true))
                        if (!device.optBoolean("revoked")) s.add(p.body, s.button("Отключить доступ") {
                            confirm("Отключить устройство?", "Это действие отзовёт его VPN-доступ.") { task(p) {
                                api.request("DELETE", "/subscriptions/${Uri.encode(sid)}/devices/${Uri.encode(device.string("id"))}"); p.dialog.dismiss(); devices()
                            } }
                        }, 44)
                    } else s.add(p.body, s.label("Управление VPN-устройствами доступно владельцу подписки.", color = s.muted))
                } catch (e: ApiError) {
                    if (e.status !in setOf(404, 405, 501)) throw e
                    s.add(p.body, s.label("Отдельное отключение VPN-устройств пока не поддерживается вашим сервером. Ниже можно завершить вход в аккаунт.", color = s.muted))
                }
                s.add(p.body, s.button("Добавить устройство по QR") { shareSubscription(sub) }, 50)
            }
            s.add(p.body, s.label("Сеансы входа в аккаунт", 18f, true))
            val sessions = objects(api.request("GET", "/me/sessions").data, "items")
            // Collapse presentation only. Same model/OS is not proof of the same physical device.
            sessions.groupBy { it.string("model") + "|" + it.string("platform") }.values.forEach { group ->
                val first = group.firstOrNull { it.optBoolean("isCurrent") } ?: group.first()
                s.add(p.body, s.button(first.string("model").ifBlank { first.string("platform") } +
                    (if (group.any { it.optBoolean("isCurrent") }) " · это устройство" else "") + "\nСеансов: ${group.size}") { sessionDetails(group) }, 68)
            }
            s.add(p.body, s.button("Обновить список") { p.dialog.dismiss(); devices() }, 48)
        }
    }
    private fun sessionDetails(rows: List<JSONObject>) {
        val p = panel("Сеансы входа")
        rows.forEach { row ->
            s.add(p.body, s.label("${row.string("model")} · Flint ${row.string("appVersion")}\nВход / обновление: ${date(row.string("lastActiveAt"))}"))
            if (row.optBoolean("isCurrent")) s.add(p.body, s.label("Текущий сеанс", color = s.mint))
            else s.add(p.body, s.button("Завершить сеанс") { confirm("Завершить вход?", "На устройстве потребуется снова войти в аккаунт. Это не отзыв общего ключа VPN.") { task(p) {
                val fresh = objects(api.request("GET", "/me/sessions").data, "items").find { it.string("id") == row.string("id") }
                check(fresh != null && !fresh.optBoolean("isCurrent"))
                api.request("DELETE", "/me/sessions/${Uri.encode(row.string("id"))}"); p.dialog.dismiss()
            } } }, 46)
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
        task(p) {
            try {
                val tickets = objects(api.request("GET", "/support/tickets").data, "items")
                tickets.forEach { ticket -> s.add(p.body, s.button(ticket.string("subject").ifBlank { "Обращение" }) { ticket(ticket.string("id")) }, 48) }
                val field = s.field("Опишите проблему").apply { setSingleLine(false); minLines = 4 }
                s.add(p.body, field, 120)
                s.add(p.body, s.button("Отправить") { task(p) {
                    var draft = api.draft("supportDraft")
                    if (draft == null) { require(field.text.isNotBlank()); draft = JSONObject().put("key", UUID.randomUUID().toString()).put("body", JSONObject().put("message", field.text.toString())); api.saveDraft("supportDraft", draft) }
                    api.request("POST", "/support/tickets", draft.getJSONObject("body"), idempotencyKey = draft.string("key"))
                    api.saveDraft("supportDraft", null); p.dialog.dismiss(); support()
                } }, 48)
            } catch (e: ApiError) {
                if (e.status !in setOf(404, 405, 501)) throw e
                val link = api.config.optJSONObject("links")?.string("support").orEmpty()
                s.add(p.body, s.label("Обращения внутри приложения пока недоступны. Свяжитесь с поддержкой Flint.", color = s.muted))
                if (link.isNotBlank()) s.add(p.body, s.button("Написать в поддержку") { if (link.startsWith("https://t.me/")) openTelegram(link, p) else openLink(link, p) }, 48)
            }
        }
    }
    private fun ticket(id: String) {
        val p = panel("Обращение")
        task(p) {
            val data = api.request("GET", "/support/tickets/${Uri.encode(id)}").data
            s.add(p.body, s.label(data.string("subject"), 18f, true))
            objects(data, "messages").forEach { s.add(p.body, s.label(it.string("text").ifBlank { it.string("message") })) }
        }
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
            return (if (percent in 1..100) "Выгода $percent% · " else "") + if (days > 0) "${(amount * 30 / days).toInt()} $currency / 30 дней" else ""
        }
    }
}
