package org.amnezia.vpn

/** Bounded, in-memory diagnostics. Never stores profile URLs, configs or account data. */
object FlintVpnDiagnostics {
    enum class Stage { IDLE, REQUESTED, VPN_PERMISSION, NOTIFICATION_PERMISSION,
        START_SERVICE, SERVICE_BOUND, CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED, ERROR, STOP_REQUESTED }
    private var generation = 0L
    private var started = System.nanoTime()
    private var current = Stage.IDLE
    private var failure = ""
    private val events = ArrayDeque<String>()

    @JvmStatic @Synchronized fun reset() { generation++; started = System.nanoTime(); events.clear(); failure = ""; current = Stage.IDLE }
    @JvmStatic @Synchronized fun begin(): Long { generation++; failure = ""; record(Stage.REQUESTED); return generation }
    @JvmStatic @Synchronized fun isCurrent(attempt: Long) = attempt == generation
    @JvmStatic @Synchronized fun currentAttempt(): Long = generation
    @JvmStatic @Synchronized fun cancel() { generation++; record(Stage.STOP_REQUESTED) }
    @JvmStatic @Synchronized fun record(stage: Stage) {
        current = stage
        if (events.size == 32) events.removeFirst()
        events.addLast("${(System.nanoTime() - started) / 1000000} ms ${stage.name}")
    }
    @JvmStatic @Synchronized fun protocolState(state: String) {
        Stage.entries.firstOrNull { it.name == state }?.let { record(it) }
    }
    @JvmStatic @Synchronized fun error(raw: String) {
        val text = raw.lowercase()
        // Only fixed diagnostic codes leave this function, never raw engine data.
        failure = when {
            "config transport" in text -> "CONFIG_TRANSFER_FAILED"
            "service bind" in text -> "SERVICE_BIND_FAILED"
            "permission" in text || "not allowed" in text -> "VPN_PERMISSION_DENIED"
            "unknownhost" in text || "resolve" in text || "no such host" in text -> "DNS_RESOLUTION_FAILED"
            "socks inbound" in text -> "SOCKS_INBOUND_MISSING"
            "tun2socks" in text -> "TUN_START_FAILED"
            "unknown transport" in text || "unknown protocol" in text -> "UNSUPPORTED_TRANSPORT"
            "config" in text || "reality" in text || "failed to start xray" in text -> "VPN_CONFIG_REJECTED"
            "foreground" in text || "start service" in text -> "SERVICE_START_FAILED"
            "timeout" in text || "timed out" in text -> "NATIVE_TIMEOUT"
            else -> "NATIVE_START_FAILED"
        }
        record(Stage.ERROR)
    }
    @JvmStatic @Synchronized fun stage(): String = current.name
    @JvmStatic @Synchronized fun code(): String = failure
    @JvmStatic @Synchronized fun report(): String =
        "native.stage=${current.name}\nnative.error=${failure.ifEmpty { "none" }}\n" + events.joinToString("\n")
}
