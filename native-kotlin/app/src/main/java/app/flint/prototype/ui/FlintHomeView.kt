package app.flint.prototype.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Native Android UI shared by the phone/TV flavours. No account or VPN logic lives here. */
class FlintHomeView(
    context: Context,
    private val isTv: Boolean,
    private val callbacks: FlintUiCallbacks,
) : FrameLayout(context) {
    private val ink = Color.rgb(244, 248, 250)
    private val muted = Color.rgb(168, 193, 208)
    private val card = Color.rgb(19, 44, 60)
    private val border = Color.rgb(49, 77, 95)
    private val mint = Color.rgb(74, 224, 171)
    private val grey = Color.rgb(139, 153, 164)
    private val yellow = Color.rgb(241, 199, 91)
    private val red = Color.rgb(239, 98, 107)
    private var state = FlintUiState()
    private var syncing = false
    private var serverDialog: AlertDialog? = null

    private val body = column()
    private val mascot = FlintMascotView(context)
    private val title = label(24f, true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitle = label(12f).apply {
        gravity = Gravity.CENTER
        setTextColor(muted)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val connect = button("Подключиться") { callbacks.onConnectToggle() }
    private val guard = label(12f).apply { gravity = Gravity.CENTER }
    private val server = button("Сервер\nАвтоматически") { showServers() }.apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(14), 0, dp(14), 0)
    }
    private val routeToggle = Switch(context).apply {
        text = "Сайты РФ"
        textSize = 14f
        setTextColor(ink)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        switchPadding = dp(4)
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(mint, grey),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.rgb(35, 112, 92), Color.rgb(62, 83, 97)),
        )
        background = surface()
        isFocusable = true
        setOnCheckedChangeListener { _, value ->
            if (!syncing) callbacks.onRuDirectChanged(value)
        }
    }
    private val traffic = column().apply {
        background = rounded(card, border)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }
    private val trafficLabel = label(13f).apply { maxLines = 1 }
    private val trafficBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(Color.rgb(0, 153, 255))
        progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(77, 100, 118))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val addServer = button("Добавить сервер") { showImportActions() }
    private val clipboard = button("Из буфера") { callbacks.onImportClipboard() }

    init {
        setBackgroundColor(Color.rgb(7, 23, 36))
        val artId = resources.getIdentifier("flint_background", "drawable", context.packageName)
        if (artId != 0) {
            addView(ImageView(context).apply {
                setImageResource(artId)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.38f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(-1, -1))
        }
        val pad = dp(if (isTv) 28 else 16)
        body.setPadding(pad, dp(8), pad, dp(8))
        addView(body, LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            body.setPadding(pad + safe.left, dp(8) + safe.top, pad + safe.right, dp(8) + safe.bottom)
            insets
        }

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logoId = resources.getIdentifier("flint_logo", "drawable", context.packageName)
        if (logoId != 0) {
            heading.addView(ImageView(context).apply {
                setImageResource(logoId)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        }
        heading.addView(column().apply {
            addView(label(if (isTv) 28f else 24f, true).apply { text = "FLINT" })
            addView(label(11f).apply { text = "Больше свободы в интернете"; setTextColor(muted) })
        })
        body.addView(heading, LinearLayout.LayoutParams(-1, dp(54)))

        val imports = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        imports.addView(addServer, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        imports.addView(clipboard, LinearLayout.LayoutParams(0, dp(44), 1f))
        val status = column().apply {
            gravity = Gravity.CENTER
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }
        subtitle.setOnClickListener {
            if (state.message.isNotBlank()) showNotice("Flint", state.message)
        }
        traffic.addView(trafficLabel, LinearLayout.LayoutParams(-1, -2))
        traffic.addView(trafficBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(3) })

        if (isTv) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val hero = column().apply {
                gravity = Gravity.CENTER
                addView(mascot, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(status, LinearLayout.LayoutParams(-1, dp(68)))
            }
            row.addView(hero, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(28) })
            val actions = column().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(imports, spaced(44))
                addView(connect, spaced(52))
                addView(guard, spaced(20))
                addView(traffic, spaced(48))
                addView(server, spaced(60))
                addView(routeToggle, spaced(56))
            }
            row.addView(actions, LinearLayout.LayoutParams(0, -1, 1.1f))
            body.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            body.addView(imports, spaced(44, 4))
            body.addView(mascot, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
            body.addView(status, spaced(64, 2))
            body.addView(connect, spaced(52, 6))
            body.addView(guard, spaced(20, 4))
            body.addView(traffic, spaced(48, 6))
            val shortcuts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            shortcuts.addView(server, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
            shortcuts.addView(routeToggle, LinearLayout.LayoutParams(0, -1, 1f))
            body.addView(shortcuts, spaced(64))
        }
        body.addView(label(10f).apply {
            text = "Flint Kotlin · тестовая версия"
            gravity = Gravity.CENTER
            setTextColor(muted)
        }, spaced(18, 6))
        render(FlintUiState())
        if (isTv) post { addServer.requestFocus() }
    }

    /** Must be called from the main thread. Reuses views to retain TV focus. */
    fun render(next: FlintUiState) {
        state = next
        val tint = when (next.phase) {
            FlintPhase.DISCONNECTED -> grey
            FlintPhase.CONNECTING -> yellow
            FlintPhase.CONNECTED -> mint
            FlintPhase.ERROR -> red
        }
        title.text = when (next.phase) {
            FlintPhase.DISCONNECTED -> "Вы не защищены"
            FlintPhase.CONNECTING -> "Подключаемся…"
            FlintPhase.CONNECTED -> "Вы защищены"
            FlintPhase.ERROR -> "Не удалось подключиться"
        }
        title.textSize = if (next.phase == FlintPhase.ERROR && !isTv) 20f else 24f
        title.setTextColor(ink)
        subtitle.text = next.message.ifBlank {
            when {
                !next.hasProfile -> "Добавьте ссылку подписки или сервер"
                next.phase == FlintPhase.CONNECTED -> next.serverLabel
                next.phase == FlintPhase.CONNECTING -> "Ожидаем подтверждения VPN-службы"
                else -> "Выберите сервер и подключитесь"
            }
        }
        subtitle.contentDescription = subtitle.text
        connect.text = when (next.phase) {
            FlintPhase.CONNECTED -> "Отключить VPN"
            FlintPhase.CONNECTING -> "Отменить подключение"
            else -> "Подключиться"
        }
        connect.background = surface(tint, Color.rgb(7, 29, 39))
        connect.setTextColor(Color.rgb(7, 29, 39))
        connect.isEnabled = next.hasProfile || next.phase == FlintPhase.CONNECTED || next.phase == FlintPhase.CONNECTING
        connect.alpha = if (connect.isEnabled) 1f else 0.60f
        connect.contentDescription = "${connect.text}. ${title.text}"
        mascot.setPhase(next.phase, tint)
        guard.text = when (next.phase) {
            FlintPhase.CONNECTED -> "Flint Guard  ·  VPN включён"
            FlintPhase.CONNECTING -> "Flint Guard  ·  подключение"
            FlintPhase.ERROR -> "Flint Guard  ·  ошибка подключения"
            else -> "Flint Guard  ·  VPN выключен"
        }
        guard.setTextColor(tint)
        server.text = "Сервер\n${next.serverLabel}"
        server.contentDescription = "Выбрать сервер. Сейчас: ${next.serverLabel}"
        server.isEnabled = next.servers.isNotEmpty()
        server.alpha = if (server.isEnabled) 1f else 0.6f
        syncing = true
        routeToggle.isChecked = next.ruDirect
        syncing = false
        routeToggle.contentDescription = "Сайты РФ: ${if (next.ruDirect) "напрямую" else "через VPN"}"
        traffic.visibility = if (next.trafficText.isBlank()) GONE else VISIBLE
        trafficLabel.text = next.trafficText
        traffic.contentDescription = next.trafficText
        val fraction = next.trafficFraction?.takeIf { it.isFinite() }
        trafficBar.visibility = if (fraction == null) GONE else VISIBLE
        trafficBar.progress = ((fraction ?: 0f).coerceIn(0f, 1f) * 1000).toInt()
        addServer.isEnabled = !next.busy
        clipboard.isEnabled = !next.busy
    }

    private fun showImportActions() {
        showActionDialog("Добавить сервер", listOf(
            "QR-код на картинке" to { callbacks.onImportQrImage() },
            "Ввести ссылку" to { callbacks.onImportText() },
            "Открыть файл" to { callbacks.onImportFile() },
        ))
    }

    private fun showServers() {
        serverDialog?.dismiss()
        val list = column().apply { setPadding(dp(18), dp(4), dp(18), dp(12)) }
        val auto = button(if (state.selectedServerId == null) "✓  Автоматически" else "Автоматически") {
            serverDialog?.dismiss()
            callbacks.onSelectServer(null)
        }
        list.addView(auto, spaced(52))
        state.servers.forEach { item ->
            val selected = state.selectedServerId == item.id
            val details = mutableListOf<String>()
            when (item.available) {
                false -> details.add("Недоступен")
                true -> details.add(item.latencyMs?.let { "$it мс" } ?: "Доступен")
                null -> details.add(item.latencyMs?.let { "$it мс" } ?: "Ещё не проверен")
            }
            item.loadPercent?.takeIf { it in 0..100 }?.let { details.add("Загрузка $it%") }
            val entry = button("${if (selected) "✓  " else ""}${item.name}\n${details.joinToString("  ·  ")}") {
                serverDialog?.dismiss()
                callbacks.onSelectServer(item.id)
            }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(16), dp(6), dp(16), dp(6))
                maxLines = 3
                if (item.available == false) {
                    background = surface(Color.rgb(50, 62, 71))
                    setTextColor(Color.rgb(183, 193, 201))
                }
                // A failed probe is not proof that the VPN cannot connect: manual retry stays available.
                contentDescription = "${item.name}. ${details.joinToString(". ")}${if (selected) ". Выбран" else ""}"
            }
            list.addView(entry, spaced(72))
        }
        serverDialog = AlertDialog.Builder(context)
            .setTitle("Серверы")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("Закрыть", null)
            .create().also { dialog ->
                styleDialog(dialog)
                dialog.setOnDismissListener { serverDialog = null }
                dialog.show()
                finishDialogStyle(dialog)
                if (isTv) auto.requestFocus()
            }
    }

    private fun showActionDialog(title: String, actions: List<Pair<String, () -> Unit>>) {
        val list = column().apply { setPadding(dp(18), 0, dp(18), dp(12)) }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(list)
            .setNegativeButton("Отмена", null).create()
        val buttons = actions.map { (text, action) ->
            button(text) { dialog.dismiss(); action() }.also { list.addView(it, spaced(52)) }
        }
        styleDialog(dialog)
        dialog.show()
        finishDialogStyle(dialog)
        if (isTv) buttons.firstOrNull()?.requestFocus()
    }

    private fun showNotice(title: String, message: String) {
        val dialog = AlertDialog.Builder(context).setTitle(title).setMessage(message)
            .setPositiveButton("Закрыть", null).create()
        styleDialog(dialog)
        dialog.show()
        finishDialogStyle(dialog)
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(rounded(Color.rgb(8, 28, 42), border, 22))
    }

    private fun finishDialogStyle(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(rounded(Color.rgb(8, 28, 42), border, 22))
        fun tint(view: View) {
            if (view is TextView && view !is Button) view.setTextColor(ink)
            if (view is ViewGroup) for (i in 0 until view.childCount) tint(view.getChildAt(i))
        }
        dialog.window?.decorView?.let(::tint)
        listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE).forEach { id ->
            dialog.getButton(id)?.let { button ->
                button.setTextColor(mint)
                button.background = surface()
                button.isAllCaps = false
            }
        }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(ink)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(text: String, action: () -> Unit) = Button(context).apply {
        id = View.generateViewId()
        this.text = text
        textSize = if (isTv) 15f else 14f
        setTextColor(ink)
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), 0, dp(10), 0)
        background = surface()
        stateListAnimator = null
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int = 16, width: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        setStroke(dp(width), stroke)
    }

    private fun surface(fill: Int = card, focusStroke: Int = mint): android.graphics.drawable.Drawable {
        val states = StateListDrawable()
        if (isTv) states.addState(intArrayOf(android.R.attr.state_focused), rounded(fill, focusStroke, width = 3))
        states.addState(intArrayOf(), rounded(fill, border))
        return RippleDrawable(ColorStateList.valueOf(Color.argb(45, 255, 255, 255)), states, null)
    }

    private fun spaced(height: Int, top: Int = 8) = LinearLayout.LayoutParams(-1, dp(height)).apply {
        topMargin = dp(top)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDetachedFromWindow() {
        serverDialog?.dismiss()
        super.onDetachedFromWindow()
    }
}
