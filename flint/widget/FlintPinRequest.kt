package org.amnezia.vpn

enum class FlintPinResult { REQUESTED, UNAVAILABLE, FAILED }

/** REQUESTED only means the launcher accepted the request, never that it installed anything. */
fun flintRequestPin(supported: () -> Boolean, request: () -> Boolean): FlintPinResult = try {
    if (!supported()) FlintPinResult.UNAVAILABLE
    else if (request()) FlintPinResult.REQUESTED
    else FlintPinResult.UNAVAILABLE
} catch (_: RuntimeException) {
    FlintPinResult.FAILED
}
