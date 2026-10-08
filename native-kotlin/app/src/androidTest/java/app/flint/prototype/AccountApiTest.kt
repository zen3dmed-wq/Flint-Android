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
    @Test fun locationsUseBearerRefreshAndExposeRealPercentages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokens = mutableListOf<String?>()
        val now = System.currentTimeMillis()
        val api = FlintAccount(context) { _, path, _, token, _ -> when (path) {
            "/auth/login" -> ApiReply(200, JSONObject("""{"accessToken":"old","refreshToken":"refresh"}"""))
            "/auth/refresh" -> ApiReply(200, JSONObject("""{"accessToken":"new","refreshToken":"refresh-new"}"""))
            "/me" -> ApiReply(200, JSONObject("""{"id":"load-fixture"}"""))
            "/subscriptions" -> ApiReply(200, JSONObject("""{"items":[]}"""))
            "/locations" -> {
                tokens.add(token)
                if (token == "old") throw ApiError(401,"expired","expired")
                assertEquals("new", token)
                ApiReply(200, JSONObject().put("loadUpdatedAt",java.time.Instant.ofEpochMilli(now).toString())
                    .put("items", org.json.JSONArray("""[{"id":"node","addresses":["vpn.example:443"],"load":37}]""")))
            }
            "/auth/logout" -> ApiReply(204, JSONObject())
            else -> error("Unexpected endpoint $path")
        } }
        try {
            api.login("test@example.invalid","fixture",false)
            val data = api.refreshLocations()
            val profile = app.flint.prototype.imports.ServerProfile("a","Server","vpn.example",443,"vless","{}")
            assertEquals(37, app.flint.prototype.vpn.ServerBalance.load(data,profile,now)?.first)
            assertEquals(listOf("old","new"), tokens)
            api.refreshLocations(); assertEquals(2,tokens.size)
            api.logout(); assertEquals(0,api.refreshLocations().length())
        } finally {
            context.getSharedPreferences("flint-account",0).edit().clear().commit()
            context.getSharedPreferences("flint-token-vault",0).edit().clear().commit()
        }
    }

    @Test fun locationBridgeWithoutLoginReturnsNoInventedData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("flint-token-vault",0).edit().clear().commit()
        assertEquals(0, kotlinx.coroutines.withTimeout(8000) { LocationClient.fetch(context) }.length())
    }

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
