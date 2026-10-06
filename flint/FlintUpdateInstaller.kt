package org.amnezia.vpn

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object FlintUpdateInstaller {
    private fun open(activity: Activity, intent: Intent) {
        activity.runOnUiThread {
            try { activity.startActivity(intent) }
            catch (_: Exception) { Toast.makeText(activity, "Не удалось открыть установку Android.", Toast.LENGTH_LONG).show() }
        }
    }
    @JvmStatic
    fun install(activity: Activity, path: String, expectedHash: String, expectedCode: Long): String {
        try {
            val file = File(path).canonicalFile
            val directory = File(activity.cacheDir, "flint-updates").canonicalFile
            if (file.parentFile != directory || file.name != "update.apk" || !file.isFile)
                return "Файл обновления не найден. Скачайте его ещё раз."
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(65536)
                while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
            }
            if (hash.digest().joinToString("") { "%02x".format(it) } != expectedHash.lowercase())
                return "Файл изменился после скачивания. Скачайте обновление ещё раз."
            val pm = activity.packageManager
            val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return "Android не распознал файл обновления."
            val current = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            if (archive.packageName != activity.packageName) return "Этот APK предназначен для другого приложения."
            if (archive.longVersionCode != expectedCode || archive.longVersionCode <= current.longVersionCode)
                return "Эта сборка не новее установленной версии."
            val installedKeys = current.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            val updateKeys = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            if (installedKeys.isNullOrEmpty() || installedKeys != updateKeys)
                return "Подпись обновления не совпадает с подписью установленного Flint."
            if ((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT)
                return "Для этой сборки нужна более новая версия Android."
            val abis = ZipFile(file).use { zip ->
                zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .map { it.split('/')[1] }.toSet()
            }
            if (abis.isEmpty() || Build.SUPPORTED_ABIS.none { it in abis })
                return "Сборка не поддерживает процессор этого устройства. Нужна совместимая или универсальная сборка."
            if (!pm.canRequestPackageInstalls()) {
                val permission = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                open(activity, permission)
                return "Разрешите установку обновлений для Flint, вернитесь и нажмите «Установить» ещё раз."
            }
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("Flint update", uri)
            open(activity, intent)
            return ""
        } catch (_: Exception) {
            return "Не удалось проверить или открыть обновление. Повторите скачивание."
        }
    }
}
