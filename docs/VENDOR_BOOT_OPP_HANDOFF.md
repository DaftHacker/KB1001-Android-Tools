# Hidden CPU OPP / vendor_boot handoff

This document records the remaining work for persistent CPU OPP activation on the verified KB1001 / Allwinner A333 platform. Runtime CPU, GPU, thermal, VF-selector, and OPP diagnostics are already integrated in Performance Manager. The remaining item is boot-image modification outside the app.

## Verified platform state

- SoC: Allwinner A333 / `sun65iw1p1`
- CPU topology: 4× Cortex-A53 + 1× Cortex-A73
- Present CPUs: `0-4`
- DVFS selector: `0x0034`
- VF table version: `V0.1`
- Active CPU VF profile: `vf0403`
- CPU OPP carrier: `vendor_boot_a`
- Stock mapped `vendor_boot_a` SHA-256:
  `11efaf3483b2ef4250ab78b6a160e64adf80d82d3965f156554189ec8083d402`

The kernel OPP framework and live device tree agree on the active `vf0403` voltage curve. Hidden higher-frequency OPP nodes already exist in the vendor device tree but are unavailable because their `vf0403` voltage entries are zero or otherwise not valid for this selector.

## Stock CPU domains

| Policy | CPUs | Core type | Stock range |
| --- | --- | --- | --- |
| `policy0` | 0-1 | Cortex-A53 | 408-1200 MHz |
| `policy2` | 2-3 | Cortex-A53 | 408-1752 MHz |
| `policy4` | 4 | Cortex-A73 | 408-1512 MHz |

The first hidden A73 nodes observed are 1560, 1608, 1704, and 1752 MHz. Other domains also contain hidden nodes.

These hidden frequencies are **diagnostic evidence only**. They are not automatically safe for this `vf0403` part, and voltage values from faster silicon bins must not be copied blindly.

## Boot / AVB layout

Recon established that the CPU/GPU OPP tables are carried by `vendor_boot_a`, not by `boot_a` or the board-only `dtbo_a` overlays.

The top-level AVB metadata references `vendor_boot`. The mapped image also contains its own integrity metadata. Any future image builder must preserve a structurally valid vendor_boot image and regenerate the appropriate integrity metadata for the modified image.

Performance Manager intentionally does not perform boot-partition modification or flashing.

## Remaining external work

A future boot-OPP implementation should be treated as a separate image-building workflow and should meet all of these requirements before it is considered ready:

1. Accept only a specifically verified stock `vendor_boot` input. Unknown or already-modified images must fail closed.
2. Modify only the intended OPP data for the selected VF profile; existing stock OPPs and other VF bins must remain unchanged.
3. Preserve the original vendor_boot container layout and all unrelated device-tree data.
4. Recompute and validate all integrity metadata associated with the resulting image.
5. Produce both a patched image and an independently verified stock restore image/backup path.
6. Never select hidden OPPs automatically. Activation must require an explicit experimental choice.
7. After boot, verify that the requested OPP appears in `scaling_available_frequencies` before the app treats the profile as active.
8. Validate stability, voltage behavior, temperature, cooling state, and kernel errors before exposing a higher stage.
9. Keep the 110 °C CPU critical protection unchanged.
10. Preserve a recovery route that does not depend on Android successfully booting.

## Suggested staged validation order

The first useful target is the A73 domain because single-thread-limited workloads benefit most from CPU4.

A conservative validation progression is:

- confirm stock `vf0403` state
- enable only one hidden A73 step
- confirm kernel exposure after boot
- perform short stability and thermal validation
- perform sustained validation
- only then consider a later stage

Do not enable an entire faster-bin table at once, and do not globally spoof another VF selector.

## App integration status

The `a333-thermal-controls` work already provides:

- live `dvfs_code` / VF version reporting
- `vf0403` detection
- kernel OPP availability maps
- hidden/exposed OPP diagnostics
- correct CPU topology and stock limits
- per-domain min/max/governor controls
- thermal manager and cooling-state reporting
- validated 696 MHz stock GPU handling
- explicit experimental labeling for above-stock GPU runtime modes
- vendor_boot carrier/readiness diagnostics

The app should continue to treat persistent hidden CPU OPP activation as an external/manual capability until a dedicated image-building and recovery workflow has been independently validated.

## Completion criteria for a future persistent OPP feature

The feature is ready to integrate into the app only when all of the following are demonstrated on the physical tablet:

- deterministic patched image generation from the verified stock image
- deterministic stock restoration
- integrity metadata validates after modification
- device boots reliably
- requested OPP appears in the running kernel
- stock restore returns the exact original behavior
- no regression to thermal protection, boot reliability, or recovery


## Current validated and candidate targets

CPU4 now has two validated higher OPPs on the active `vf0403` profile:

| Domain | OPP | VF0403 voltage | Validation |
| --- | ---: | ---: | --- |
| `policy4` / CPU4 Cortex-A73 | 1560 MHz | 1,150,000 uV | Stage 6C short CPU4 load PASS |
| `policy4` / CPU4 Cortex-A73 | 1608 MHz | 1,150,000 uV | Stage 7C short CPU4 load PASS |

The exact validated Stage 7 `vendor_boot_a` SHA-256 is:

`3107c282f462fc680dfafd82fb1f2a88c8b93c79cdb6904828ec061fa32b10f7`

That image preserves the validated 1560 MHz turbo OPP and adds only CPU4 1608 MHz as a second 1.15 V turbo OPP. Passive first boot, no-load transition, PLL tracking, regulator voltage, thermal telemetry, and a five-second CPU4-pinned busy-loop all passed. Normal boot/runtime state remains boost disabled with the 1512 MHz stock policy ceiling until the user explicitly selects an OC mode.

The next physical-validation targets are:

| Domain | Candidate | Proposed VF0403 voltage | Reason for candidate |
| --- | ---: | ---: | --- |
| `policy2` / CPU2-3 Cortex-A53 | 1776 MHz | 1,150,000 uV | hidden node exists only 24 MHz above the vf0403 stock 1752 MHz point, which already runs at 1.15 V |
| `policy0` / CPU0-1 Cortex-A53 | 1296 MHz | 1,100,000 uV | next dormant OPP above 1200 MHz while staying at the cluster's current validated peak rail |

CPU2-3 1776 MHz should be validated next as a separate Stage 8 image. CPU0-1 1296 MHz should follow separately. Because the Linux cpufreq boost switch is global, only one new unvalidated turbo OPP should be introduced per validation stage.

Temporary recon/validation scripts remain external to this repository. Production code should only contain the validated runtime controls and exact image-state detection needed by the app.

The app may expose CPU4 1560/1608 only when the running OPP table and exact known-good `vendor_boot_a` hash match the validated configuration. Unknown or modified configurations must fail closed.
