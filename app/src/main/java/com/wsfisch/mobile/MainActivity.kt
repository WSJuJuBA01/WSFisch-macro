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
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAPTURE = 4211
    }

    private lateinit var statusText: TextView
    private var waitingForOverlayPermission = false
    private var pendingScript = ""

    private val backgroundColor = Color.rgb(22, 26, 34)
    private val cardColor = Color.rgb(33, 40, 52)
    private val accentColor = Color.rgb(45, 105, 235)
    private val textColor = Color.rgb(240, 243, 248)
    private val mutedColor = Color.rgb(157, 167, 183)
    private val successColor = Color.rgb(76, 201, 145)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_YES
        )

        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(16, 20, 27)
        window.navigationBarColor = Color.rgb(16, 20, 27)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        buildInterface()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(backgroundColor)

            minimumHeight = (
                resources.displayMetrics.heightPixels - dp(70)
                ).coerceAtLeast(0)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)

            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
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
            setPadding(dp(8), dp(8), dp(8), dp(14))
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)

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

        val macroCard = makeCard()

        macroCard.addView(
            sectionTitle("МАКРОС")
        )

        val description = TextView(this).apply {
            text = "Настрой удочку, оставь Roblox на экране и запусти панель. Макрос ищет Shake и мини-игру по изображению экрана."
            textSize = 12f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), dp(10))
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
            text = "WSFisch Mobile · experimental vision"
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

            val config =
                MacroScriptBuilder.readConfig(this)

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
        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

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
            statusText.text = "Ошибка захвата: ${e.message}"
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
            statusText.text = "Остановка захвата..."
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
        val accessibilityEnabled =
            MacroAccessibilityService.instance != null

        val overlayEnabled =
            Settings.canDrawOverlays(this)

        val capture =
            ScreenCaptureService.instance

        val captureReady =
            capture?.isCaptureReady() == true

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

            if (captureReady && capture != null) {
                append(
                    "● Кадры ${capture.frameWidth()}×" +
                        "${capture.frameHeight()} · " +
                        "${capture.frameAgeMs()} мс"
                )
            } else {
                append("○ Нет свежих кадров экрана")
            }
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

            background = rounded(cardColor, 15)
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
            setPadding(0, dp(1), 0, dp(10))
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
            background = rounded(accentColor, 10)
            setPadding(dp(8), 0, dp(8), 0)

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

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
            ).toInt()
    }
}
