package app.flint.prototype.imports

import android.content.Context
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The Qt fallback pool is used only for HTTPS subscription downloads, never account traffic. */
object FlintSubscriptionImport {
    fun import(context: Context, text: String): ImportResult {
        var original: ImportException
        try { return SubscriptionClient(4000, 5000, 6000).import(text) } catch (e: ImportException) { original = e }
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: throw original
        val host = uri.host ?: throw original
        if (uri.scheme != "https" || uri.userInfo != null || host.equals("localhost", true) || host.endsWith(".local") || '.' !in host) throw original
        val resolved = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        if (resolved.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }) throw original
        val catalog = context.assets.open("flint-import-proxies.json").bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("proxies")
        val proxies = (0 until minOf(catalog.length(), 20)).mapNotNull { i -> runCatching {
            val p = URI(catalog.getString(i)); require(p.scheme == "http" && p.userInfo == null && p.port in 1..65535)
            val ip = InetAddress.getByName(p.host); require(!ip.isLoopbackAddress && !ip.isSiteLocalAddress && !ip.isLinkLocalAddress && !ip.isAnyLocalAddress)
            Proxy(Proxy.Type.HTTP, InetSocketAddress(ip, p.port))
        }.getOrNull() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = ExecutorCompletionService<ImportResult?>(executor)
            proxies.forEach { proxy -> work.submit(Callable { try { SubscriptionClient(3500, 4500, 5500, proxy).import(text) } catch (_: ImportException) { null } }) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(32)
            repeat(proxies.size) {
                val remaining = deadline - System.nanoTime(); if (remaining <= 0) throw original
                work.poll(remaining, TimeUnit.NANOSECONDS)?.get()?.let { return it }
            }
            throw original
        } finally { executor.shutdownNow() }
    }
}
