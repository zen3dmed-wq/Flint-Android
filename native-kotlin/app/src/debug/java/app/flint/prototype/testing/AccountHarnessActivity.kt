package app.flint.prototype.testing

import android.app.Activity
import android.os.Bundle
import app.flint.prototype.account.*
import app.flint.prototype.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Fake backend is restricted to debug builds. No production account requests. */
class AccountHarnessActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var screens: AccountScreens
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val api = FlintAccount(this) { method, path, _, _, _ ->
            val data = when {
                path == "/auth/login" -> """{"accessToken":"test-only-access","refreshToken":"test-only-refresh"}"""
                path == "/me" -> """{"id":"ui-fixture","email":"test@example.invalid","hasPassword":true}"""
                path == "/subscriptions" -> """{"items":[{"id":"fixture-sub","status":"active","plan":{"name":"12 Месяцев"},"expiresAt":"2027-09-17T11:04:00Z","traffic":{"usedBytes":199600000000,"limitBytes":1000000000000,"updatedAt":"2026-10-08T07:00:00Z"}}]}"""
                path == "/config" -> """{"purchasesEnabled":true}"""
                path == "/plans" -> """{"items":[{"id":"1","name":"1 Месяц","durationDays":30,"price":{"amount":120,"currency":"RUB"}},{"id":"3","name":"3 Месяца","durationDays":90,"price":{"amount":340,"currency":"RUB"}},{"id":"6","name":"6 Месяцев","durationDays":180,"price":{"amount":680,"currency":"RUB"}},{"id":"12","name":"12 Месяцев","durationDays":360,"price":{"amount":1300,"currency":"RUB"}}]}"""
                path == "/payment-methods" -> """{"items":[{"id":"fixture-card","title":"Карта, СБП, крипта"}]}"""
                path == "/referrals" -> """{"code":"FLINT-TEST","invitedCount":3,"bonusDays":12,"terms":{"bonusPercent":10,"minPurchaseDays":30,"maxBonusDays":90},"referrer":{}}"""
                path == "/me/sessions" -> """{"items":[{"id":"current","model":"Samsung Android","platform":"android","isCurrent":true,"appVersion":"0.2.0"},{"id":"other","model":"Android TV","platform":"android-tv","isCurrent":false,"appVersion":"0.2.0"}]}"""
                method == "GET" && path.endsWith("/devices") -> throw ApiError(404,"fixture","Unavailable in fixture")
                else -> error("Unexpected fake API call: $method $path")
            }
            ApiReply(200, JSONObject(data))
        }
        val home = FlintHomeView(this, false, object : FlintUiCallbacks {
            override fun onConnectToggle() {}
            override fun onSelectServer(id: String?) {}
            override fun onImportClipboard() {}
            override fun onImportQrImage() {}
            override fun onImportText() {}
            override fun onImportFile() {}
            override fun onRuDirectChanged(enabled: Boolean) {}
        })
        setContentView(home); home.render(UiHarnessActivity.fixture())
        screens = AccountScreens(this, api, scope) {}
        scope.launch {
            api.login("test@example.invalid", "test-only", false); api.publicConfig()
            when(intent.getStringExtra("screen")) {
                "purchase" -> screens.purchase()
                "friends" -> screens.friends()
                "devices" -> screens.devices()
                else -> screens.subscriptions()
            }
        }
    }
    override fun onDestroy() {
        if (::screens.isInitialized) screens.close(); scope.cancel()
        getSharedPreferences("flint-account", MODE_PRIVATE).edit().clear().commit()
        getSharedPreferences("flint-token-vault", MODE_PRIVATE).edit().clear().commit()
        super.onDestroy()
    }
}
