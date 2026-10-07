package org.amnezia.vpn
fun main() {
    check(flintHomeColour(FlintWidgetAction.CONNECT, false)==FlintHomeColour.OFF)
    check(flintHomeColour(FlintWidgetAction.CONNECT, true)==FlintHomeColour.ERROR)
    check(flintHomeColour(FlintWidgetAction.DISCONNECT, true)==FlintHomeColour.ON)
    check(flintHomeColour(FlintWidgetAction.WAIT, false)==FlintHomeColour.WAIT)
    check(flintHomeColour(FlintWidgetAction.OPEN, false)==FlintHomeColour.OFF)
    println("Home icon: connected, disconnected, connecting, error and unconfigured checks passed")
}
