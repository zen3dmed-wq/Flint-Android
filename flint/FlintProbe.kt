package org.amnezia.vpn

import android.content.Context
import android.net.Uri
import org.amnezia.vpn.protocol.xray.libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Runs in the app process. The actual VPN core lives in :amneziaXrayService.
 * No TUN adapter, routing edits, account tokens or TLS exceptions are involved. */
object FlintProbe {
    @JvmStatic @Synchronized fun probe(context: Context, text: String, fingerprint: String): Long {
        val uri = Uri.parse(text)
        if (uri.scheme != "vless" || uri.getQueryParameter("security") !in listOf("reality", "tls")) return -2
        var configFile: File? = null
        var connection: HttpsURLConnection? = null
        try {
            val port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val network = uri.getQueryParameter("type") ?: "tcp"
            val security = uri.getQueryParameter("security")!!
            val user = JSONObject().put("id", uri.userInfo).put("encryption", "none")
            uri.getQueryParameter("flow")?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
            val tls = JSONObject().put("serverName", uri.getQueryParameter("sni") ?: uri.host)
                .put("fingerprint", fingerprint).put("allowInsecure", false)
            if (security == "reality") tls.put("publicKey", uri.getQueryParameter("pbk"))
                .put("shortId", uri.getQueryParameter("sid") ?: "").put("spiderX", uri.getQueryParameter("spx") ?: "/")
            val stream = JSONObject().put("network", network).put("security", security).put(security+"Settings", tls)
            when (network) {
                "ws", "httpupgrade" -> stream.put(network+"Settings", JSONObject().put("path",uri.getQueryParameter("path") ?: "/").put("headers",JSONObject().put("Host",uri.getQueryParameter("host") ?: uri.host)))
                "grpc" -> stream.put("grpcSettings",JSONObject().put("serviceName",uri.getQueryParameter("serviceName") ?: "").put("multiMode",uri.getQueryParameter("mode")=="multi"))
                "xhttp" -> stream.put("xhttpSettings",JSONObject().put("path",uri.getQueryParameter("path") ?: "/").put("host",uri.getQueryParameter("host") ?: "").put("mode",uri.getQueryParameter("mode") ?: "auto"))
                "tcp", "raw" -> {}
                else -> return -2
            }
            val outbound=JSONObject().put("protocol","vless").put("streamSettings",stream).put("settings",JSONObject().put("vnext",JSONArray().put(JSONObject().put("address",uri.host).put("port",if(uri.port>0)uri.port else 443).put("users",JSONArray().put(user)))))
            val config=JSONObject().put("log",JSONObject().put("loglevel","none"))
                .put("inbounds",JSONArray().put(JSONObject().put("listen","127.0.0.1").put("port",port).put("protocol","socks").put("settings",JSONObject().put("auth","noauth").put("udp",false))))
                .put("outbounds",JSONArray().put(outbound))
            configFile=File.createTempFile("flint-probe-", ".json", context.cacheDir)
            configFile.writeText(config.toString())
            val dir=context.getDir("flint-probe",Context.MODE_PRIVATE).absolutePath
            LibXray.initXray(dir)
            val error=LibXray.runXray(dir,configFile.absolutePath,64L*1024*1024)
            if(!error.isNullOrBlank()) return -1
            var ready=false
            for(i in 0..10) {try { Socket().use { it.connect(InetSocketAddress("127.0.0.1",port),100) };ready=true;break }catch(_:Exception){Thread.sleep(50)} }
            if(!ready) return -1
            val start=System.nanoTime()
            connection=URL("https://cp.cloudflare.com/generate_204").openConnection(Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port))) as HttpsURLConnection
            connection.connectTimeout=2500;connection.readTimeout=2500;connection.instanceFollowRedirects=false
            return if(connection.responseCode==204) maxOf(1,(System.nanoTime()-start)/1000000) else -1
        } catch(_:Exception) { return -1 }
        finally {connection?.disconnect();try{LibXray.stopXray()}catch(_:Exception){};configFile?.delete()}
    }
}
