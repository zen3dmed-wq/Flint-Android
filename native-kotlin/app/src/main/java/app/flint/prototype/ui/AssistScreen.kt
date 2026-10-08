package app.flint.prototype.ui

import android.app.Activity
import kotlin.math.min

/** The same compact Flint Assist popup, shortcuts and operator flow as Qt. */
class AssistScreen(private val activity: Activity, private val operator: () -> Unit) {
    fun show() {
        val s = FlintStyle(activity)
        val height = min(380, (activity.resources.displayMetrics.heightPixels / activity.resources.displayMetrics.density * .56f).toInt())
        val p = s.panel("Flint Assist", maxHeight = height, maxWidth = 460, showClose = false, logo = true)
        val title = s.label("Flint готов помочь", 14f, true, s.mint)
        val reply = s.label("Если соединение даст сбой — подскажу, что делать.", 14f)
        s.add(p.body, title)
        s.add(p.body, reply)
        s.add(p.footer, s.button("Написать оператору") { p.dialog.dismiss(); operator() }, 48)
        s.buttons(p.footer,
            s.button("Подключение") { title.text = "Подключение"; reply.text = "Flint использует последний рабочий профиль, даже если API или сервер подписки временно отвечает медленно." },
            s.button("Госзакупки") { title.text = "Российские сервисы"; reply.text = "zakupki.gov.ru, ЕИС и другие выбранные российские сервисы идут напрямую." })
        s.closeButton(p)
    }
}
