package app.flint.prototype.account

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.flint.prototype.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiError(val status: Int, val code: String, message: String) : Exception(message)
data class ApiReply(val status: Int, val data: JSONObject)

/** Fixed first-party API; secrets are encrypted with a non-exportable Android key. */
class FlintAccount(private val context: Context,
    private val testTransport: (suspend (String, String, JSONObject?, String?, String?) -> ApiReply)? = null) {
    init { require(testTransport == null || BuildConfig.DEBUG) }
    private val prefs = context.getSharedPreferences("flint-account", Context.MODE_PRIVATE)
    private val authLock = Mutex()
    private val vault = TokenVault(context)
    private var tokens = vault.read()
    private var epoch = 0L
    var me: JSONObject = cached("me"); private set
    var config: JSONObject = cached("config"); private set
    var subscriptions: List<JSONObject> = objects(cached("subscriptions"), "items"); private set
    var locations: JSONObject = JSONObject(); private set
    val loggedIn: Boolean get() = tokens.optString("accessToken").isNotBlank()
    var selectedId: String
        get() = prefs.getString("selected", "").orEmpty()
        set(value) { prefs.edit().putString("selected", value).apply() }
    val selected: JSONObject? get() = subscriptions.find { it.string("id") == selectedId }
    val title: String get() = me.string("email").ifBlank {
        me.optJSONObject("telegram")?.string("username")?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: "Аккаунт Flint"
    }
    fun device(): JSONObject {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val id = if (androidId.isNotBlank()) MessageDigest.getInstance("SHA-256")
            .digest(("flint-device:" + androidId).toByteArray()).joinToString("") { "%02x".format(it) }
        else prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).commit() }
        return JSONObject().put("deviceId", id).put("platform", if (BuildConfig.IS_TV) "android-tv" else "android")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("osVersion", Build.VERSION.RELEASE)
            .put("appVersion", BuildConfig.VERSION_NAME)
    }
    suspend fun publicConfig() {
        val value = request("GET", "/config", authenticated = false).data
        config = value; cache("config", value)
    }
    suspend fun refresh() {
        if (!loggedIn) return
        val revision = epoch
        val profile = request("GET", "/me").data
        val subs = request("GET", "/subscriptions").data
        if (revision != epoch) return
        me = profile; subscriptions = objects(subs, "items")
        if (subscriptions.none { it.string("id") == selectedId }) selectedId =
            (subscriptions.firstOrNull { it.string("status").equals("active", true) } ?: subscriptions.firstOrNull())?.string("id").orEmpty()
        cache("me", me); cache("subscriptions", subs)
    }
    suspend fun refreshLocations(): JSONObject {
        locations = request("GET", "/locations", authenticated = false).data
        return locations
    }
    suspend fun login(email: String, password: String, register: Boolean, referral: String = "") {
        val body = JSONObject().put("email", email.trim()).put("password", password).put("device", device())
        if (register) body.put("referralCode", referral.trim().ifBlank { null } ?: JSONObject.NULL)
        authLock.withLock {
            val reply = raw("POST", if (register) "/auth/register" else "/auth/login", body, null, null)
            accept(reply.data)
        }
        refresh()
    }
    suspend fun acceptTelegram(data: JSONObject) { authLock.withLock { accept(data) }; refresh() }
    private fun accept(data: JSONObject) {
        require(data.string("accessToken").isNotBlank() && data.string("refreshToken").isNotBlank()) { "Некорректный ответ входа" }
        epoch++; tokens = JSONObject(data.toString()); vault.write(tokens)
        me = JSONObject(); subscriptions = emptyList(); selectedId = ""
        prefs.edit().remove("me").remove("subscriptions").remove("orderDraft").remove("supportDraft").remove("supportInput").apply()
    }
    suspend fun logout() {
        authLock.withLock {
            try { raw("POST", "/auth/logout", JSONObject().put("refreshToken", tokens.string("refreshToken")), tokens.string("accessToken"), null) }
            finally {
                epoch++; tokens = JSONObject(); vault.write(tokens); me = JSONObject(); subscriptions = emptyList()
                prefs.edit().remove("me").remove("subscriptions").remove("selected").remove("orderDraft").remove("supportDraft").remove("supportInput").apply()
            }
        }
    }
    suspend fun request(method: String, path: String, body: JSONObject? = null,
                        authenticated: Boolean = true, idempotencyKey: String? = null): ApiReply {
        require(path.startsWith('/') && !path.contains("://") && !path.contains(".."))
        val before = epoch
        val used = if (authenticated) tokens.string("accessToken") else ""
        try {
            val result = raw(method, path, body, used.takeIf { authenticated }, idempotencyKey)
            if (authenticated && epoch != before) throw ApiError(401, "account_changed", "Аккаунт изменён. Повторите действие.")
            return result
        }
        catch (error: ApiError) {
            if (!authenticated || error.status != 401) throw error
        }
        authLock.withLock {
            if (epoch != before) throw ApiError(401, "account_changed", "Аккаунт изменён. Повторите действие.")
            if (tokens.string("accessToken") == used) {
                val refreshed = raw("POST", "/auth/refresh", JSONObject().put("refreshToken", tokens.string("refreshToken")), null, null).data
                if (refreshed.string("accessToken").isBlank() || refreshed.string("refreshToken").isBlank())
                    throw ApiError(401, "expired", "Войдите в аккаунт заново.")
                tokens = refreshed; vault.write(tokens)
            }
        }
        if (epoch != before) throw ApiError(401, "account_changed", "Аккаунт изменён. Повторите действие.")
        val retry = raw(method, path, body, tokens.string("accessToken"), idempotencyKey)
        if (epoch != before) throw ApiError(401, "account_changed", "Аккаунт изменён. Повторите действие.")
        return retry
    }
    private suspend fun raw(method: String, path: String, body: JSONObject?, token: String?, key: String?): ApiReply = withContext(Dispatchers.IO) {
        testTransport?.let { return@withContext it(method, path, body, token, key) }
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 12_000; c.readTimeout = 20_000
            c.instanceFollowRedirects = false; c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Flint/${BuildConfig.VERSION_NAME} Android")
            if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            if (!key.isNullOrBlank()) c.setRequestProperty("Idempotency-Key", key)
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = c.responseCode
            val bytes = (if (status in 200..299) c.inputStream else c.errorStream)?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (out.size() <= 2_000_000) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: byteArrayOf()
            if (bytes.size > 2_000_000) throw ApiError(status, "too_large", "Слишком большой ответ сервера.")
            val data = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrDefault(JSONObject())
            if (status !in 200..299) {
                val code = data.string("code").ifBlank { data.optJSONObject("error")?.string("code").orEmpty() }
                throw ApiError(status, code, friendly(status, code))
            }
            ApiReply(status, data)
        } catch (e: ApiError) { throw e }
        catch (_: java.net.SocketTimeoutException) { throw ApiError(0, "timeout", "Сервер не ответил вовремя. Повторите попытку.") }
        catch (_: java.io.IOException) { throw ApiError(0, "network", "Не удалось связаться с Flint. Проверьте интернет.") }
        finally { c.disconnect() }
    }
    fun draft(kind: String): JSONObject? = prefs.getString(kind, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun saveDraft(kind: String, value: JSONObject?) {
        val edit = prefs.edit().putString(kind, value?.toString())
        if (kind == "supportInput") edit.apply() else edit.commit()
    }
    private fun cached(key: String) = runCatching { JSONObject(prefs.getString(key, "{}").orEmpty()) }.getOrDefault(JSONObject())
    private fun cache(key: String, value: JSONObject) { prefs.edit().putString(key, value.toString()).apply() }
    companion object {
        const val BASE = "https://flintmain.ru/api/v1"
        fun friendly(status: Int, code: String): String = when {
            code in setOf("email_taken", "email_already_linked") -> "Эта почта уже связана с аккаунтом. Войдите через неё или обратитесь в поддержку."
            code.contains("telegram") && code.contains("linked") -> "Telegram уже связан с другим аккаунтом. Обратитесь в поддержку."
            status == 401 -> "Не удалось войти. Проверьте данные или войдите заново."
            status == 403 -> "Действие недоступно для этого аккаунта."
            status == 404 || status == 501 -> "Эта возможность пока недоступна на сервере Flint."
            status == 410 -> "Ссылка входа истекла. Начните вход заново."
            status == 409 -> "Действие уже выполнено или данные изменились. Обновите окно."
            status == 429 -> "Слишком много запросов. Повторите немного позже."
            status in 400..499 -> "Проверьте введённые данные и повторите действие."
            else -> "Сервер Flint временно недоступен (HTTP $status)."
        }
    }
}

fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)
fun objects(value: JSONObject, key: String): List<JSONObject> = value.optJSONArray(key)?.let { a ->
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }
} ?: emptyList()

private class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("flint-token-vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("flint-account-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("flint-account-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject = runCatching {
        val stored = prefs.getString("sealed", null) ?: return JSONObject()
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
    }.getOrDefault(JSONObject())
    fun write(value: JSONObject) {
        if (value.length() == 0) { prefs.edit().remove("sealed").commit(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("sealed", encoded).commit()) { "Не удалось сохранить вход" }
    }
}
