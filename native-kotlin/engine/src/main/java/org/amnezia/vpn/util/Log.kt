package org.amnezia.vpn.util

/** Do not expose imported endpoints or credentials through native engine logs.
 * The prototype reports safe lifecycle stages through its service contract. */
@Suppress("UNUSED_PARAMETER")
object Log {
    fun v(tag: String, message: Any?) {}
    fun d(tag: String, message: Any?) {}
    fun i(tag: String, message: Any?) {}
    fun w(tag: String, message: Any?) {}
    fun e(tag: String, message: Any?) {}
}
