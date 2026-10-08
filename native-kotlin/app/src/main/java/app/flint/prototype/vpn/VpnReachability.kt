package app.flint.prototype.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.Process
import app.flint.prototype.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Require a response carried by this app's VPN network, not Android's fallback network. */
object VpnReachability {
    fun vpnNetworks(context: Context): List<Network> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.filter {
            val caps = cm.getNetworkCapabilities(it)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true && caps.ownerUid == Process.myUid()
        }
    }
    suspend fun verify(context: Context, config: JSONObject, previousNetworks: Set<Network> = emptySet()): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = withTimeoutOrNull(4000) {
            while (true) {
                vpnNetworks(context).firstOrNull { it !in previousNetworks && cm.getLinkProperties(it)?.interfaceName != null }?.let { return@withTimeoutOrNull it }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: return false
        val testUrl = if (BuildConfig.DEBUG) config.optString("flintTestProbeUrl").takeIf { it == "http://93.184.215.14:18080/android/verify" } else null
        val urls = if (testUrl != null) listOf(testUrl) else listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
        // Establishing the Android network and starting forwarding are separate
        // operations. Retry a transient startup failure on the same new VPN;
        // never fall back to Wi-Fi/mobile or accept local TUN creation as success.
        repeat(2) { attempt ->
          val ok = coroutineScope {
            urls.map { url -> async(Dispatchers.IO) {
                var c: HttpURLConnection? = null
                try {
                    c = network.openConnection(URL(url)) as HttpURLConnection
                    c.connectTimeout = 2500; c.readTimeout = 2500; c.instanceFollowRedirects = false; c.useCaches = false
                    c.setRequestProperty("Connection", "close")
                    if (testUrl != null) c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText() } == "FLINT_VPN_TUNNEL_OK"
                    else c.responseCode == 204
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.d("FlintProbe", "VPN network=$network attempt=$attempt failure=${e.javaClass.simpleName}")
                    false
                } finally { c?.disconnect() }
            } }.awaitAll().any { it }
          }
          if (ok) return true
          if (attempt == 0) delay(250)
        }
        return false
    }
}
