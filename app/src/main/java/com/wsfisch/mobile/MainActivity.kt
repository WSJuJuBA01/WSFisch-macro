package com.wsfisch.mobile

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wsfisch.engine.ScriptEngine
import com.wsfisch.engine.ScriptHost
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val stopFlag = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF101318.toInt())
        }

        val title = TextView(this).apply {
            text = "WSFisch Mobile Macro • 1.0 alpha"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        }

        status = TextView(this).apply {
            text = "Touch service: not enabled"
            textSize = 13f
            setTextColor(0xFF58D6A5.toInt())
            setPadding(0, 12, 0, 12)
        }

        editor = EditText(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8A929E.toInt())
            hint = "Paste an AHK-like script here"

            setText(
                """
                #Requires AutoHotkey v1.1
                Loop, 3
                {
                    Click, 540, 960
                    Sleep, 500
                }
                """.trimIndent()
            )

            setBackgroundColor(0xFF202630.toInt())
            minLines = 10
            gravity = android.view.Gravity.TOP
        }

        val accessibility = Button(this).apply {
            text = "Enable touch service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val run = Button(this).apply {
            text = "RUN SCRIPT"
            setOnClickListener {
                runScript()
            }
        }

        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                stopFlag.set(true)
                status.text = "Stop requested"
            }
        }

        val rodSettings = Button(this).apply {
            text = "Rod settings"
            setOnClickListener {
                RodSettingsDialog.show(this@MainActivity)
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(accessibility)
        root.addView(rodSettings)

        root.addView(
            editor,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        root.addView(run)
        root.addView(stop)

        setContentView(
            ScrollView(this).apply {
                addView(root)
            }
        )
    }

    private fun runScript() {
        stopFlag.set(false)

        val script = editor.text.toString()
        status.text = "Running…"

        thread(name = "WSFischScript") {
            try {
                val host = object : ScriptHost {
                    override fun click(x: Float, y: Float) {
                        MacroAccessibilityService.instance?.tap(x, y)
                            ?: throw IllegalStateException(
                                "Enable WSFisch Touch Service first"
                            )
                    }

                    override fun sleep(millis: Long) {
                        Thread.sleep(millis)
                    }

                    override fun log(message: String) {
                        runOnUiThread {
                            status.text = message
                        }
                    }

                    override fun isStopRequested(): Boolean {
                        return stopFlag.get()
                    }
                }

                ScriptEngine(host).run(script)

                runOnUiThread {
                    status.text =
                        if (stopFlag.get()) "Stopped" else "Finished"
                }

            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Error: ${e.message}"
                }
            }
        }
    }
}
