package org.amnezia.vpn

enum class FlintWidgetAction { CONNECT, DISCONNECT, OPEN, WAIT }
data class FlintWidgetModel(val title: String, val button: String, val action: FlintWidgetAction)

// Never report a saved CONNECTED state after its service process has died.
fun flintWidgetModel(state: String, serviceRunning: Boolean, configured: Boolean): FlintWidgetModel = when {
    !configured -> FlintWidgetModel("Настройте подключение", "Открыть Flint", FlintWidgetAction.OPEN)
    !serviceRunning -> FlintWidgetModel("VPN отключён", "Подключиться", FlintWidgetAction.CONNECT)
    state == "CONNECTED" -> FlintWidgetModel("Вы защищены", "Отключить", FlintWidgetAction.DISCONNECT)
    state == "CONNECTING" -> FlintWidgetModel("Подключение…", "Подождите…", FlintWidgetAction.WAIT)
    state == "RECONNECTING" -> FlintWidgetModel("Восстановление…", "Подождите…", FlintWidgetAction.WAIT)
    state == "DISCONNECTING" -> FlintWidgetModel("Отключение…", "Подождите…", FlintWidgetAction.WAIT)
    state == "DISCONNECTED" -> FlintWidgetModel("VPN отключён", "Подключиться", FlintWidgetAction.CONNECT)
    else -> FlintWidgetModel("Проверка VPN", "Открыть Flint", FlintWidgetAction.OPEN)
}
