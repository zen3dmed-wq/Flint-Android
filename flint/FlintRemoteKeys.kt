package org.amnezia.vpn

// Android KeyEvent values; kept free of Android dependencies for JVM regression tests.
object FlintRemoteKeys {
    fun isTelevision(leanback: Boolean, televisionMode: Boolean, touchscreen: Boolean): Boolean =
        leanback || televisionMode || !touchscreen

    fun normalizedKeyCode(code: Int): Int? = when (code) {
        19, 20, 21, 22 -> code // DPAD arrows
        23, 66, 96, 109, 160 -> 66 // DPAD_CENTER, ENTER, BUTTON_A/SELECT, NUMPAD_ENTER
        97 -> 4 // BUTTON_B -> BACK; system BACK/HOME otherwise stay untouched
        else -> null
    }
}
