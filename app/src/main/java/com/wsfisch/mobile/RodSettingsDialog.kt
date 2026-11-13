package com.wsfisch.mobile

/**
 * Профили специальных удочек WSFisch Android.
 *
 * Original project: WSFisch macro / DeepFish BETA by Yato.
 * Personal modification context: WSJuJuBA01.
 *
 * Профиль описывает требуемую механику и режим обнаружения.
 * Реальная работа каждого специального детектора должна быть подключена
 * к FischVision и MacroRunner.
 */
object RodProfiles {

    const val ATTRIBUTION = "Original macro by Yato"

    enum class DetectionMode {
        DEFAULT,
        COLOR_TRACKING,
        NOTE_TRACKING,
        GREEN_ZONE,
        DARK_RED_BAR,
        AUTO_BAR,
        SPECIAL_MARKER,
        ZODIAC
    }

    enum class ControlMode {
        ADAPTIVE,
        PRECISION,
        LINES,
        NOTE_PRIORITY,
        REVERSED_AFTER_DELAY
    }

    enum class Mechanic {
        NONE,
        FALLING_NOTES,
        REQUIEM_HOLD_RELEASE,
        DARKHEART_CONTROL,
        BELLONA_CONTROL,
        DREAMBREAKER_REVERSE,
        TRANQUILITY_MANIA,
        NOISEFORM_TRACKING,
        VERDANT_GREEN_ZONE,
        LULLABY_LIGHT_SECTORS,
        CASTBOUND_SWORD_MARKER,
        REMEMBRANCE_MODE,
        MIGU_AUTO_BAR,
        HALIBUT_EXCLAMATION,
        SPLITBRANCH_FISH_PICK,
        ASTRAENUS_PRESET,
        TRYHARD_DARK_RED_BAR,
        CHASM_PRESET,
        STELLAR_ZODIAC
    }

    data class Profile(
        val name: String,
        val detectionMode: DetectionMode,
        val controlMode: ControlMode,
        val mechanics: Set<Mechanic> = emptySet(),
        val fishColorKey: String? = null,
        val barColorKey: String? = null,
        val trackColorKey: String? = null,
        val headColorKey: String? = null,
        val requiresNoteDetection: Boolean = false,
        val supportsColorCalibration: Boolean = false,
        val hasCustomMinigame: Boolean = false
    )

    private val profiles = listOf(
        Profile(
            name = "None",
            detectionMode = DetectionMode.DEFAULT,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.NONE)
        ),

        Profile(
            name = "Pinions Aria",
            detectionMode = DetectionMode.NOTE_TRACKING,
            controlMode = ControlMode.NOTE_PRIORITY,
            mechanics = setOf(Mechanic.FALLING_NOTES),
            requiresNoteDetection = true,
            hasCustomMinigame = true
        ),

        Profile(
            name = "Requiem",
            detectionMode = DetectionMode.DEFAULT,
            controlMode = ControlMode.PRECISION,
            mechanics = setOf(Mechanic.REQUIEM_HOLD_RELEASE),
            hasCustomMinigame = true
        ),

        Profile(
            name = "Darkheart",
            detectionMode = DetectionMode.DEFAULT,
            controlMode = ControlMode.PRECISION,
            mechanics = setOf(Mechanic.DARKHEART_CONTROL),
            hasCustomMinigame = true
        ),

        Profile(
            name = "Bellona's Waraxe",
            detectionMode = DetectionMode.DEFAULT,
            controlMode = ControlMode.PRECISION,
            mechanics = setOf(Mechanic.BELLONA_CONTROL),
            hasCustomMinigame = true
        ),

        Profile(
            name = "Dreambreaker",
            detectionMode = DetectionMode.DEFAULT,
            controlMode = ControlMode.REVERSED_AFTER_DELAY,
            mechanics = setOf(Mechanic.DREAMBREAKER_REVERSE),
            hasCustomMinigame = true
        ),

        Profile(
            name = "Tranquility",
            detectionMode = DetectionMode.NOTE_TRACKING,
            controlMode = ControlMode.NOTE_PRIORITY,
            mechanics = setOf(Mechanic.TRANQUILITY_MANIA),
            requiresNoteDetection = true,
            hasCustomMinigame = true
        ),

        Profile(
            name = "Noiseform",
            detectionMode = DetectionMode.COLOR_TRACKING,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.NOISEFORM_TRACKING),
            requiresNoteDetection = true,
            hasCustomMinigame = true
        ),

        Profile(
            name = "Verdant Oath",
            detectionMode = DetectionMode.GREEN_ZONE,
            controlMode = ControlMode.LINES,
            mechanics = setOf(Mechanic.VERDANT_GREEN_ZONE),
            fishColorKey = "VerdantFishColors",
            barColorKey = "VerdantBarColors",
            supportsColorCalibration = true,
            hasCustomMinigame = true
        ),

        Profile(
            name = "Lullaby",
            detectionMode = DetectionMode.NOTE_TRACKING,
            controlMode = ControlMode.NOTE_PRIORITY,
            mechanics = setOf(Mechanic.LULLABY_LIGHT_SECTORS),
            requiresNoteDetection = true,
            hasCustomMinigame = true
        ),

        Profile(
            name = "Castbound",
            detectionMode = DetectionMode.COLOR_TRACKING,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.CASTBOUND_SWORD_MARKER),
            fishColorKey = "CastboundFishColors",
            barColorKey = "CastboundBarColors",
            trackColorKey = "CastboundTrackColors",
            supportsColorCalibration = true
        ),

        Profile(
            name = "Remembrance",
            detectionMode = DetectionMode.SPECIAL_MARKER,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.REMEMBRANCE_MODE),
            hasCustomMinigame = true
        ),

        Profile(
            name = "MiguRod",
            detectionMode = DetectionMode.AUTO_BAR,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.MIGU_AUTO_BAR),
            fishColorKey = "MiguFishColors",
            supportsColorCalibration = true
        ),

        Profile(
            name = "Halibut Harpoon",
            detectionMode = DetectionMode.SPECIAL_MARKER,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.HALIBUT_EXCLAMATION),
            hasCustomMinigame = true
        ),

        Profile(
            name = "Splitbranch Twig",
            detectionMode = DetectionMode.SPECIAL_MARKER,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.SPLITBRANCH_FISH_PICK),
            hasCustomMinigame = true
        ),

        Profile(
            name = "Astraenus Serenade",
            detectionMode = DetectionMode.DEFAULT,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.ASTRAENUS_PRESET)
        ),

        Profile(
            name = "Tryhard Rod",
            detectionMode = DetectionMode.DARK_RED_BAR,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.TRYHARD_DARK_RED_BAR),
            fishColorKey = "TryhardFishColors",
            barColorKey = "TryhardBarColors",
            trackColorKey = "TryhardTrackColors",
            supportsColorCalibration = true
        ),

        Profile(
            name = "Chasm Stalker",
            detectionMode = DetectionMode.DEFAULT,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.CHASM_PRESET)
        ),

        Profile(
            name = "Stellarwave Melody",
            detectionMode = DetectionMode.ZODIAC,
            controlMode = ControlMode.ADAPTIVE,
            mechanics = setOf(Mechanic.STELLAR_ZODIAC),
            fishColorKey = "StellarFishColors",
            barColorKey = "StellarBarColors",
            headColorKey = "StellarHeadColors",
            supportsColorCalibration = true,
            hasCustomMinigame = true
        )
    )

    private val profilesByName: Map<String, Profile> =
        profiles.associateBy { normalize(it.name) }

    /**
     * Все доступные названия.
     * 18 специальных профилей + обычная удочка None.
     */
    fun names(): Array<String> =
        profiles.map { it.name }.toTypedArray()

    fun all(): List<Profile> =
        profiles.toList()

    /**
     * Совместимость со старыми значениями SharedPreferences.
     */
    private fun canonicalName(value: String?): String {
        val name = value?.trim().orEmpty()

        return when {
            name.equals("Pinion Aria", ignoreCase = true) ->
                "Pinions Aria"

            name.equals("Tryhard", ignoreCase = true) ->
                "Tryhard Rod"

            name.equals("Halibut", ignoreCase = true) ->
                "Halibut Harpoon"

            else -> name
        }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase()

    fun get(name: String?): Profile {
        val canonical = canonicalName(name)
        return profilesByName[normalize(canonical)]
            ?: profiles.first()
    }

    fun canonicalize(name: String?): String =
        get(name).name

    fun isKnown(name: String?): Boolean {
        val canonical = canonicalName(name)
        return profilesByName.containsKey(normalize(canonical))
    }

    fun hasMechanic(
        rodName: String?,
        mechanic: Mechanic
    ): Boolean {
        return get(rodName).mechanics.contains(mechanic)
    }

    fun defaultControlMode(name: String?): ControlMode =
        get(name).controlMode

    fun defaultDetectionMode(name: String?): DetectionMode =
        get(name).detectionMode
}
