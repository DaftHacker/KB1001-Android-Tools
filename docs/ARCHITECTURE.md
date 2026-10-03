# Architecture

## Android front end

The Android application is intentionally a controller, not the owner of the long-running game detector.

It provides:

- manual Stock 696, Dynamic 744, Performance 744 and guarded Extreme 792 controls;
- AutoBoost enable/disable;
- game and idle profile selection;
- foreground-detection interval selection;
- installed-app game picker;
- live module/GPU/backend state;
- backend restart control.

The app invokes the root controller at:

`/data/adb/modules/kb1001_gpu_profiles/kb1001ctl`

Closing the Android UI therefore has no effect on AutoBoost.

## Persistent root backend

The Magisk module owns the persistent backend. `service.sh` is started by Magisk after boot and starts `game_boost.sh` when AutoBoost is enabled.

The daemon:

1. determines the current foreground package;
2. checks it against `/data/adb/kb1001_gpu_profiles/games.list`;
3. applies the configured game profile only when entering/switching games;
4. restores the configured idle profile after leaving a registered game;
5. continues running with no Android activity or app process alive.

This is preferable to an app-owned Android background service on this rooted device because Android 15 foreground-service restrictions and OEM process management do not control the Magisk daemon.

## Future backend

The polling detector is deliberately replaceable. A future native daemon can subscribe to Android Binder process/activity events and feed the same state machine. The app-facing `kb1001ctl` interface should remain stable so the front end does not need to be redesigned.
