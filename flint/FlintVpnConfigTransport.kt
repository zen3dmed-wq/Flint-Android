package org.amnezia.vpn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Keep the expanded routing catalog out of Binder's UTF-16 String payload.
 * The same compressed envelope works for service start, reconnect and redelivery.
 * Never log this payload: it contains VPN credentials.
 */
object FlintVpnConfigTransport {
    const val EXTRA = "FLINT_VPN_CONFIG_GZIP_V1"
    const val MAX_PACKED_BYTES = 128 * 1024
    const val MAX_CONFIG_BYTES = 8 * 1024 * 1024

    fun encode(config: String): ByteArray {
        require(config.isNotBlank() && config.length <= MAX_CONFIG_BYTES) { "Invalid VPN config size" }
        val raw = config.toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_CONFIG_BYTES) { "Invalid VPN config size" }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        return output.toByteArray().also {
            require(it.size <= MAX_PACKED_BYTES) { "VPN config transport size exceeded" }
        }
    }

    fun decode(packed: ByteArray): String {
        require(packed.isNotEmpty() && packed.size <= MAX_PACKED_BYTES) { "Invalid VPN config envelope" }
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(packed)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CONFIG_BYTES) { "Invalid VPN config size" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8).also {
            require(it.isNotBlank()) { "Empty VPN config envelope" }
        }
    }
}
