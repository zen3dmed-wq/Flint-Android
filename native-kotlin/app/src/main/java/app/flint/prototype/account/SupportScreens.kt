package app.flint.prototype.account

import android.app.Activity
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.*
import app.flint.prototype.ui.FlintStyle
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/** First-party ticket chat. Polling and read receipts stop when the app is hidden. */
class SupportScreens(private val activity: Activity, private val api: FlintAccount,
                     private val parent: CoroutineScope, private val unreadChanged: () -> Unit) {
    private val s = FlintStyle(activity)
    private var session: Session? = null
    private var foreground = true
    private var retryAt = 0L
    fun close() { session?.p?.dialog?.dismiss(); session = null }
    fun setForeground(value: Boolean) { foreground = value; session?.poll() }
    fun show() { close(); session = Session().also { it.load() } }
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private inner class Session {
        val p = s.panel("Поддержка", maxWidth = 560)
        private val job = SupervisorJob(parent.coroutineContext[Job])
        private val scope = CoroutineScope(parent.coroutineContext + job)
        private val children = mutableListOf<FlintStyle.Panel>()
        private fun child(title: String): FlintStyle.Panel = s.panel(title, maxWidth = 560).also { item ->
            children.add(item); item.dialog.setOnDismissListener { children.remove(item) }
        }
        private var pollJob: Job? = null
        private var ticket: JSONObject? = null
        private val messages = linkedMapOf<String, JSONObject>()
        private var categories = emptyList<JSONObject>()
        private var category = ""
        private var list = emptyList<JSONObject>()
        private var readId = ""
        private var readInFlight = false
        private var listLoaded = false
        private var messageLimit = false
        private var listing = true
        private var revision = 0
        private var loading = false
        private val info = s.label("Напишите нам — ответ появится в этом чате.", color = s.muted)
        private val limits = s.label("До 3 открытых обращений · до 5 новых в час", 11f, color = s.muted)
        private val history = s.column()
        private val ticketList = s.column()
        private val create = s.primary("Создать обращение") { newTicket() }
        private val back = s.button("Все обращения") { launch { refreshList(); showList() } }
        private val categoryButton = s.button("Загрузка тем…") { chooseCategory() }
        private val input = s.field("Опишите проблему").apply {
            setSingleLine(false); minLines = 2; maxLines = 4; gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(4000))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        private val send = s.primary("Отправить в поддержку") { send() }
        private val finish = s.button("Закрыть обращение") { closeTicket() }
        private val rate = s.button("Оценить работу поддержки") { rating() }

        init {
            s.add(p.body, create, 48); s.add(p.body, back, 44); s.add(p.body, ticketList)
            s.add(p.body, info); s.add(p.body, limits); s.add(p.body, categoryButton, 44)
            s.add(p.body, history)
            s.add(p.footer, input, 94); s.add(p.footer, send, 48)
            s.buttons(p.body, finish, rate)
            p.dialog.setOnDismissListener { children.toList().forEach { it.dialog.dismiss() }; pollJob?.cancel(); job.cancel(); if (session === this) session = null }
            val draft = api.draft("supportDraft")
            // The former integration's {text,platform} payload is not the v1 ticket contract.
            if (draft != null && draft.string("kind").isBlank()) api.saveDraft("supportDraft", null)
            input.setText(draft?.optJSONObject("body")?.string("text") ?: api.draft("supportInput")?.string("text").orEmpty())
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(t: CharSequence?, start: Int, before: Int, count: Int) {
                    api.saveDraft("supportInput", JSONObject().put("ticketId", ticket?.string("id").orEmpty()).put("text", t?.toString().orEmpty()))
                }
                override fun afterTextChanged(e: android.text.Editable?) {}
            })
            controls()
        }
        private fun controls() {
            val closed = ticket?.string("status") == "closed"
            create.visibility = if (listing) View.VISIBLE else View.GONE
            create.isEnabled = !loading && listLoaded
            back.visibility = if (listing) View.GONE else View.VISIBLE
            ticketList.visibility = if (listing) View.VISIBLE else View.GONE
            info.visibility = if (listing) View.GONE else View.VISIBLE
            history.visibility = if (listing) View.GONE else View.VISIBLE
            categoryButton.visibility = if (!listing && ticket == null) View.VISIBLE else View.GONE
            categoryButton.text = "Тема: " + (categories.find { it.string("id") == category }?.string("title") ?: "загрузка…")
            categoryButton.isEnabled = categories.isNotEmpty() && !loading
            input.visibility = if (listing || closed) View.GONE else View.VISIBLE
            send.visibility = if (listing || closed) View.GONE else View.VISIBLE
            val exhausted = messageLimit || messages.values.count { it.string("author") == "user" } >= 300
            send.isEnabled = !loading && (ticket != null || (category.isNotBlank() && listLoaded)) && (!exhausted || api.draft("supportDraft") != null)
            limits.text = if (exhausted) "В этом чате уже 300 ваших сообщений. Закройте его и создайте новое обращение."
                else "Открыто обращений: ${list.count { it.string("status") != "closed" }} из 3 · до 5 новых в час"
            input.isEnabled = !loading
            finish.visibility = if (!listing && ticket != null && !closed) View.VISIBLE else View.GONE
            rate.visibility = if (!listing && ticket?.optBoolean("canRate") == true) View.VISIBLE else View.GONE
            finish.isEnabled = !loading; rate.isEnabled = !loading
        }
        private fun launch(action: suspend () -> Unit) {
            if (loading) return
            if (System.currentTimeMillis() < retryAt) { p.error("Слишком много запросов. Повторите через ${(retryAt-System.currentTimeMillis())/1000+1} сек."); return }
            pollJob?.cancel(); pollJob = null
            loading = true; p.message.text = "Загрузка…"; controls()
            scope.launch {
                try { action(); if (p.message.text == "Загрузка…") p.message.text = "" }
                catch (e: CancellationException) { throw e }
                catch (e: ApiError) {
                    p.error(e.message.orEmpty())
                    if (e.status in setOf(400,409,422)) api.saveDraft("supportDraft", null)
                    if (e.status == 429) retryAt = System.currentTimeMillis() + (e.retryAfterSeconds ?: 60) * 1000L
                    if (e.code == "too_many_open_tickets") { runCatching { refreshList() }; showList() }
                    if (e.code == "ticket_message_limit") messageLimit = true
                    if (e.code in setOf("ticket_closed", "ticket_message_limit", "ticket_already_rated", "ticket_not_closed")) {
                        api.saveDraft("supportDraft", null)
                        runCatching { refreshTicket(false) }
                    }
                } catch (_: Exception) { p.error("Не удалось связаться с поддержкой. Сообщение сохранено — повторите отправку.") }
                finally { loading = false; controls(); poll() }
            }
        }
        fun load() = launch {
            categories = objects(api.request("GET", "/support/categories").data, "items")
            category = (categories.find { it.string("id") == "other" } ?: categories.firstOrNull())?.string("id").orEmpty()
            refreshList()
            showList()
            if (categories.isEmpty() && ticket == null) p.error("Сервис пока не предлагает темы обращений.")
        }
        private suspend fun refreshList() { list = api.refreshSupportTickets(); listLoaded = true; unreadChanged(); if (listing) renderList(); controls() }
        private fun creationLimit(): String? {
            if (list.count { it.string("status") != "closed" } >= 3) return "У вас уже 3 открытых обращения. Продолжите существующий чат или закройте решённый вопрос."
            val cutoff = System.currentTimeMillis() - 3_600_000L
            if (list.count { runCatching { java.time.Instant.parse(it.string("createdAt")).toEpochMilli() > cutoff }.getOrDefault(false) } >= 5)
                return "Можно создать не больше 5 обращений в час. Продолжите существующий чат или попробуйте позже."
            return null
        }
        private fun chooseCategory() {
            val picker = child("Тема обращения")
            categories.forEach { item -> s.add(picker.body, s.button(item.string("title")) {
                category = item.string("id"); controls(); picker.dialog.dismiss()
            }, 46) }
        }
        private fun showList() {
            listing = true; renderList(); controls(); poll()
        }
        private fun renderList() {
                ticketList.removeAllViews()
                if (list.isEmpty()) s.add(ticketList, s.label("Обращений пока нет. Нажмите «Создать обращение», чтобы написать нам.", color = s.muted))
                list.forEach { item ->
                    val unread = item.optInt("unreadCount").takeIf { it > 0 }?.let { " · новых: $it" }.orEmpty()
                    s.add(ticketList, s.button("№${item.string("id")} · ${status(item)}$unread\n${item.string("subject")}") {
                        launch { open(item.string("id")) }
                    }, 76)
                }
                s.add(ticketList, s.button("Обновить список") { launch { refreshList() } }, 46)
        }
        private fun newTicket() {
            if (loading) return
            val pending = api.draft("supportDraft")
            if (pending != null && pending.string("ticketId").isEmpty()) {
                listing = false; ticket = null; input.setText(pending.optJSONObject("body")?.string("text")); controls(); return
            }
            creationLimit()?.let { p.error(it); showList(); return }
            if (api.draft("supportDraft") != null) { p.error("Сначала повторите отправку сохранённого сообщения, чтобы проверить доставку."); return }
            listing = false; revision++; ticket = null; messageLimit = false; messages.clear(); readId = ""; history.removeAllViews()
            input.text.clear(); info.text = "Новое обращение"; p.message.text = ""; controls(); poll()
        }
        private suspend fun open(id: String) {
            if (api.draft("supportDraft")?.string("ticketId")?.let { it != id } == true) {
                p.error("Повторите отправку сохранённого сообщения перед сменой обращения."); return
            }
            val value = api.request("GET", "/support/tickets/${encoded(id)}").data
            listing = false; revision++; messageLimit = false; messages.clear(); readId = ""; ticket = value
            objects(value, "messages").forEach { messages[it.string("id")] = it }
            val saved = api.draft("supportInput")
            input.setText(saved?.takeIf { it.string("ticketId") == id }?.string("text").orEmpty())
            draw(); markRead(); poll()
        }
        private suspend fun refreshTicket(incremental: Boolean) {
            val id = ticket?.string("id") ?: return
            val generation = revision
            val cursor = messages.keys.lastOrNull()
            val query = if (incremental && cursor != null) "?afterMessageId=${encoded(cursor)}" else ""
            val value = api.request("GET", "/support/tickets/${encoded(id)}$query").data
            if (generation != revision || ticket?.string("id") != id) return
            val changed = !incremental || objects(value,"messages").isNotEmpty() ||
                value.string("status") != ticket?.string("status") || value.optBoolean("canRate") != ticket?.optBoolean("canRate")
            ticket = value
            if (!incremental) messages.clear()
            objects(value, "messages").forEach { messages[it.string("id")] = it }
            if (changed) draw()
            markRead()
        }
        private fun draw() {
            val current = ticket ?: return
            info.text = "Обращение №${current.string("id")} · ${status(current)}\n${current.string("subject")}" +
                if (current.string("closedBy") == "system") "\nЗакрыто автоматически" else ""
            history.removeAllViews()
            messages.values.forEach { message ->
                val author = if (message.string("author") == "user") "Вы" else message.string("authorName").ifBlank { "Поддержка" }
                val card = s.column().apply { background = s.shape(if (message.string("author") == "support") 0xFF173A36.toInt() else s.card); setPadding(s.dp(12),s.dp(10),s.dp(12),s.dp(10)) }
                s.add(card, s.label(author + " · " + AccountScreens.date(message.string("createdAt")), 11f, color = s.muted), gap = 0)
                s.add(card, s.label(message.string("text"), 14f))
                s.add(history, card)
            }
            controls()
        }
        private fun markRead() {
            if (listing || !foreground || !p.dialog.isShowing || readInFlight || System.currentTimeMillis() < retryAt) return
            val id = ticket?.string("id") ?: return
            val cursor = messages.keys.lastOrNull() ?: return
            if (cursor == readId) return
            val generation = revision
            p.body.post {
                if (foreground && p.dialog.isShowing && generation == revision) scope.launch {
                    if (readInFlight) return@launch
                    readInFlight = true
                    try {
                        api.request("POST", "/support/tickets/${encoded(id)}/read", JSONObject().put("upToMessageId", cursor))
                        if (generation == revision) readId = cursor
                        refreshList()
                    } catch (_: ApiError) { /* Retry after the next displayed update. */ }
                    finally { readInFlight = false }
                }
            }
        }
        fun poll() {
            pollJob?.cancel(); pollJob = null
            if (listing || !foreground || !p.dialog.isShowing || ticket == null || ticket?.string("status") == "closed") return
            pollJob = scope.launch {
                while (isActive && foreground && ticket?.string("status") != "closed") {
                    delay(maxOf(12_000L, retryAt - System.currentTimeMillis()))
                    if (loading) continue
                    try { refreshTicket(true) }
                    catch (e: ApiError) {
                        p.error(e.message.orEmpty())
                        if (e.status == 429) retryAt = System.currentTimeMillis() + (e.retryAfterSeconds ?: 60)*1000L
                        if (e.status in setOf(401,403,404)) break
                    }
                }
            }
        }
        private fun send() {
            val text = input.text.toString().trim()
            if (text.isBlank()) { p.error("Опишите проблему"); return }
            val id = ticket?.string("id").orEmpty()
            val pending = api.draft("supportDraft")
            if (pending != null && (pending.string("ticketId") != id || pending.optJSONObject("body")?.string("text") != text)) {
                input.setText(pending.optJSONObject("body")?.string("text")); p.error("Повторите отправку сохранённого сообщения без изменений."); return
            }
            launch {
                // A retry may recover an already-created ticket: never block its saved key locally.
                if (id.isEmpty() && pending == null) {
                    refreshList()
                    creationLimit()?.let { p.error(it); showList(); return@launch }
                }
                if (id.isNotEmpty() && pending == null && (messageLimit || messages.values.count { it.string("author") == "user" } >= 300)) {
                    p.error("Достигнут лимит 300 сообщений. Закройте этот чат и создайте новый."); return@launch
                }
                val body = JSONObject().put("text", text)
                if (id.isEmpty()) body.put("category", category).put("subject", text.lineSequence().first().take(100))
                val draft = pending ?: JSONObject().put("kind", if (id.isEmpty()) "create" else "message").put("ticketId", id)
                    .put("key", UUID.randomUUID().toString()).put("body", body)
                api.saveDraft("supportDraft", draft)
                val path = if (id.isEmpty()) "/support/tickets" else "/support/tickets/${encoded(id)}/messages"
                val reply = api.request("POST", path, draft.getJSONObject("body"), idempotencyKey = draft.string("key")).data
                api.saveDraft("supportDraft", null); api.saveDraft("supportInput", null); input.text.clear()
                if (id.isEmpty()) { ticket = reply; revision++; messages.clear(); readId = ""; objects(reply, "messages").forEach { messages[it.string("id")] = it }; draw(); markRead() }
                else { if (reply.string("id").isNotBlank()) messages[reply.string("id")] = reply; refreshTicket(true) }
                p.message.text = "Сообщение отправлено"; refreshList()
            }
        }
        private fun closeTicket() {
            val id = ticket?.string("id") ?: return
            if (api.draft("supportDraft") != null) { p.error("Сначала проверьте доставку сохранённого сообщения."); return }
            val confirm = child("Закрыть обращение?")
            s.add(confirm.body, s.label("С новым вопросом можно будет создать новое обращение.", color = s.muted))
            s.add(confirm.body, s.button("Закрыть обращение") { confirm.dialog.dismiss(); launch {
                ticket = api.request("POST", "/support/tickets/${encoded(id)}/close", JSONObject()).data
                draw(); refreshList()
            } }, 46)
        }
        private fun rating() {
            val id = ticket?.string("id") ?: return
            val form = child("Оцените работу поддержки")
            val resolved = CheckBox(activity).apply { text = "Вопрос решён"; isChecked = true; setTextColor(s.ink) }
            val stars = s.row(); var score = 5
            val choices = (1..5).map { n -> s.button(n.toString()) { score = n; stars.childrenEnabled(n) } }
            choices.forEach { stars.addView(it, LinearLayout.LayoutParams(0,s.dp(46),1f)) }; stars.childrenEnabled(5)
            val comment = s.field("Комментарий (необязательно)").apply { filters = arrayOf(InputFilter.LengthFilter(1000)) }
            s.add(form.body,resolved); s.add(form.body,stars,46); s.add(form.body,comment,48)
            s.add(form.body,s.primary("Отправить оценку") { form.dialog.dismiss(); launch {
                ticket = api.request("POST", "/support/tickets/${encoded(id)}/rating", JSONObject().put("resolved",resolved.isChecked).put("score",score).put("comment",comment.text.toString().trim())).data
                draw(); p.message.text = "Спасибо за оценку"; refreshList()
            } },48)
        }
        private fun LinearLayout.childrenEnabled(selected: Int) { for (i in 0 until childCount) getChildAt(i).alpha = if (i+1 == selected) 1f else .5f }
        private fun status(item: JSONObject) = when(item.string("status")) {
            "new" -> "Новое"; "in_progress" -> "В работе"; "waiting_for_user" -> "Есть ответ"
            "waiting_for_support" -> "Ждём ответа поддержки"; "closed" -> "Закрыто"; else -> "Обращение"
        }
    }
}
