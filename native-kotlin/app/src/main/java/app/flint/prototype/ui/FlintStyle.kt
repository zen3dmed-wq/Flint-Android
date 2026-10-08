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
    fun panel(title: String, wide: Boolean = false, maxHeight: Int = 640,
              maxWidth: Int = if (wide) 690 else 460, topAligned: Boolean = false,
              showClose: Boolean = true, logo: Boolean = false): Panel {
        val dialog = Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer = column().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); background = shape(dark, line, 23) }
        val head = row()
        if (title.isEmpty() && !logo && !showClose) head.visibility = View.GONE
        if (logo) head.addView(ImageView(context).apply { setImageResource(app.flint.prototype.R.drawable.flint_logo) },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        head.addView(label(title, 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (showClose) head.addView(button("×") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        outer.addView(head)
        val content = column()
        outer.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        val message = label("", 12f, color = muted)
        outer.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val footer = column(); outer.addView(footer, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(outer); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        val metrics = context.resources.displayMetrics
        dialog.window?.setLayout(minOf(metrics.widthPixels - dp(28), dp(maxWidth)), minOf(metrics.heightPixels - dp(60), dp(maxHeight)))
        if (topAligned && !tv) dialog.window?.let { w -> w.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL); w.attributes = w.attributes.apply { y = dp(12) } }
        if (tv && showClose) head.getChildAt(head.childCount - 1).requestFocus()
        return Panel(dialog, content, message, footer)
    }
    fun notice(title: String, text: String) = panel(title).also { add(it.body, label(text, color = muted)) }
    data class Panel(val dialog: Dialog, val body: LinearLayout, val message: TextView, val footer: LinearLayout) {
        var busy = false
        fun error(text: String) { message.text = text }
    }
}
