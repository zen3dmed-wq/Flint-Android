package app.flint.prototype.pairing

import app.flint.prototype.data.ProfileCollection
import app.flint.prototype.imports.ImportException
import app.flint.prototype.imports.ServerProfile
import app.flint.prototype.imports.XrayConfigBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The QR contains an encryption key, never the TV's polling secret or an account token. */
internal object PairingProtocol {
    const val MAX_BYTES = 512 * 1024
    private val random = SecureRandom()
    fun secret(): String = encode(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String): String = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun isPairing(value: String): Boolean = value.trim().startsWith("flint://pair", true)
    fun qr(id: String, key: String): String {
        require(validSecret(id) && validSecret(key))
        return "flint://pair?v=1&id=$id#key=$key"
    }
    class Target(val id: String, val key: String) {
        override fun toString() = "Flint TV pairing (redacted)"
    }
    fun parse(value: String): Target {
        try {
            require(value.length < 512)
            val uri = URI(value.trim())
            require(uri.scheme.equals("flint", true) && uri.host == "pair" && uri.path.isNullOrEmpty() && uri.userInfo == null && uri.port == -1)
            val query = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            require(query.keys == setOf("v", "id") && query["v"] == "1")
            val id = query.getValue("id"); val fragment = uri.rawFragment.orEmpty()
            require(fragment.startsWith("key="))
            val key = fragment.removePrefix("key=")
            require(validSecret(id) && validSecret(key))
            return Target(id, key)
        } catch (_: Exception) { throw ImportException("Неверный QR добавления телевизора. Откройте новый QR на телевизоре.") }
    }
    fun seal(id: String, key: String, value: JSONObject): JSONObject {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES && validSecret(id) && validSecret(key))
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(decode(key), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(("Flint-TV-pairing-v1:" + id).toByteArray(Charsets.US_ASCII))
        return JSONObject().put("version", 1).put("nonce", encode(iv)).put("ciphertext", encode(cipher.doFinal(bytes)))
    }
    fun open(id: String, key: String, envelope: JSONObject): Transfer {
        try {
            require(validSecret(id) && validSecret(key) && envelope.getInt("version") == 1)
            val nonce = decode(envelope.getString("nonce")); require(nonce.size == 12)
            val encoded = envelope.getString("ciphertext"); require(encoded.length <= (MAX_BYTES + 16) * 4 / 3 + 4)
            val bytes = decode(encoded); require(bytes.size in 17..MAX_BYTES + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decode(key), "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(("Flint-TV-pairing-v1:" + id).toByteArray(Charsets.US_ASCII))
            val root = JSONObject(cipher.doFinal(bytes).toString(Charsets.UTF_8))
            require(root.getInt("version") == 1)
            val profiles = ProfileCollection.decode(root.getJSONObject("collection").toString().toByteArray(Charsets.UTF_8))
            require(profiles.isNotEmpty())
            val source = root.getString("subscriptionUrl")
            val uri = URI(source)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && source.length <= 8192)
            val title = root.getString("title").take(120)
            val rawSites = root.getJSONArray("directSites"); require(rawSites.length() <= 500)
            val sites = (0 until rawSites.length()).map { XrayConfigBuilder.normalizeSite(rawSites.getString(it)) }.distinct()
            val policy = root.optJSONObject("russianPolicy")
            require(policy == null || policy.toString().length <= 128 * 1024)
            val selected = root.optString("selectedServerId").takeIf { id -> profiles.any { it.id == id } }
            return Transfer(profiles, source, title, sites, root.optBoolean("automaticRouting", true), policy, selected)
        } catch (_: Exception) { throw ImportException("Не удалось проверить настройки телевизора. Создайте новый QR и повторите добавление.") }
    }
    class Transfer(val profiles: List<ServerProfile>, val source: String, val title: String,
                   val sites: List<String>, val automaticRouting: Boolean, val policy: JSONObject?, val selectedId: String?)
    fun payload(profiles: List<ServerProfile>, source: String, title: String, sites: List<String>,
                automaticRouting: Boolean, policy: String?, selectedId: String?): JSONObject =
        JSONObject().put("version", 1).put("collection", JSONObject(ProfileCollection.encode(profiles).toString(Charsets.UTF_8)))
            .put("subscriptionUrl", source).put("title", title.take(120)).put("directSites", JSONArray(sites))
            .put("automaticRouting", automaticRouting).put("russianPolicy", policy?.let(::JSONObject))
            .put("selectedServerId", selectedId)
    private fun validSecret(value: String) = value.matches(Regex("[A-Za-z0-9_-]{43}")) && runCatching { decode(value).size == 32 }.getOrDefault(false)
    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
}
