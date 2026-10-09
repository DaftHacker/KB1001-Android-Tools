# Firmware OPP candidate staging — NO FLASH

**State (2026-10-09):** Android preparation UI and backend are implemented. Stage 4B boot-tested an app-generated 1512 MHz unlock image; Stage 4C checked all nine targeted runtime OPPs. **No automatic installation is implemented or authorized.**

The physically tested Stage 3B image has SHA256 `a4906f29b8ae138fee0606e017e9405d8eebfef72d749fcae0aef6dbb76ce13c`. It is the exact Stage 15 candidate with only CPU0–1 1512 MHz disabled.

The rooted controller exposes:

```sh
su -c 'sh /data/local/kb1001perf/backend/kb1001ctl firmware status'```

It reports the live SHA and nine real OPP debugfs entries. The current backend also accepts `firmware start tx-123 1512 unlock` and `firmware finish tx-123 1512 unlock PATCHED_DTB_SHA256`, **but do not run those directly without the DTB patching and independent semantic validator**. `start` extracts the boot image into ordinary private files and retains an exact SHA-checked recovery copy; `finish` verifies DTB SHA, repack readback and every non-DTB component. Neither flashes or reboots.

**Hard limits:**

- Only the exact Stage 15, Stage 3B and Stage 4C boot-tested source images are currently allowlisted. The allowlist does not automatically accept arbitrary app-generated images.
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

## Unified nine-OPP configuration (2026-10-09)

The Android firmware screen now offers nine independent lock/unlock switches and one **PREPARE NINE-OPP CONFIGURATION** operation. The Java DTB patcher applies the selected states to one DTB. The backend's `firmware start TX 0 config` / `firmware finish TX 0 config SHA` routes produce a single candidate image and a SHA-verified copy of the previously installed image. The manifest uses `frequency_mhz=0` and `action=config` to indicate a combined operation.

The backend remains pinned to exact Stage 15, Stage 3B or Stage 4C source image hashes. Arbitrary newly installed configurations cannot yet be used as the next trusted source, because they need provenance validation; the existing runtime OC backend also retains its independent exact-image allowlist. Preparing a combined image does not write any partition or activate its OPP changes. The candidate is untested for boot compatibility. Keep the previously verified external Linux fastboot recovery image.

Each configuration operation produces one candidate, not nine candidates. Historical transaction directories and their recovery copies are intentionally retained instead of automatically deleting the only backups. Automatic installation, deletion of backed-up firmware, and updating the running kernel are not implemented.

## Stage 4B / Stage 4C validated milestone (2026-10-09)

Stage 4B source image: Stage 3B locked `vendor_boot_a`, SHA256 `a4906f29b8ae138fee0606e017e9405d8eebfef72d749fcae0aef6dbb76ce13c`.

App-generated 1512 MHz **unlock** candidate and now physically booted Stage 4C image:

- `vendor_boot_a` SHA256: `4f7e071938cc4712f2ee9e77c657db38c64f353221b69a96e5c14076bf13163f`
- Candidate DTB SHA256: `aacfecd3dc616994c0d8f461b25b4335bf720504d3ee09467164c4e0df2146bf`
- Prepared manifest: transaction `tx-1791527896204`, 32 MiB, target `1512`, action `unlock`, `installation=NOT_PERFORMED` at preparation time.
- Subsequent **manual** fastboot flash was followed by `sys.boot_completed=1`; running partition SHA exactly matched the candidate.
- Kernel debugfs `cpu0/opp:1512000000` reported `available=Y`, `turbo=Y`, `u_volt_target=1150000`.
- Stage 4C checked all nine approved OPPs: 1296 MHz @ 1100000 µV, 1344/1368/1416/1464/1512/1560/1608/1776 MHz @ 1150000 µV; every entry `available=Y`, `turbo=Y`.
- `policy0/scaling_max_freq=1200000`, `policy2/scaling_max_freq=1752000`, `policy4/scaling_max_freq=1512000`, global CPU boost `0`.

The main firmware backend now pins Stage 4C's exact full-image SHA **and** known-good DTB SHA, allowing future **candidate preparation** from this boot-tested state. This does **not** enable flashing, runtime boost or arbitrary OPP combinations. Load/stability validation for 1512 MHz and the experimental 1800 MHz OPP remains outstanding.
