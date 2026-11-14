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

/** Полное окно профилей и параметров Android-порта WSFisch. */
object RodSettingsDialog {

    private const val PREFS = "mobile_macro_rod_settings"

    private data class Setting(
        val key: String,
        val title: String,
        val defaultValue: String,
        val numeric: Boolean = true
    )

    private val castSettings = listOf(
        Setting("CastXPercent", "Положение заброса X (%)", "50"),
        Setting("CastYPercent", "Положение заброса Y (%)", "50"),
        Setting("CastHoldMs", "Удержание заброса (мс)", "1000"),
        Setting("WaitForBobberMs", "Ожидание поплавка (мс)", "1000"),
        Setting("ShakeTimeoutMs", "Тайм-аут поиска поклёвки (мс)", "35000"),
        Setting("ReelTimeoutMs", "Тайм-аут мини-игры (мс)", "120000"),
        Setting("RecastDelayMs", "Пауза между забросами (мс)", "1000")
    )

    private val specialSettings = listOf(
        Setting("NoteColor", "Pinions: цвет падающей ноты (HEX)", "0xB2CFFF", false),
        Setting("NoteColorTolerance", "Pinions: допуск цвета ноты", "15"),
        Setting("NoteBias", "Pinions: приоритет ноты (%)", "100"),
        Setting("NoteHandoff", "Pinions: передача цели (px)", "50"),
        Setting("ReelMinHold", "Requiem: минимальное удержание (мс)", "200"),
        Setting("ReelMinRelease", "Requiem: минимальное отпускание (мс)", "200"),
        Setting("DreambreakerReverseDelayMs", "Dreambreaker: задержка переворота (мс)", "1500"),
        Setting("ManiaLead", "Tranquility: упреждение Mania (мс)", "45"),
        Setting("LullRadiusPct", "Lullaby: радиус кольца (%)", "16"),
        Setting("LullPivotUp", "Lullaby: смещение центра (px)", "26"),
        Setting("LullLightLum", "Lullaby: порог яркости", "90"),
        Setting("LullArmDelta", "Lullaby: изменение яркости", "45"),
        Setting("LullTrigPct", "Lullaby: доля активных точек (%)", "10"),
        Setting("LullClearMs", "Lullaby: время повторной активации (мс)", "100"),
        Setting("LullMinDeg", "Lullaby: минимальный сектор (градусы)", "4")
    )

    private val tuningSettings = listOf(
        Setting("Kp", "Precision: Kp", "0.5"),
        Setting("Kd", "Precision: Kd", "0.3"),
        Setting("VelocitySmoothing", "Сглаживание скорости", "0.2"),
        Setting("StoppingDistanceMultiplier", "Stopping distance", "3.0")
    )

    private val colorSettings = listOf(
        Setting("TryhardBarColors", "Tryhard: дополнительные цвета полосы", "", false),
        Setting("TryhardFishColors", "Tryhard: дополнительные цвета рыбы", "", false),
        Setting("TryhardTrackColors", "Tryhard: цвета дорожки", "", false),
        Setting("CastboundBarColors", "Castbound: дополнительные цвета полосы", "", false),
        Setting("CastboundFishColors", "Castbound: цвет меча", "0x5522A3", false),
        Setting("CastboundTrackColors", "Castbound: цвета дорожки", "", false),
        Setting("MiguFishColors", "MiguRod: цвета рыбы", "0xF9D9D4,0xFAD6CE,0xF9D4C7,0xF9D2C4,0xF8D0B7", false),
        Setting("VerdantBarColors", "Verdant Oath: цвета зелёной зоны", "", false),
        Setting("VerdantFishColors", "Verdant Oath: цвет рыбы", "0x434B5B", false),
        Setting("StellarBarColors", "Stellarwave: цвет знаков на полосе", "0x55446D", false),
        Setting("StellarFishColors", "Stellarwave: цвет рыбы", "0xAFDAE8", false),
        Setting("StellarHeadColors", "Stellarwave: цвет верхних знаков", "0xFFFFFF", false)
    )

    private val toleranceSettings = listOf(
        Setting("TryhardBarTol", "Tryhard: допуск полосы", "20"),
        Setting("TryhardFishTol", "Tryhard: допуск рыбы", "14"),
        Setting("TryhardTrackTol", "Tryhard: допуск дорожки", "20"),
        Setting("CastboundBarTol", "Castbound: допуск полосы", "20"),
        Setting("CastboundFishTol", "Castbound: допуск меча", "20"),
        Setting("CastboundTrackTol", "Castbound: допуск дорожки", "20"),
        Setting("MiguFishTol", "MiguRod: допуск рыбы", "20"),
        Setting("VerdantBarTol", "Verdant Oath: допуск зелёной зоны", "20"),
        Setting("VerdantFishTol", "Verdant Oath: допуск рыбы", "20"),
        Setting("StellarBarTol", "Stellarwave: допуск полосы", "26"),
        Setting("StellarFishTol", "Stellarwave: допуск рыбы", "22"),
        Setting("StellarHeadTol", "Stellarwave: допуск знаков", "30")
    )

    fun show(activity: Activity, onSaved: () -> Unit = {}) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val background = Color.rgb(22, 26, 34)
        val mainText = Color.rgb(239, 243, 249)
        val mutedText = Color.rgb(165, 176, 193)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(background)
        }

        fun section(title: String) {
            root.addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTextColor(mainText)
                setPadding(0, dp(16), 0, dp(7))
            })
        }

        fun hint(value: String) {
            root.addView(TextView(activity).apply {
                text = value
                textSize = 11f
                setTextColor(mutedText)
                setPadding(0, dp(5), 0, dp(8))
            })
        }

        val rodButton = TextView(activity).apply {
            textSize = 16f
            setTextColor(mainText)
            setPadding(0, dp(12), 0, dp(12))
        }
        fun refreshRodLabel() {
            val selected = RodProfiles.canonicalize(prefs.getString("SpecialRod", "None"))
            rodButton.text = "Удочка: $selected\nНажми, чтобы изменить"
        }
        refreshRodLabel()
        rodButton.setOnClickListener {
            val names = RodProfiles.names()
            val selected = RodProfiles.canonicalize(prefs.getString("SpecialRod", "None"))
            AlertDialog.Builder(activity)
                .setTitle("Выбор удочки")
                .setSingleChoiceItems(names, names.indexOf(selected).coerceAtLeast(0)) { dialog, which ->
                    prefs.edit().putString("SpecialRod", names[which]).apply()
                    refreshRodLabel()
                    dialog.dismiss()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        root.addView(rodButton)

        val controlModes = arrayOf("Adaptive", "Precision", "Lines")
        var selectedControlMode = prefs.getString("ControlMode", "Adaptive") ?: "Adaptive"
        val controlButton = TextView(activity).apply {
            textSize = 14f
            setTextColor(mainText)
            setPadding(0, dp(8), 0, dp(12))
        }
        fun refreshControlLabel() {
            controlButton.text = "Режим управления: $selectedControlMode\nНажми, чтобы изменить"
        }
        refreshControlLabel()
        controlButton.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Режим управления")
                .setSingleChoiceItems(controlModes, controlModes.indexOf(selectedControlMode).coerceAtLeast(0)) { dialog, which ->
                    selectedControlMode = controlModes[which]
                    refreshControlLabel()
                    dialog.dismiss()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        root.addView(controlButton)

        val halibut = CheckBox(activity).apply {
            text = "Halibut Harpoon: вести полосу к маркеру-восклицанию"
            setTextColor(mainText)
            isChecked = prefs.getBoolean("HalibutPlayMinigame", true)
        }
        val zodiacSigns = CheckBox(activity).apply {
            text = "Stellarwave: автоматически нажимать Zodiac"
            setTextColor(mainText)
            isChecked = prefs.getBoolean("SwClickSigns", true)
        }
        val zodiacFast = CheckBox(activity).apply {
            text = "Stellarwave: быстрые нажатия"
            setTextColor(mainText)
            isChecked = prefs.getBoolean("SwFastClicks", false)
        }
        val zodiacRetry = CheckBox(activity).apply {
            text = "Stellarwave: повторять неподтверждённые клики"
            setTextColor(mainText)
            isChecked = prefs.getBoolean("SwRetryClicks", false)
        }
        val remembranceDeparted = CheckBox(activity).apply {
            text = "Remembrance: Departed (выключено = Living)"
            setTextColor(mainText)
            isChecked = prefs.getString("RemembranceMode", "Living") == "Departed"
        }
        root.addView(halibut)
        root.addView(zodiacSigns)
        root.addView(zodiacFast)
        root.addView(zodiacRetry)
        root.addView(remembranceDeparted)

        val editors = linkedMapOf<String, EditText>()
        fun addSettings(title: String, definitions: List<Setting>) {
            section(title)
            definitions.forEach { setting ->
                root.addView(TextView(activity).apply {
                    text = setting.title
                    textSize = 12f
                    setTextColor(mutedText)
                    setPadding(0, dp(5), 0, 0)
                })
                val field = EditText(activity).apply {
                    inputType = if (setting.numeric) {
                        InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            InputType.TYPE_NUMBER_FLAG_SIGNED
                    } else InputType.TYPE_CLASS_TEXT
                    isSingleLine = true
                    setTextColor(mainText)
                    setHintTextColor(mutedText)
                    setHint(setting.defaultValue)
                    setText(prefs.getString(setting.key, setting.defaultValue))
                }
                editors[setting.key] = field
                root.addView(field, ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
            }
        }

        addSettings("ЗАБРОС И ЦИКЛ", castSettings)
        hint("X/Y — координаты касания относительно размера экрана. 50/50 — центр.")
        addSettings("МЕХАНИКИ УДОЧЕК", specialSettings)
        addSettings("НАСТРОЙКА УПРАВЛЕНИЯ", tuningSettings)
        addSettings("ЦВЕТОВАЯ КАЛИБРОВКА (HEX)", colorSettings)
        addSettings("ДОПУСКИ ЦВЕТОВ", toleranceSettings)

        section("АТРИБУЦИЯ")
        hint("Original WSFisch macro / DeepFish BETA by Yato. Android Kotlin port: WSJuJuBA01.")
        section("ПРИМЕЧАНИЯ")
        hint(
            "Детекторы Tranquility, Lullaby, Bellona и специальные цветовые профили " +
                "обрабатываются отдельными ветками MacroRunner. Astraenus Serenade и " +
                "Chasm Stalker используют совместимый стандартный профиль."
        )

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(background)
            isFillViewport = true
            addView(root)
        }

        AlertDialog.Builder(activity)
            .setTitle("Настройки WSFisch")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                fun number(key: String): Int? = editors[key]?.text?.toString()?.trim()?.toIntOrNull()

                val castX = number("CastXPercent")
                val castY = number("CastYPercent")
                val castHold = number("CastHoldMs")
                val bobber = number("WaitForBobberMs")
                val shake = number("ShakeTimeoutMs")
                val reel = number("ReelTimeoutMs")
                val recast = number("RecastDelayMs")

                val valid = castX != null && castX in 0..100 &&
                    castY != null && castY in 0..100 &&
                    castHold != null && castHold in 250..5000 &&
                    bobber != null && bobber in 250..5000 &&
                    shake != null && shake in 10_000..90_000 &&
                    reel != null && reel in 30_000..180_000 &&
                    recast != null && recast in 300..5_000
                if (!valid) {
                    AlertDialog.Builder(activity)
                        .setTitle("Некорректные настройки")
                        .setMessage(
                            "X/Y: 0–100; удержание и ожидание: 250–5000 мс; " +
                                "поиск поклёвки: 10000–90000 мс; мини-игра: " +
                                "30000–180000 мс; пауза: 300–5000 мс."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }

                val editor = prefs.edit()
                    .putString("SpecialRod", RodProfiles.canonicalize(prefs.getString("SpecialRod", "None")))
                    .putString("ControlMode", selectedControlMode)
                    .putBoolean("HalibutPlayMinigame", halibut.isChecked)
                    .putBoolean("SwClickSigns", zodiacSigns.isChecked)
                    .putBoolean("SwFastClicks", zodiacFast.isChecked)
                    .putBoolean("SwRetryClicks", zodiacRetry.isChecked)
                    .putString("RemembranceMode", if (remembranceDeparted.isChecked) "Departed" else "Living")

                editors.forEach { (key, field) ->
                    val value = field.text.toString().trim()
                    if (value.isNotEmpty()) editor.putString(key, value)
                }
                editor.apply()
                onSaved()
            }
            .show()
    }
}
