package app.flint.prototype.routing

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/** Exact package IDs, never an inference from the language or a ru.* prefix. */
object DirectApps {
    const val CONFIG_KEY = "flintAppRouting"
    private const val PREF_KEY = "directAppOverrides"
    val catalog = linkedMapOf(
        "ru.rostel" to "Госуслуги",
        "com.gpn.azs" to "АЗС Газпромнефть",
        "ru.yandex.yandexmaps" to "Яндекс Карты",
        "ru.yandex.yandexnavi" to "Яндекс Навигатор",
        "ru.yandex.taxi" to "Яндекс Go",
        "ru.dublgis.dgismobile" to "2ГИС",
        "ru.sberbankmobile" to "СберБанк Онлайн",
        "com.idamob.tinkoff.android" to "Т-Банк",
        "ru.vtb24.mobilebanking.android" to "ВТБ Онлайн",
        "ru.alfabank.mobile.android" to "Альфа-Банк",
        "ru.ozon.app.android" to "Ozon",
        "com.wildberries.ru" to "Wildberries",
        "com.octopod.russianpost.client.android" to "Почта России",
        "ru.fns.lkfl" to "Налоги ФЛ"
    )
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun overrides(prefs: SharedPreferences): Map<String, Boolean> = runCatching {
        val value = JSONObject(prefs.getString(PREF_KEY, "{}") ?: "{}")
        value.keys().asSequence().filter { packagePattern.matches(it) && value.opt(it) is Boolean }
            .associateWith { value.getBoolean(it) }
    }.getOrDefault(emptyMap())
    fun save(prefs: SharedPreferences, overrides: Map<String, Boolean>): Boolean =
        prefs.edit().putString(PREF_KEY, JSONObject(overrides).toString()).commit()
    fun policy(prefs: SharedPreferences) = JSONObject()
        .put("automatic", prefs.getBoolean("automaticRouting", true))
        .put("overrides", JSONObject(overrides(prefs)))

    fun selected(enabled: Boolean, automatic: Boolean, overrides: Map<String, Boolean>, ownPackage: String): Set<String> {
        if (!enabled) return emptySet()
        val result = if (automatic) catalog.keys.toMutableSet() else mutableSetOf()
        overrides.forEach { (name, direct) ->
            if (packagePattern.matches(name)) { if (direct) result.add(name) else result.remove(name) }
        }
        result.remove(ownPackage)
        return result
    }

    /** Recheck installation on every connect, including widget, recovery and failover. */
    fun applyInstalled(context: Context, config: JSONObject, lockdown: Boolean = false) {
        // Android blocks excluded applications in lockdown mode. Keep them inside
        // the VPN and let the existing Russian domain/IP rules select direct routes.
        if (lockdown) {
            config.put("appSplitTunnelType",0).put("splitTunnelApps",JSONArray()).put("flintRussianAppsDirect",false)
            return
        }
        val policy = config.optJSONObject(CONFIG_KEY) ?: if (config.optBoolean("flintRussianAppsDirect", false))
            JSONObject().put("automatic", true).put("overrides", JSONObject()).also { config.put(CONFIG_KEY, it) }
        else return
        val saved = policy.optJSONObject("overrides") ?: JSONObject()
        val choices = saved.keys().asSequence().filter { saved.opt(it) is Boolean }.associateWith { saved.getBoolean(it) }
        val enabled = config.optBoolean("flintRuDirect", config.optBoolean("flintRussianAppsDirect", false))
        val direct = selected(enabled, policy.optBoolean("automatic", true), choices, context.packageName)
            .filter { name ->
                try { context.packageManager.getApplicationInfo(name, 0).let { it.enabled && it.uid != context.applicationInfo.uid } }
                catch (_: PackageManager.NameNotFoundException) { false }
            }.sorted()
        // Use Android's per-application bypass: covers DNS, UDP and hidden CDN endpoints.
        config.put("appSplitTunnelType", if (direct.isEmpty()) 0 else 2)
            .put("splitTunnelApps", JSONArray(direct))
            .put("flintRuDirect", enabled).put("flintRussianAppsDirect", false)
    }

    data class Installed(val packageName: String, val title: String)
    fun installed(context: Context): List<Installed> {
        val pm = context.packageManager
        val names = catalog.keys.toMutableSet()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .forEach { names.add(it.activityInfo.packageName) }
        }
        return names.mapNotNull { name ->
            try {
                val info = pm.getApplicationInfo(name, 0)
                if (!info.enabled || info.uid == context.applicationInfo.uid) null
                else Installed(name, pm.getApplicationLabel(info).toString())
            } catch (_: PackageManager.NameNotFoundException) { null }
        }.sortedBy { it.title.lowercase() }
    }
}
