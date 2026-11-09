package com.wsfisch.mobile

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAPTURE = 4211
    }

    private lateinit var statusText: TextView
    private lateinit var editor: EditText

    private var waitingForOverlayPermission = false
    private var pendingScript = ""

    private val backgroundColor = Color.rgb(13, 17, 25)
    private val cardColor = Color.rgb(24, 30, 42)
    private val accentColor = Color.rgb(91, 111, 255)
    private val textColor = Color.WHITE
    private val mutedColor = Color.rgb(157, 168, 188)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(26), dp(20), dp(24))
            setBackgroundColor(backgroundColor)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        val title = TextView(this).apply {
            text = "WSFISCH"
            textSize = 29f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "MOBILE MACRO"
            textSize = 12f
            letterSpacing = 0.22f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "● Готов к работе"
            textSize = 14f
            setTextColor(Color.rgb(91, 220, 160))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)

        val permissionsCard = makeCard()

        permissionsCard.addView(sectionTitle("РАЗРЕШЕНИЯ"))
        permissionsCard.addView(makeButton("Включить управление касаниями") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        permissionsCard.addView(makeButton("Разрешить поверх окон") {
            openOverlaySettings()
        })
        permissionsCard.addView(makeButton("Проверить разрешения") {
            updateStatus()
        })

        root.addView(permissionsCard, matchWidth())

        val captureCard = makeCard()
        captureCard.addView(sectionTitle("ЗАХВАТ ЭКРАНА"))
        captureCard.addView(makeButton("▶ Начать захват экрана") {
            requestScreenCapture()
        })
        captureCard.addView(makeButton("■ Остановить захват") {
            stopScreenCapture()
        })
        root.addView(captureCard, matchWidth())

        val scriptCard = makeCard()
        scriptCard.addView(sectionTitle("СКРИПТ"))

        editor = EditText(this).apply {
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minLines = 9
            setBackground(roundBackground(Color.rgb(14, 19, 29), 14))
            typeface = Typeface.MONOSPACE
            hint = "Вставь сюда скрипт"

            setText(
                """
                Loop, 3
                {
                    Click, 540, 960
                    Sleep, 500
                }
                """.trimIndent()
            )
        }

        scriptCard.addView(
            editor,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(230)
            )
        )

        scriptCard.addView(makeButton("▶ ЗАПУСТИТЬ МАКРОС") {
            prepareMacro()
        })

        scriptCard.addView(makeButton("■ ОСТАНОВИТЬ МАКРОС") {
            stopMacro()
        })

        scriptCard.addView(makeButton("Настройки удочек") {
            RodSettingsDialog.show(this)
        })

        root.addView(scriptCard, matchWidth())

        val footer = TextView(this).apply {
            text = "WSFisch Mobile • Alpha"
            textSize = 11f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(4))
        }

        root.addView(footer)
        setContentView(scroll)
        updateStatus()
    }

    private fun prepareMacro() {
        pendingScript = editor.text.toString()

        if (pendingScript.isBlank()) {
            statusText.text = "⚠ Скрипт пустой"
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            statusText.text = "Нужно разрешение поверх окон"

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        showFloatingControls()
    }

    override fun onResume() {
        super.onResume()

        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false

            if (Settings.canDrawOverlays(this)) {
                showFloatingControls()
            } else {
                statusText.text = "⚠ Разрешение не выдано"
            }
        }

        updateStatus()
    }

    private fun showFloatingControls() {
        val intent = Intent(this, OverlayControlService::class.java).apply {
            action = OverlayControlService.ACTION_SHOW
            putExtra(OverlayControlService.EXTRA_SCRIPT, pendingScript)
        }

        try {
            startService(intent)
            statusText.text = "● Плавающее управление запущено"
        } catch (e: Exception) {
            statusText.text = "Ошибка: ${e.message}"
        }
    }

    private fun stopMacro() {
        val intent = Intent(this, OverlayControlService::class.java).apply {
            action = OverlayControlService.ACTION_STOP_MACRO
        }

        startService(intent)
        statusText.text = "Остановка запрошена"
    }

    private fun requestScreenCapture() {
        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        @Suppress("DEPRECATION")
        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_CAPTURE
        )
    }

    @Deprecated("Using activity result for compatibility with current project")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            statusText.text = "Захват экрана отменён"
            return
        }

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusText.text = "● Запуск захвата экрана..."
        } catch (e: Exception) {
            statusText.text = "Ошибка захвата: ${e.message}"
        }
    }

    private fun stopScreenCapture() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }

        try {
            startService(intent)
            statusText.text = "Захват экрана остановлен"
        } catch (e: Exception) {
            statusText.text = "Ошибка остановки: ${e.message}"
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun updateStatus() {
        val accessibilityEnabled = MacroAccessibilityService.instance != null
        val overlayEnabled = Settings.canDrawOverlays(this)
        val captureEnabled = ScreenCaptureService.instance != null

        statusText.text = buildString {
            append(if (accessibilityEnabled) "● Касания включены\n" else "○ Касания выключены\n")
            append(if (overlayEnabled) "● Поверх окон разрешено\n" else "○ Поверх окон запрещено\n")
            append(if (captureEnabled) "● Захват активен" else "○ Захват выключен")
        }
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundBackground(cardColor, 20)
        }
    }

    private fun sectionTitle(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundBackground(accentColor, 14)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { action() }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
            params.bottomMargin = dp(9)
            layoutParams = params
        }
    }

    private fun roundBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun matchWidth() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(16)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
