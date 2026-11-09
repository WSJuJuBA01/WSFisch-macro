
package com.wsfisch.mobile

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAPTURE = 4211
    }

    private lateinit var statusText: TextView

    private var waitingForOverlayPermission = false
    private var pendingScript = ""

    // Спокойная серо-синяя палитра
    private val backgroundColor = Color.rgb(226, 229, 234)
    private val cardColor = Color.rgb(242, 244, 247)
    private val accentColor = Color.rgb(37, 99, 235)
    private val textColor = Color.rgb(31, 41, 55)
    private val mutedColor = Color.rgb(101, 112, 132)
    private val successColor = Color.rgb(22, 145, 92)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
            )
            setBackgroundColor(backgroundColor)

            // Центрирование по вертикали, пока контент помещается.
            // Если контента станет больше экрана — появится прокрутка.
            minimumHeight = (
                resources.displayMetrics.heightPixels - dp(70)
            ).coerceAtLeast(0)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(
                root,
                ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val title = TextView(this).apply {
            text = "WSFISCH"
            textSize = 25f
            typeface = Typeface.create(
                "sans-serif",
                Typeface.BOLD
            )
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "MOBILE MACRO"
            textSize = 10f
            letterSpacing = 0.20f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(12))
        }

        statusText = TextView(this).apply {
            text = "Проверка состояния..."
            textSize = 12f
            setTextColor(successColor)
            gravity = Gravity.CENTER
            setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(14)
            )
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)

        // Разрешения
        val permissionsCard = makeCard()

        permissionsCard.addView(
            sectionTitle("РАЗРЕШЕНИЯ")
        )

        permissionsCard.addView(
            makeButton("Управление касаниями") {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        )

        permissionsCard.addView(
            makeButton("Разрешение поверх окон") {
                openOverlaySettings()
            }
        )

        permissionsCard.addView(
            makeButton("Проверить состояние") {
                updateStatus()
            }
        )

        root.addView(
            permissionsCard,
            matchWidth()
        )

        // Захват экрана
        val captureCard = makeCard()

        captureCard.addView(
            sectionTitle("ЭКРАН")
        )

        captureCard.addView(
            makeButton("Начать захват") {
                requestScreenCapture()
            }
        )

        captureCard.addView(
            makeButton("Остановить захват") {
                stopScreenCapture()
            }
        )

        root.addView(
            captureCard,
            matchWidth()
        )

        // Макрос
        val macroCard = makeCard()

        macroCard.addView(
            sectionTitle("МАКРОС")
        )

        val description = TextView(this).apply {
            text = "Настрой удочку и запусти плавающую панель управления."
            textSize = 12f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(
                dp(4),
                0,
                dp(4),
                dp(10)
            )
        }

        macroCard.addView(description)

        macroCard.addView(
            makeButton("Настройки удочек") {
                RodSettingsDialog.show(this) {
                    statusText.text = "Настройки сохранены"
                }
            }
        )

        macroCard.addView(
            makeButton("Собрать и запустить") {
                prepareMacro()
            }
        )

        macroCard.addView(
            makeButton("Остановить макрос") {
                stopMacro()
            }
        )

        root.addView(
            macroCard,
            matchWidth()
        )

        val footer = TextView(this).apply {
            text = "WSFisch Mobile · Alpha"
            textSize = 10f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(2))
        }

        root.addView(footer)

        setContentView(scroll)
        updateStatus()
    }

    private fun prepareMacro() {
        try {
            pendingScript = MacroScriptBuilder.build(this)

            if (pendingScript.isBlank()) {
                statusText.text = "Не удалось собрать сценарий"
                return
            }

            val config = MacroScriptBuilder.readConfig(this)

            if (!Settings.canDrawOverlays(this)) {
                waitingForOverlayPermission = true
                statusText.text = "Нужно разрешение поверх окон"
                openOverlaySettings()
                return
            }

            showFloatingControls()

            statusText.text =
                "Панель готова · ${config.specialRod}"

        } catch (e: Exception) {
            statusText.text =
                "Ошибка: ${e.message ?: "unknown"}"
        }
    }

    override fun onResume() {
        super.onResume()

        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false

            if (Settings.canDrawOverlays(this)) {
                showFloatingControls()
            } else {
                statusText.text = "Разрешение не выдано"
            }
        }

        updateStatus()
    }

    private fun showFloatingControls() {
        if (pendingScript.isBlank()) {
            statusText.text = "Сначала собери сценарий"
            return
        }

        val intent = Intent(
            this,
            OverlayControlService::class.java
        ).apply {
            action = OverlayControlService.ACTION_SHOW
            putExtra(
                OverlayControlService.EXTRA_SCRIPT,
                pendingScript
            )
        }

        try {
            startService(intent)
            statusText.text = "Плавающая панель готова"
        } catch (e: Exception) {
            statusText.text = "Ошибка: ${e.message}"
        }
    }

    private fun stopMacro() {
        val intent = Intent(
            this,
            OverlayControlService::class.java
        ).apply {
            action = OverlayControlService.ACTION_STOP_MACRO
        }

        try {
            startService(intent)
            statusText.text = "Остановка запрошена"
        } catch (e: Exception) {
            statusText.text = "Ошибка остановки: ${e.message}"
        }
    }

    private fun requestScreenCapture() {
        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        @Suppress("DEPRECATION")
        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_CAPTURE
        )
    }

    @Deprecated("Using activity result for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            statusText.text = "Захват экрана отменён"
            return
        }

        val serviceIntent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ScreenCaptureService.ACTION_START

            putExtra(
                ScreenCaptureService.EXTRA_RESULT_CODE,
                resultCode
            )

            putExtra(
                ScreenCaptureService.EXTRA_RESULT_DATA,
                data
            )
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            statusText.text = "Запуск захвата экрана..."
        } catch (e: Exception) {
            statusText.text =
                "Ошибка захвата: ${e.message}"
        }
    }

    private fun stopScreenCapture() {
        val intent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ScreenCaptureService.ACTION_STOP
        }

        try {
            startService(intent)
            statusText.text = "Захват остановлен"
        } catch (e: Exception) {
            statusText.text =
                "Ошибка остановки: ${e.message}"
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
        val accessibilityEnabled =
            MacroAccessibilityService.instance != null

        val overlayEnabled =
            Settings.canDrawOverlays(this)

        val captureEnabled =
            ScreenCaptureService.instance != null

        statusText.text = buildString {
            append(
                if (accessibilityEnabled) {
                    "● Касания включены\n"
                } else {
                    "○ Касания выключены\n"
                }
            )

            append(
                if (overlayEnabled) {
                    "● Поверх окон разрешено\n"
                } else {
                    "○ Поверх окон запрещено\n"
                }
            )

            append(
                if (captureEnabled) {
                    "● Захват активен"
                } else {
                    "○ Захват выключен"
                }
            )
        }
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(14),
                dp(13),
                dp(14),
                dp(8)
            )

            background = rounded(
                cardColor,
                15
            )
        }
    }

    private fun sectionTitle(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.13f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(
                0,
                dp(1),
                0,
                dp(10)
            )
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            setTextColor(Color.WHITE)

            background = rounded(
                accentColor,
                10
            )

            setPadding(
                dp(8),
                0,
                dp(8),
                0
            )

            setOnClickListener {
                action()
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(39)
            ).apply {
                bottomMargin = dp(7)
            }
        }
    }

    private fun rounded(
        color: Int,
        radius: Int
    ): GradientDrawable {
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
            bottomMargin = dp(11)
        }

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density
        ).toInt()
}
