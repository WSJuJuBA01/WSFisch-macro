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
        val defaultValue: String
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
        NumberSetting("CastboundFishColors", "Castbound sword color (hex)", "0x5522A3"),
        NumberSetting("StellarBarColors", "Stellarwave bar color (hex)", "0x55446D"),
        NumberSetting("StellarFishColors", "Stellarwave fish color (hex)", "0xAFDAE8"),
        NumberSetting("StellarHeadColors", "Stellarwave head color (hex)", "0xFFFFFF")
    )

    fun show(activity: Activity, onSaved: () -> Unit = {}) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

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

        val selected = prefs.getString("SpecialRod", "None") ?: "None"
        val selectedIndex = rodNames.indexOf(selected).coerceAtLeast(0)

        val rodButton = TextView(activity).apply {
            text = "Special rod: $selected  (tap to change)"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Special rod")
                    .setSingleChoiceItems(rodNames, selectedIndex) { dialog, which ->
                        prefs.edit().putString("SpecialRod", rodNames[which]).apply()
                        text = "Special rod: ${rodNames[which]}  (tap to change)"
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        content.addView(rodButton)

        val halibut = CheckBox(activity).apply {
            text = "Play minigame with Halibut"
            isChecked = prefs.getBoolean("HalibutPlayMinigame", true)
        }

        val clickSigns = CheckBox(activity).apply {
            text = "Stellarwave: click signs"
            isChecked = prefs.getBoolean("SwClickSigns", true)
        }

        val fastClicks = CheckBox(activity).apply {
            text = "Stellarwave: fast clicks"
            isChecked = prefs.getBoolean("SwFastClicks", false)
        }

        val retryClicks = CheckBox(activity).apply {
            text = "Stellarwave: retry clicks"
            isChecked = prefs.getBoolean("SwRetryClicks", false)
        }

        content.addView(halibut)
        content.addView(clickSigns)
        content.addView(fastClicks)
        content.addView(retryClicks)

        val fields = linkedMapOf<String, EditText>()

        fun addSection(title: String, settings: List<NumberSetting>) {
            content.addView(TextView(activity).apply {
                text = title
                textSize = 18f
                setPadding(0, dp(14), 0, dp(6))
            })

            settings.forEach { setting ->
                content.addView(TextView(activity).apply {
                    text = setting.label
                    textSize = 13f
                    setPadding(0, dp(5), 0, 0)
                })

                val editor = EditText(activity).apply {
                    inputType = if (setting.key.endsWith("Tol")) {
                        InputType.TYPE_CLASS_NUMBER
                    } else {
                        InputType.TYPE_CLASS_TEXT
                    }
                    singleLine = true
                    setText(prefs.getString(setting.key, setting.defaultValue))
                }

                fields[setting.key] = editor
                content.addView(editor, ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            }
        }

        addSection("Color calibration (hex)", colorSettings)
        addSection("Color tolerances", numberSettings)

        content.addView(TextView(activity).apply {
            text = "Settings defaults adapted from WSFisch by Yato. This is an independent Android settings screen, not the Windows macro."
            textSize = 12f
            setPadding(0, dp(16), 0, dp(4))
        })

        val scroll = ScrollView(activity).apply { addView(content) }

        AlertDialog.Builder(activity)
            .setTitle("Rod settings")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val editor = prefs.edit()
                    .putBoolean("HalibutPlayMinigame", halibut.isChecked)
                    .putBoolean("SwClickSigns", clickSigns.isChecked)
                    .putBoolean("SwFastClicks", fastClicks.isChecked)
                    .putBoolean("SwRetryClicks", retryClicks.isChecked)

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
