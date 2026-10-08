package app.flint.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.flint.prototype.account.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountApiTest {
    @Test fun tokenRefreshKeepsTheSameOrderKeyAndStableDeviceIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var refreshes = 0
        val orderKeys = mutableListOf<String?>()
        val api = FlintAccount(context) { _, path, body, token, key ->
            when(path) {
                "/auth/login" -> { assertTrue(body!!.getJSONObject("device").getString("deviceId").isNotBlank()); ApiReply(200, JSONObject("""{"accessToken":"first","refreshToken":"refresh-one"}""")) }
                "/auth/refresh" -> { refreshes++; ApiReply(200, JSONObject("""{"accessToken":"second","refreshToken":"refresh-two"}""")) }
                "/me" -> ApiReply(200, JSONObject("""{"id":"api-fixture","email":"test@example.invalid"}"""))
                "/subscriptions" -> ApiReply(200, JSONObject("""{"items":[]}"""))
                "/orders" -> {
                    orderKeys.add(key)
                    if (token == "first") throw ApiError(401, "expired", "Fixture token expired")
                    assertEquals("second", token); assertEquals("plan-test", body!!.getString("planId"))
                    ApiReply(200, JSONObject("""{"id":"one-order"}"""))
                }
                "/auth/logout" -> ApiReply(204, JSONObject())
                else -> error("Unexpected fake endpoint")
            }
        }
        try {
            val device = api.device().getString("deviceId")
            api.login("test@example.invalid", "fixture", false)
            val order = api.request("POST", "/orders", JSONObject().put("planId", "plan-test").put("provider", "fixture"), idempotencyKey = "same-purchase-key")
            assertEquals("one-order", order.data.getString("id"))
            assertEquals(listOf("same-purchase-key", "same-purchase-key"), orderKeys)
            assertEquals(1, refreshes)
            assertEquals(device, FlintAccount(context).device().getString("deviceId"))
            api.logout(); assertFalse(api.loggedIn)
            assertFalse(FlintAccount(context).loggedIn)
        } finally {
            context.getSharedPreferences("flint-account", 0).edit().clear().commit()
            context.getSharedPreferences("flint-token-vault", 0).edit().clear().commit()
        }
    }
}
