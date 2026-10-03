KB1001 Performance Manager / GPU Profiles v1.2-AutoBoost
=========================================================

This extends the already-tested KB1001 GPU Profiles v1.1 with an automatic
foreground-game state machine. It intentionally does NOT enable unverified
CPU, DDR, or thermal-control writes.

Safe default AutoBoost behavior
-------------------------------
Outside a selected game: Dynamic 744 (DVFS enabled)
Inside a selected game:  Performance 744 (744 MHz pinned)
Game exits/loses foreground: Dynamic 744 restored

Extreme 792 is blocked from unattended AutoBoost. It remains a manual,
session-only test profile with Dynamic 744 as the reboot fallback.

The Magisk backend owns game detection, so AutoBoost keeps running when the
companion Android app is closed.

Controller examples
-------------------
su -c '/data/adb/modules/kb1001_gpu_profiles/kb1001ctl game add com.example.game'
su -c '/data/adb/modules/kb1001_gpu_profiles/kb1001ctl auto enable'
su -c '/data/adb/modules/kb1001_gpu_profiles/kb1001ctl status'

Safety
------
- Existing suspend-safe Allwinner OPP replacement logic is retained.
- Thermal protection is never disabled.
- Automatic mode never selects Extreme 792.
- CPU and DDR tuning remain untouched until their actual KB1001 interfaces are validated.
