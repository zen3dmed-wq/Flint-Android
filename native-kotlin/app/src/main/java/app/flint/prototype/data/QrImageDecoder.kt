package app.flint.prototype.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Looper
import app.flint.prototype.imports.ImportException
import com.google.zxing.LuminanceSource
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

object QrImageDecoder {
    private const val MAX_SIDE = 4096
    private const val MAX_PIXELS = 16_000_000

    /** Decode on a worker. ImageDecoder applies image orientation metadata before returning pixels. */
    fun decode(context: Context, uri: Uri): String {
        if (Looper.myLooper() == Looper.getMainLooper()) throw ImportException("Сканирование изображения нужно выполнять в фоне")
        if (uri.scheme !in setOf("content", "file")) throw ImportException("Выберите изображение из файлов или галереи")
        var bitmap: Bitmap? = null
        try {
            bitmap = try { loadBitmap(context, uri, MAX_SIDE) }
                catch (_: OutOfMemoryError) { loadBitmap(context, uri, 2048) }
            val source = luminance(bitmap)
            bitmap.recycle()
            bitmap = null
            return QrPayloadDecoder.decode(source)
        } catch (error: ImportException) { throw error }
        catch (_: SecurityException) { throw ImportException("Нет доступа к изображению. Выберите его ещё раз") }
        catch (_: OutOfMemoryError) { throw ImportException("Изображение слишком большое. Обрежьте его до области QR-кода") }
        catch (_: Exception) { throw ImportException("Не удалось прочитать изображение. Попробуйте другой файл") }
        finally { bitmap?.recycle() }
    }

    private fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            if (width <= 0 || height <= 0) throw ImportException("Некорректный размер изображения")
            val scale = min(1.0, min(maxSide.toDouble() / maxOf(width, height), sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height))))
            decoder.setTargetSize(maxOf(1, floor(width * scale).toInt()), maxOf(1, floor(height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.isMutableRequired = false
            decoder.setOnPartialImageListener { false }
        }
    }

    private fun luminance(bitmap: Bitmap): LuminanceSource {
        val width = bitmap.width
        val height = bitmap.height
        if (width > MAX_SIDE || height > MAX_SIDE || width.toLong() * height > MAX_PIXELS)
            throw ImportException("Изображение слишком большое")
        val matrix = ByteArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            if (Thread.currentThread().isInterrupted) throw ImportException("Сканирование отменено")
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                val alpha = pixel ushr 24
                val gray = (((pixel shr 16) and 255) + 2 * ((pixel shr 8) and 255) + (pixel and 255)) / 4
                matrix[y * width + x] = ((gray * alpha + 255 * (255 - alpha)) / 255).toByte()
            }
        }
        return GraySource(width, height, matrix)
    }
}
