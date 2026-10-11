package app.flint.prototype.ui

import android.app.Activity
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import app.flint.prototype.routing.DirectApps
import kotlinx.coroutines.*

class DirectAppsScreen(private val activity: Activity, private val prefs: SharedPreferences,
                       private val scope: CoroutineScope, private val enabled: () -> Boolean,
                       private val edited: () -> Unit) {
    fun show() {
        val s = FlintStyle(activity); val p = s.panel("Приложения напрямую", maxWidth = 500)
        s.add(p.body, s.label("Отмеченные приложения целиком работают без VPN, включая карты, вход и оплату. Остальные используют правила сайтов.", 13f, color = s.muted), gap = 0)
        val automatic = prefs.getBoolean("automaticRouting", true)
        s.add(p.body, s.label(if (!enabled()) "Сейчас «Сайты РФ» выключены. Выбор применится после включения."
            else if (automatic) "Известные российские приложения отмечены автоматически. Вы можете изменить выбор."
            else "Автоматический выбор выключен. Отметьте нужные приложения вручную.", 13f, color = s.muted))
        val search = s.field("Поиск приложения"); s.add(p.body, search, 48)
        val count = s.label("Загрузка приложений…", 12f, color = s.muted); s.add(p.body, count)
        val list = s.column(); s.add(p.body, list)
        val more = s.button("Показать ещё") {}; s.add(p.body, more, 48)
        val choices = DirectApps.overrides(prefs).toMutableMap()
        var apps = emptyList<DirectApps.Installed>(); var limit = 50
        fun chosen(name: String) = choices[name] ?: (automatic && name in DirectApps.catalog)
        fun draw() {
            val query = search.text.toString().trim()
            val found = apps.filter { it.title.contains(query, true) || it.packageName.contains(query, true) }
            list.removeAllViews()
            found.take(limit).forEach { app ->
                val check = CheckBox(activity).apply {
                    text = app.title; textSize = 14f; setTextColor(s.ink); minHeight = s.dp(52)
                    maxLines = 2; buttonTintList = ColorStateList.valueOf(s.mint)
                    isChecked = chosen(app.packageName)
                    contentDescription = "${app.title}: напрямую"
                    setOnCheckedChangeListener { _, value ->
                        if (value == (automatic && app.packageName in DirectApps.catalog)) choices.remove(app.packageName)
                        else choices[app.packageName] = value
                        count.text = "Напрямую: ${apps.count { chosen(it.packageName) }} · Найдено: ${found.size}"
                    }
                }
                s.add(list, check, gap = 4)
            }
            count.text = "Напрямую: ${apps.count { chosen(it.packageName) }} · Найдено: ${found.size}"
            more.visibility = if (found.size > limit) View.VISIBLE else View.GONE
        }
        more.setOnClickListener { limit += 50; draw() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(t: CharSequence?, st: Int, b: Int, c: Int) { limit = 50; draw() }
            override fun afterTextChanged(e: Editable?) {}
        })
        val save = s.primary("Сохранить") {
            if (DirectApps.save(prefs, choices)) { p.dialog.dismiss(); edited() }
            else p.error("Не удалось сохранить выбор. Попробуйте ещё раз.")
        }
        s.add(p.footer, save, 48)
        save.isEnabled = false
        val job = scope.launch {
            try {
                apps = withContext(Dispatchers.IO) { DirectApps.installed(activity) }
                    .sortedWith(compareByDescending<DirectApps.Installed> { chosen(it.packageName) }.thenBy { it.title.lowercase() })
                if (p.dialog.isShowing) { draw(); save.isEnabled = true }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { p.error("Не удалось получить список приложений.") }
        }
        p.dialog.setOnDismissListener { job.cancel() }
    }
}
