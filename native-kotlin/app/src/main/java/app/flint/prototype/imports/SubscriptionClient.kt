package app.flint.prototype.imports

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.util.zip.GZIPInputStream

/** Blocking API: callers must use a worker, never the Android main thread. */
class SubscriptionClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val totalTimeoutMs: Int = 30_000,
) {
    fun import(input: String): ImportResult {
        val value = input.trim()
        return if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
            SubscriptionParser.parse(fetch(value))
        } else SubscriptionParser.parse(value)
    }

    fun fetch(address: String): String {
        val deadline = System.nanoTime() + totalTimeoutMs.toLong() * 1_000_000
        var url = safeUrl(address)
        try {
            repeat(6) { attempt ->
                checkDeadline(deadline)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1).toInt()
                    connection.connectTimeout = minOf(connectTimeoutMs, remaining)
                    connection.readTimeout = minOf(readTimeoutMs, remaining)
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "Flint-Kotlin-Prototype/0.1")
                    connection.setRequestProperty("Accept", "text/plain, application/json, */*")
                    connection.setRequestProperty("Accept-Encoding", "gzip")
                    when (val status = connection.responseCode) {
                        in setOf(301, 302, 303, 307, 308) -> {
                            if (attempt == 5) throw ImportException("Слишком много перенаправлений подписки")
                            val location = connection.getHeaderField("Location") ?: throw ImportException("Пустое перенаправление подписки")
                            val next = safeUrl(URL(url, location).toExternalForm())
                            if (url.protocol == "https" && next.protocol != "https")
                                throw ImportException("Сервер перенаправляет подписку на незащищённое соединение")
                            url = next
                        }
                        in 200..299 -> {
                            if (connection.contentLengthLong > SubscriptionParser.MAX_BYTES)
                                throw ImportException("Подписка слишком большая")
                            val body: InputStream = if (connection.getHeaderField("Content-Encoding")?.equals("gzip", true) == true)
                                GZIPInputStream(connection.inputStream) else connection.inputStream
                            return body.use { readLimited(it, deadline) }
                        }
                        401, 403 -> throw ImportException("Нет доступа к подписке. Проверьте ссылку или срок действия")
                        404, 410 -> throw ImportException("Ссылка подписки больше недоступна. Получите новую ссылку в аккаунте")
                        else -> throw ImportException("Сервер подписки вернул ошибку HTTP $status")
                    }
                } finally { connection.disconnect() }
            }
        } catch (error: ImportException) { throw error }
        catch (_: SocketTimeoutException) { throw ImportException("Сервер подписки не ответил вовремя") }
        catch (_: Exception) { throw ImportException("Не удалось загрузить подписку. Проверьте сеть и ссылку") }
        throw ImportException("Не удалось загрузить подписку")
    }

    private fun safeUrl(value: String): URL {
        val uri = try { URI(value) } catch (_: Exception) { throw ImportException("Некорректный адрес подписки") }
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null)
            throw ImportException("Нужна HTTP или HTTPS ссылка подписки")
        return try { uri.toURL() } catch (_: Exception) { throw ImportException("Некорректный адрес подписки") }
    }

    private fun checkDeadline(deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw ImportException("Импорт отменён")
        if (System.nanoTime() >= deadline) throw ImportException("Превышено время загрузки подписки")
    }

    private fun readLimited(input: InputStream, deadline: Long): String {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkDeadline(deadline)
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > SubscriptionParser.MAX_BYTES) throw ImportException("Подписка слишком большая")
            result.write(buffer, 0, count)
        }
        return result.toByteArray().toString(Charsets.UTF_8)
    }
}
