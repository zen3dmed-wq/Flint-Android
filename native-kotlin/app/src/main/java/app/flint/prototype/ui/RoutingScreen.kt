package app.flint.prototype.ui

import android.app.Activity
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import app.flint.prototype.imports.XrayConfigBuilder
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Same starter list as Qt build_flint.py, initialized once without erasing edits. */
object DirectSites {
    val defaults = listOf("zakupki.gov.ru", "lk.zakupki.gov.ru", "eruz.zakupki.gov.ru",
        "gosuslugi.ru", "esia.gosuslugi.ru", "nalog.gov.ru", "roskazna.gov.ru",
        "tbank.ru", "sberbank.ru", "vtb.ru", "alfabank.ru", "yandex.ru", "vk.com", "mail.ru",
        "ozon.ru", "wildberries.ru", "wb.ru", "2gis.ru", "rzd.ru", "avito.ru")
    fun read(prefs: SharedPreferences): List<String> {
        val sites = runCatching { JSONArray(prefs.getString("directSites", "[]"))
            .let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        if (prefs.getBoolean("qtDirectSitesInitialized", false)) return sites
        val initial = (sites + defaults).distinct()
        prefs.edit().putString("directSites", JSONArray(initial).toString()).putBoolean("qtDirectSitesInitialized", true).commit()
        return initial
    }
    fun save(prefs: SharedPreferences, sites: List<String>) {
        prefs.edit().putString("directSites", JSONArray(sites.distinct()).toString()).putBoolean("qtDirectSitesInitialized", true).apply()
    }
}

class RoutingScreen(private val activity: Activity, private val prefs: SharedPreferences,
                    private val scope: CoroutineScope, private val policy: () -> String?,
                    private val enabled: () -> Boolean, private val setEnabled: (Boolean) -> Unit,
                    private val edited: () -> Unit) {
    private val s = FlintStyle(activity)
    fun show() {
        val p = s.panel("Раздельное проксирование", maxWidth = 500, showClose = false)
        val summary = s.button("") { showAutomaticRules() }.apply {
            setTextColor(s.mint); textSize = 12f; gravity = android.view.Gravity.CENTER_VERTICAL
            background = null; setPadding(0, 0, 0, 0)
            contentDescription = "Посмотреть автоматические правила"
        }; s.add(p.body, summary, 44, 0)
        s.add(p.body, s.label("Автоматически: российские сервисы, Яндекс Карты, Навигатор и локальная сеть — напрямую, остальные сайты — через VPN. Правила обновляются из конфигурации.", 13f, color = s.muted), gap = 0)
        fun toggle(label: String, value: Boolean): Switch = Switch(activity).apply { text = label; textSize = 14f; setTextColor(s.ink); isChecked = value; minHeight = s.dp(48) }
        val direct = toggle("Разделять трафик", enabled()); s.add(p.body, direct, 48)
        val automatic = toggle("Определять маршрут автоматически", prefs.getBoolean("automaticRouting", true)); s.add(p.body, automatic, 56)
        val explanation = s.label("", 13f, color = s.muted); s.add(p.body, explanation)
        fun updateMode() {
            automatic.isEnabled = direct.isChecked
            explanation.text = if (!direct.isChecked) "Выключено: весь интернет-трафик идёт через VPN."
                else if (automatic.isChecked) "Ваши сайты ниже дополняют автоматические правила."
                else "Вручную: только сайты ниже идут напрямую, остальные — через VPN."
            summary.text = if (!automatic.isChecked) "Вручную: напрямую идут только добавленные сайты"
                else if (policy() == null) "Встроенные правила: geosite:category-ru · Подробнее ›"
                else "Правила из конфигурации Flint · Подробнее ›"
        }
        fun saved() { p.message.text = "Сохранено. Изменения применяются к подключению."; edited() }
        direct.setOnCheckedChangeListener { _, value -> setEnabled(value); updateMode() }
        automatic.setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean("automaticRouting", value).apply(); updateMode(); saved() }
        updateMode()
        val field = s.field("example.ru или https://…"); s.add(p.body, field, 50)
        val sites = s.column()
        fun draw() {
            sites.removeAllViews()
            DirectSites.read(prefs).forEach { site ->
                val row = s.row().apply { background = s.shape(); setPadding(s.dp(10), s.dp(10), s.dp(10), s.dp(10)) }
                row.addView(s.label(site, 13f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.dp(8) })
                row.addView(s.button("Удалить") { DirectSites.save(prefs, DirectSites.read(prefs).filter { it != site }); draw(); saved() }.apply { contentDescription = "Удалить $site" }, LinearLayout.LayoutParams(s.dp(90), s.dp(46)))
                s.add(sites, row)
            }
            if (sites.childCount == 0) s.add(sites, s.label("Добавьте первый сайт", color = s.muted))
        }
        s.add(p.body, s.primary("Добавить сайт") {
            try {
                val site = XrayConfigBuilder.normalizeSite(field.text.toString())
                val current = DirectSites.read(prefs)
                if (site in current) { p.error("Сайт уже есть в списке"); return@primary }
                DirectSites.save(prefs, current + site); field.text.clear(); draw(); saved()
            } catch (e: Exception) { p.error(e.message ?: "Укажите домен, IP-адрес или подсеть") }
        }, 48)
        s.add(p.body, sites); draw(); s.closeButton(p)
    }
    private fun showAutomaticRules() {
        val p = s.panel("Автоматические правила", maxWidth = 500)
        val search = s.field("Поиск сайта или подсети"); s.add(p.body, search, 48)
        val counter = s.label("Загрузка правил…", 12f, color = s.muted); s.add(p.body, counter)
        val list = s.column(); s.add(p.body, list)
        var rows = emptyList<String>(); var count = 60
        val more = s.button("Показать ещё") {}; s.add(p.body, more, 48)
        fun draw() {
            val query = search.text.toString().trim()
            val filtered = rows.filter { it.contains(query, true) }
            list.removeAllViews()
            filtered.take(count).forEach { s.add(list, s.label(it, 13f), gap = 9) }
            counter.text = "Правил: ${rows.size} · Найдено: ${filtered.size}"
            more.visibility = if (filtered.size > count) View.VISIBLE else View.GONE
        }
        more.setOnClickListener { count += 60; draw() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(t: CharSequence?, st: Int, b: Int, c: Int) { count = 60; draw() }
            override fun afterTextChanged(e: Editable?) {}
        })
        val job = scope.launch {
            try {
                val selectedPolicy = policy()
                rows = withContext(Dispatchers.IO) {
                    val catalog = JSONObject(activity.assets.open("flint-routing-catalog.json").bufferedReader().use { it.readText() })
                    val resolved = XrayConfigBuilder.russianRules(catalog, emptyList(), selectedPolicy)
                    resolved.first.map { it.removePrefix("domain:") } + resolved.second
                }
                if (p.dialog.isShowing) draw()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { p.error("Не удалось прочитать правила. Закройте окно и попробуйте снова.") }
        }
        p.dialog.setOnDismissListener { job.cancel() }; s.closeButton(p)
    }
}
