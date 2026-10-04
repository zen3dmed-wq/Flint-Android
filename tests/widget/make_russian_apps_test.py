"""Execute the patched Protocol.configAppSplitTunneling with inert Android boundaries."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
source = (root/'client/android/protocolApi/src/main/kotlin/Protocol.kt').read_text(encoding='utf-8')
start = source.index('    protected fun ProtocolConfig.Builder.configAppSplitTunneling(')
end = source.index('    protected open fun buildVpnInterface(', start)
method = source[start:end]
assert 'FlintRussianApps.apply' in method
manifest = ET.parse(root/'client/android/AndroidManifest.xml').getroot()
queries = {e.attrib['{http://schemas.android.com/apk/res/android}name'] for e in manifest.findall('queries/package')}
assert {'ru.yandex.yandexmaps','ru.yandex.yandexnavi'} <= queries
cpp = (root/'client/vpnConnection.cpp').read_text(encoding='utf-8')
assert '''m_vpnConfiguration.insert("flintRussianAppsDirect",
        m_appSettingsRepository->isSitesSplitTunnelingEnabled() &&
        m_appSettingsRepository->routeMode() == amnezia::RouteMode::VpnAllExceptSites &&
        m_appSettingsRepository->flintAutomaticRouting());''' in cpp
(out/'PackageManager.kt').write_text('''package android.content.pm
class PackageManager(var installed: Set<String> = emptySet()) {
    class NameNotFoundException: Exception()
    fun getApplicationInfo(name: String, flags: Int): Any {
        if (name !in installed) throw NameNotFoundException()
        return Any()
    }
}
''', encoding='utf-8')
code = '''package org.amnezia.vpn.protocol
import android.content.pm.PackageManager
const val SPLIT_TUNNEL_DISABLE=0
const val SPLIT_TUNNEL_INCLUDE=1
const val SPLIT_TUNNEL_EXCLUDE=2
class BadConfigException(message: String): Exception(message)
class JSONArray(private val items: List<String>) {
    fun length()=items.size
    fun getString(i: Int)=items[i]
}
class JSONObject(val type: Int=0, val apps: List<String>?=null, val direct: Boolean=false) {
    fun optInt(key: String)=type
    fun optBoolean(key: String, fallback: Boolean)=direct
    fun getJSONArray(key: String)=JSONArray(checkNotNull(apps))
}
class ProtocolConfig {
    class Builder {
        val includedApplications=linkedSetOf<String>()
        val excludedApplications=linkedSetOf<String>()
        fun includeApplication(s: String) { includedApplications.add(s) }
        fun excludeApplication(s: String) { excludedApplications.add(s) }
    }
}
class Context {
    val packageName="app.flint.vpn"
    val packageManager=PackageManager()
}
open class Fixture {
    val context=Context()
'''+method+'''
    fun run(config: JSONObject): ProtocolConfig.Builder = ProtocolConfig.Builder().apply { configAppSplitTunneling(config) }
}
fun main() {
    val maps="ru.yandex.yandexmaps"; val navi="ru.yandex.yandexnavi"; val browser="org.example.browser"
    val f=Fixture(); f.context.packageManager.installed=setOf(maps,navi,browser)
    var cases=0
    fun verify(c: JSONObject, allow: Set<String>, deny: Set<String>) {
        val b=f.run(c)
        check(b.includedApplications==allow)
        check(b.excludedApplications==deny)
        cases++
    }
    // Type 0 omits splitTunnelApps entirely; the old early return must not skip RF routing.
    verify(JSONObject(direct=true), emptySet(),setOf(maps,navi))
    verify(JSONObject(),emptySet(),emptySet()) // Legacy saved config remains unchanged.
    verify(JSONObject(2,listOf(browser),true),emptySet(),setOf(browser,maps,navi))
    verify(JSONObject(1,listOf(browser,maps,navi),true),setOf(browser),emptySet())
    verify(JSONObject(1,listOf(maps),true),setOf("app.flint.vpn"),emptySet())
    verify(JSONObject(1,listOf(browser,maps),false),setOf(browser,maps),emptySet())
    verify(JSONObject(2,listOf(browser),false),emptySet(),setOf(browser))
    f.context.packageManager.installed=setOf(maps)
    verify(JSONObject(direct=true),emptySet(),setOf(maps))
    f.context.packageManager.installed=emptySet()
    verify(JSONObject(direct=true),emptySet(),emptySet())
    try { f.run(JSONObject(3,emptyList(),true)); error("Invalid mode accepted") }
    catch (_: BadConfigException) { cases++ }
    println("Actual Protocol routing method: $cases cases passed")
}
'''
(out/'ProtocolRoutingTest.kt').write_text(code, encoding='utf-8')
print('Generated real Protocol routing harness; native config flag and manifest verified')
