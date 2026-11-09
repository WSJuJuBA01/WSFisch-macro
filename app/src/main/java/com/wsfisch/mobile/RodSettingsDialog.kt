package com.wsfisch.mobile

import android.app.Activity
import android.app.AlertDialog
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
            "Положение кнопки заброса по X (%)",
            "50"
        ),
        NumberSetting(
            "CastYPercent",
            "Положение кнопки заброса по Y (%)",
            "85"
        ),
        NumberSetting(
            "CastHoldMs",
            "Время удержания заброса (мс)",
            "1000"
        )
    )

    private val numberSettings = listOf(
        NumberSetting("TryhardBarTol", "Tryhard bar tolerance", "20"),
        NumberSetting("TryhardFishTol", "Tryhard fish tolerance", "14"),
        NumberSetting("TryhardTrackTol", "Tryhard track tolerance", "20"),
        NumberSetting("CastboundBarTol", "Castbound bar tolerance", "20"),
        NumberSetting("CastboundFishTol", "Castbound fish tolerance", "20"),
        NumberSetting("CastboundTrackTol", "Castbound track tolerance", "20"),
        NumberSetting("StellarBarTol", "Stellarwave bar tolerance", "26"),
        NumberSetting("StellarFishTol", "Stellarwave fish tolerance", "22"),
        NumberSetting("StellarHeadTol", "Stellarwave head tolerance", "30")
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

        val density = activity.resources.displayMetrics.density

        fun dp(value: Int) =
            (value * density).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
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

        val selected = prefs.getString(
            "SpecialRod",
            "None"
        ) ?: "None"

        val selectedIndex = rodNames.indexOf(selected).coerceAtLeast(0)

        val rodButton = TextView(activity).apply {
            text = "Special rod: $selected  (tap to change)"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))

            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Special rod")
                    .setSingleChoiceItems(
                        rodNames,
                        selectedIndex
                    ) { dialog, which ->
                        prefs.edit()
                            .putString("SpecialRod", rodNames[which])
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
            isChecked = prefs.getBoolean(
                "HalibutPlayMinigame",
                true
            )
        }

        val clickSigns = CheckBox(activity).apply {
            text = "Stellarwave: click signs"
            isChecked = prefs.getBoolean(
                "SwClickSigns",
                true
            )
        }

        val fastClicks = CheckBox(activity).apply {
            text = "Stellarwave: fast clicks"
            isChecked = prefs.getBoolean(
                "SwFastClicks",
                false
            )
        }

        val retryClicks = CheckBox(activity).apply {
            text = "Stellarwave: retry clicks"
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
                    textSize = 18f
                    setPadding(0, dp(14), 0, dp(6))
                }
            )

            settings.forEach { setting ->
                content.addView(
                    TextView(activity).apply {
                        text = setting.label
                        textSize = 13f
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
                    dp(48)
                )
            }
        }

        addSection("Cast / Заброс", castSettings)

        content.addView(
            TextView(activity).apply {
                text = "X и Y — положение центра кнопки заброса в процентах от экрана. Например, X = 50, Y = 85 означает центр по горизонтали и 85% высоты экрана."
                textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
            }
        )

        addSection("Color calibration (hex)", colorSettings)
        addSection("Color tolerances", numberSettings)

        content.addView(
            TextView(activity).apply {
                text = "Настройки заброса используются Android MacroRunner. Остальные параметры удочек пока сохраняются для следующих этапов реализации."
                textSize = 12f
                setPadding(0, dp(16), 0, dp(4))
            }
        )

        val scroll = ScrollView(activity).apply {
            addView(content)
        }

        AlertDialog.Builder(activity)
            .setTitle("Rod settings")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->

                val castX = fields["CastXPercent"]
                    ?.text?.toString()?.toIntOrNull()

                val castY = fields["CastYPercent"]
                    ?.text?.toString()?.toIntOrNull()

                val castHold = fields["CastHoldMs"]
                    ?.text?.toString()?.toIntOrNull()

                if (
                    castX == null || castX !in 0..100 ||
                    castY == null || castY !in 0..100 ||
                    castHold == null || castHold !in 100..5000
                ) {
                    AlertDialog.Builder(activity)
                        .setTitle("Некорректные настройки")
                        .setMessage(
                            "X и Y должны быть от 0 до 100, время удержания — от 100 до 5000 мс. Открой настройки и исправь значения."
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
                    val value = field.text.toString().trim()

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
