# Firmware OPP candidate staging — NO FLASH

**State:** backend staged on main, Android UI preparation is pending. This is an engineering preview, not an approved installer.

The physically tested Stage 3B image has SHA256 `a4906f29b8ae138fee0606e017e9405d8eebfef72d749fcae0aef6dbb76ce13c`. It is the exact Stage 15 candidate with only CPU0–1 1512 MHz disabled.

The rooted controller exposes:

```sh
su -c 'sh /data/local/kb1001perf/backend/kb1001ctl firmware status'```

It reports the live SHA and nine real OPP debugfs entries. The current backend also accepts `firmware start tx-123 1512 unlock` and `firmware finish tx-123 1512 unlock PATCHED_DTB_SHA256`, **but do not run those directly without the DTB patching and independent semantic validator**. `start` extracts the boot image into ordinary private files and retains an exact SHA-checked recovery copy; `finish` verifies DTB SHA, repack readback and every non-DTB component. Neither flashes or reboots.

**Hard limits:**

- Only the exact Stage 15 and tested 1512-locked Stage 3B source images are currently allowlisted.
- Nine fully qualified frequencies only (1296–1776 MHz; excluding the unvalidated 1800).
- The current `cpu_control.sh` still requires known stage-image hashes; support for arbitrary generated combinations must not be enabled until per-image provenance and live checks are established.
- Repacked custom images are NOT authorized for installation by this tool.
- Firmware preparation must not be interpreted as unlocking an OPP on the running kernel.
- Production automation needs Android UI integration, full-tree semantic verification in the app path, automatic rollback/recovery protocol, post-reboot verification and tests on this device.
- Keep the verified original backup accessible from a separate Linux/USB recovery host.

## Device procedure

1. Use the existing physically booted candidate and host-side recovery backup.
2. Inspect `firmware status`, verify OPP availability and voltage.
3. Build candidates into root-only ordinary files; export candidate + recovery and hashes to the recovery host.
4. Validate candidate against the original DTB semantically and re-unpack independently.
5. Do not flash automatically or silently.

See `docs/CPU_OPP_FIRMWARE_UNLOCK.md` for the architecture and separate staged physical boot validations.
