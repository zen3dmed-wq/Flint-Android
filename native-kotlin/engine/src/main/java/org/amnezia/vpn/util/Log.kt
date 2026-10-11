package org.amnezia.vpn.util

/** Do not expose imported endpoints or credentials through native engine logs.
 * The prototype reports safe lifecycle stages through its service contract. */
@Suppress("UNUSED_PARAMETER")
object Log {
    @Volatile var lastIssue: String = "none"
        private set
    fun clear() { lastIssue = "none" }
    fun v(tag: String, message: Any?) {}
    fun d(tag: String, message: Any?) {}
    fun i(tag: String, message: Any?) {}
    fun w(tag: String, message: Any?) { classify(message) }
    fun e(tag: String, message: Any?) { classify(message) }
    private fun classify(message: Any?) {
        val text = message?.toString()?.lowercase().orEmpty()
        // Persist only fixed categories; never the original endpoint, key or error text.
        val code = when {
            "certificate" in text -> "TLS_CERTIFICATE"
            "reality" in text && ("failed" in text || "invalid" in text) -> "REALITY_HANDSHAKE"
            "invalid user" in text || "authentication" in text -> "SERVER_AUTH"
            "failed to lookup" in text || "dns" in text && "failed" in text -> "DNS_LOOKUP"
            "connection refused" in text -> "PORT_REFUSED"
            "network is unreachable" in text || "no route to host" in text -> "NETWORK_ROUTE"
            "timeout" in text || "timed out" in text -> "SERVER_TIMEOUT"
            "failed to find an available destination" in text -> "SERVER_UNREACHABLE"
            else -> return
        }
        lastIssue = code
    }
}
