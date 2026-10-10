package app.flint.prototype

import androidx.test.platform.app.InstrumentationRegistry
import app.flint.prototype.data.ProfileStore
import app.flint.prototype.imports.ServerProfile
import app.flint.prototype.routing.DirectApps
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRefreshTest {
    @Test fun refreshReplacesRemovedNodesAndKeepsOtherSubscriptions() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=ProfileStore(context)
        fun server(name:String)=ServerProfile(ServerProfile.stableId("refresh-test-$name"),name,"example.invalid",443,"vless","""{"protocol":"vless"}""")
        val old=server("old");val shared=server("shared");val new=server("new")
        val manual=server("manual")
        val first="https://fixture.invalid/sub/refresh-one";val second="https://fixture.invalid/sub/refresh-two"
        store.merge(listOf(manual))
        store.merge(listOf(old,shared),first)
        store.merge(listOf(shared),second)
        val result=ProfileStore(context).merge(listOf(new),first)
        assertFalse(result.any {it.id==old.id})
        assertTrue(result.any {it.id==new.id})
        assertTrue(result.any {it.id==shared.id})
        assertTrue(result.any {it.id==manual.id})
        assertTrue(ProfileStore(context).sources().containsAll(listOf(first,second)))
        store.merge(emptyList(),first);store.merge(emptyList(),second)
    }
    @Test fun lockdownRoutesAllAppsInsideTunnelInsteadOfExcludingThem() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val config=JSONObject().put("flintRuDirect",true).put("flintRussianAppsDirect",true)
            .put("appSplitTunnelType",2).put("splitTunnelApps",JSONArray(listOf("ru.rostel")))
        DirectApps.applyInstalled(context,config,lockdown=true)
        assertEquals(0,config.getInt("appSplitTunnelType"))
        assertEquals(0,config.getJSONArray("splitTunnelApps").length())
        assertTrue(config.getBoolean("flintRuDirect"))
    }
}
