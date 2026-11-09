package com.wsfisch.mobile

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class OverlayControlService : Service() {

    companion object {
        const val ACTION_SHOW =
            "com.wsfisch.mobile.SHOW_OVERLAY"

        const val ACTION_STOP_MACRO =
            "com.wsfisch.mobile.STOP_MACRO"

        const val EXTRA_SCRIPT = "macro_script"
    }

    private lateinit var windowManager: WindowManager
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null

    private val stopFlag = AtomicBoolean(false)

    @Volatile
    private var running = false

    private var macroThread: Thread? = null

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_STOP_MACRO -> stopMacro()
        }

        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlay != null) return

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Разреши отображение поверх окон",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(
                Color.rgb(31, 38, 50),
                14
            )
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), 0, dp(5), 0)

            layoutParams = LinearLayout.LayoutParams(
                dp(100),
                dp(42)
            )
        }

        val header = TextView(this).apply {
            text = "WSFISCH"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(237, 241, 248))
            gravity = Gravity.CENTER
        }

        statusView = TextView(this).apply {
            text = "● READY"
            textSize = 8f
            setTextColor(Color.rgb(168, 181, 199))
            gravity = Gravity.CENTER
            maxLines = 2
        }

        info.addView(header)
        info.addView(statusView)

        val startButton = makeButton(
            "▶",
            Color.rgb(45, 105, 235)
        )

        startButton.contentDescription = "START"
        startButton.setOnClickListener {
            startMacro()
        }

        val stopButton = makeButton(
            "■",
            Color.rgb(65, 77, 96)
        )

        stopButton.contentDescription = "STOP"
        stopButton.setOnClickListener {
            stopMacro()
        }

        val closeButton = makeButton(
            "×",
            Color.rgb(49, 58, 73)
        )

        closeButton.contentDescription = "CLOSE"
        closeButton.setOnClickListener {
            stopMacro()
            removeOverlay()
            stopSelf()
        }

        panel.addView(info)
        panel.addView(startButton)
        panel.addView(stopButton)
        panel.addView(closeButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity =
                Gravity.TOP or Gravity.CENTER_HORIZONTAL

            x = 0
            y = dp(4)
        }

        try {
            windowManager.addView(panel, params)
            overlay = panel
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Не удалось открыть панель: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()
        }
    }

    private fun startMacro() {
        if (running) {
            updateOverlayStatus("Макрос уже работает")
            return
        }

        if (MacroAccessibilityService.instance == null) {
            updateOverlayStatus(
                "Включи службу управления касаниями"
            )
            return
        }

        val capture = ScreenCaptureService.instance

        if (capture == null || !capture.isCaptureReady()) {
            updateOverlayStatus(
                "Нет свежих кадров · включи захват экрана"
            )
            return
        }

        stopFlag.set(false)
        running = true

        updateOverlayStatus("● ЗАПУСК · подготовка")

        macroThread = thread(
            start = true,
            name = "WSFischMacroRunner"
        ) {
            try {
                MacroRunner.run(
                    context = applicationContext,
                    stopFlag = stopFlag,
                    onStatus = { message ->
                        updateOverlayStatus(message)
                    }
                )
            } finally {
                running = false
                macroThread = null

                if (!stopFlag.get()) {
                    updateOverlayStatus(
                        "Готово · проверь статус"
                    )
                }
            }
        }
    }

    private fun stopMacro() {
        stopFlag.set(true)
        macroThread?.interrupt()
        running = false

        updateOverlayStatus("■ ОСТАНОВКА")
    }

    private fun updateOverlayStatus(message: String) {
        Handler(mainLooper).post {
            statusView?.text = message.take(76)
        }
    }

    private fun removeOverlay() {
        val old = overlay ?: return

        try {
            windowManager.removeView(old)
        } catch (_: Exception) {
        }

        overlay = null
        statusView = null
    }

    private fun makeButton(
        label: String,
        color: Int
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false

            minHeight = 0
            minimumHeight = 0
            minWidth = 0

            setTextColor(Color.WHITE)
            background = rounded(color, 9)
            setPadding(dp(3), 0, dp(3), 0)

            layoutParams = LinearLayout.LayoutParams(
                dp(42),
                dp(36)
            ).apply {
                marginStart = dp(3)
            }
        }
    }

    private fun rounded(
        color: Int,
        radius: Int
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
            ).toInt()
    }

    override fun onDestroy() {
        stopMacro()
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
