package app.flint.prototype.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.*
import app.flint.prototype.BuildConfig

class FlintStyle(val context: Context, val tv: Boolean = BuildConfig.IS_TV) {
    val ink = Color.parseColor("#F8FBFF")
    val muted = Color.parseColor("#B7C9DA")
    val mint = Color.parseColor("#4AE6A3")
    val line = Color.parseColor("#46637A")
    val card = Color.parseColor("#142E40")
    val dark = Color.parseColor("#081827")
    fun dp(n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(fill: Int = card, stroke: Int = line, radius: Int = 14, weight: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(weight), stroke)
    }
    fun surface(fill: Int = card, radius: Int = 14): android.graphics.drawable.Drawable {
        val states = StateListDrawable()
        if (tv) states.addState(intArrayOf(android.R.attr.state_focused), shape(fill, mint, radius, 3))
        states.addState(intArrayOf(), shape(fill, line, radius))
        return RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), states, null)
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun label(text: String = "", size: Float = 14f, bold: Boolean = false, color: Int = ink) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun button(text: String, action: () -> Unit) = Button(context).apply {
        id = View.generateViewId(); this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(ink); setTypeface(typeface, Typeface.BOLD); setPadding(dp(12), dp(6), dp(12), dp(6))
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        stateListAnimator = null; background = surface(); isFocusable = true; isFocusableInTouchMode = tv
        setOnClickListener { action() }
    }
    fun primary(text: String, action: () -> Unit) = button(text, action).apply {
        background = surface(mint); setTextColor(0xFF052A20.toInt())
    }
    fun buttons(parent: LinearLayout, vararg buttons: Button) {
        val row = row()
        buttons.forEachIndexed { i, button -> row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(10) }) }
        add(parent, row, 48)
    }
    fun closeButton(panel: Panel) {
        val row = row().apply { gravity = Gravity.END }
        row.addView(button("Закрыть") { panel.dialog.dismiss() }, LinearLayout.LayoutParams(dp(114), dp(46)))
        add(panel.footer, row, 46, 10)
    }
    fun field(hint: String, password: Boolean = false) = EditText(context).apply {
        this.hint = hint; textSize = 14f; setTextColor(ink); setHintTextColor(muted)
        setSingleLine(true); background = shape(0xFF112838.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (password) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    fun add(parent: LinearLayout, view: View, height: Int = -2, gap: Int = 10) {
        parent.addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { topMargin = dp(gap) })
    }
    fun panel(title: String, wide: Boolean = false,
              maxWidth: Int = if (wide) 690 else 460,
              showClose: Boolean = true, logo: Boolean = false): Panel {
        val dialog = Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val head = row()
        if (title.isEmpty() && !logo && !showClose) head.visibility = View.GONE
        if (logo) head.addView(ImageView(context).apply { setImageResource(app.flint.prototype.R.drawable.flint_logo) },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        head.addView(label(title, 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (showClose) head.addView(button("×") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val content = column()
        val scroll = ScrollView(context).apply { addView(content); isFillViewport = false }
        val message = label("", 12f, color = muted)
        val footer = column()
        val outer = AdaptivePanel(context, head, scroll, message, footer, dp(8)).apply {
            tag = "flint-panel"
            setPadding(dp(18), dp(18), dp(18), dp(18)); background = shape(dark, line, 23)
        }
        dialog.setContentView(outer); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        val metrics = context.resources.displayMetrics
        dialog.window?.setLayout(minOf(metrics.widthPixels - dp(28), dp(maxWidth)), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        if (tv && showClose) head.getChildAt(head.childCount - 1).requestFocus()
        return Panel(dialog, content, message, footer)
    }
    fun notice(title: String, text: String) = panel(title).also { add(it.body, label(text, color = muted)) }
    data class Panel(val dialog: Dialog, val body: LinearLayout, val message: TextView, val footer: LinearLayout) {
        var busy = false
        fun error(text: String) { message.text = text }
    }
}

/** Measure fixed controls first, then let content use the remaining window.
 * Short forms wrap naturally; only overflowing content scrolls. Window's
 * AT_MOST constraint already excludes system bars and the visible keyboard. */
private class AdaptivePanel(context: Context, private val head: View,
    private val scroll: ScrollView, private val message: TextView,
    private val footer: View, private val gap: Int) : ViewGroup(context) {
    init { listOf(head, scroll, message, footer).forEach { addView(it) } }
    private var headGap = 0
    private var messageGap = 0
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val windowLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            resources.displayMetrics.heightPixels else MeasureSpec.getSize(heightMeasureSpec)
        val maxHeight = (windowLimit - gap * 2).coerceAtLeast(0)
        val innerWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val w = MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY)
        val natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        head.measure(w, natural); footer.measure(w, natural)
        message.visibility = if (message.text.isNullOrEmpty()) GONE else VISIBLE
        message.measure(w, natural)
        headGap = if (head.visibility != GONE) gap else 0
        messageGap = if (message.visibility != GONE) gap else 0
        val fixed = paddingTop + paddingBottom + headGap + messageGap +
            (if (head.visibility == GONE) 0 else head.measuredHeight) + footer.measuredHeight +
            (if (message.visibility == GONE) 0 else message.measuredHeight)
        scroll.measure(w, MeasureSpec.makeMeasureSpec((maxHeight - fixed).coerceAtLeast(0), MeasureSpec.AT_MOST))
        setMeasuredDimension(width, minOf(maxHeight, fixed + scroll.measuredHeight))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        fun place(view: View) { if (view.visibility != GONE) {
            view.layout(paddingLeft, y, width - paddingRight, y + view.measuredHeight); y += view.measuredHeight
        } }
        place(head); y += headGap; place(scroll); y += messageGap; place(message); place(footer)
    }
}
