package app.flint.prototype.updates

import android.app.Activity
import android.os.Build
import app.flint.prototype.BuildConfig
import app.flint.prototype.account.*
import app.flint.prototype.ui.FlintStyle
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class UpdateScreen(private val activity: Activity, private val api: FlintAccount, private val scope: CoroutineScope) {
    fun show() {
        val s = FlintStyle(activity); val p = s.panel("Обновление приложения")
        s.add(p.body, s.label("Установлена версия ${BuildConfig.VERSION_NAME}", 16f, true))
        if (!api.loggedIn) { p.error("Войдите во Flint для проверки обновлений."); return }
        scope.launch {
            try {
                p.message.text = "Проверяем обновления…"
                val target = if (BuildConfig.IS_TV) "tv" else "phone"
                val data = api.request("GET", "/app/update?platform=android&version=${BuildConfig.VERSION_NAME.substringBefore('-')}&distribution=$target&arch=${Build.SUPPORTED_ABIS.first()}").data
                val latest = data.optJSONObject("latest")
                if (!data.optBoolean("updateAvailable") || latest == null) { p.message.text = "Установлена актуальная версия"; return@launch }
                s.add(p.body, s.label("Доступна версия ${latest.string("version")}", 19f, true, s.mint))
                s.add(p.body, s.label(latest.string("notes"), color = s.muted))
                s.add(p.body, s.label("Тестовая Kotlin-сборка устанавливается отдельно. Установщик проверит, что обновление предназначено именно для неё.", 12f, color = s.muted))
                val file = File(activity.cacheDir, "flint-updates/update.apk")
                val download = s.button("Скачать обновление") {}
                s.add(p.body, download, 50)
                download.setOnClickListener {
                    if (p.busy) return@setOnClickListener
                    p.busy = true; download.isEnabled = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { fetch(latest, file) { percent -> activity.runOnUiThread { p.message.text = "Скачивание: $percent%" } } }
                            p.message.text = "Скачано и проверено"
                            download.text = "Установить"
                            download.setOnClickListener { scope.launch {
                                val error = withContext(Dispatchers.IO) { FlintUpdateInstaller.install(activity, file.path, latest.string("sha256"), latest.optLong("versionCode")) }
                                p.message.text = error.ifBlank { "Открыт установщик Android" }
                            } }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { p.error(e.message ?: "Не удалось скачать обновление") }
                        finally { p.busy = false; download.isEnabled = true }
                    }
                }
                p.message.text = ""
            } catch (e: CancellationException) { throw e }
            catch (e: ApiError) { p.error(e.message.orEmpty()) }
            catch (_: Exception) { p.error("Не удалось проверить обновления") }
        }
    }
    private fun fetch(metadata: JSONObject, target: File, progress: (Int) -> Unit) {
        val expected = metadata.string("sha256").lowercase()
        require(expected.matches(Regex("[a-f0-9]{64}"))) { "Сервер не предоставил контрольную сумму APK." }
        val size = metadata.optLong("size")
        require(size in 1..250_000_000) { "Некорректный размер обновления." }
        var uri = URI(metadata.string("url")); target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "update.part")
        try {
            repeat(5) {
                require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Небезопасная ссылка обновления." }
                val c = uri.toURL().openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 15_000; c.readTimeout = 20_000; c.instanceFollowRedirects = false
                    if (c.responseCode in setOf(301, 302, 303, 307, 308)) { uri = uri.resolve(c.getHeaderField("Location")); return@repeat }
                    require(c.responseCode == 200) { "Ссылка скачивания истекла или недоступна. Откройте окно обновления заново." }
                    val digest = MessageDigest.getInstance("SHA-256"); var total = 0L; var reported = -1
                    partial.outputStream().buffered().use { output -> c.inputStream.buffered().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            total += count; require(total <= size) { "Размер файла не совпадает с данными сервера." }
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                            val percent = (100 * total / size).toInt(); if (percent != reported) { reported = percent; progress(percent) }
                        }
                    } }
                    require(total == size && digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Файл повреждён. Повторите скачивание." }
                    if (target.exists()) check(target.delete())
                    check(partial.renameTo(target)); return
                } finally { c.disconnect() }
            }
            error("Слишком много перенаправлений скачивания.")
        } finally { partial.delete() }
    }
}
