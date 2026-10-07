# WORK_STATE

Updated: 2026-10-07
Authoritative branch: `main`
Production integration baseline: `e8f57f746b60d926d817e8d4bd8380c0101ce173` — Integrate validated CPU4 1608 MHz overclock

## Current task

Complete validated CPU overclock support for all three A333 CPU domains without weakening recovery, VF, voltage, or thermal safety.

CPU4 / Cortex-A73 is complete through 1608 MHz at the project's current short-load validation standard. Next action is a separate Stage 8 validation for CPU2-3 / Cortex-A53 at 1776 MHz / 1.15 V.\n\nExternal Stage 8 build-only tooling has now been prepared from the validated Stage 7 workflow. It is intentionally not committed to this repository and has not yet been run against the physical Stage 7 image.

## Repository status

- PR #3, `Integrate validated CPU4 1608 MHz overclock`, is merged to `main`.
- Main post-merge GitHub Actions build #453 passed, including backend shell validation, APK build/signing verification, update manifest generation, and dev-latest publication.
- Branch `a333-cpu4-1608-integration` still exists but is fully merged. `main` is ahead only by the merge commit and there are no file differences. It is safe to delete.
- Temporary recon/validation scripts are intentionally **not** stored in this repo. Keep future physical-validation helpers external until a feature is validated and ready for production integration.

## Validated CPU4 state

SoC / kernel context:

- Device: KB1001
- SoC: Allwinner A333 / `sun65iw1p1`
- Kernel: `6.6.77-android15-8-g9fab1123989e-ab13366090-4k`
- cpufreq driver: `sunxi-cpufreq-hw`
- active VF selector: `dvfs_code=0x0034` -> `vf0403`
- VF version: `V0.1`

Known vendor_boot hashes:

- Stock `vendor_boot_a`: `11efaf3483b2ef4250ab78b6a160e64adf80d82d3965f156554189ec8083d402`
- Validated Stage 6 / CPU4 1560 image: `def940b0dbb58c68e2b815143f2829f5bef5688e6e62e7e211387fc582e45c87`
- Validated Stage 7 / CPU4 1560+1608 image: `3107c282f462fc680dfafd82fb1f2a88c8b93c79cdb6904828ec061fa32b10f7`
- Stage 7 embedded DTB: `1767ddc904bfa490d2e7b622fb28961a2175d985a26eb51fb9ffab331de6ca1d`

Validated higher OPPs:

- CPU4 1560 MHz @ 1,150,000 uV, turbo, Stage 6C short CPU4 load PASS.
- CPU4 1608 MHz @ 1,150,000 uV, turbo, Stage 7C short CPU4 load PASS.

Stage 7 physical validation completed:

- Stage 7A: passive boot PASS; boost remained 0; normal policy4 ceiling 1512 MHz; 1560 and 1608 OPPs present/turbo/1.15 V.
- Stage 7B: no-load 1608 transition PASS; all five samples at 1608 MHz, `pll-cpu2=1608000000`, `axp1530-dcdc1=1150000` uV; restored boost=0 / 1512 MHz.
- Stage 7C: CPU4-pinned ~5 s userspace busy-loop PASS; affinity verified `Cpus_allowed_list: 4`; all five samples held 1608 MHz / PLL 1608 / rail 1.15 V; max sampled thermal ~46.5 C; zero new DVFS/cpufreq/OPP/PLL errors; restored boost=0 / 1512 MHz.
- This is short-load validation, not long-duration thermal/stress qualification.

## Production integration now on main

Relevant files:

- `app/src/main/assets/backend/cpu_control.sh`
- `app/src/main/java/com/dafthacker/kb1001perf/CpuActivity.java`
- `app/src/main/java/com/dafthacker/kb1001perf/MainActivity.java`
- `README.md`
- `docs/VENDOR_BOOT_OPP_HANDOFF.md`

Behavior:

- Exact Stage 6 and Stage 7 vendor_boot hashes are recognized.
- Unknown/modified vendor_boot configurations fail closed.
- 1560 remains available on validated Stage 6 and Stage 7 images.
- Stage 7 exposes `Dynamic 1608` and `Performance 1608`.
- 1608 controls require the exact validated Stage 7 image plus live 1608 turbo OPP @ 1.15 V.
- CPU4 OC enters boost from powersave, then clamps policy4 to the requested validated target before applying schedutil/performance.
- Normal boot remains boost disabled with the stock 1512 MHz policy4 ceiling.
- CPU2-3 1776 and CPU0-1 1296 remain locked.
- Critical thermal protection is unchanged.

## CPU cluster mapping / next candidates

### policy0 — CPUs 0-1 / Cortex-A53

- stock max: 1200 MHz
- regulator: `axp2202-dcdc1`
- PLL/debug clock: `pll-cpu0`
- stock 1200 MHz target: 1.10 V
- next conservative candidate: **1296 MHz @ 1.10 V**
- validate only after Stage 8; decide turbo-mode behavior deliberately because boost is global.

### policy2 — CPUs 2-3 / Cortex-A53

- stock max: 1752 MHz
- regulator: `axp1530-dcdc2`
- PLL/debug clock: `pll-cpu1`
- stock 1752 MHz target: 1.15 V
- next candidate: **1776 MHz @ 1.15 V**
- this is the immediate Stage 8 target.

### policy4 — CPU4 / Cortex-A73

- stock max: 1512 MHz
- regulator: `axp1530-dcdc1`
- PLL/debug clock: `pll-cpu2`
- validated 1560 and 1608 MHz at 1.15 V
- do not chase 1704/1752 before the other clusters are completed unless explicitly requested.

## Stage 8 exact next action

Run the external Stage 8 build-only package against the exact validated Stage 7 image (`3107c282f462fc680dfafd82fb1f2a88c8b93c79cdb6904828ec061fa32b10f7`). The prepared tooling changes **only policy2 / CPU2-3 1776 MHz for vf0403 to 1.15 V**, marks that node `turbo-mode`, preserves the validated CPU4 1560/1608 OPPs, and verifies policy2 1800 remains disabled.

Required sequence:

1. Deterministic DTB patch limited to the cluster1/policy2 OPP table; preserve all Stage 7 CPU4 changes and all unrelated DT/vendor_boot data.
2. Repack and independently verify component identity plus exact OPP semantics before flashing.
3. Create/verify a recovery backup before any flash.
4. Passive first boot with global boost disabled.
5. No-load transition test.
6. Short CPU2-3-targeted load test while monitoring `pll-cpu1`, `axp1530-dcdc2`, thermals, and kernel DVFS errors.
7. Restore boost=0 and normal stock ceilings after every test.
8. Only after PASS, integrate 1776 into production app controls/status.

### Global boost caveat for Stage 8

The Linux cpufreq boost switch is global. A Stage 8 image will contain already-validated CPU4 turbo OPPs plus the new CPU2-3 turbo candidate. Therefore Stage 8 test/runtime code must explicitly clamp every non-target boosted policy to its intended ceiling when boost is enabled. Do not assume enabling boost affects only the policy being tested.

For a CPU2-3 1776 validation pulse, keep CPU4 controlled at its intended safe ceiling (normally stock 1512 for an isolated Stage 8 test) and clamp policy2 to 1776 only after entering the boost path safely.

## Hard safety constraints

- Never live unbind/rebind the cpufreq driver; prior Stage 3A testing caused a crash/reset.
- Never globally map vf0403 to another VF profile.
- Never copy faster-bin voltages blindly.
- Do not assume 1.12 V vf0300 is valid for vf0403.
- Current experimental ceiling is 1.15 V unless a separate validation explicitly establishes otherwise.
- Do not alter the critical thermal shutdown trip.
- Do not expose a new hidden OPP to normal app users until its physical staged validation passes.
- Preserve a recovery route that does not require Android to boot.
- Empty slot B is not a recovery strategy.
- This tablet's U-Boot fastboot interface can take roughly 86 seconds to enumerate; allow up to 300 seconds before treating bootloader enumeration as failed.
- Do not put temporary recon/validation scripts into the production repository.

## External validation notes

Recent standalone validation used Android/Toybox `taskset`. CPU4 affinity syntax is a bare hexadecimal mask, not util-linux `-c` and not a `0x` prefix:

- CPU4 mask: `10`
- validation probe: `Cpus_allowed_list: 4`

Keep the same Toybox affinity semantics in future standalone validation scripts.

## Resume protocol

On the next execution:

1. Verify `main` and remote status before editing.
2. Read this file.
3. Inspect recent commits and any diff.
4. Read only `cpu_control.sh`, the vendor_boot handoff, and other files directly needed for Stage 8.
5. Keep Stage 8 build/recon/test tooling external to the repo.
6. Continue with CPU2-3 1776 MHz / 1.15 V candidate generation and staged validation.
