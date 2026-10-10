package app.flint.prototype.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings

object VpnProtectionSettings {
    fun show(activity: Activity) {
        val s=FlintStyle(activity); val p=s.panel("Автоподключение и защита", maxWidth=490)
        s.add(p.body,s.label("1. Сначала подключитесь во Flint хотя бы один раз.\n\n2. В настройках VPN нажмите шестерёнку рядом с Flint и включите «Постоянная VPN». Android будет запускать VPN после перезагрузки и восстанавливать соединение.\n\n3. Включите «Блокировать соединения без VPN», чтобы при обрыве трафик не уходил через обычную сеть.",color=s.muted))
        s.add(p.body,s.label("При блокировке соединений российские сервисы работают через правила сайтов внутри Flint. Исключение целого приложения из VPN недоступно. На некоторых телевизорах системная настройка VPN отсутствует.",12f,color=s.muted))
        s.add(p.footer,s.button("Открыть настройки VPN") {
            try { activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            catch (_: Exception) { p.error("Прошивка не предоставляет экран VPN. Проверьте системные настройки сети.") }
        },48)
        s.closeButton(p)
    }
}
