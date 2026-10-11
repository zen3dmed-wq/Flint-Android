package app.flint.prototype.imports

import org.json.JSONObject
import java.security.MessageDigest

/** Credentials stay in JSON and must never be sent to logs or analytics. */
class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val outboundJson: String,
    val originalConfigJson: String? = null,
) {
    fun outbound(): JSONObject = JSONObject(outboundJson)
    override fun toString(): String = "ServerProfile(id=$id, protocol=$protocol)"

    companion object {
        fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

class ImportException(message: String) : Exception(message)

data class ImportResult(val profiles: List<ServerProfile>, val warnings: List<String> = emptyList(), val sourceText: String? = null)
