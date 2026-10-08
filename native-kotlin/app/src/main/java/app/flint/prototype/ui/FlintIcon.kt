package app.flint.prototype.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** The same paths as the Qt client's FlintIcons assets. */
class FlintIcon(kind: String, color: Int, size: Int) : Drawable() {
    private val path = PathParser.createPathFromPathData(when (kind) {
        "power" -> "M12 3v9M6 5.6a8.5 8.5 0 1 0 12 0"
        "shield" -> "M12 2l8 3v6c0 5-4 9-8 11-4-2-8-6-8-11V5zM8 12l3 3 5-6"
        "location" -> "M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0zM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0"
        "devices" -> "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8"
        else -> "M9.2 3l.6-2h4.4l.6 2 2 .9 1.9-.5 2.2 3.8-1.4 1.5.2 2.2 1.3 1.5-2.2 3.8-2-.5-2 .9-.6 2h-4.4l-.6-2-2-.9-2 .5L1 12.5 2.4 11l-.2-2.2L.9 7.3l2.2-3.8 2 .5zM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0"
    })!!
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    init { setBounds(0, 0, size, size) }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f); canvas.drawPath(path, paint); canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
