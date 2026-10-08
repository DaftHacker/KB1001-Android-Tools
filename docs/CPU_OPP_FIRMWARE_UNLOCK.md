# A333 CPU OPP firmware lock/unlock integration

## Status (Stage 3B physical verification)

The KB1001 VF0403 per-OPP DTB patcher is included in the Android source tree as `CpuOppDtbPatcher`. It modifies an extracted Device Tree Blob **in memory only**; it cannot unpack, repack, flash, reboot, or change runtime cpufreq.

Physical test (Stage 3B, 2026-10-08): A 32 MiB custom `vendor_boot_a` image with only CPU0–1 1512 MHz disabled was flashed and booted successfully; Android reported `sys.boot_completed=1`, `available=N`, `turbo=N`, `0 uV` for 1512, while other previously exposed OPPs were unchanged. The exact candidate SHA256 was `a4906f29b8ae138fee0606e017e9405d8eebfef72d749fcae0aef6dbb76ce13c`. A verified pre-flash recovery image was preserved (Stage15 SHA256 `9eb390f96d2c3b320aff471ee33bb478f6ea7ff682e954305b418f4a31b1fb15`). **That is a single-configuration boot test, not approval for unattended flashing.**

Eligible tested OPPs: policy0 1296/1344/1368/1416/1464/1512; policy2 1776; policy4 1560/1608 MHz. 1800 MHz (Stage 15) still needs load qualification and is intentionally rejected by patcher.

## Correct design for app-controlled *firmware* unlock/lock

1. Verify device identity, boot slot, VF0403 profile, charging/battery condition, and immutable base-boot image provenance.
2. Read active image into private regular-file staging; save externally accessible verified recovery backup BEFORE mutations. Do not overwrite a previously verified recovery.
3. Extract DTB with tested on-device MagiskBoot, read it into app-owned Java, inspect every approved OPP and selected target, and patch only that one.
4. Verify that the entire DTB is semantically identical outside the intended `opp-microvolt-vf0403` / `turbo-mode` properties. A global reference-DTB comparison is essential; byte-diff alone is insufficient because serialization changes layout.
5. Repack to a new 32 MiB **regular file** and re-unpack; compare every non-DTB component SHA and target DTB semantic result.
6. Record a manifest with source image SHA, candidate SHA, target OPP, action, other OPP states, partition and backup location.
7. Only after tested recovery, explicit user confirmation and *independent* checks may a separately developed installer flash `vendor_boot_a`. Flashing and reboot must not occur during candidate generation.
8. After reboot, verify full-image SHA, `sys.boot_completed`, actual OPP debugfs values and cpufreq boost frequency sets before marking successful. Failure must preserve recovery and block subsequent actions.
9. The existing `cpu_control.sh` backend pins OC support to exact stage hashes. Custom combinations require a verified provenance manifest + live per-OPP VF/voltage/boost checks; **do not** weaken the checks to accept arbitrary modified images. Once approved, `cpu oc` must reject a locked frequency and retain safeguards for global boost.

## Current repo limitations

The Java DTB patcher and regression tests are committed. **The Android UI, live image preparation and actual flashing are not yet integrated.** No automatic flashing is enabled. Earlier draft PR #11 implemented app-level access gating, not firmware unlock, and was closed without merge.

## Test

Use extracted KB1001 VF0403 `original.dtb`, or a synthetic fixture of the same expected structure:
```bash
mkdir -p /tmp/kb1001-opp-test
javac -d /tmp/kb1001-opp-test \
  app/src/main/java/com/dafthacker/kb1001perf/CpuOppDtbPatcher.java \
  tests/opp-firmware/CpuOppDtbPatcherTest.java
java -cp /tmp/kb1001-opp-test CpuOppDtbPatcherTest /path/to/original.dtb
```
No partition writes take place in this test.
