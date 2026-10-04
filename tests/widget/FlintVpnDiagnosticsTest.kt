package org.amnezia.vpn

fun main() {
    FlintVpnDiagnostics.reset()
    val old = FlintVpnDiagnostics.begin()
    check(FlintVpnDiagnostics.isCurrent(old))
    FlintVpnDiagnostics.cancel()
    check(!FlintVpnDiagnostics.isCurrent(old)) // Late permission callback cannot connect.
    val newer = FlintVpnDiagnostics.begin()
    check(FlintVpnDiagnostics.isCurrent(newer))
    check(!FlintVpnDiagnostics.isCurrent(old))
    val cases = mapOf(
        "VPN permission denied" to "VPN_PERMISSION_DENIED",
        "UnknownHostException private.example" to "DNS_RESOLUTION_FAILED",
        "socks inbound not found" to "SOCKS_INBOUND_MISSING",
        "Failed to start tun2socks" to "TUN_START_FAILED",
        "unknown transport QUIC" to "UNSUPPORTED_TRANSPORT",
        "Failed to start xray: reality invalid key SECRET" to "VPN_CONFIG_REJECTED",
        "foreground start service" to "SERVICE_START_FAILED",
        "operation timed out" to "NATIVE_TIMEOUT",
        "vless://TEST-SECRET@example.invalid:443?pbk=PRIVATE" to "NATIVE_START_FAILED")
    for ((message, code) in cases) {
        FlintVpnDiagnostics.error(message)
        check(FlintVpnDiagnostics.code() == code)
        val report = FlintVpnDiagnostics.report()
        check(!report.contains("SECRET") && !report.contains("PRIVATE") && !report.contains("example"))
    }
    repeat(60) { FlintVpnDiagnostics.record(FlintVpnDiagnostics.Stage.CONNECTING) }
    check(FlintVpnDiagnostics.report().lines().size == 34)
    FlintVpnDiagnostics.begin()
    check(FlintVpnDiagnostics.code().isEmpty()) // Old server's error cannot label a retry.
    FlintVpnDiagnostics.reset()
    check(FlintVpnDiagnostics.stage() == "IDLE")
    check(FlintVpnDiagnostics.report().lines().size == 3)
    println("Flint native diagnostics: cancellation generations, 9 failure classes, no credentials, bounded history and reset passed")
}
