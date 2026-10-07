package app.flint.prototype.data

import app.flint.prototype.imports.ImportException
import app.flint.prototype.imports.SubscriptionParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test

private const val SUB = "https://example.test/sub/test-fixture"
private const val VLESS = "vless://00000000-0000-4000-8000-000000000001@vpn.example.test:443#Test"

private fun qr(value: String): GraySource {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 300, 300)
    val pixels = ByteArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) 0 else -1 }
    return GraySource(matrix.width, matrix.height, pixels)
}

private fun twoQr(first: String, second: String): GraySource {
    val left = qr(first)
    val right = qr(second)
    val pixels = ByteArray(680 * 340) { -1 }
    for (y in 0 until 300) {
        left.getRow(y, null).copyInto(pixels, (y + 20) * 680 + 20)
        right.getRow(y, null).copyInto(pixels, (y + 20) * 680 + 360)
    }
    return GraySource(680, 340, pixels)
}

fun dataScenarios() {
    val first = SubscriptionParser.parse(VLESS).profiles.single()
    val second = SubscriptionParser.parse(VLESS.replace("vpn.example", "vpn2.example")).profiles.single()
    val third = SubscriptionParser.parse(VLESS.replace("vpn.example", "vpn3.example")).profiles.single()
    val merged = ProfileCollection.merge(listOf(first, second), listOf(first, third))
    check(merged.map { it.id } == listOf(first.id, second.id, third.id))
    val decoded = ProfileCollection.decode(ProfileCollection.encode(merged))
    check(decoded.map { it.outboundJson } == merged.map { it.outboundJson })
    val malformed = try { ProfileCollection.decode("{}".toByteArray()); null } catch (error: ImportException) { error }
    check(malformed != null)
    println("PASS: profile persistence round trip, corruption and merge across subscriptions")

    check(QrPayloadDecoder.decode(qr(SUB)) == SUB)
    check(QrPayloadDecoder.decode(qr(VLESS)) == VLESS)
    check(QrPayloadDecoder.decode(qr(VLESS).invert().rotateCounterClockwise()) == VLESS)
    check(QrPayloadDecoder.decode(twoQr(SUB, SUB)) == SUB)
    check(QrPayloadDecoder.decode(twoQr("Unrelated text", SUB)) == SUB)
    val multiple = try { QrPayloadDecoder.decode(twoQr(SUB, VLESS)); null } catch (error: ImportException) { error }
    check(multiple?.message.orEmpty().contains("несколько"))
    val noProfile = try { QrPayloadDecoder.decode(qr("No VPN profile")); null } catch (error: ImportException) { error }
    check(noProfile?.message.orEmpty().contains("нет ссылки"))
    val blank = try { QrPayloadDecoder.decode(GraySource(300, 300, ByteArray(90_000) { -1 })); null } catch (error: ImportException) { error }
    check(blank?.message.orEmpty().contains("не найден"))
    println("PASS: real QR pixels, rotated/inverted QR, multi-code rejection, unrelated QR and blank image")
}

class ProfileDataTests {
    @Test(timeout = 30000)
    fun profileAndQrScenarios() = dataScenarios()
}

fun main() = dataScenarios()
