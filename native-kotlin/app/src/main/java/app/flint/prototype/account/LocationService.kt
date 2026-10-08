package app.flint.prototype.account

import android.app.Service
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume

/** One account process owns the vault and refresh lock. VPN gets only telemetry. */
class LocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != 1 || msg.sendingUid != Process.myUid()) return
            val reply = msg.replyTo ?: return
            scope.launch {
                val text = try { FlintAccount.shared(this@LocationService).refreshLocations().toString() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { "{}" }
                val safe = text.takeIf { it.toByteArray(Charsets.UTF_8).size <= 240_000 } ?: "{}"
                runCatching { reply.send(Message.obtain(null, 2).apply { data = Bundle().apply { putString("locations", safe) } }) }
            }
        }
    })
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

object LocationClient {
    suspend fun fetch(context: Context): JSONObject = withContext(Dispatchers.Main.immediate) {
        val text = withTimeoutOrNull(35_000) {
            suspendCancellableCoroutine<String> { result ->
                val app = context.applicationContext
                var bound = false
                lateinit var connection: ServiceConnection
                fun finish(value: String) {
                    if (bound) { bound = false; runCatching { app.unbindService(connection) } }
                    if (result.isActive) result.resume(value)
                }
                val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
                    if (msg.what == 2) finish(msg.data.getString("locations", "{}"))
                    true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        runCatching { Messenger(service).send(Message.obtain(null, 1).apply { replyTo = replies }) }.onFailure { finish("{}") }
                    }
                    override fun onServiceDisconnected(name: ComponentName?) { finish("{}") }
                    override fun onNullBinding(name: ComponentName?) { finish("{}") }
                    override fun onBindingDied(name: ComponentName?) { finish("{}") }
                }
                result.invokeOnCancellation { Handler(Looper.getMainLooper()).post { finish("{}") } }
                bound = runCatching { app.bindService(Intent(app, LocationService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
                if (!bound) finish("{}")
            }
        }
        runCatching { JSONObject(text ?: "{}") }.getOrDefault(JSONObject())
    }
}
