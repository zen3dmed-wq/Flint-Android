package org.amnezia.vpn

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max

object FlintQrImage {
    // Called by the Qt worker thread. Decoding never opens a URL or sends the image.
    @JvmStatic fun decode(context: Context, url: String): String {
        val result = JSONObject()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        try {
            val uri = Uri.parse(url)
            require(uri.scheme == "content" || uri.scheme == "file")
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val ratio = max(info.size.width, info.size.height) / 2048.0
                if (ratio > 1) decoder.setTargetSize(max(1, (info.size.width / ratio).toInt()), max(1, (info.size.height / ratio).toInt()))
            }
            val values = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
                .mapNotNull { it.rawValue }.filter { it.isNotBlank() }.distinct()
            when (values.size) {
                0 -> result.put("error", "На изображении не найден QR-код. Выберите более чёткую картинку.")
                1 -> result.put("text", values.first())
                else -> result.put("error", "На изображении несколько QR-кодов. Обрежьте картинку, оставив один.")
            }
        } catch (_: Exception) {
            result.put("error", "Не удалось прочитать QR-код. Выберите другую картинку или сохраните её как PNG/JPG.")
        } finally { scanner.close() }
        return result.toString()
    }
}
