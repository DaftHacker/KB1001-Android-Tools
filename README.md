# KB1001 Android Tools

Root utilities and performance tooling for the AUMI/Ainmel KB1001 (Allwinner A333) Android tablet.

## Performance Manager

The project is split into two independent halves:

- **`app/` — Android front end:** profile switching, AutoBoost setup, installed-app game selection, polling settings, live status, and backend controls.
- **`module/` — Magisk backend:** persistent root daemon that starts at boot, detects selected foreground games, applies the game profile, and restores the idle profile.

**Closing the Android app does not stop game detection or profile switching.** The UI only sends commands to the root backend.

### Current validated GPU profiles

- Stock 696 MHz — factory 200 / 300 / 400 / 600 / 696 MHz table with DVFS
- Performance 696 MHz — validated stock maximum pinned
- Experimental 744 MHz — manual/session-only runtime OPP mode
- Experimental 792 MHz — manual/session-only runtime OPP mode

Default AutoBoost behavior is **Stock 696 outside games → Performance 696 in selected games → Stock 696 after leaving the game**. AutoBoost never selects 744/792 MHz experimental modes.

### A333 CPU and thermal mapping

Recon identifies the KB1001 SoC as **Allwinner A333 / sun65iw1p1** with 4× Cortex-A53 + 1× Cortex-A73 across three cpufreq policies. The app-owned backend now exposes stock CPU governor/min/max controls plus a Thermal Manager for verified writable non-critical CPU/GPU trip temperatures. Critical shutdown trips remain locked.

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

- Automatic mode never uses the experimental 744/792 MHz OPP modes.
- Runtime OPP changes wait for a safe GPU suspend window.
- Failed OPP changes attempt stock runtime recovery.
- Thermal protection is not disabled.
- Experimental GPU OPP modes keep a validated reboot fallback.


<!-- UI redesign work in progress -->


### Validated CPU boot OPPs

CPU0-1 1296 MHz at 1.10 V and 1344 MHz at 1.15 V, CPU2-3 1776 MHz at 1.15 V, and CPU4 1560/1608 MHz at 1.15 V on VF0403 have completed staged DTB/repack, patched boot, boost-transition, idle, and short pinned-load validation. The one-time `vendor_boot_a` enablement remains an explicit external operation; Performance Manager recognizes exact validated Stage 6/7/8/9/10 image states and exposes only OC controls whose image hash and live OPP semantics match.


## Validated A333 overclock controls

- **CPU:** normal MIN/MAX/governor controls stay on non-boost frequencies. CPU0-1 1296 MHz @ 1.10 V and 1344 MHz @ 1.15 V, CPU2-3 1776 MHz @ 1.15 V, and CPU4 1560/1608 MHz @ 1.15 V are explicit overclock paths using validated `vendor_boot_a` turbo OPPs, Linux cpufreq boost, and VF0403. Because the boost switch is global on this platform, the backend holds all CPU policies at the low OPP across boost transitions and re-clamps non-target policies before applying the selected OC.
- **GPU:** 200/300/400/600/696 MHz are factory OPPs. 744/792 MHz remain session-only runtime OPP experiments through Allwinner `gpu_opp_ops`; read-back checks verify the requested state.
- **Thermal:** controls map to writable Linux thermal trip points for CPU/GPU/idle cooling. Critical shutdown trips are not modified.
- **Settings:** CPU OC support reports the exact validated boot/OPP state; unknown configurations fail closed.
