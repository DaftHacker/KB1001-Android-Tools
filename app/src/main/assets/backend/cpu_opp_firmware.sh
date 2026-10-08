#!/system/bin/sh
# KB1001 CPU OPP candidate builder (no flashing, reboot or CPU boost).
set -eu
ROOT=/data/local/kb1001perf/opp_firmware
PART=/dev/block/by-name/vendor_boot_a
MAGISKBOOT=/data/adb/magisk/magiskboot
SIZE=33554432
S15=9eb390f96d2c3b320aff471ee33bb478f6ea7ff682e954305b418f4a31b1fb15
S3B=a4906f29b8ae138fee0606e017e9405d8eebfef72d749fcae0aef6dbb76ce13c
umask 077
err(){ echo "firmware_error=$1"; exit 3; }
sha(){ sha256sum "$1" | cut -d ' ' -f1; }
known(){ case "$1" in "$S15"|"$S3B") return 0;; *) return 1;; esac; }
allowed(){ case "$1" in 1296|1344|1368|1416|1464|1512|1560|1608|1776) return 0;; *) return 1;; esac; }
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
 case "$mode" in lock|unlock) ;; *) err invalid_action;; esac
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
 ( cd "$dir/work" && "$MAGISKBOOT" unpack -h "$dir/recovery.img" >/dev/null ) || err unpack_failed
 [ -s "$dir/work/dtb" ] || err missing_dtb
 echo "transaction=$tx"
 echo "source_sha256=$live"
 echo "original_dtb=$dir/work/dtb"
 echo "recovery_image=$dir/recovery.img"
 echo "firmware_stage=extracted_no_flash"
}
finish(){
 tx="$1"; mhz="$2"; mode="$3"; patched="$4"
 valid_tx "$tx" || err invalid_transaction
 allowed "$mhz" || err not_validated
 case "$mode" in lock|unlock) ;; *) err invalid_action;; esac
 case "$patched" in *[!a-f0-9]*|'') err invalid_sha;; esac
 [ "$(printf '%s' "$patched" | wc -c | tr -d ' ')" = 64 ] || err invalid_sha_length
 check
 dir="$ROOT/$tx"
 [ -d "$dir/work" ] && [ -d "$dir/check" ] || err missing_transaction
 [ "$(cat "$dir/mhz")" = "$mhz" ] && [ "$(cat "$dir/mode")" = "$mode" ] || err transaction_mismatch
 source="$(cat "$dir/source.sha")"
 known "$source" || err unsupported_source
 [ "$(sha "$PART")" = "$source" ] && [ "$(sha "$dir/recovery.img")" = "$source" ] || err source_changed
 [ "$(sha "$dir/work/dtb")" = "$patched" ] || err patched_dtb_mismatch
 ( cd "$dir/work" && "$MAGISKBOOT" repack "$dir/recovery.img" "$dir/candidate.img" >/dev/null ) || err repack_failed
 [ "$(wc -c < "$dir/candidate.img" | tr -d ' ')" = "$SIZE" ] || err candidate_bad_size
 ( cd "$dir/check" && "$MAGISKBOOT" unpack -h "$dir/candidate.img" >/dev/null ) || err readback_failed
 [ "$(sha "$dir/check/dtb")" = "$patched" ] || err candidate_dtb_mismatch
 ( cd "$dir/work" && find . -type f ! -name dtb ! -name header | sort | while IFS= read -r name; do sha256sum "$name"; done ) > "$dir/components_before.txt" || err component_list_failed
 ( cd "$dir/check" && find . -type f ! -name dtb ! -name header | sort | while IFS= read -r name; do sha256sum "$name"; done ) > "$dir/components_after.txt" || err component_list_failed
 [ -s "$dir/components_before.txt" ] || err component_list_empty
 cmp -s "$dir/components_before.txt" "$dir/components_after.txt" || err non_dtb_components_changed
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
}
case "$1" in
 status) status ;;
 start) [ "$#" = 4 ] || err arguments; start "$2" "$3" "$4" ;;
 finish) [ "$#" = 5 ] || err arguments; finish "$2" "$3" "$4" "$5" ;;
 *) echo "cpu_opp_firmware.sh status|start TX MHz lock/unlock|finish TX MHz lock/unlock SHA"; exit 2 ;;
esac