package org.amnezia.vpn

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Visible fallback survives launchers that report pinning support but silently ignore a request. */
class FlintHomeSetupActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var prefs: SharedPreferences
    private var requestToken: String? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        title = "Flint на главном экране"
        requestToken = saved?.getString("requestToken")
        prefs = getSharedPreferences(FlintHomePinReceiver.PREFERENCES, MODE_PRIVATE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded("#0B2231")
        }
        fun text(value: String, size: Float, color: String): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.parseColor(color))
            setPadding(0, 0, 0, dp(14)); column.addView(this)
        }
        text("Кнопка Flint", 24f, "#F0F6FA").setTypeface(null, Typeface.BOLD)
        text("Включайте и выключайте VPN одним нажатием с главного экрана.", 15f, "#BCD0DD")
        status = text(saved?.getString("status") ?: "Выберите виджет или обычный ярлык. Ярлык подходит, если виджеты скрыты вашей оболочкой.", 15f, "#BCD0DD")
        fun button(label: String, action: () -> Unit) {
            column.addView(Button(this).apply {
                text = label; textSize = 15f; isAllCaps = false
                setTextColor(Color.parseColor("#F0F6FA"))
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#18394C"))
                background = rounded("#18394C")
                minHeight = dp(52); setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        button("Добавить виджет 1×1") { pinWidget() }
        button("Добавить иконку VPN") { pinShortcut() }
        button("Как добавить вручную") { showManualHelp() }
        button("Настройки рабочего стола") {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            catch (_: RuntimeException) { showManualHelp() }
        }
        button("Закрыть") { finish() }
        setContentView(ScrollView(this).apply { addView(column) })
        window.setBackgroundDrawableResource(android.R.color.transparent)
        FlintWidgetProvider.schedule(applicationContext)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(this)
        showConfirmation()
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("requestToken", requestToken)
        out.putString("status", status.text.toString())
        super.onSaveInstanceState(out)
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (key == "confirmed") showConfirmation()
    }

    private fun showConfirmation() {
        if (requestToken != null && prefs.getString("confirmed", null) == requestToken) {
            status.text = "Добавлено на главный экран. Нажатие включает или выключает VPN."
            requestToken = null
        }
    }

    private fun callback(): PendingIntent {
        requestToken = UUID.randomUUID().toString()
        // Separate tokens keep a delayed callback from confirming a newer request.
        val intent = Intent(this, FlintHomePinReceiver::class.java)
            .setAction(FlintHomePinReceiver.ACTION_PINNED)
            .setData(android.net.Uri.parse("flint-home-pin://callback/$requestToken"))
            .putExtra("token", requestToken)
        return PendingIntent.getBroadcast(this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun pinWidget() {
        val result = flintRequestPin(
            { AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported },
            {
                // OEM launchers may reject a custom extras preview; the provider has all preview formats.
                AppWidgetManager.getInstance(this).requestPinAppWidget(
                    ComponentName(this, FlintWidgetProvider::class.java), null, callback())
            })
        status.text = if (result == FlintPinResult.REQUESTED)
            "Подтвердите добавление в окне рабочего стола. Если окно не появилось, нажмите «Добавить иконку VPN» или добавьте виджет вручную."
        else "Рабочий стол не принял запрос виджета. Попробуйте «Добавить иконку VPN» или добавление вручную."
    }

    private fun pinShortcut() {
        scope.launch {
            try {
            val info = withContext(Dispatchers.IO) {
                val state = VpnStateStore.getVpnState()
                val colour = flintHomeColour(FlintWidgetProvider.model(applicationContext, state).action, state.flintError)
                FlintHomeShortcut.info(applicationContext, colour)
            }
            val result = flintRequestPin(
                { getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true },
                { requireNotNull(getSystemService(ShortcutManager::class.java)).requestPinShortcut(info, callback().intentSender) })
            status.text = if (result == FlintPinResult.REQUESTED)
                "Подтвердите добавление иконки VPN. Цвет фона меняется по состоянию подключения. Старую иконку «Вкл./выкл.» удалите с рабочего стола вручную — она не поддерживает смену цвета."
            else "Рабочий стол не принял запрос. Удерживайте значок Flint и перетащите ярлык «VPN» на экран. Проверьте разрешение добавления значков."
            } catch (_: Exception) {
                if (!isFinishing) status.text = "Не удалось подготовить иконку. Откройте Flint и повторите добавление."
            }
        }
    }

    private fun showManualHelp() {
        status.text = "Удерживайте пустое место рабочего стола → Виджеты → Flint VPN. В ColorOS проверьте также раздел обычных виджетов Android, если он есть внизу списка.\n\nЕсли Flint отсутствует: удерживайте значок приложения → «Вкл./выкл. VPN» и перетащите эту кнопку на экран.\n\nПроверьте блокировку расположения значков и разрешение добавлять ярлыки. После обновления Flint откройте приложение; если список не обновился, перезапустите телефон."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(18).toFloat()
    }
}
