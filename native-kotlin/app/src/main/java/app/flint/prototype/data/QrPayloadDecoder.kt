package app.flint.prototype.data

import app.flint.prototype.imports.ImportException
import app.flint.prototype.imports.SubscriptionParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader

/** Actual pixel decoder, kept independent of Android for deterministic image tests. */
internal object QrPayloadDecoder {
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decode(input: LuminanceSource): String {
        var source = input
        val results = linkedSetOf<String>()
        repeat(4) {
            collect(source, results)
            collect(source.invert(), results)
            if (it < 3) source = source.rotateCounterClockwise()
        }
        val supported = results.map(String::trim).filter { it.isNotEmpty() && isImport(it) }.distinct()
        return when {
            supported.size > 1 -> throw ImportException("На изображении несколько QR-кодов подписок. Обрежьте изображение, оставив нужный QR-код")
            supported.size == 1 -> supported.single()
            results.isNotEmpty() -> throw ImportException("QR-код найден, но в нём нет ссылки подписки или поддерживаемого VPN-профиля")
            else -> throw ImportException("QR-код не найден. Выберите чёткое изображение с QR-кодом целиком")
        }
    }

    private fun collect(source: LuminanceSource, results: MutableSet<String>) {
        if (Thread.currentThread().isInterrupted) throw ImportException("Сканирование отменено")
        val binary = BinaryBitmap(HybridBinarizer(source))
        try { QRCodeMultiReader().decodeMultiple(binary, hints).forEach { results.add(it.text) } }
        catch (_: ReaderException) { }
        // Single-code detection also covers a small QR surrounded by screenshot UI.
        try { results.add(QRCodeReader().decode(binary, hints).text) }
        catch (_: ReaderException) { }
    }

    private fun isImport(text: String): Boolean {
        if (text.startsWith("https://", true) || text.startsWith("http://", true)) return true
        return try { SubscriptionParser.parse(text).profiles.isNotEmpty() } catch (_: Exception) { false }
    }
}

internal class GraySource(width: Int, height: Int, private val pixels: ByteArray) : LuminanceSource(width, height) {
    init { require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) }
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height)
        val output = if (row != null && row.size >= width) row else ByteArray(width)
        System.arraycopy(pixels, y * width, output, 0, width)
        return output
    }
    override fun getMatrix(): ByteArray = pixels
    override fun isRotateSupported(): Boolean = true
    override fun rotateCounterClockwise(): LuminanceSource {
        val rotated = ByteArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) rotated[(width - 1 - x) * height + y] = pixels[y * width + x]
        return GraySource(height, width, rotated)
    }
}
