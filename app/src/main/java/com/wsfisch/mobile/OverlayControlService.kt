package com.wsfisch.mobile

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Point
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import com.wsfisch.engine.ScriptEngine
import com.wsfisch.engine.ScriptHost

class OverlayControlService : Service() {

    companion object {
        const val ACTION_SHOW = "com.wsfisch.mobile.SHOW_OVERLAY"
        const val ACTION_STOP_MACRO = "com.wsfisch.mobile.STOP_MACRO"
        const val EXTRA_SCRIPT = "macro_script"
    }

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var statusView: TextView? = null

    private var scriptText = ""
    private val stopFlag = AtomicBoolean(false)
    private var macroThread: Thread? = null

    private var running = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                scriptText = intent.getStringExtra(EXTRA_SCRIPT) ?: ""
                showOverlay()
            }

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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(22, 28, 42), 18)
        }

        val header = TextView(this).apply {
            text = "WSFISCH  ⋮"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(12))
        }

        statusView = TextView(this).apply {
            text = "● READY"
            textSize = 11f
            setTextColor(Color.rgb(100, 220, 160))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        val startButton = makeButton("▶ START", Color.rgb(65, 160, 110))
        startButton.setOnClickListener { startMacro() }

        val stopButton = makeButton("■ STOP", Color.rgb(190, 70, 80))
        stopButton.setOnClickListener { stopMacro() }

        val closeButton = makeButton("× CLOSE", Color.rgb(65, 75, 95))
        closeButton.setOnClickListener {
            stopMacro()
            removeOverlay()
            stopSelf()
        }

        panel.addView(header)
        panel.addView(statusView)
        panel.addView(startButton)
        panel.addView(stopButton)
        panel.addView(closeButton)

        val params = WindowManager.LayoutParams(
            dp(190),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }

        header.setOnTouchListener(DragListener(params))

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
        if (running) return

        if (scriptText.isBlank()) {
            statusView?.text = "⚠ EMPTY SCRIPT"
            return
        }

        if (MacroAccessibilityService.instance == null) {
            statusView?.text = "⚠ ENABLE TOUCH SERVICE"
            return
        }

        stopFlag.set(false)
        running = true
        statusView?.text = "● RUNNING"

        macroThread = thread(
            start = true,
            name = "WSFischMacroThread"
        ) {
            try {
                val host = object : ScriptHost {
                    override fun click(x: Float, y: Float) {
                        if (stopFlag.get()) return

                        val service = MacroAccessibilityService.instance
                            ?: throw IllegalStateException(
                                "Touch service is disabled"
                            )

                        service.tap(x, y)
                    }

                    override fun sleep(millis: Long) {
                        var remaining = millis.coerceAtLeast(0)

                        while (remaining > 0 && !stopFlag.get()) {
                            val part = minOf(remaining, 100L)
                            Thread.sleep(part)
                            remaining -= part
                        }
                    }

                    override fun log(message: String) {
                        updateOverlayStatus(message)
                    }

                    override fun isStopRequested(): Boolean {
                        return stopFlag.get()
                    }
                }

                ScriptEngine(host).run(scriptText)

                updateOverlayStatus(
                    if (stopFlag.get()) "■ STOPPED" else "✓ FINISHED"
                )
            } catch (e: InterruptedException) {
                updateOverlayStatus("■ STOPPED")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                updateOverlayStatus("⚠ ${e.message ?: "SCRIPT ERROR"}")
            } finally {
                running = false
                macroThread = null
            }
        }
    }

    private fun stopMacro() {
        stopFlag.set(true)
        macroThread?.interrupt()
        running = false
        statusView?.text = "■ STOPPING"
    }

    private fun updateOverlayStatus(message: String) {
        android.os.Handler(mainLooper).post {
            statusView?.text = message.take(45)
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

    private fun makeButton(label: String, color: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(color, 12)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply {
                bottomMargin = dp(6)
            }
        }
    }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class DragListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()

                    try {
                        overlay?.let {
                            windowManager.updateViewLayout(it, params)
                        }
                    } catch (_: Exception) {
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> return true
            }
            return false
        }
    }

    override fun onDestroy() {
        stopMacro()
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
