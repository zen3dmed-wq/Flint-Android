package app.flint.prototype

import android.graphics.Bitmap
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.flint.prototype.data.QrImageDecoder
import app.flint.prototype.data.ProfileStore
import app.flint.prototype.imports.SubscriptionParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** All endpoints and credentials below are synthetic. These tests perform no network requests. */
@RunWith(AndroidJUnit4::class)
class ImportInstrumentationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = listOf(
        "vless://00000000-0000-4000-8000-000000000001@192.0.2.10:443?type=tcp&security=tls&sni=example.invalid#Armenia",
        "vless://00000000-0000-4000-8000-000000000002@192.0.2.11:443?type=tcp&security=tls&sni=example.invalid#Armenia",
        "trojan://test-only-password@192.0.2.12:443?type=tcp&security=tls&sni=example.invalid#USA",
    ).joinToString("\n")

    @Test fun localSubscriptionFileKeepsServersWithTheSameCountryDistinct() {
        val file = File(context.cacheDir, "subscription-test-only.txt")
        try {
            file.writeText(Base64.encodeToString(fixture.toByteArray(), Base64.NO_WRAP))
            val imported = context.contentResolver.openInputStream(Uri.fromFile(file))!!.bufferedReader().use {
                SubscriptionParser.parse(it.readText())
            }
            assertEquals(3, imported.profiles.size)
            assertEquals(3, imported.profiles.map { it.id }.distinct().size)
            assertEquals(setOf("192.0.2.10", "192.0.2.11", "192.0.2.12"), imported.profiles.map { it.host }.toSet())
            assertEquals(2, imported.profiles.count { it.name.startsWith("Armenia") })
            assertEquals(2, imported.profiles.filter { it.name.startsWith("Armenia") }.map { it.name }.distinct().size)
        } finally { file.delete() }
    }

    @Test fun qrImageImportDecodesItsPayloadThenUsesTheSameSubscriptionParser() {
        val payload = fixture.lineSequence().first().substringBefore('#') + "#Армения — QR тест"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 800, 800,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        val image = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "qr-import-test-only.png")
        try {
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = QrImageDecoder.decode(context, Uri.fromFile(file))
            assertEquals(payload, decoded)
            val profiles = SubscriptionParser.parse(decoded).profiles
            assertEquals(1, profiles.size)
            assertEquals("192.0.2.10", profiles.single().host)
            assertEquals("Армения — QR тест", profiles.single().name)
        } finally { file.delete(); image.recycle() }
    }

    @Test fun importedServersSurviveStoreRecreationWithoutDuplicates() {
        val directory = File(context.cacheDir, "isolated-profile-store-test").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val parsed = SubscriptionParser.parse(fixture).profiles
        try {
            assertEquals(2, ProfileStore(isolated).merge(parsed.take(2)).size)
            assertEquals(3, ProfileStore(isolated).merge(parsed.takeLast(1)).size)
            assertEquals(3, ProfileStore(isolated).merge(parsed).size)
            val restored = ProfileStore(isolated).load()
            assertEquals(parsed.map { it.id }, restored.map { it.id })
            assertEquals(parsed.map { it.outboundJson }, restored.map { it.outboundJson })
        } finally {
            // Only this test's flat cache directory is touched.
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
