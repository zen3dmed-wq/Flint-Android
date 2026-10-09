package app.flint.prototype.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import kotlin.math.min

/** Geometry and navigation follow flint/PageHome.qml in the working Qt client. */
class FlintHomeView(context: Context, private val isTv: Boolean, private val callbacks: FlintUiCallbacks) : FrameLayout(context) {
    private val s = FlintStyle(context, isTv)
    private val canvas = FrameLayout(context)
    private val mascot = FlintMascotView(context)
    private val header = s.row()
    private val settings = s.button("") { callbacks.onSettings() }.apply { contentDescription = "Настройки Flint"; setCompoundDrawables(FlintIcon("settings", s.muted, s.dp(22)), null, null, null) }
    private val qr = s.button("QR-код") { showImportActions() }
    private val clipboard = s.button("Из буфера") { callbacks.onImportClipboard() }
    private val title = s.label("", 26f, true).apply { gravity = Gravity.CENTER; maxLines = 1 }
    private val subtitle = s.label("", 12f, color = s.muted).apply { gravity = Gravity.CENTER; maxLines = 2 }
    private val connect = s.button("ПОДКЛЮЧИТЬСЯ") { callbacks.onConnectToggle() }.apply { textSize = 18f }
    private val guard = s.label("", 12f, true).apply { gravity = Gravity.CENTER; background = s.shape(0xE20A1A2A.toInt()) }
    private val buy = s.button("Купить / продлить подписку") { callbacks.onPurchase() }
    private val tvPair = s.button("Добавить с помощью QR") { callbacks.onTvPair() }.apply { visibility = if (isTv) VISIBLE else GONE }
    private val subscription = FrameLayout(context).apply { background = s.surface(); isClickable = true; isFocusable = true; isFocusableInTouchMode = isTv }
    private val subscriptionTitle = s.label("", 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val change = s.label("Сменить ›", 12f, color = s.mint)
    private val traffic = FrameLayout(context).apply { background = s.shape(0xFF77818E.toInt(), Color.TRANSPARENT, 8); clipToOutline = true }
    private val filled = View(context).apply { background = s.shape(0xFF008CFF.toInt(), Color.TRANSPARENT, 8) }
    private val trafficText = s.label("", 12f).apply { gravity = Gravity.CENTER }
    private val expiry = s.label("", 11f, color = s.muted).apply { gravity = Gravity.CENTER; maxLines = 1 }
    private val server = tile("location", "Автоматически", "По доступности, задержке и загрузке") { showServers() }
    private val russian = tile("", "Сайты РФ", "Правила →") { callbacks.onRouting() }
    private val devices = tile("devices", "Устройства", "Войти в аккаунт") { callbacks.onDevices() }
    private val support = tile("", "Поддержка", "Flint готов помочь") { callbacks.onSupport() }
    private val routeToggle = Switch(context).apply {
        showText = false; isFocusable = true; isFocusableInTouchMode = isTv
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(s.mint, 0xFF647384.toInt()))
        setOnCheckedChangeListener { _, checked -> if (!syncing) callbacks.onRuDirectChanged(checked) }
    }
    private var state = FlintUiState()
    private var syncing = false
    private var safeTop = 0
    private var safeBottom = 0
    private var safeLeft = 0
    private var safeRight = 0
    private var popup: FlintStyle.Panel? = null
    private val serverRows = mutableMapOf<String, Button>()
    init {
        setBackgroundColor(s.dark)
        addView(ImageView(context).apply {
            setImageResource(resources.getIdentifier("flint_background", "drawable", context.packageName))
            scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(-1, -1))
        addView(canvas, LayoutParams(-1, -1))
        header.addView(ImageView(context).apply {
            setImageResource(resources.getIdentifier("flint_logo", "drawable", context.packageName)); scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(s.dp(40), s.dp(40)).apply { marginEnd = s.dp(10) })
        header.addView(s.column().apply {
            addView(s.label("FLINT", 22f, true).apply { letterSpacing = .06f })
            addView(s.label("Больше свободы в интернете", 11f, color = s.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(settings, LinearLayout.LayoutParams(s.dp(44), s.dp(44)))
        subscription.addView(subscriptionTitle); subscription.addView(change); subscription.addView(traffic); subscription.addView(expiry)
        traffic.addView(filled); traffic.addView(trafficText, LayoutParams(-1, -1))
        subscription.setOnClickListener { callbacks.onSubscriptions() }
        russian.addView(routeToggle)
        support.addView(ImageView(context).apply { setImageResource(resources.getIdentifier("flint_logo", "drawable", context.packageName)) }, LayoutParams(s.dp(24), s.dp(24)).apply { leftMargin = s.dp(12); topMargin = s.dp(10) })
        listOf(header, mascot, qr, clipboard, title, subtitle, connect, guard, buy, tvPair, subscription, server, russian, devices, support).forEach { canvas.addView(it) }
        subtitle.setOnClickListener { if (state.message.isNotBlank()) s.notice("Подключение", state.message) }
        setOnApplyWindowInsetsListener { _, i ->
            val safe = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safeTop = safe.top; safeBottom = safe.bottom; safeLeft = safe.left; safeRight = safe.right
            requestLayout(); i
        }
        render(state)
        if (isTv) post { qr.requestFocus() }
    }
    private fun tile(icon: String, title: String, hint: String, action: () -> Unit) = FrameLayout(context).apply {
        background = s.surface(0xDE0A1C2D.toInt(), 18); isClickable = true; isFocusable = true; isFocusableInTouchMode = isTv
        setOnClickListener { action() }; contentDescription = title
        addView(ImageView(context).apply { if (icon.isNotBlank()) setImageDrawable(FlintIcon(icon, s.muted, s.dp(26))) }, LayoutParams(s.dp(28), s.dp(28)).apply { leftMargin = s.dp(12); topMargin = s.dp(9) })
        addView(s.label(title, 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LayoutParams(-1, s.dp(24)).apply { leftMargin = s.dp(if (title == "Сайты РФ") 12 else 45); rightMargin = s.dp(8); topMargin = s.dp(9) })
        addView(s.label(hint, 11f, color = s.mint).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LayoutParams(-1, s.dp(19)).apply { leftMargin = s.dp(12); rightMargin = s.dp(8); gravity = Gravity.BOTTOM; bottomMargin = s.dp(6) })
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
        val wide = width > height * 1.2f
        val usableW = min(width - safeLeft - safeRight - s.dp(28), s.dp(if (wide) 1120 else 480))
        val left = (width - usableW) / 2; val top = safeTop + s.dp(8)
        val availableH = height - top - safeBottom - s.dp(8)
        val dense = availableH / resources.displayMetrics.density < 700 || wide
        val gap = s.dp(if (dense) 6 else 10); val headerH = s.dp(if (dense) 44 else 48)
        fun place(v: View, x: Int, y: Int, ww: Int, hh: Int) {
            val old = v.layoutParams as? LayoutParams
            if (old?.width != ww.coerceAtLeast(1) || old.height != hh.coerceAtLeast(1) || old.leftMargin != x || old.topMargin != y)
                v.layoutParams = LayoutParams(ww.coerceAtLeast(1), hh.coerceAtLeast(1)).apply { leftMargin = x; topMargin = y }
        }
        place(header, left, top, usableW, headerH)
        val colW = if (wide) (usableW - s.dp(16)) / 2 else usableW
        val bodyTop = top + headerH + gap; val bodyH = availableH - headerH - gap
        val tileH = s.dp(if (dense) 56 else 80)
        val subH = if (state.subscriptionTitle.isBlank() && state.trafficText.isBlank()) 0 else s.dp(if (dense) 76 else 84)
        val rightX = if (wide) left + colW + s.dp(16) else left
        var tilesTop = bodyTop + bodyH - 2 * tileH - gap
        val pairHeight = if (isTv) s.dp(44) + gap else 0
        val subTop = if (wide) bodyTop + pairHeight else tilesTop - subH - gap
        if (wide) tilesTop = subTop + subH + gap
        val pairW = (colW - gap) / 2
        place(tvPair, rightX, bodyTop, colW, s.dp(44))
        place(subscription, rightX, subTop, colW, subH)
        place(server, rightX, tilesTop, pairW, tileH); place(russian, rightX + pairW + gap, tilesTop, pairW, tileH)
        place(devices, rightX, tilesTop + tileH + gap, pairW, tileH); place(support, rightX + pairW + gap, tilesTop + tileH + gap, pairW, tileH)
        listOf(server, devices, support).forEach { tile ->
            val size = s.dp(if (dense) 22 else 28)
            place(tile.getChildAt(0), s.dp(12), s.dp(7), size, size)
            if (tile === support) place(tile.getChildAt(3), s.dp(12), s.dp(7), size, size)
        }
        place(routeToggle, pairW - s.dp(56), s.dp(8), s.dp(44), s.dp(36))
        (russian.getChildAt(1) as TextView).textSize = if (!wide && pairW < s.dp(175)) 13f else 14f
        val p = s.dp(12)
        place(subscriptionTitle, p, s.dp(8), colW - p * 2 - s.dp(76), s.dp(20))
        place(change, colW - p - s.dp(76), s.dp(8), s.dp(76), s.dp(20))
        place(traffic, p, s.dp(31), colW - 2 * p, s.dp(20))
        place(expiry, p, s.dp(55), colW - 2 * p, s.dp(18))
        val fillWidth = ((colW - 2 * p) * (state.trafficFraction ?: 0f).coerceIn(0f, 1f)).toInt()
        if (filled.layoutParams.width != fillWidth) filled.layoutParams = LayoutParams(fillWidth, -1)
        val connectH = s.dp(if (dense) 48 else 56); val guardH = s.dp(if (dense) 28 else 36)
        val buyH = s.dp(44); val statusH = s.dp(if (dense) 32 else 61)
        val end = if (wide) bodyTop + bodyH else subTop - gap
        val buyY = end - buyH; val guardY = buyY - gap - guardH; val connectY = guardY - gap - connectH; val statusY = connectY - gap - statusH
        place(mascot, left, bodyTop, colW, (statusY - bodyTop - gap).coerceAtLeast(s.dp(72)))
        val importW = min(s.dp(110), (colW * .28).toInt())
        place(qr, left, bodyTop, importW, s.dp(44)); place(clipboard, left + colW - importW, bodyTop, importW, s.dp(44))
        place(title, left, statusY, colW, s.dp(if (dense) 30 else 35)); title.textSize = if (dense) 21f else 26f
        place(subtitle, left, statusY + s.dp(35), colW, s.dp(26)); subtitle.visibility = if (dense) GONE else VISIBLE
        place(connect, left, connectY, colW, connectH); place(guard, left, guardY, colW, guardH); place(buy, left, buyY, colW, buyH)
        super.onMeasure(w, h)
    }
    fun render(next: FlintUiState) {
        state = next
        val color = when (next.phase) { FlintPhase.DISCONNECTED -> 0xFF82909E.toInt(); FlintPhase.CONNECTING -> 0xFFF1C75B.toInt(); FlintPhase.CONNECTED -> s.mint; FlintPhase.ERROR -> 0xFFEF626B.toInt() }
        title.text = when (next.phase) { FlintPhase.CONNECTING -> "Подключаемся…"; FlintPhase.CONNECTED -> "Вы защищены"; else -> "Вы не защищены" }
        title.setTextColor(if (next.phase == FlintPhase.CONNECTED) s.mint else s.ink)
        subtitle.text = next.message.ifBlank { if (next.phase == FlintPhase.CONNECTED) "Ваше соединение защищено" else "Подключитесь, чтобы защитить свои данные" }
        connect.text = when (next.phase) { FlintPhase.CONNECTED -> "ОТКЛЮЧИТЬ"; FlintPhase.CONNECTING -> "ОТМЕНИТЬ"; else -> "ПОДКЛЮЧИТЬСЯ" }
        connect.setCompoundDrawables(FlintIcon("power", 0xFF061D27.toInt(), s.dp(26)), null, null, null)
        connect.setPadding(s.dp(28), 0, s.dp(28), 0)
        connect.contentDescription = when (next.phase) { FlintPhase.CONNECTED -> "Отключить VPN"; FlintPhase.CONNECTING -> "Отменить подключение"; else -> "Подключиться" }
        connect.background = s.surface(color, 28); connect.setTextColor(0xFF061D27.toInt()); mascot.setPhase(next.phase, color)
        guard.setCompoundDrawables(FlintIcon("shield", s.muted, s.dp(21)), null, null, null)
        guard.setPadding(s.dp(20), 0, s.dp(20), 0)
        guard.text = "Flint Guard  •  " + when (next.phase) { FlintPhase.CONNECTED -> "Включён"; FlintPhase.CONNECTING -> "Подключение"; FlintPhase.ERROR -> "Ошибка подключения"; else -> "Отключён" }
        (server.getChildAt(1) as TextView).text = next.serverLabel
        (server.getChildAt(2) as TextView).text = if (next.selectedServerId == null) "По доступности и загрузке" else "Сменить сервер →"
        (devices.getChildAt(2) as TextView).text = if (next.loggedIn) "Устройства и доступ" else "Войти в аккаунт"
        (support.getChildAt(2) as TextView).text = if (next.supportUnread > 0) "Новых ответов: ${next.supportUnread}" else "Flint готов помочь"
        subscription.visibility = if (next.subscriptionTitle.isBlank() && next.trafficText.isBlank()) GONE else VISIBLE
        subscriptionTitle.text = next.subscriptionTitle.ifBlank { "Подписка Flint" }; trafficText.text = next.trafficText; expiry.text = next.expiryText
        syncing = true; routeToggle.isChecked = next.ruDirect; syncing = false
        routeToggle.contentDescription = "Сайты РФ: ${if (next.ruDirect) "напрямую" else "через VPN"}"
        serverRows.forEach { (id, button) -> next.servers.find { it.id == id }?.let { item ->
            button.text = "${item.name}\n${serverHint(item)}"
            val selected = id == next.selectedServerId
            button.background = s.surface(if (item.available == false) 0xFF35404A.toInt() else if (selected) 0xFF57E4B0.toInt() else s.card)
            button.setTextColor(if (selected && item.available != false) 0xFF052A20.toInt() else s.ink)
        } }
        qr.isEnabled = !next.busy; clipboard.isEnabled = !next.busy; requestLayout()
    }
    private fun showImportActions() {
        popup?.dialog?.dismiss()
        popup = s.panel("QR-код").also { p ->
            s.add(p.body, s.button("Поделиться через QR-код") { p.dialog.dismiss(); callbacks.onShareQr() }, 50)
            s.add(p.body, s.label("Добавить подключение", 13f, color = s.muted))
            if (isTv) s.add(p.body, s.button("Добавить с помощью телефона") { p.dialog.dismiss(); callbacks.onTvPair() }, 50)
            else s.add(p.body, s.button("Сканировать QR-код") { p.dialog.dismiss(); callbacks.onScanCamera() }, 50)
            listOf("QR-код на картинке" to { callbacks.onImportQrImage() }, "Ввести ссылку" to { callbacks.onImportText() }, "Открыть файл" to { callbacks.onImportFile() }).forEach { (name, action) ->
                s.add(p.body, s.button(name) { p.dialog.dismiss(); action() }, 50)
            }
        }
    }
    private fun serverHint(item: FlintServerUi) = (if (item.available == false) "Недоступен" else item.latencyMs?.let { "$it мс" } ?: "Не проверен") +
        (item.loadPercent?.let { " · Загрузка $it%" } ?: " · Загрузка: нет данных")
    private fun showServers() {
        serverRows.clear()
        popup?.dialog?.dismiss()
        popup = s.panel("Выбор сервера", showClose = false).also { p ->
            fun entry(name: String, id: String?, hint: String, unavailable: Boolean = false) {
                val b = s.button("$name\n$hint") { p.dialog.dismiss(); callbacks.onSelectServer(id) }
                b.gravity = Gravity.CENTER_VERTICAL or Gravity.START; b.setPadding(s.dp(16), s.dp(8), s.dp(16), s.dp(8))
                if (unavailable) b.background = s.surface(0xFF35404A.toInt())
                else if (id == state.selectedServerId) { b.background = s.surface(0xFF57E4B0.toInt()); b.setTextColor(0xFF052A20.toInt()) }
                s.add(p.body, b, 78, 10)
                if (id != null) serverRows[id] = b
                if (isTv && id == null) b.post { b.requestFocus() }
            }
            entry("Автоматически", null, "По доступности, задержке и загрузке")
            state.servers.forEach { item -> entry(item.name, item.id,
                (if (item.available == false) "Недоступен" else item.latencyMs?.let { "$it мс" } ?: "Не проверен") +
                    (item.loadPercent?.let { " · Загрузка $it%" } ?: " · Загрузка: нет данных"), item.available == false) }
            s.buttons(p.footer, s.button("Проверить") { callbacks.onProbe(false) },
                s.button("Автонастройка") { p.dialog.dismiss(); callbacks.onProbe(true) })
            s.closeButton(p)
        }
    }
    override fun onDetachedFromWindow() { popup?.dialog?.dismiss(); super.onDetachedFromWindow() }
}
