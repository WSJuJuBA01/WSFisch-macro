package com.wsfisch.mobile

fun main() {
    val names = RodProfiles.names().toList()
    check(names.size == 19) { "Expected 19 rod profiles, got ${names.size}" }
    check(names.first() == "None")
    check(RodProfiles.canonicalize("Pinion Aria") == "Pinions Aria")
    check(RodProfiles.canonicalize("Tryhard") == "Tryhard Rod")
    check(RodProfiles.canonicalize("Halibut") == "Halibut Harpoon")
    for (name in names) check(RodProfiles.isKnown(name)) { "Unknown profile: $name" }
    println("PASS RodProfiles catalog: ${names.size} profiles and legacy aliases")
}
