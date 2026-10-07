import org.amnezia.vpn.*

fun main() {
    var requests = 0
    check(flintRequestPin({ false }, { requests++; true }) == FlintPinResult.UNAVAILABLE)
    check(requests == 0) // Never call an unsupported launcher.
    check(flintRequestPin({ true }, { requests++; false }) == FlintPinResult.UNAVAILABLE)
    check(requests == 1) // Capability can change or an OEM can refuse the actual request.
    check(flintRequestPin({ true }, { true }) == FlintPinResult.REQUESTED) // No installation claim.
    check(flintRequestPin({ throw SecurityException() }, { error("must not run") }) == FlintPinResult.FAILED)
    check(flintRequestPin({ true }, { throw IllegalStateException("no foreground") }) == FlintPinResult.FAILED)
    check(flintRequestPin({ true }, { throw SecurityException("launcher denied") }) == FlintPinResult.FAILED)
    println("Flint pin requests: 6 scenarios passed")
}
