# WSFisch Mobile Macro — starter project

Android starter project for a mobile macro runner with an independent AHK-like interpreter module.

## Modules
- `app`: Kotlin Android UI, accessibility service declaration and touch bridge.
- `scriptengine`: standalone Java interpreter module. Keeping it separate lets us replace or extend the runtime without coupling it to Android UI.

## Current prototype support
- AHK-style `Sleep, 500` (v1.1) and `Sleep(500)` (v2)
- AHK-style `Click, 540, 960` (v1.1) and `Click(540, 960)` (v2)
- `Loop, 3` / `Loop 3` blocks with braces
- `#Requires AutoHotkey v1.1` / `#Requires AutoHotkey v2.0` dialect hints

This is a compatibility prototype, not the official AutoHotkey interpreter. It does not run Windows API, registry, GUI, DLL, COM, or arbitrary Windows scripts. PixelSearch/ImageSearch and screen capture are planned for a later phase.

## Build
Open this folder in Android Studio (JDK 17), allow Gradle sync, then run the `app` configuration on an Android device.

## First run
1. Install and open the app.
2. Open Android Settings > Accessibility > Downloaded apps/services > WSFisch Macro Touch Service and enable it.
3. Return to the app and run a simple test script.
4. Android may show additional confirmation prompts for accessibility or overlay permissions.

The prototype uses AccessibilityService `dispatchGesture` for touch gestures. Android requires the user to explicitly enable the service. Keep the service disabled when you are not using macros.

## Example
```ahk
#Requires AutoHotkey v1.1
Loop, 3
{
    Click, 540, 960
    Sleep, 500
}
```

Equivalent v2-style syntax for the currently supported commands:
```ahk
#Requires AutoHotkey v2.0
Loop 3 {
    Click(540, 960)
    Sleep(500)
}
```
