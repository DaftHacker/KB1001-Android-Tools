# KB1001 Android Tools

Root utilities and performance tooling for the AUMI/Ainmel KB1001 (Allwinner A333) Android tablet.

## Performance Manager

The project is split into two independent halves:

- **`app/` — Android front end:** profile switching, AutoBoost setup, installed-app game selection, polling settings, live status, and backend controls.
- **`module/` — Magisk backend:** persistent root daemon that starts at boot, detects selected foreground games, applies the game profile, and restores the idle profile.

**Closing the Android app does not stop game detection or profile switching.** The UI only sends commands to the root backend.

### Current validated GPU profiles

- Stock 696 MHz
- Dynamic 744 MHz
- Performance 744 MHz (pinned)
- Extreme 792 MHz — manual/session-only test mode; never selected by AutoBoost

Default AutoBoost behavior is **Dynamic 744 outside games → Performance 744 in selected games → Dynamic 744 after leaving the game**.

Thermal protection remains enabled. CPU and DDR tuning are intentionally not applied until the real KB1001 interfaces are mapped and validated.

## Build

The GitHub Actions workflow **Build KB1001 Performance Manager** validates the module scripts, builds the Android debug APK, packages the Magisk module, and uploads both as workflow artifacts.

Local build:

```bash
gradle :app:assembleDebug
bash scripts/package-module.sh
```

Android Studio can open the repository root directly.

## Install

1. Flash the generated `KB1001-GPU-Profiles-v1.2-AutoBoost.zip` in Magisk and reboot.
2. Install the generated Android APK.
3. Grant the app root access when prompted.
4. Choose AutoBoost games and enable AutoBoost from the app.

## Backend controller

The front end calls `/data/adb/modules/kb1001_gpu_profiles/kb1001ctl`. It can also be used manually:

```bash
su -c '/data/adb/modules/kb1001_gpu_profiles/kb1001ctl status'
su -c '/data/adb/modules/kb1001_gpu_profiles/kb1001ctl game add com.example.game'
su -c '/data/adb/modules/kb1001_gpu_profiles/kb1001ctl auto enable'
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the process/lifecycle design.

## Safety

This project is device-specific. Do not flash the module on unrelated hardware.

- Automatic mode never uses Extreme 792.
- Runtime OPP changes wait for a safe GPU suspend window.
- Failed OPP changes attempt stock runtime recovery.
- Thermal protection is not disabled.
- Extreme 792 keeps a safe reboot fallback.
