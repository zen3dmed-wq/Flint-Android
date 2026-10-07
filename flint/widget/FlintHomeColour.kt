package org.amnezia.vpn

enum class FlintHomeColour(val hex: String, val label: String) {
    OFF("#82909E", "VPN выкл."), WAIT("#F1C75B", "Подключение"),
    ON("#4AE6A3", "VPN вкл."), ERROR("#EF6571", "Ошибка VPN")
}
fun flintHomeColour(action: FlintWidgetAction, failed: Boolean): FlintHomeColour = when (action) {
    FlintWidgetAction.DISCONNECT -> FlintHomeColour.ON
    FlintWidgetAction.WAIT -> FlintHomeColour.WAIT
    else -> if (failed) FlintHomeColour.ERROR else FlintHomeColour.OFF
}
