package app.flint.prototype.ui

import android.app.Activity
import app.flint.prototype.BuildConfig

class SettingsScreen(private val activity: Activity) {
    fun show(accountTitle: String, friends: () -> Unit, updates: () -> Unit,
             diagnostics: () -> Unit, widget: () -> Unit, account: () -> Unit,
             notifications: () -> Unit = { NotificationSettings.show(activity) }) {
        val s = FlintStyle(activity); val p = s.panel("", maxWidth = 440, showClose = false)
        fun action(label: String, callback: () -> Unit) = s.button(label) { p.dialog.dismiss(); callback() }
        s.add(p.body, action("Пригласить друга", friends), 48)
        s.add(p.body, action("Обновление приложения", updates), 48)
        s.add(p.body, s.label("Настройки Flint", 21f, true))
        s.add(p.body, s.label("Flint Android ${BuildConfig.VERSION_NAME}", color = s.muted))
        s.add(p.body, action("Диагностика подключения", diagnostics), 48)
        if (!BuildConfig.IS_TV) s.buttons(p.body, action("Добавить виджет на экран", widget),
            action("Добавить кнопку в шторку") { app.flint.prototype.home.FlintTileService.setup(activity) })
        if (!BuildConfig.IS_TV) s.add(p.body, action("Значок VPN в строке состояния", notifications), 48)
        s.add(p.body, action(accountTitle.ifBlank { "Войти во Flint" }, account), 48)
        s.closeButton(p)
    }
}
