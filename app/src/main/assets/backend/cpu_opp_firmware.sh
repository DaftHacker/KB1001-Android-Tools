#!/system/bin/sh
# KB1001 CPU OPP candidate builder (no flashing, reboot or CPU boost).
set -eu
ROOT=/data/local/kb1001perf/opp_firmware
PART=/dev/block/by-name/vendor_boot_a
MAGISKBOOT=/data/adb/magisk/magiskboot
SIZE=33554432
S15=9eb390f96d2c3b320aff471ee33bb478f6ea7ff682e954305b418f4a31b1fb15
S3B=a4906f29b8ae138fee0606e017e9405d8eebfef72d749fcae0aef6dbb76ce13c
# Stage 4B/C: app-generated 1512 MHz unlock, physical boot and all nine OPPs verified.
S4C=4f7e071938cc4712f2ee9e77c657db38c64f353221b69a96e5c14076bf13163f
DTB_S4C=aacfecd3dc616994c0d8f461b25b4335bf720504d3ee09467164c4e0df2146bf
umask 077
err(){ echo "firmware_error=$1"; if [ -n "${dir:-}" ] && [ -d "$dir" ]; then echo "diagnostic_log=$dir/diagnostic.log"; fi; exit 3; }
sha(){ sha256sum "$1" | cut -d ' ' -f1; }
known(){
 case "$1" in "$S15"|"$S3B"|"$S4C") return 0;; esac
 # Accept an app-generated image only after a verified, completed boot record.
 [ -f "$ROOT/last_install_verified" ] || return 1
 [ "$(sed -n 's/^candidate_sha256=//p' "$ROOT/last_install_verified" | head -n 1)" = "$1" ]
}
allowed(){ case "$1" in 0|1296|1344|1368|1416|1464|1512|1560|1608|1776) return 0;; *) return 1;; esac; }
valid_tx(){ case "$1" in tx-[0-9]*) case "$1" in *[!a-z0-9-]*) return 1;; esac; return 0;; *) return 1;; esac; }
check(){
 [ "$(id -u)" = 0 ] || err no_root
 [ "$(getprop ro.boot.slot_suffix)" = _a ] || err unsupported_slot
 [ -e "$PART" ] && [ -x "$MAGISKBOOT" ] || err missing_required_device_files
 [ "$(getprop sys.boot_completed)" = 1 ] || err device_not_booted
 [ "$(cat /sys/devices/system/cpu/cpufreq/boost 2>/dev/null)" = 0 ] || err boost_enabled
 [ "$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq)" = 1200000 ] || err nonstock_policy0
 [ "$(cat /sys/devices/system/cpu/cpufreq/policy2/scaling_max_freq)" = 1752000 ] || err nonstock_policy2
 [ "$(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq)" = 1512000 ] || err nonstock_policy4
}
status(){
 check
 live="$(sha "$PART")"
 echo "live_vendor_boot_sha256=$live"
 if known "$live"; then echo "firmware_source=recognized"; else echo "firmware_source=unsupported"; fi
 for spec in 'cpu0 1296 1100000' 'cpu0 1344 1150000' 'cpu0 1368 1150000' 'cpu0 1416 1150000' 'cpu0 1464 1150000' 'cpu0 1512 1150000' 'cpu4 1560 1150000' 'cpu4 1608 1150000' 'cpu2 1776 1150000'; do
  set -- $spec
  dir="/sys/kernel/debug/opp/$1/opp:$(($2 * 1000000))"
  av="$(cat "$dir/available" 2>/dev/null || echo '?')"
  turbo="$(cat "$dir/turbo" 2>/dev/null || echo '?')"
  uv="$(cat "$dir/supply-0/u_volt_target" 2>/dev/null || echo '?')"
  echo "opp_$2=$av,$turbo,$uv,$3"
 done
 echo "firmware_flash_available=NO"
}
start(){
 tx="$1"; mhz="$2"; mode="$3"
 valid_tx "$tx" || err invalid_transaction
 allowed "$mhz" || err not_validated
 case "$mode" in lock|unlock|config) ;; *) err invalid_action;; esac
 [ "$mode" != config ] || [ "$mhz" = 0 ] || err invalid_combined_configuration
 [ "$mode" = config ] || [ "$mhz" != 0 ] || err invalid_single_frequency
 check
 live="$(sha "$PART")"
 known "$live" || err unknown_boot_image
 [ -d /data/local/kb1001perf ] || err backend_missing
 mkdir -p "$ROOT" && chmod 0700 "$ROOT" || err cannot_prepare_root
 dir="$ROOT/$tx"
 [ ! -e "$dir" ] || err duplicate_transaction
 mkdir -m 0700 -p "$dir/work" "$dir/check" || err staging_failed
 echo "$live" > "$dir/source.sha"
 echo "$mhz" > "$dir/mhz"
 echo "$mode" > "$dir/mode"
 dd if="$PART" of="$dir/recovery.img" bs=1048576 count=32 2>/dev/null || err backup_failed
 [ "$(wc -c < "$dir/recovery.img" | tr -d ' ')" = "$SIZE" ] || err backup_bad_size
 [ "$(sha "$dir/recovery.img")" = "$live" ] || err backup_mismatch
 unpack_rc=0
 ( cd "$dir/work" && "$MAGISKBOOT" unpack -h "$dir/recovery.img" ) > "$dir/magiskboot-unpack.log" 2>&1 || unpack_rc=$?
 echo "magiskboot_unpack_exit=$unpack_rc" > "$dir/diagnostic.log"
 echo "magiskboot_log=$dir/magiskboot-unpack.log" >> "$dir/diagnostic.log"
 # MagiskBoot may report a nonzero status despite extracting the components.
 # Trust only exact known-good hashes, never its exit code alone.
 if [ "$live" = "$S15" ]; then
  expected_dtb=0c6dd5d70f4ad6de5e378c330635483a444b0343821824fa49141b034de626f8
 elif [ "$live" = "$S3B" ]; then
  expected_dtb=88e5fdf7b249ba9e0111a139ea5b199480459ce8e8f7a2e018169cb435
 elif [ "$live" = "$S4C" ]; then
  expected_dtb="$DTB_S4C"
 elif [ -f "$ROOT/last_install_verified" ] && [ "$live" = "$(sed -n 's/^candidate_sha256=//p' "$ROOT/last_install_verified" | head -n 1)" ]; then
  prior_tx="$(sed -n 's/^transaction=//p' "$ROOT/last_install_verified" | head -n 1)"
  valid_tx "$prior_tx" || err invalid_verified_transaction
  [ "$(sha "$ROOT/$prior_tx/candidate.img")" = "$live" ] || err verified_candidate_not_preserved
  expected_dtb="$(sed -n 's/^patched_dtb_sha256=//p' "$ROOT/$prior_tx/MANIFEST.txt" | head -n 1)"
  [ "$(sha "$ROOT/$prior_tx/check/dtb")" = "$expected_dtb" ] || err verified_dtb_mismatch
 else
  err unknown_boot_image
 fi
 [ -s "$dir/work/dtb" ] || err missing_dtb
 [ "$(sha "$dir/work/dtb")" = "$expected_dtb" ] || err extracted_dtb_hash_mismatch
 [ -f "$dir/work/bootconfig" ] || err missing_bootconfig
 [ "$(sha "$dir/work/bootconfig")" = 2c377199832de350f65144ea82d2a9ccc7cd619ebc4ad4a865127302587da1e9 ] || err bootconfig_hash_mismatch
 [ -f "$dir/work/vendor_ramdisk/ramdisk.cpio" ] || err missing_ramdisk
 [ "$(sha "$dir/work/vendor_ramdisk/ramdisk.cpio")" = eaa00bac784b7a57db666df0f871155929acca302049b3fc28a6effc3e29f4bc ] || err ramdisk_hash_mismatch
 echo "source_components_verified=YES" >> "$dir/diagnostic.log"
 echo "transaction=$tx"
 echo "source_sha256=$live"
 echo "original_dtb=$dir/work/dtb"
 echo "recovery_image=$dir/recovery.img"
 echo "firmware_stage=extracted_no_flash"
 echo "diagnostic_log=$dir/diagnostic.log"
}
finish(){
 tx="$1"; mhz="$2"; mode="$3"; patched="$4"
 valid_tx "$tx" || err invalid_transaction
 allowed "$mhz" || err not_validated
 case "$mode" in lock|unlock|config) ;; *) err invalid_action;; esac
 case "$patched" in *[!a-f0-9]*|'') err invalid_sha;; esac
 [ "$(printf '%s' "$patched" | wc -c | tr -d ' ')" = 64 ] || err invalid_sha_length
 check
 dir="$ROOT/$tx"
 [ -d "$dir/work" ] && [ -d "$dir/check" ] || err missing_transaction
 [ "$(cat "$dir/mhz")" = "$mhz" ] && [ "$(cat "$dir/mode")" = "$mode" ] || err transaction_mismatch
 [ "$mode" != config ] || [ "$mhz" = 0 ] || err invalid_combined_configuration
 [ "$mode" = config ] || [ "$mhz" != 0 ] || err invalid_single_frequency
 source="$(cat "$dir/source.sha")"
 known "$source" || err unsupported_source
 [ "$(sha "$PART")" = "$source" ] && [ "$(sha "$dir/recovery.img")" = "$source" ] || err source_changed
 [ "$(sha "$dir/work/dtb")" = "$patched" ] || err patched_dtb_mismatch
 if [ "$mode" = config ]; then
  [ -f "$dir/selected_mask" ] || err missing_selected_opp_mask
  selection="$(cat "$dir/selected_mask")"
  case "$selection" in *[!01]*|'') err invalid_opp_mask;; esac
  [ "${#selection}" -eq 9 ] || err invalid_mask_length
 fi
 # Combined config is a candidate-only path. Exact boot-source and component verification still apply.
 # Some MagiskBoot versions return exit 3 even after producing a full image.
 # Do not accept a nonzero status unless complete independent checks pass.
 repack_rc=0
 ( cd "$dir/work" && "$MAGISKBOOT" repack "$dir/recovery.img" "$dir/candidate.img" ) > "$dir/magiskboot-repack.log" 2>&1 || repack_rc=$?
 echo "magiskboot_repack_exit=$repack_rc" >> "$dir/diagnostic.log"
 echo "repack_log=$dir/magiskboot-repack.log" >> "$dir/diagnostic.log"
 [ -f "$dir/candidate.img" ] || err candidate_not_created
 [ "$(wc -c < "$dir/candidate.img" | tr -d ' ')" = "$SIZE" ] || err candidate_bad_size
 echo "candidate_size_verified=YES" >> "$dir/diagnostic.log"
 readback_rc=0
 ( cd "$dir/check" && "$MAGISKBOOT" unpack -h "$dir/candidate.img" ) > "$dir/magiskboot-readback.log" 2>&1 || readback_rc=$?
 echo "magiskboot_readback_exit=$readback_rc" >> "$dir/diagnostic.log"
 echo "readback_log=$dir/magiskboot-readback.log" >> "$dir/diagnostic.log"
 [ -s "$dir/check/dtb" ] || err readback_dtb_missing
 [ "$(sha "$dir/check/dtb")" = "$patched" ] || err candidate_dtb_mismatch
 [ -f "$dir/check/bootconfig" ] || err readback_bootconfig_missing
 [ "$(sha "$dir/check/bootconfig")" = 2c377199832de350f65144ea82d2a9ccc7cd619ebc4ad4a865127302587da1e9 ] || err readback_bootconfig_bad_hash
 [ -f "$dir/check/vendor_ramdisk/ramdisk.cpio" ] || err readback_ramdisk_missing
 [ "$(sha "$dir/check/vendor_ramdisk/ramdisk.cpio")" = eaa00bac784b7a57db666df0f871155929acca302049b3fc28a6effc3e29f4bc ] || err readback_ramdisk_bad_hash
 echo "candidate_components_verified=YES" >> "$dir/diagnostic.log"
 ( cd "$dir/work" && find . -type f ! -name dtb ! -name header | sort | while IFS= read -r name; do sha256sum "$name"; done ) > "$dir/components_before.txt" || err component_list_failed
 ( cd "$dir/check" && find . -type f ! -name dtb ! -name header | sort | while IFS= read -r name; do sha256sum "$name"; done ) > "$dir/components_after.txt" || err component_list_failed
 [ -s "$dir/components_before.txt" ] || err component_list_empty
 cmp -s "$dir/components_before.txt" "$dir/components_after.txt" || err non_dtb_components_changed
 echo "non_dtb_components_unchanged=YES" >> "$dir/diagnostic.log"
 if [ "$repack_rc" -ne 0 ] || [ "$readback_rc" -ne 0 ]; then
  echo "magiskboot_nonzero_accepted_only_after_exact_readback=YES" >> "$dir/diagnostic.log"
 fi
 [ "$(sha "$PART")" = "$source" ] || err live_image_changed
 result="$(sha "$dir/candidate.img")"
 {
 echo "format=KB1001_CPU_OPP_STAGE_ONLY_V1"
 echo "transaction=$tx"
 echo "partition=vendor_boot_a"
 echo "source_sha256=$source"
 echo "candidate_sha256=$result"
 echo "candidate_size=$SIZE"
 echo "patched_dtb_sha256=$patched"
 echo "frequency_mhz=$mhz"
 echo "action=$mode"
 echo "installation=NOT_PERFORMED"
 if [ "$mode" = config ]; then echo "opp_mask=$selection"; fi
 } > "$dir/MANIFEST.txt"
 echo "transaction=$tx"
 echo "source_sha256=$source"
 echo "candidate_sha256=$result"
 echo "candidate_image=$dir/candidate.img"
 echo "recovery_image=$dir/recovery.img"
 echo "manifest=$dir/MANIFEST.txt"
 echo "firmware_stage=prepared_not_flashed"
 echo "NO_FLASH_PERFORMED"
 echo "NO_PARTITION_MODIFIED"
 echo "diagnostic_log=$dir/diagnostic.log"
}
manifest_value(){ sed -n "s/^$2=//p" "$1" | head -n 1; }
install_check(){
 tx="$1"; valid_tx "$tx" || err invalid_transaction
 check
 dir="$ROOT/$tx"; file="$dir/MANIFEST.txt"
 [ -f "$file" ] || err missing_manifest
 [ "$(manifest_value "$file" transaction)" = "$tx" ] || err manifest_mismatch
 [ "$(manifest_value "$file" partition)" = vendor_boot_a ] || err wrong_partition
 [ "$(manifest_value "$file" candidate_size)" = "$SIZE" ] || err candidate_size_bad
 [ "$(manifest_value "$file" installation)" = NOT_PERFORMED ] || err already_installed
 source="$(manifest_value "$file" source_sha256)"
 candidate="$(manifest_value "$file" candidate_sha256)"
 target_dtb="$(manifest_value "$file" patched_dtb_sha256)"
 [ "$(sha "$PART")" = "$source" ] || err running_firmware_changed
 [ "$(sha "$dir/recovery.img")" = "$source" ] || err recovery_mismatch
 [ "$(sha "$dir/candidate.img")" = "$candidate" ] || err candidate_mismatch
 [ "$(sha "$dir/check/dtb")" = "$target_dtb" ] || err candidate_dtb_mismatch
 [ "$(sha "$dir/work/dtb")" = "$target_dtb" ] || err staged_dtb_mismatch
 cmp -s "$dir/components_before.txt" "$dir/components_after.txt" || err component_integrity_failed
 grep -qx "candidate_components_verified=YES" "$dir/diagnostic.log" || err readback_not_verified
 grep -qx "non_dtb_components_unchanged=YES" "$dir/diagnostic.log" || err components_not_verified
 [ "$(wc -c < "$dir/recovery.img" | tr -d " ")" = "$SIZE" ] || err recovery_size_bad
 [ "$(wc -c < "$dir/candidate.img" | tr -d " ")" = "$SIZE" ] || err candidate_size_bad
 echo "install_preflight=PASS"
 echo "transaction=$tx"
 echo "source_sha256=$source"
 echo "candidate_sha256=$candidate"
 echo "recovery_image=$dir/recovery.img"
 echo "candidate_image=$dir/candidate.img"
 echo "automatic_reboot=NO"
}
install_image(){
 tx="$1"; [ "$2" = CONFIRM_INSTALL ] || err install_confirmation_missing
 install_check "$tx" >/dev/null
 [ ! -e "$ROOT/install_pending" ] || err prior_install_pending_verification
 mkdir "$ROOT/.install_lock" 2>/dev/null || err install_busy
 trap 'rmdir "$ROOT/.install_lock" 2>/dev/null || :' EXIT
 install_check "$tx" >/dev/null
 [ "$(blockdev --getsize64 "$PART" 2>/dev/null)" = "$SIZE" ] || err partition_size_mismatch
 battery="$(dumpsys battery | sed -n "s/^[[:space:]]*level: //p" | head -n 1)"
 case "$battery" in ""|*[!0-9]*) err battery_unavailable ;; esac
 [ "$battery" -ge 50 ] || err low_battery
 printf "transaction=%s\nsource_sha256=%s\ncandidate_sha256=%s\nboot_id=%s\nstate=STARTED\n" "$tx" "$source" "$candidate" "$(cat /proc/sys/kernel/random/boot_id)" > "$ROOT/install_pending"
 sync
 write_rc=0
 dd if="$dir/candidate.img" of="$PART" bs=1048576 count=32 > "$dir/install-write.log" 2>&1 || write_rc=$?
 sync
 readback="$(sha "$PART")"
 printf "write_exit=%s\nreadback_sha256=%s\n" "$write_rc" "$readback" >> "$ROOT/install_pending"
 if [ "$write_rc" -ne 0 ] || [ "$readback" != "$candidate" ]; then
  echo "state=FAILED_RECOVERY_REQUIRED" >> "$ROOT/install_pending"
  err partition_write_or_readback_failed
 fi
 echo "state=WRITE_VERIFIED_AWAITING_REBOOT" >> "$ROOT/install_pending"
 echo "install_write=VERIFIED"
 echo "readback_sha256=$readback"
 echo "reboot_required=YES"
 echo "recovery_image=$dir/recovery.img"
}
verify_install_boot(){
 check
 pending="$ROOT/install_pending"
 [ -f "$pending" ] || { echo "install_postboot=NO_PENDING_INSTALL"; return 0; }
 tx="$(manifest_value "$pending" transaction)"
 valid_tx "$tx" || err pending_transaction_bad
 candidate="$(manifest_value "$pending" candidate_sha256)"
 [ "$(sha "$PART")" = "$candidate" ] || err installed_image_does_not_match
 [ "$(manifest_value "$pending" boot_id)" != "$(cat /proc/sys/kernel/random/boot_id)" ] || err reboot_not_completed
 [ "$(manifest_value "$pending" state | tail -n 1)" = WRITE_VERIFIED_AWAITING_REBOOT ] || err pending_install_incomplete
 for spec in "1296 cpu0 1100000" "1344 cpu0 1150000" "1368 cpu0 1150000" "1416 cpu0 1150000" "1464 cpu0 1150000" "1512 cpu0 1150000" "1560 cpu4 1150000" "1608 cpu4 1150000" "1776 cpu2 1150000"; do
  set -- $spec
  path="/sys/kernel/debug/opp/$2/opp:$(($1 * 1000000))"
  available="$(cat "$path/available" 2>/dev/null || echo unknown)"
  turbo="$(cat "$path/turbo" 2>/dev/null || echo unknown)"
  volts="$(cat "$path/supply-0/u_volt_target" 2>/dev/null || echo unknown)"
  case "$available:$turbo:$volts" in "Y:Y:$3"|"N:N:0") ;; *) err postboot_opp_inconsistent ;; esac
 done
 mv "$pending" "$ROOT/last_install_verified"
 echo "install_postboot=VERIFIED"
 echo "installed_sha256=$candidate"
 echo "recovery_retained=YES"
}
case "$1" in
 status) status ;;
 verify-install-boot) [ "$#" = 1 ] || err arguments; verify_install_boot ;;
 install) [ "$#" = 3 ] || err arguments; install_image "$2" "$3" ;;
 install-check) [ "$#" = 2 ] || err arguments; install_check "$2" ;;
 start) [ "$#" = 4 ] || err arguments; start "$2" "$3" "$4" ;;
 finish) [ "$#" = 5 ] || err arguments; finish "$2" "$3" "$4" "$5" ;;
 *) echo "cpu_opp_firmware.sh status|start TX MHz lock/unlock|finish TX MHz lock/unlock SHA"; exit 2 ;;
esac