package app.flint.prototype.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.LinearLayout
import app.flint.prototype.R
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ViewfinderView

/** CaptureActivity owns camera permission, pause/resume and the result contract. */
class FlintQrCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        // CaptureManager applies format restrictions only for the scan action.
        // Keep that contract even when Android recreates or directly opens this screen.
        intent.action = Intents.Scan.ACTION
        intent.putExtra(Intents.Scan.FORMATS, "QR_CODE")
        intent.putExtra(Intents.Scan.ORIENTATION_LOCKED, false)
        intent.putExtra(Intents.Scan.BEEP_ENABLED, false)
        intent.putExtra(Intents.Scan.PROMPT_MESSAGE, "")
        val s = FlintStyle(this, false)
        val root = s.column().apply { setBackgroundColor(s.dark); setPadding(s.dp(20), s.dp(12), s.dp(20), s.dp(12)) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(s.dp(20) + bars.left, s.dp(12) + bars.top, s.dp(20) + bars.right, s.dp(12) + bars.bottom)
            insets
        }
        s.add(root, s.label("Сканировать QR-код", 24f, true), gap = 0)
        s.add(root, s.label("Поместите QR-код целиком в квадрат. Он считается автоматически.", color = s.muted))
        val scanner = layoutInflater.inflate(R.layout.flint_qr_capture, root, false) as DecoratedBarcodeView
        root.addView(scanner, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = s.dp(16); bottomMargin = s.dp(16) })
        val close = s.button("Закрыть") { finish() }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            var lit = false
            val torch = s.button("Фонарик") {}
            torch.setOnClickListener { lit = !lit; if (lit) scanner.setTorchOn() else scanner.setTorchOff(); torch.text = if (lit) "Выключить свет" else "Фонарик" }
            s.buttons(root, torch, close)
        } else s.add(root, close, 48)
        setContentView(root)
        root.requestApplyInsets()
        return scanner
    }
}

/** The decode crop stays square in portrait, landscape and small windows. */
class SquareQrBarcodeView(context: Context, attrs: AttributeSet?) : BarcodeView(context, attrs) {
    override fun calculateFramingRect(container: Rect, surface: Rect): Rect {
        val visible = Rect(container)
        visible.intersect(surface)
        val side = (minOf(visible.width(), visible.height()) * 0.78f).toInt().coerceAtLeast(1)
        val x = visible.centerX() - side / 2
        val y = visible.centerY() - side / 2
        return Rect(x, y, x + side, y + side)
    }
}

class FlintQrViewfinder(context: Context, attrs: AttributeSet?) : ViewfinderView(context, attrs) {
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4AE6A3.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * resources.displayMetrics.density
    }
    init { setLaserVisibility(false) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val preview = rootView.findViewById<BarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_surface)
        preview?.framingRect?.let { canvas.drawRect(it, border) }
    }
}
