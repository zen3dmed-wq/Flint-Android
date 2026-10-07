package app.flint.prototype.testing

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import app.flint.prototype.ui.FlintHomeView
import app.flint.prototype.ui.FlintPhase
import app.flint.prototype.ui.FlintServerUi
import app.flint.prototype.ui.FlintUiCallbacks
import app.flint.prototype.ui.FlintUiState
import java.util.concurrent.CopyOnWriteArrayList

/** Debug-only UI fixture. No code in this activity binds, starts, or stops a VPN. */
class UiHarnessActivity : Activity() {
    lateinit var flintView: FlintHomeView
        private set
    var uiState = fixture()
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phaseName = savedInstanceState?.getString("phase") ?: intent.getStringExtra("phase")
        val phase = phaseName?.let { FlintPhase.valueOf(it) } ?: FlintPhase.DISCONNECTED
        uiState = fixture().copy(phase = phase,
            selectedServerId = savedInstanceState?.getString("serverId"),
            serverLabel = savedInstanceState?.getString("serverLabel") ?: "Автоматически")
        flintView = FlintHomeView(this, intent.getBooleanExtra("tv", false), object : FlintUiCallbacks {
            override fun onConnectToggle() { events.add("connectToggle") }
            override fun onSelectServer(id: String?) {
                events.add("server:${id ?: "auto"}")
                showState(uiState.copy(selectedServerId = id,
                    serverLabel = uiState.servers.firstOrNull { it.id == id }?.name ?: "Автоматически"))
            }
            override fun onImportClipboard() { events.add("clipboard") }
            override fun onImportQrImage() { events.add("qr") }
            override fun onImportText() { events.add("text") }
            override fun onImportFile() { events.add("file") }
            override fun onRuDirectChanged(enabled: Boolean) {
                events.add("ru:$enabled")
                showState(uiState.copy(ruDirect = enabled))
            }
        })
        val container = FrameLayout(this)
        val badgeHeight = (18 * resources.displayMetrics.density).toInt()
        container.addView(flintView, FrameLayout.LayoutParams(-1, -1).apply { topMargin = badgeHeight })
        container.addView(TextView(this).apply {
            text = "UI TEST · VPN не запущен"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(94, 48, 10))
        }, FrameLayout.LayoutParams(-1, badgeHeight))
        setContentView(container)
        showState(uiState)
    }

    fun showState(state: FlintUiState) {
        uiState = state
        flintView.render(state)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("phase", uiState.phase.name)
        outState.putString("serverId", uiState.selectedServerId)
        outState.putString("serverLabel", uiState.serverLabel)
        super.onSaveInstanceState(outState)
    }

    companion object {
        val events = CopyOnWriteArrayList<String>()
        fun fixture() = FlintUiState(
            phase = FlintPhase.DISCONNECTED,
            hasProfile = true,
            trafficText = "199,6 GB / 1000,0 GB",
            trafficFraction = 0.1996f,
            servers = listOf(
                FlintServerUi("server-a", "Армения · тест A", 23, true, 27),
                FlintServerUi("server-b", "Армения · тест B", null, false, null),
                FlintServerUi("server-c", "США · тест", null, null, 62),
            ),
        )
    }
}
