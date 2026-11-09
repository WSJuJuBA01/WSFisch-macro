package com.wsfisch.mobile

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object RodSettingsDialog {

    private const val PREFS = "mobile_macro_rod_settings"

    private data class NumberSetting(
        val key: String,
        val label: String,
        val defaultValue: String,
        val numeric: Boolean = true
    )

    private val castSettings = listOf(
        NumberSetting(
            "CastXPercent",
            "Положение заброса X (%)",
            "50"
        ),
        NumberSetting(
            "CastYPercent",
            "Положение заброса Y (%)",
            "50"
        ),
        NumberSetting(
            "CastHoldMs",
            "Удержание заброса (мс)",
            "1000"
        ),
        NumberSetting(
            "WaitForBobberMs",
            "Пауза после заброса (мс)",
            "1000"
        ),
        NumberSetting(
            "ShakeTimeoutMs",
            "Тайм-аут поиска поклёвки (мс)",
            "35000"
        ),
        NumberSetting(
            "ReelTimeoutMs",
            "Тайм-аут мини-игры (мс)",
            "120000"
        ),
        NumberSetting(
            "RecastDelayMs",
            "Пауза перед следующим забросом (мс)",
            "1000"
        )
    )

    private val numberSettings = listOf(
        NumberSetting(
            "TryhardBarTol",
            "Tryhard bar tolerance",
            "20"
        ),
        NumberSetting(
            "TryhardFishTol",
            "Tryhard fish tolerance",
            "14"
        ),
        NumberSetting(
            "TryhardTrackTol",
            "Tryhard track tolerance",
            "20"
        ),
        NumberSetting(
            "CastboundBarTol",
            "Castbound bar tolerance",
            "20"
        ),
        NumberSetting(
            "CastboundFishTol",
            "Castbound fish tolerance",
            "20"
        ),
        NumberSetting(
            "CastboundTrackTol",
            "Castbound track tolerance",
            "20"
        ),
        NumberSetting(
            "StellarBarTol",
            "Stellarwave bar tolerance",
            "26"
        ),
        NumberSetting(
            "StellarFishTol",
            "Stellarwave fish tolerance",
            "22"
        ),
        NumberSetting(
            "StellarHeadTol",
            "Stellarwave head tolerance",
            "30"
        )
    )

    private val colorSettings = listOf(
        NumberSetting(
            "CastboundFishColors",
            "Castbound sword color (hex)",
            "0x5522A3",
            false
        ),
        NumberSetting(
            "StellarBarColors",
            "Stellarwave bar color (hex)",
            "0x55446D",
            false
        ),
        NumberSetting(
            "StellarFishColors",
            "Stellarwave fish color (hex)",
            "0xAFDAE8",
            false
        ),
        NumberSetting(
            "StellarHeadColors",
            "Stellarwave head color (hex)",
            "0xFFFFFF",
            false
        )
    )

    fun show(
        activity: Activity,
        onSaved: () -> Unit = {}
    ) {
        val prefs = activity.getSharedPreferences(
            PREFS,
            Activity.MODE_PRIVATE
        )

        val density =
            activity.resources.displayMetrics.density

        val darkText = Color.rgb(239, 243, 249)
        val mutedText = Color.rgb(165, 176, 193)

        fun dp(value: Int) =
            (value * density).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(18),
                dp(12),
                dp(18),
                dp(12)
            )

            setBackgroundColor(Color.rgb(22, 26, 34))
        }

        val rodNames = arrayOf(
            "None",
            "Tryhard",
            "Castbound",
            "Halibut",
            "Stellarwave Melody",
            "Remembrance",
            "Bellona's Waraxe"
        )

        val selected =
            prefs.getString("SpecialRod", "None") ?: "None"

        val selectedIndex =
            rodNames.indexOf(selected).coerceAtLeast(0)

        val rodButton = TextView(activity).apply {
            text = "Special rod: $selected  (tap to change)"
            textSize = 15f
            setTextColor(darkText)
            setPadding(0, dp(8), 0, dp(12))

            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Special rod")
                    .setSingleChoiceItems(
                        rodNames,
                        selectedIndex
                    ) { dialog, which ->
                        prefs.edit()
                            .putString(
                                "SpecialRod",
                                rodNames[which]
                            )
                            .apply()

                        text =
                            "Special rod: ${rodNames[which]}  (tap to change)"

                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        content.addView(rodButton)

        val halibut = CheckBox(activity).apply {
            text = "Play minigame with Halibut"
            setTextColor(darkText)

            isChecked = prefs.getBoolean(
                "HalibutPlayMinigame",
                true
            )
        }

        val clickSigns = CheckBox(activity).apply {
            text = "Stellarwave: click signs"
            setTextColor(darkText)

            isChecked = prefs.getBoolean(
                "SwClickSigns",
                true
            )
        }

        val fastClicks = CheckBox(activity).apply {
            text = "Stellarwave: fast clicks"
            setTextColor(darkText)

            isChecked = prefs.getBoolean(
                "SwFastClicks",
                false
            )
        }

        val retryClicks = CheckBox(activity).apply {
            text = "Stellarwave: retry clicks"
            setTextColor(darkText)

            isChecked = prefs.getBoolean(
                "SwRetryClicks",
                false
            )
        }

        content.addView(halibut)
        content.addView(clickSigns)
        content.addView(fastClicks)
        content.addView(retryClicks)

        val fields = linkedMapOf<String, EditText>()

        fun addSection(
            title: String,
            settings: List<NumberSetting>
        ) {
            content.addView(
                TextView(activity).apply {
                    text = title
                    textSize = 17f
                    setTextColor(darkText)
                    setPadding(0, dp(14), 0, dp(6))
                }
            )

            settings.forEach { setting ->
                content.addView(
                    TextView(activity).apply {
                        text = setting.label
                        textSize = 12f
                        setTextColor(mutedText)
                        setPadding(0, dp(5), 0, 0)
                    }
                )

                val editor = EditText(activity).apply {
                    inputType = if (setting.numeric) {
                        InputType.TYPE_CLASS_NUMBER
                    } else {
                        InputType.TYPE_CLASS_TEXT
                    }

                    isSingleLine = true

                    setTextColor(darkText)
                    setHintTextColor(mutedText)

                    setText(
                        prefs.getString(
                            setting.key,
                            setting.defaultValue
                        )
                    )
                }

                fields[setting.key] = editor

                content.addView(
                    editor,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46)
                )
            }
        }

        addSection(
            "ЗАБРОС И ЦИКЛ",
            castSettings
        )

        content.addView(
            TextView(activity).apply {
                text = "X/Y — координаты удержания на экране. По умолчанию центр (50/50). Если игра не реагирует на заброс, сначала проверь, что удочка экипирована и точка не перекрыта игровыми кнопками."

                textSize = 11f
                setTextColor(mutedText)

                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(8)
                )
            }
        )

        addSection(
            "COLOR CALIBRATION (HEX)",
            colorSettings
        )

        addSection(
            "COLOR TOLERANCES",
            numberSettings
        )

        content.addView(
            TextView(activity).apply {
                text = "Примечание: текущий визуальный контроллер подключён для базового интерфейса Fisch. Параметры особых удочек сохраняются, но их уникальные режимы ещё не все реализованы."

                textSize = 11f
                setTextColor(mutedText)

                setPadding(
                    0,
                    dp(14),
                    0,
                    dp(4)
                )
            }
        )

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.rgb(22, 26, 34))
            addView(content)
        }

        AlertDialog.Builder(activity)
            .setTitle("Rod settings")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->

                fun number(key: String): Int? {
                    return fields[key]
                        ?.text
                        ?.toString()
                        ?.trim()
                        ?.toIntOrNull()
                }

                val castX = number("CastXPercent")
                val castY = number("CastYPercent")
                val castHold = number("CastHoldMs")
                val waitBobber = number("WaitForBobberMs")
                val shakeTimeout = number("ShakeTimeoutMs")
                val reelTimeout = number("ReelTimeoutMs")
                val recastDelay = number("RecastDelayMs")

                val valid =
                    castX != null && castX in 0..100 &&
                    castY != null && castY in 0..100 &&
                    castHold != null && castHold in 250..5000 &&
                    waitBobber != null && waitBobber in 250..5000 &&
                    shakeTimeout != null &&
                    shakeTimeout in 10_000..90_000 &&
                    reelTimeout != null &&
                    reelTimeout in 30_000..180_000 &&
                    recastDelay != null &&
                    recastDelay in 300..5000

                if (!valid) {
                    AlertDialog.Builder(activity)
                        .setTitle("Некорректные настройки")
                        .setMessage(
                            "X/Y: 0–100; удержание и ожидание: 250–5000 мс; поиск поклёвки: 10000–90000 мс; мини-игра: 30000–180000 мс; пауза: 300–5000 мс."
                        )
                        .setPositiveButton("OK", null)
                        .show()

                    return@setPositiveButton
                }

                val editor = prefs.edit()
                    .putBoolean(
                        "HalibutPlayMinigame",
                        halibut.isChecked
                    )
                    .putBoolean(
                        "SwClickSigns",
                        clickSigns.isChecked
                    )
                    .putBoolean(
                        "SwFastClicks",
                        fastClicks.isChecked
                    )
                    .putBoolean(
                        "SwRetryClicks",
                        retryClicks.isChecked
                    )

                fields.forEach { (key, field) ->
                    val value =
                        field.text.toString().trim()

                    if (value.isNotEmpty()) {
                        editor.putString(key, value)
                    }
                }

                editor.apply()
                onSaved()
            }
            .show()
    }
}
