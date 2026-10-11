package app.flint.prototype.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Reuses Flint's artwork. The original paws, medallion and icons stay unchanged. */
internal class FlintMascotView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var phase: FlintPhase? = null
    private var artwork: Bitmap? = null
    private var previous: Bitmap? = null
    private var blend = 1f
    private var animation: android.animation.ValueAnimator? = null
    private var tint = Color.GRAY
    private val cached = mutableMapOf<FlintPhase, Bitmap>()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setPhase(next: FlintPhase, color: Int) {
        if (phase == next) return
        phase = next
        tint = color
        cached[next]?.let { display(it); return }
        renderer.execute {
            val image = loadArtwork(next != FlintPhase.CONNECTED, color)
            post {
                if (image != null) cached[next] = image
                if (phase == next && image != null) display(image)
            }
        }
        invalidate()
    }

    private fun display(image: Bitmap) {
        animation?.cancel(); previous = artwork; artwork = image
        if (previous == null) { blend = 1f; invalidate(); return }
        animation = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; addUpdateListener { blend = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onDetachedFromWindow() { animation?.cancel(); super.onDetachedFromWindow() }
    private fun decode(name: String): Bitmap? {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(resources, id, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
        return BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = sample
        })
    }

    private fun loadArtwork(sad: Boolean, color: Int): Bitmap? {
        val original = decode("flint_mascot") ?: decode("flint_logo") ?: return null
        val width = original.width
        val height = original.height
        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)
        val sadImage = if (sad) decode("flint_mascot_sad") else null
        val expression = sadImage?.let {
            val matched = if (it.width == width && it.height == height) it
                else Bitmap.createScaledBitmap(it, width, height, true)
            IntArray(width * height).also { data -> matched.getPixels(data, 0, width, 0, 0, width, height) }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var pixel = pixels[i]
                val faceX = (x.toFloat() / width - 0.50f) / 0.19f
                val faceY = (y.toFloat() / height - 0.455f) / 0.185f
                val faceRadius2 = faceX * faceX + faceY * faceY
                if (expression != null && faceRadius2 < 1f) {
                    val ratio = ((1f - sqrt(faceRadius2)) / 0.20f).coerceIn(0f, 1f)
                    val target = expression[i]
                    fun channel(a: Int, b: Int) = (a + (b - a) * ratio).toInt()
                    pixel = Color.argb(
                        Color.alpha(pixel),
                        channel(Color.red(pixel), Color.red(target)),
                        channel(Color.green(pixel), Color.green(target)),
                        channel(Color.blue(pixel), Color.blue(target)),
                    )
                }
                val dx = x.toFloat() / width - 0.50f
                val dy = y.toFloat() / height - 0.49f
                val radius2 = dx * dx + dy * dy
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Same ring region used by the existing Qt artwork, excluding the face/icons.
                if (sad && radius2 >= 0.375f * 0.375f && radius2 <= 0.49f * 0.49f &&
                    Color.alpha(pixel) != 0 && g > r + 6 && g >= b * 0.65f) {
                    val brightness = max(r, max(g, b)) / 255f
                    pixel = Color.argb(Color.alpha(pixel),
                        (Color.red(color) * brightness).toInt(),
                        (Color.green(color) * brightness).toInt(),
                        (Color.blue(color) * brightness).toInt())
                }
                pixels[i] = pixel
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(min(width, height).toFloat(), 300 * resources.displayMetrics.density)
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        destination.set(left, top, left + side, top + side)
        val image = artwork
        if (image != null) {
            paint.style = Paint.Style.FILL
            previous?.takeIf { blend < 1f }?.let { paint.alpha = 255; canvas.drawBitmap(it, null, destination, paint) }
            paint.alpha = (255 * blend).toInt(); canvas.drawBitmap(image, null, destination, paint); paint.alpha = 255
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * resources.displayMetrics.density
            paint.color = tint
            canvas.drawCircle(width / 2f, height / 2f, side * 0.40f, paint)
        }
    }

    companion object {
        private val renderer = ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>())
    }
}
