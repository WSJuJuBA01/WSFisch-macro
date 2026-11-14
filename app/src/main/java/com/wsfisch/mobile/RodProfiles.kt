package com.wsfisch.mobile

/** Profiles for all standard and special rods available in the Android port. */
object RodProfiles {
    const val ATTRIBUTION = "Original macro by Yato"

    enum class DetectionMode { DEFAULT, COLOR_TRACKING, NOTE_TRACKING, GREEN_ZONE, DARK_RED_BAR, AUTO_BAR, SPECIAL_MARKER, ZODIAC }
    enum class ControlMode { ADAPTIVE, PRECISION, LINES, NOTE_PRIORITY, REVERSED_AFTER_DELAY }
    enum class Mechanic { NONE, FALLING_NOTES, REQUIEM_HOLD_RELEASE, DARKHEART_CONTROL, BELLONA_CONTROL, DREAMBREAKER_REVERSE, TRANQUILITY_MANIA, NOISEFORM_TRACKING, VERDANT_GREEN_ZONE, LULLABY_LIGHT_SECTORS, CASTBOUND_SWORD_MARKER, REMEMBRANCE_MODE, MIGU_AUTO_BAR, HALIBUT_EXCLAMATION, SPLITBRANCH_FISH_PICK, ASTRAENUS_PRESET, TRYHARD_DARK_RED_BAR, CHASM_PRESET, STELLAR_ZODIAC }

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
        Profile("None", DetectionMode.DEFAULT, ControlMode.ADAPTIVE, setOf(Mechanic.NONE)),
        Profile("Pinions Aria", DetectionMode.NOTE_TRACKING, ControlMode.NOTE_PRIORITY, setOf(Mechanic.FALLING_NOTES), requiresNoteDetection=true, hasCustomMinigame=true),
        Profile("Requiem", DetectionMode.DEFAULT, ControlMode.PRECISION, setOf(Mechanic.REQUIEM_HOLD_RELEASE), hasCustomMinigame=true),
        Profile("Darkheart", DetectionMode.DEFAULT, ControlMode.PRECISION, setOf(Mechanic.DARKHEART_CONTROL), hasCustomMinigame=true),
        Profile("Bellona's Waraxe", DetectionMode.DEFAULT, ControlMode.PRECISION, setOf(Mechanic.BELLONA_CONTROL), hasCustomMinigame=true),
        Profile("Dreambreaker", DetectionMode.DEFAULT, ControlMode.REVERSED_AFTER_DELAY, setOf(Mechanic.DREAMBREAKER_REVERSE), hasCustomMinigame=true),
        Profile("Tranquility", DetectionMode.NOTE_TRACKING, ControlMode.NOTE_PRIORITY, setOf(Mechanic.TRANQUILITY_MANIA), requiresNoteDetection=true, hasCustomMinigame=true),
        Profile("Noiseform", DetectionMode.COLOR_TRACKING, ControlMode.ADAPTIVE, setOf(Mechanic.NOISEFORM_TRACKING), requiresNoteDetection=true, hasCustomMinigame=true),
        Profile("Verdant Oath", DetectionMode.GREEN_ZONE, ControlMode.LINES, setOf(Mechanic.VERDANT_GREEN_ZONE), fishColorKey="VerdantFishColors", barColorKey="VerdantBarColors", supportsColorCalibration=true, hasCustomMinigame=true),
        Profile("Lullaby", DetectionMode.NOTE_TRACKING, ControlMode.NOTE_PRIORITY, setOf(Mechanic.LULLABY_LIGHT_SECTORS), requiresNoteDetection=true, hasCustomMinigame=true),
        Profile("Castbound", DetectionMode.COLOR_TRACKING, ControlMode.ADAPTIVE, setOf(Mechanic.CASTBOUND_SWORD_MARKER), fishColorKey="CastboundFishColors", barColorKey="CastboundBarColors", trackColorKey="CastboundTrackColors", supportsColorCalibration=true),
        Profile("Remembrance", DetectionMode.SPECIAL_MARKER, ControlMode.ADAPTIVE, setOf(Mechanic.REMEMBRANCE_MODE), hasCustomMinigame=true),
        Profile("MiguRod", DetectionMode.AUTO_BAR, ControlMode.ADAPTIVE, setOf(Mechanic.MIGU_AUTO_BAR), fishColorKey="MiguFishColors", supportsColorCalibration=true),
        Profile("Halibut Harpoon", DetectionMode.SPECIAL_MARKER, ControlMode.ADAPTIVE, setOf(Mechanic.HALIBUT_EXCLAMATION), hasCustomMinigame=true),
        Profile("Splitbranch Twig", DetectionMode.SPECIAL_MARKER, ControlMode.ADAPTIVE, setOf(Mechanic.SPLITBRANCH_FISH_PICK), hasCustomMinigame=true),
        Profile("Astraenus Serenade", DetectionMode.DEFAULT, ControlMode.ADAPTIVE, setOf(Mechanic.ASTRAENUS_PRESET)),
        Profile("Tryhard Rod", DetectionMode.DARK_RED_BAR, ControlMode.ADAPTIVE, setOf(Mechanic.TRYHARD_DARK_RED_BAR), fishColorKey="TryhardFishColors", barColorKey="TryhardBarColors", trackColorKey="TryhardTrackColors", supportsColorCalibration=true),
        Profile("Chasm Stalker", DetectionMode.DEFAULT, ControlMode.ADAPTIVE, setOf(Mechanic.CHASM_PRESET)),
        Profile("Stellarwave Melody", DetectionMode.ZODIAC, ControlMode.ADAPTIVE, setOf(Mechanic.STELLAR_ZODIAC), fishColorKey="StellarFishColors", barColorKey="StellarBarColors", headColorKey="StellarHeadColors", supportsColorCalibration=true, hasCustomMinigame=true)
    )

    private val byName = profiles.associateBy { normalize(it.name) }
    fun names(): Array<String> = profiles.map { it.name }.toTypedArray()
    fun all(): List<Profile> = profiles.toList()
    private fun normalize(value: String) = value.trim().lowercase()
    private fun canonicalName(value: String?): String = when {
        value.equals("Pinion Aria", true) -> "Pinions Aria"
        value.equals("Tryhard", true) -> "Tryhard Rod"
        value.equals("Halibut", true) -> "Halibut Harpoon"
        else -> value?.trim().orEmpty()
    }
    fun get(name: String?): Profile = byName[normalize(canonicalName(name))] ?: profiles.first()
    fun canonicalize(name: String?): String = get(name).name
    fun isKnown(name: String?): Boolean = byName.containsKey(normalize(canonicalName(name)))
    fun hasMechanic(name: String?, mechanic: Mechanic) = get(name).mechanics.contains(mechanic)
    fun defaultControlMode(name: String?) = get(name).controlMode
    fun defaultDetectionMode(name: String?) = get(name).detectionMode
}
