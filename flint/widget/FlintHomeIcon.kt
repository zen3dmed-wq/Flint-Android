package org.amnezia.vpn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** Draw the original dark medallion over a state colour. The source artwork is unchanged. */
object FlintHomeIcon {
    fun bitmap(context: Context, state: FlintHomeColour, adaptive: Boolean = false): Bitmap {
        val size = 192
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = Color.parseColor(state.hex)
        if (adaptive) canvas.drawColor(paint.color)
        else canvas.drawRoundRect(RectF(0f, 0f, 192f, 192f), 42f, 42f, paint)
        val source = BitmapFactory.decodeResource(context.resources, R.drawable.flint_emblem)
        // 640px source has a dark circle at 46..594; clip inside its edge to exclude white paper.
        val src = Rect((source.width * .075f).toInt(), (source.height * .075f).toInt(),
            (source.width * .925f).toInt(), (source.height * .925f).toInt())
        val inset = if (adaptive) 40f else 12f
        val dest = RectF(inset, inset, 192f-inset, 192f-inset)
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(96f, 96f, 96f-inset, Path.Direction.CW) })
        canvas.drawBitmap(source, src, dest, paint)
        canvas.restore()
        source.recycle()
        return result
    }
}
