package com.wsfisch.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wsfisch.engine.ScriptEngine
import com.wsfisch.engine.ScriptHost
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val stopFlag = AtomicBoolean(false)

    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var projectionManager: MediaProjectionManager

    private val prefs by lazy {
        getSharedPreferences("mobile_macro", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF101318.toInt())
        }

        val title = TextView(this).apply {
            text = "WSFisch Mobile Macro"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        }

        status = TextView(this).apply {
            setTextColor(0xFF58D6A5.toInt())
            setPadding(0, 12, 0, 12)
        }

        editor = EditText(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8A929E.toInt())
            hint = "Supported Android macro commands"
            setText(prefs.getString("script", DEFAULT_SCRIPT))
            setBackgroundColor(0xFF202630.toInt())
            minLines = 12
            gravity = android.view.Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val enableTouch = Button(this).apply {
            text = "Enable touch service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val startCapture = Button(this).apply {
            text = "Allow screen capture"
            setOnClickListener { requestScreenCapture() }
        }

        val stopCapture = Button(this).apply {
            text = "Stop screen capture"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, ScreenCaptureService::class.java)
                        .setAction(ScreenCaptureService.ACTION_STOP)
                )
                refreshStatus("Capture stopped")
            }
        }

        val run = Button(this).apply {
            text = "RUN"
            setOnClickListener { runScript() }
        }

        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                stopFlag.set(true)
                status.text = "Stop requested"
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(enableTouch)
        root.addView(startCapture)
        root.addView(stopCapture)
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(run)
        root.addView(stop)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onPause() {
        if (::editor.isInitialized) {
            prefs.edit().putString("script", editor.text.toString()).apply()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun requestScreenCapture() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    @Deprecated("Uses Android's MediaProjection consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            refreshStatus("Screen capture permission was not granted")
            return
        }

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }

        ContextCompat.startForegroundService(this, intent)
        refreshStatus("Capture starting")
    }

    private fun refreshStatus(message: String? = null) {
        val touch = if (MacroAccessibilityService.instance != null) {
            "touch enabled"
        } else {
            "touch service off"
        }
        val capture = if (ScreenCaptureService.instance != null) {
            "capture active"
        } else {
            "capture off"
        }
        status.text = listOfNotNull(message, touch, capture).joinToString(" • ")
    }

    private fun runScript() {
        val script = editor.text.toString()
        prefs.edit().putString("script", script).apply()
        stopFlag.set(false)
        status.text = "Running…"

        thread(name = "MobileMacro") {
            try {
                val host = object : ScriptHost {
                    override fun click(x: Float, y: Float) {
                        (MacroAccessibilityService.instance
                            ?: error("Enable the touch service first")).tap(x, y)
                    }

                    override fun swipe(
                        x1: Float,
                        y1: Float,
                        x2: Float,
                        y2: Float,
                        durationMs: Long
                    ) {
                        (MacroAccessibilityService.instance
                            ?: error("Enable the touch service first"))
                            .swipe(x1, y1, x2, y2, durationMs)
                    }

                    override fun sleep(millis: Long) {
                        Thread.sleep(millis)
                    }

                    override fun log(message: String) {
                        runOnUiThread { status.text = message }
                    }

                    override fun isStopRequested(): Boolean = stopFlag.get()

                    override fun pixelColor(x: Int, y: Int): Int =
                        (ScreenCaptureService.instance
                            ?: error("Start screen capture first"))
                            .pixelColor(x, y)

                    override fun findPixel(
                        x1: Int,
                        y1: Int,
                        x2: Int,
                        y2: Int,
                        color: Int,
                        tolerance: Int
                    ): IntArray? =
                        (ScreenCaptureService.instance
                            ?: error("Start screen capture first"))
                            .findPixel(x1, y1, x2, y2, color, tolerance)
                }

                ScriptEngine(host).run(script)
                runOnUiThread {
                    status.text = if (stopFlag.get()) "Stopped" else "Finished"
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                runOnUiThread { status.text = "Interrupted" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error: ${e.message}" }
            }
        }
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 2001
        private const val REQUEST_NOTIFICATIONS = 2002

        private const val DEFAULT_SCRIPT = """; Android macro commands
Loop, 3
{
    Click, 540, 960
    Sleep, 500
}
"""
    }
}
