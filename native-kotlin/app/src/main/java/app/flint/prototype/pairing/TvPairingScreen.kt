package app.flint.prototype.pairing

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.widget.ImageView
import app.flint.prototype.BuildConfig
import app.flint.prototype.account.ApiError
import app.flint.prototype.account.FlintAccount
import app.flint.prototype.account.objects
import app.flint.prototype.account.string
import app.flint.prototype.imports.ImportException
import app.flint.prototype.ui.FlintStyle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** HTTPS first-party rendezvous. No Telegram, shared router or TV account login. */
internal class TvPairingScreen(private val activity: Activity, private val api: FlintAccount,
    private val scope: CoroutineScope, private val accept: suspend (PairingProtocol.Transfer) -> Unit,
    private val prepare: suspend (JSONObject) -> JSONObject, private val login: (() -> Unit) -> Unit,
    private val connect: () -> Unit) {
    private val style = FlintStyle(activity)
    private val panels = mutableSetOf<FlintStyle.Panel>()
    private var receiving: FlintStyle.Panel? = null
    private var pendingTarget: PairingProtocol.Target? = null
    fun close() { panels.toList().forEach { it.dialog.dismiss() }; panels.clear() }
    private fun panel(title: String): FlintStyle.Panel = style.panel(title, maxWidth = 560).also { panels.add(it) }
    private fun explain(p: FlintStyle.Panel, error: Exception) {
        p.error(when {
            error is ApiError && error.status in setOf(404, 405, 501, 503) -> "Добавление ТВ без Telegram ещё не включено на сервере Flint. Пока можно войти на ТВ по почте."
            error is ApiError && error.status == 410 -> "QR истёк или уже использован. Откройте новый QR на телевизоре."
            error is ApiError && error.status == 409 -> "Этот QR уже подтверждён. Проверьте телевизор или откройте новый QR."
            error is ApiError && error.status == 429 -> "Слишком много попыток. Подождите немного и создайте новый QR."
            error is ApiError -> error.message.orEmpty()
            error is ImportException -> error.message.orEmpty()
            else -> "Не удалось передать настройки. Проверьте интернет и повторите."
        })
    }
    fun receive() {
        if (receiving?.dialog?.isShowing == true) return
        val p = panel("Добавить телевизор")
        receiving = p
        val verifier = PairingProtocol.secret()
        val key = PairingProtocol.secret()
        var id = ""
        var completed = false
        var job: Job? = null
        p.dialog.setOnDismissListener {
            panels.remove(p); receiving = null; job?.cancel()
            if (id.isNotBlank() && !completed) scope.launch { runCatching {
                api.request("POST", "/devices/pairing/cancel", JSONObject().put("pairingId", id).put("codeVerifier", verifier), false)
            } }
        }
        style.add(p.body, style.label("На телефоне откройте Flint → QR-код → Камера и отсканируйте код. Выберите подписку и подтвердите добавление. Телефон и ТВ могут быть в разных сетях.", color = style.muted))
        val qr = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Одноразовый QR для подключения телевизора без Telegram" }
        val status = style.label("Создаём QR…", color = style.muted)
        style.add(p.body, qr, if (activity.resources.displayMetrics.heightPixels / activity.resources.displayMetrics.density < 600) 210 else 280)
        style.add(p.body, status)
        style.add(p.footer, style.button("Новый QR") { p.dialog.dismiss(); receive() }, 46)
        job = scope.launch {
            try {
                val start = api.request("POST", "/devices/pairing/start", JSONObject()
                    .put("codeChallenge", PairingProtocol.challenge(verifier))
                    .put("device", api.device().put("platform", "android-tv")), false).data
                id = start.getString("pairingId")
                val value = PairingProtocol.qr(id, key)
                val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 4))
                val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565)
                bitmap.setPixels(IntArray(640 * 640) { i -> if (matrix[i % 640, i / 640]) Color.BLACK else Color.WHITE }, 0, 640, 0, 0, 640, 640)
                qr.setImageBitmap(bitmap)
                val deadline = SystemClock.elapsedRealtime() + start.optLong("expiresInSeconds", 300).coerceIn(1, 300) * 1000
                val interval = start.optLong("intervalSeconds", 2).coerceIn(2, 10) * 1000
                while (isActive && p.dialog.isShowing && SystemClock.elapsedRealtime() < deadline) {
                    val seconds = ((deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                    status.text = "Ожидаем подтверждения на телефоне · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                    delay(interval)
                    val result = try { api.request("POST", "/devices/pairing/complete", JSONObject().put("pairingId", id).put("codeVerifier", verifier), false) }
                    catch (e: ApiError) {
                        if (e.status == 0 || e.status == 429 || e.status in 500..599) {
                            status.text = "Связь прервалась. Повторяем ожидание…"
                            delay((e.retryAfterSeconds ?: 2).coerceIn(2, 15) * 1000); continue
                        }
                        throw e
                    }
                    if (result.status == 202) continue
                    val transfer = withContext(Dispatchers.Default) { PairingProtocol.open(id, key, result.data.getJSONObject("encryptedSettings")) }
                    accept(transfer)
                    completed = true
                    runCatching { api.request("POST", "/devices/pairing/ack", JSONObject().put("pairingId", id).put("codeVerifier", verifier), false) }
                    if (p.dialog.isShowing) p.dialog.dismiss()
                    connect()
                    return@launch
                }
                qr.setImageDrawable(null); status.text = "QR истёк. Нажмите «Новый QR»."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { qr.setImageDrawable(null); qr.visibility = android.view.View.GONE; status.text = ""; explain(p, e) }
        }
    }
    fun scanned(value: String) {
        if (BuildConfig.IS_TV) return
        val target = try { PairingProtocol.parse(value) } catch (e: Exception) {
            style.notice("Добавить телевизор", e.message ?: "Неверный QR"); return
        }
        if (pendingTarget != null) return
        if (!api.loggedIn) {
            login { scanned(value) }
            return
        }
        pendingTarget = target
        val p = panel("Добавить телевизор")
        var job: Job? = null
        p.dialog.setOnDismissListener { panels.remove(p); pendingTarget = null; job?.cancel() }
        p.message.text = "Проверяем QR…"
        job = scope.launch {
            try {
                val info = api.request("POST", "/devices/pairing/inspect", JSONObject().put("pairingId", target.id)).data
                api.refresh()
                style.add(p.body, style.label(info.optJSONObject("device")?.string("model")?.take(120)?.ifBlank { "Android TV" } ?: "Android TV", 19f, true))
                style.add(p.body, style.label("Выберите подписку для этого телевизора. Передадим её серверы и настройки «Сайты РФ». VPN и Telegram на телевизоре не нужны.", color = style.muted))
                val subscriptions = api.subscriptions.filter { it.string("status") == "active" && it.string("subscriptionUrl").startsWith("https://") }
                p.message.text = if (subscriptions.isEmpty()) "Нет активной подписки. Сначала оформите её на главной странице." else ""
                val buttons = mutableListOf<android.widget.Button>()
                for (sub in subscriptions) {
                    val title = app.flint.prototype.account.AccountScreens.subTitle(sub)
                    val requestId = UUID.randomUUID().toString()
                    var envelope: JSONObject? = null
                    val button = style.primary("Передать · $title") {
                        if (p.busy) return@primary
                        p.busy = true; buttons.forEach { it.isEnabled = false }; p.message.text = "Передаём настройки…"
                        job = scope.launch {
                            try {
                                val payload = prepare(sub)
                                // Keep exactly the same envelope and request ID for an idempotent retry.
                                if (envelope == null) envelope = withContext(Dispatchers.Default) { PairingProtocol.seal(target.id, target.key, payload) }
                                api.request("POST", "/devices/pairing/approve", JSONObject().put("pairingId", target.id)
                                    .put("subscriptionId", sub.string("id")).put("requestId", requestId).put("encryptedSettings", envelope))
                                p.body.removeAllViews()
                                style.add(p.body, style.label("Настройки отправлены. На телевизоре появится кнопка подключения; при первом запуске подтвердите системный запрос VPN.", color = style.mint))
                                p.message.text = ""; style.closeButton(p)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { explain(p, e) }
                            finally { p.busy = false; buttons.forEach { it.isEnabled = true } }
                        }
                    }
                    buttons.add(button); style.add(p.body, button, 52)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { explain(p, e) }
        }
    }
}
