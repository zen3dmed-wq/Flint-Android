package app.flint.prototype.pairing

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import app.flint.prototype.BuildConfig
import app.flint.prototype.account.*
import app.flint.prototype.ui.FlintStyle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Owner displays an HTTPS QR; the recipient imports without signing in. */
internal class SharePairingScreen(private val activity: Activity, private val api: FlintAccount,
    private val scope: CoroutineScope, private val accept: suspend (PairingProtocol.Transfer) -> Unit,
    private val prepare: suspend (JSONObject) -> JSONObject, private val login: (() -> Unit) -> Unit,
    private val connect: () -> Unit) {
    private val s = FlintStyle(activity)
    private val panels = mutableSetOf<FlintStyle.Panel>()
    fun close() { panels.toList().forEach { it.dialog.dismiss() }; panels.clear() }
    private fun panel(title: String) = s.panel(title, maxWidth = 560).also { panels.add(it) }
    private fun message(e: Exception) = when {
        e is ApiError && e.status in setOf(404,405,501,503) -> "Передача по QR ещё не включена на сервере Flint. Нужна установка модуля API."
        e is ApiError && e.status == 410 -> "Ссылка истекла или уже использована. Попросите владельца показать новый QR."
        e is ApiError && e.status == 409 -> "Этот QR уже получает другое устройство. Попросите владельца показать новый QR."
        e is ApiError && e.status == 429 -> "Слишком много попыток. Подождите и повторите."
        else -> "Не удалось передать настройки. Проверьте интернет и повторите."
    }
    fun share(selected: JSONObject? = null) {
        if (!api.loggedIn) { login { share(selected) }; return }
        val p = panel("Поделиться подключением")
        var job: Job? = null
        p.dialog.setOnDismissListener { panels.remove(p); job?.cancel() }
        s.add(p.body, s.label("Выберите подписку. Другой человек сканирует QR обычной камерой: Flint откроется, а если его нет — появится страница установки. Передаётся доступ к подписке, без паролей и вашего аккаунта.", color=s.muted))
        job = scope.launch {
            try {
                api.refresh()
                val subscriptions = api.subscriptions.filter { it.string("status") == "active" && it.string("subscriptionUrl").startsWith("https://") && (selected == null || it.string("id") == selected.string("id")) }
                if (subscriptions.isEmpty()) { p.error("Нет активной подписки. Оформите подписку на главной странице."); return@launch }
                for (sub in subscriptions) s.add(p.body, s.primary("Поделиться · ${AccountScreens.subTitle(sub)}") {
                    if (p.busy) return@primary
                    p.busy = true
                    job = scope.launch {
                        try {
                            p.message.text = "Готовим настройки…"
                            val payload = prepare(sub)
                            val key = PairingProtocol.secret(); val request = UUID.randomUUID().toString()
                            val start = api.request("POST", "/devices/pairing/share/start", JSONObject()
                                .put("subscriptionId",sub.string("id")).put("claimChallenge",PairingProtocol.challenge(PairingProtocol.shareSecret(key)))
                                .put("requestId",request)).data
                            val id = start.getString("pairingId")
                            val envelope = withContext(Dispatchers.Default) { PairingProtocol.seal(id,key,payload) }
                            api.request("POST", "/devices/pairing/approve", JSONObject().put("pairingId",id)
                                .put("subscriptionId",sub.string("id")).put("requestId",request).put("encryptedSettings",envelope))
                            currentCoroutineContext().ensureActive()
                            p.body.removeAllViews(); p.message.text = ""
                            val qr = PairingProtocol.shareQr(id,key)
                            val matrix = QRCodeWriter().encode(qr,BarcodeFormat.QR_CODE,640,640,mapOf(EncodeHintType.MARGIN to 4))
                            val bitmap = Bitmap.createBitmap(640,640,Bitmap.Config.RGB_565)
                            bitmap.setPixels(IntArray(640*640) { if(matrix[it%640,it/640]) Color.BLACK else Color.WHITE },0,640,0,0,640,640)
                            s.add(p.body,ImageView(activity).apply { setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="QR передачи подписки Flint" },280)
                            s.add(p.body,s.label("Покажите QR другому человеку. Ссылка действует 15 минут и подключает одно устройство. Если Flint не установлен, после установки вернитесь к странице и нажмите «Открыть Flint» или снова сканируйте этот же QR.",color=s.muted))
                            s.add(p.footer,s.button("Отменить передачу") {
                                job=scope.launch { try { api.request("POST","/devices/pairing/share/cancel",JSONObject().put("pairingId",id));p.dialog.dismiss() }
                                    catch(e:CancellationException){throw e} catch(e:Exception){p.error(message(e))} }
                            },46)
                            s.closeButton(p)
                        } catch(e:CancellationException){throw e}
                        catch(e:Exception){p.error(message(e))}
                        finally {p.busy=false}
                    }
                },52)
                if(selected != null && subscriptions.size == 1) (p.body.getChildAt(p.body.childCount-1) as? android.widget.Button)?.performClick()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){p.error(message(e))}
        }
    }
    fun receive(target: PairingProtocol.Target) {
        val p = panel("Подключиться по приглашению")
        var job: Job? = null
        val verifier = PairingProtocol.secret()
        p.dialog.setOnDismissListener { panels.remove(p);job?.cancel() }
        s.add(p.body,s.label("Получить серверы и настройки подписки владельца QR? Вход в его аккаунт и Telegram не нужны.",color=s.muted))
        s.add(p.body,s.primary("Получить и подключиться") {
            if(p.busy) return@primary
            p.busy=true
            job=scope.launch {
                try {
                    p.message.text="Получаем настройки…"
                    val result=api.request("POST","/devices/pairing/share/claim",JSONObject().put("pairingId",target.id)
                        .put("claimSecret",PairingProtocol.shareSecret(target.key)).put("codeChallenge",PairingProtocol.challenge(verifier))
                        .put("device",api.device().put("platform",if(BuildConfig.IS_TV) "android-tv" else "android")),false).data
                    val transfer=withContext(Dispatchers.Default){PairingProtocol.open(target.id,target.key,result.getJSONObject("encryptedSettings"))}
                    accept(transfer)
                    runCatching {api.request("POST","/devices/pairing/ack",JSONObject().put("pairingId",target.id).put("codeVerifier",verifier),false)}
                    currentCoroutineContext().ensureActive()
                    if(!p.dialog.isShowing)return@launch
                    p.dialog.dismiss();connect()
                } catch(e:CancellationException){throw e}
                catch(e:Exception){p.error(message(e))}
                finally {p.busy=false}
            }
        },52)
        s.closeButton(p)
    }
}
