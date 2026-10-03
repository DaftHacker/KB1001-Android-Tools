# KB1001 Android Tools

Root utilities and performance tooling for the AUMI/Ainmel KB1001 (Allwinner A333) Android tablet.

## Performance Manager

This repository contains both halves of the KB1001 Performance Manager:

- **Android app (`app/`)** — the front end. It shows GPU/backend status, switches profiles, configures AutoBoost, and manages the game list.
- **Magisk backend (`module/`)** — the persistent root backend. It starts at boot, detects selected foreground games, applies the game profile, and restores the idle profile when the game is no longer foreground.

Closing the Android app does **not** stop AutoBoost. The app is only a controller; the Magisk daemon owns the long-running work.

### Current validated GPU profiles

- Stock 696 MHz
- Dynamic 744 MHz
- Performance 744 MHz (pinned)
- Extreme 792 MHz — manual/session-only test mode; never selected by AutoBoost

Thermal protection remains enabled. CPU and DDR tuning are intentionally not applied until the real KB1001 interfaces are mapped and validated.

## Build

GitHub Actions builds:

1. an installable debug APK; and
2. a flashable Magisk module ZIP.

Run the **Build KB1001 Performance Manager** workflow or push to `main`.

Locally, open the repository in Android Studio or build with a compatible Gradle 8.9 installation:

```bash
gradle :app:assembleDebug
bash scripts/package-module.sh
```

## Backend architecture

The first backend uses a small Magisk daemon that polls Android foreground focus and only performs profile work when the game/idle state changes. This avoids tying detection to the Android UI lifecycle.

A later optimization can replace polling with a native Binder process observer (the same general event-driven pattern used by projects such as Encore), while preserving the same app/controller interface.

## Safety

This project is device-specific. Do not flash the module on unrelated hardware.

- Automatic mode never uses Extreme 792.
- Runtime OPP changes wait for a safe GPU suspend window.
- Failed OPP changes attempt stock runtime recovery.
- Thermal protection is not disabled.
- Extreme 792 always keeps a safe reboot fallback.
