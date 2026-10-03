package org.amnezia.vpn

fun main() {
    check(flintWidgetModel("CONNECTED", true, true).action == FlintWidgetAction.DISCONNECT)
    check(flintWidgetModel("CONNECTED", false, true).action == FlintWidgetAction.CONNECT) // killed service, stale store
    check(flintWidgetModel("DISCONNECTED", false, true).action == FlintWidgetAction.CONNECT)
    check(flintWidgetModel("DISCONNECTED", true, true).action == FlintWidgetAction.CONNECT)
    check(flintWidgetModel("CONNECTED", false, false).action == FlintWidgetAction.OPEN) // no configured profile
    for (state in listOf("CONNECTING", "RECONNECTING", "DISCONNECTING")) {
        check(flintWidgetModel(state, true, true).action == FlintWidgetAction.WAIT) // no duplicate start/stop
    }
    check(flintWidgetModel("UNKNOWN", true, true).action == FlintWidgetAction.OPEN)
    println("Flint widget: 9 state/action cases passed")
}
