package app.flint.prototype.testing

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import app.flint.prototype.account.*
import app.flint.prototype.data.ProfileStore
import app.flint.prototype.imports.ServerProfile
import app.flint.prototype.pairing.PairingProtocol
import app.flint.prototype.pairing.TvPairingScreen
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only, completely synthetic API. No production login/pairing requests. */
object PairingFixture {
    val requests = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
    val saved = AtomicInteger()
    val connected = AtomicInteger()
    @Volatile var envelope: JSONObject? = null
    @Volatile var challenge = ""
    @Volatile var acknowledged = false
    @Volatile var holdAck = false
    @Volatile var ackStarted = false
    val id = PairingProtocol.secret()
    val source = "https://pairing.example.invalid/sub/test"
    val profile = ServerProfile(ServerProfile.stableId("tv-pairing-fixture"), "Test TV node", "node.example.invalid", 443,
        "vless", """{"protocol":"vless","settings":{"vnext":[{"address":"node.example.invalid","port":443,"users":[{"id":"00000000-0000-0000-0000-000000000001"}]}]}}""")
    fun reset() {requests.clear();saved.set(0);connected.set(0);envelope=null;challenge="";acknowledged=false;holdAck=false;ackStarted=false}
}
class PairingHarnessActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var screen: TvPairingScreen
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {text="Flint pairing fixture"})
        val missing = intent.getBooleanExtra("missing",false)
        val api = FlintAccount(this) { _,path,body,token,_ ->
            PairingFixture.requests.add(path to (token!=null))
            delay(25)
            when(path) {
                "/auth/login" -> ApiReply(200,JSONObject("""{"accessToken":"pairing-test-only-access","refreshToken":"pairing-test-only-refresh"}"""))
                "/me" -> ApiReply(200,JSONObject("""{"id":"pairing-test-owner","email":"test@example.invalid"}"""))
                "/subscriptions" -> ApiReply(200,JSONObject().put("items",org.json.JSONArray().put(JSONObject()
                    .put("id","test-sub").put("status","active").put("plan",JSONObject().put("name","Тестовая подписка"))
                    .put("subscriptionUrl",PairingFixture.source))))
                "/devices/pairing/start" -> {
                    if(missing) throw ApiError(404,"not_found","Not configured")
                    check(token==null);PairingFixture.challenge=body!!.getString("codeChallenge")
                    ApiReply(201,JSONObject().put("pairingId",PairingFixture.id).put("expiresInSeconds",300).put("intervalSeconds",2))
                }
                "/devices/pairing/inspect" -> {check(token!=null);ApiReply(200,JSONObject().put("device",JSONObject().put("model","Тестовый телевизор")))}
                "/devices/pairing/approve" -> {
                    check(token!=null);check(body!!.getString("subscriptionId")=="test-sub")
                    PairingFixture.envelope=body.getJSONObject("encryptedSettings");ApiReply(200,JSONObject().put("status","approved"))
                }
                "/devices/pairing/complete" -> {
                    check(token==null);check(PairingProtocol.challenge(body!!.getString("codeVerifier"))==PairingFixture.challenge)
                    PairingFixture.envelope?.let {ApiReply(200,JSONObject().put("encryptedSettings",it))} ?: ApiReply(202,JSONObject().put("status","pending"))
                }
                "/devices/pairing/ack" -> {
                    check(token==null);PairingFixture.ackStarted=true
                    if(PairingFixture.holdAck) awaitCancellation()
                    PairingFixture.acknowledged=true;ApiReply(204,JSONObject())
                }
                "/devices/pairing/cancel" -> ApiReply(204,JSONObject())
                else -> error("Unexpected fake pairing endpoint")
            }
        }
        screen=TvPairingScreen(this,api,scope,{ transfer ->
            withContext(Dispatchers.IO){ProfileStore(this@PairingHarnessActivity).merge(transfer.profiles,transfer.source)}
            check(transfer.sites==listOf("gosuslugi.ru","yandex.ru"));check(transfer.selectedId==PairingFixture.profile.id)
            PairingFixture.saved.incrementAndGet()
        },{PairingProtocol.payload(listOf(PairingFixture.profile),PairingFixture.source,"Тестовая подписка",
            listOf("gosuslugi.ru","yandex.ru"),true,null,PairingFixture.profile.id)}, {error("Fixture already signed in")}, {PairingFixture.connected.incrementAndGet()})
        val qr=intent.getStringExtra("qr")
        if(qr==null) screen.receive() else scope.launch {api.login("test@example.invalid","test-only",false);screen.scanned(qr)}
    }
    fun closePairing() {screen.close()}
    override fun onDestroy() {if(::screen.isInitialized)screen.close();scope.cancel();super.onDestroy()}
}
