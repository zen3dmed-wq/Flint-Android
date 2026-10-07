package app.flint.prototype.ui

/** Values here describe measured/service state; the view never infers VPN success. */
enum class FlintPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class FlintServerUi(
    val id: String,
    val name: String,
    val latencyMs: Long? = null,
    val available: Boolean? = null,
    val loadPercent: Int? = null,
)

data class FlintUiState(
    val phase: FlintPhase = FlintPhase.DISCONNECTED,
    val serverLabel: String = "Автоматически",
    val servers: List<FlintServerUi> = emptyList(),
    val selectedServerId: String? = null,
    val ruDirect: Boolean = true,
    val trafficText: String = "",
    val trafficFraction: Float? = null,
    val message: String = "",
    val hasProfile: Boolean = false,
    val busy: Boolean = false,
)

interface FlintUiCallbacks {
    fun onConnectToggle()
    /** null selects automatic mode; a non-null id selects the exact imported server. */
    fun onSelectServer(id: String?)
    fun onImportClipboard()
    fun onImportQrImage()
    fun onImportText()
    fun onImportFile()
    fun onRuDirectChanged(enabled: Boolean)
}
