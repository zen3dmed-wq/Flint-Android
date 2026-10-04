package org.amnezia.vpn

import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

private fun rejected(block: () -> Unit) {
    check(runCatching(block).isFailure) { "Invalid payload was accepted" }
}

fun main(args: Array<String>) {
    val config = File(args.single()).readText()
    check(config.length * 2 > 950_000) { "Fixture must exercise the expanded RU catalog" }
    val packed = FlintVpnConfigTransport.encode(config)
    check(packed.size < 100_000) { "Routing config leaves insufficient Binder headroom" }
    check(FlintVpnConfigTransport.decode(packed) == config)
    // Intent redelivery and a different service-process instance can reuse it.
    repeat(3) { check(FlintVpnConfigTransport.decode(packed.copyOf()) == config) }
    for (text in listOf("{\"protocol\":\"xray\"}", "{\"description\":\"Армения 🐶\"}")) {
        check(FlintVpnConfigTransport.decode(FlintVpnConfigTransport.encode(text)) == text)
    }
    rejected { FlintVpnConfigTransport.encode(" ") }
    rejected { FlintVpnConfigTransport.decode(byteArrayOf()) }
    rejected { FlintVpnConfigTransport.decode(packed.copyOf(packed.size - 5)) }
    rejected { FlintVpnConfigTransport.decode(packed.copyOf().apply { this[lastIndex - 4] = 0 }) }
    rejected { FlintVpnConfigTransport.decode(ByteArray(FlintVpnConfigTransport.MAX_PACKED_BYTES + 1)) }
    rejected { FlintVpnConfigTransport.encode("x".repeat(FlintVpnConfigTransport.MAX_CONFIG_BYTES + 1)) }
    val bomb = ByteArrayOutputStream()
    GZIPOutputStream(bomb).use { it.write(ByteArray(FlintVpnConfigTransport.MAX_CONFIG_BYTES + 1) { 65 }) }
    rejected { FlintVpnConfigTransport.decode(bomb.toByteArray()) }
    val random = Random(17)
    rejected { FlintVpnConfigTransport.encode(CharArray(400_000) { (32 + random.nextInt(90)).toChar() }.concatToString()) }
    println("VPN config: ${config.length * 2} UTF-16 bytes -> ${packed.size} Binder bytes; round trips, redelivery, UTF-8, CRC and size limits passed")
}
