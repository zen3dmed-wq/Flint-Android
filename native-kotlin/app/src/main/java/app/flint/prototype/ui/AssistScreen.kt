package app.flint.prototype.ui

import android.app.Activity

/** Kept for callers from older screens; support opens the message form directly. */
class AssistScreen(activity: Activity, private val operator: () -> Unit) {
    fun show() = operator()
}
