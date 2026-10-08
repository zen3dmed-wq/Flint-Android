package app.flint.prototype.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.flint.prototype.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Require a response carried by this app's VPN network, not Android's fallback network. */
object VpnReachability {
    suspend fun verify(context: Context, config: JSONObject): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = withTimeoutOrNull(4000) {
            while (true) {
                cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }?.let { return@withTimeoutOrNull it }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: return false
        val testUrl = if (BuildConfig.DEBUG) config.optString("flintTestProbeUrl").takeIf { it == "http://93.184.215.14:18080/android/verify" } else null
        val urls = if (testUrl != null) listOf(testUrl) else listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
        return coroutineScope {
            urls.map { url -> async(Dispatchers.IO) {
                var c: HttpURLConnection? = null
                try {
                    c = network.openConnection(URL(url)) as HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false; c.useCaches = false
                    if (testUrl != null) c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText() } == "FLINT_VPN_TUNNEL_OK"
                    else c.responseCode == 204
                } catch (_: Exception) { false } finally { c?.disconnect() }
            } }.awaitAll().any { it }
        }
    }
}
