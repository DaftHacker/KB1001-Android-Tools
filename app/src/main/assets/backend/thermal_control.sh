#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

THERMAL_STATE="$STATE_DIR/thermal_stock.conf"
THERMAL_MODE="$STATE_DIR/thermal_mode.conf"
THERMAL_BOOT="$STATE_DIR/thermal_stock.boot_id"
TX="/data/local/tmp/kb1001_thermal_tx.$$"

boot_id(){ cat /proc/sys/kernel/random/boot_id 2>/dev/null; }

zone_for_type(){
 wanted="$1"
 for z in /sys/class/thermal/thermal_zone*; do
  [ -d "$z" ] || continue
  [ "$(cat "$z/type" 2>/dev/null)" = "$wanted" ] && { echo "$z"; return 0; }
 done
 return 1
}

cooling_for_type(){
 wanted="$1"
 for cd in /sys/class/thermal/cooling_device*; do
  [ -d "$cd" ] || continue
  [ "$(cat "$cd/type" 2>/dev/null)" = "$wanted" ] && { echo "$cd"; return 0; }
 done
 return 1
}

trip_node(){
 z="$1"; idx="$2"
 [ -e "$z/trip_point_${idx}_temp" ] && echo "$z/trip_point_${idx}_temp" && return 0
 [ -e "$z/trip_point_${idx}_temp_passive" ] && echo "$z/trip_point_${idx}_temp_passive" && return 0
 return 1
}

trip_type(){
 z="$1"; idx="$2"
 cat "$z/trip_point_${idx}_type" 2>/dev/null
}

trip_value(){
 z="$1"; idx="$2"
 n="$(trip_node "$z" "$idx")" || return 1
 cat "$n" 2>/dev/null
}

save_stock(){
 current_boot="$(boot_id)"
 saved_boot="$(cat "$THERMAL_BOOT" 2>/dev/null)"
 if [ -s "$THERMAL_STATE" ] && [ -n "$current_boot" ] && [ "$saved_boot" = "$current_boot" ]; then
  return 0
 fi

 tmp="$THERMAL_STATE.tmp.$$"
 : > "$tmp"
 for z in /sys/class/thermal/thermal_zone*; do
  [ -d "$z" ] || continue
  type="$(cat "$z/type" 2>/dev/null)"
  policy="$(cat "$z/policy" 2>/dev/null)"
  [ -n "$type" ] || continue
  i=0
  while [ $i -lt 12 ]; do
   node="$(trip_node "$z" "$i" 2>/dev/null)"
   [ -n "$node" ] || { i=$((i+1)); continue; }
   ttype="$(trip_type "$z" "$i")"
   temp="$(cat "$node" 2>/dev/null)"
   case "$temp" in ''|*[!0-9-]*) i=$((i+1)); continue;; esac
   printf '%s|%s|%s|%s|%s|%s\n' "$z" "$type" "$policy" "$i" "$ttype" "$temp" >> "$tmp"
   i=$((i+1))
  done
 done

 [ -s "$tmp" ] || { rm -f "$tmp"; return 1; }
 mv "$tmp" "$THERMAL_STATE"
 [ -n "$current_boot" ] && printf '%s\n' "$current_boot" > "$THERMAL_BOOT"
 echo stock > "$THERMAL_MODE"
}

restore_stock(){
 save_stock || return 1
 rc=0
 while IFS='|' read -r z type policy idx ttype temp; do
  [ "$ttype" = critical ] && continue
  [ -d "$z" ] || continue
  node="$(trip_node "$z" "$idx" 2>/dev/null)"
  [ -n "$node" ] || continue
  echo "$temp" > "$node" 2>/dev/null || rc=1
 done < "$THERMAL_STATE"
 [ $rc -eq 0 ] && echo stock > "$THERMAL_MODE"
 return $rc
}

valid_c(){
 value="$1"; lo="$2"; hi="$3"
 case "$value" in ''|*[!0-9]*) return 1;; esac
 [ "$value" -ge "$lo" ] 2>/dev/null && [ "$value" -le "$hi" ] 2>/dev/null
}

trip_c(){
 type="$1"; idx="$2"
 z="$(zone_for_type "$type")" || return 1
 raw="$(trip_value "$z" "$idx")" || return 1
 case "$raw" in ''|*[!0-9]*) return 1;; esac
 echo $((raw/1000))
}

rollback_tx(){
 [ -s "$TX" ] || return 0
 while IFS='|' read -r node old; do
  [ -e "$node" ] && echo "$old" > "$node" 2>/dev/null
 done < "$TX"
 rm -f "$TX"
}

write_trip_c(){
 type="$1"; idx="$2"; c="$3"
 z="$(zone_for_type "$type")" || return 1
 [ "$(trip_type "$z" "$idx")" != critical ] || return 1
 node="$(trip_node "$z" "$idx")" || return 1
 old="$(cat "$node" 2>/dev/null)"
 case "$old" in ''|*[!0-9-]*) return 1;; esac
 printf '%s|%s\n' "$node" "$old" >> "$TX"
 wanted=$((c*1000))
 echo "$wanted" > "$node" 2>/dev/null || return 1
 actual="$(cat "$node" 2>/dev/null)"
 [ "$actual" = "$wanted" ]
}

set_group(){
 key="$1"; c="$2"
 save_stock || return 1
 : > "$TX"

 case "$key" in
  cpu-target)
   valid_c "$c" 65 85 || { rm -f "$TX"; return 2; }
   for type in cpul_thermal_zone cpum_thermal_zone cpub_thermal_zone; do
    other="$(trip_c "$type" 1)" || { rm -f "$TX"; return 1; }
    [ "$c" -le $((other-5)) ] || { rm -f "$TX"; return 2; }
   done
   for type in cpul_thermal_zone cpum_thermal_zone cpub_thermal_zone; do
    write_trip_c "$type" 0 "$c" || { rollback_tx; return 1; }
   done
   ;;
  cpu-throttle)
   valid_c "$c" 80 100 || { rm -f "$TX"; return 2; }
   for type in cpul_thermal_zone cpum_thermal_zone cpub_thermal_zone; do
    other="$(trip_c "$type" 0)" || { rm -f "$TX"; return 1; }
    [ "$c" -ge $((other+5)) ] || { rm -f "$TX"; return 2; }
   done
   for type in cpul_thermal_zone cpum_thermal_zone cpub_thermal_zone; do
    write_trip_c "$type" 1 "$c" || { rollback_tx; return 1; }
   done
   ;;
  gpu-target)
   valid_c "$c" 65 85 || { rm -f "$TX"; return 2; }
   other="$(trip_c gpu_thermal_zone 1)" || { rm -f "$TX"; return 1; }
   [ "$c" -le $((other-5)) ] || { rm -f "$TX"; return 2; }
   write_trip_c gpu_thermal_zone 0 "$c" || { rollback_tx; return 1; }
   ;;
  gpu-throttle)
   valid_c "$c" 80 100 || { rm -f "$TX"; return 2; }
   other="$(trip_c gpu_thermal_zone 0)" || { rm -f "$TX"; return 1; }
   [ "$c" -ge $((other+5)) ] || { rm -f "$TX"; return 2; }
   write_trip_c gpu_thermal_zone 1 "$c" || { rollback_tx; return 1; }
   ;;
  idle-throttle)
   valid_c "$c" 95 105 || { rm -f "$TX"; return 2; }
   for type in cpum_idle_zone cpub_idle_zone; do
    write_trip_c "$type" 1 "$c" || { rollback_tx; return 1; }
   done
   ;;
  *) rm -f "$TX"; return 2 ;;
 esac

 rm -f "$TX"
 echo custom > "$THERMAL_MODE"
 return 0
}

emit_zone(){
 key="$1"; type="$2"
 z="$(zone_for_type "$type" 2>/dev/null)"
 [ -n "$z" ] || return 0
 echo "${key}_zone=${z##*/}"
 echo "${key}_type=$type"
 echo "${key}_policy=$(cat "$z/policy" 2>/dev/null)"
 echo "${key}_temp_millic=$(cat "$z/temp" 2>/dev/null)"
 i=0
 while [ $i -lt 4 ]; do
  node="$(trip_node "$z" "$i" 2>/dev/null)"
  [ -n "$node" ] || { i=$((i+1)); continue; }
  echo "${key}_trip${i}_type=$(trip_type "$z" "$i")"
  echo "${key}_trip${i}_millic=$(cat "$node" 2>/dev/null)"
  i=$((i+1))
 done
}

emit_cooling(){
 key="$1"; type="$2"
 cd="$(cooling_for_type "$type" 2>/dev/null)"
 [ -n "$cd" ] || return 0
 echo "${key}_cooling_type=$type"
 echo "${key}_cooling_cur=$(cat "$cd/cur_state" 2>/dev/null)"
 echo "${key}_cooling_max=$(cat "$cd/max_state" 2>/dev/null)"
}

status(){
 save_stock >/dev/null 2>&1 || true
 echo "mode=$(cat "$THERMAL_MODE" 2>/dev/null)"
 echo "stock_boot=$(cat "$THERMAL_BOOT" 2>/dev/null)"
 echo "critical_locked=1"
 echo "hysteresis_writable=0"
 echo "android_hot_thresholds_c=75,80,85,100,105,110"
 emit_zone cpul cpul_thermal_zone
 emit_zone cpum cpum_thermal_zone
 emit_zone cpub cpub_thermal_zone
 emit_zone gpu gpu_thermal_zone
 emit_zone cpum_idle cpum_idle_zone
 emit_zone cpub_idle cpub_idle_zone
 emit_zone skin skin_zone
 emit_zone battery battery_zone
 emit_cooling cpul cpufreq-cpu0
 emit_cooling cpum cpufreq-cpu2
 emit_cooling cpub cpufreq-cpu4
 emit_cooling gpu devfreq-1800000.gpu
 emit_cooling idle2 idle-cpu2
 emit_cooling idle3 idle-cpu3
 emit_cooling idle4 idle-cpu4
}

save_stock >/dev/null 2>&1 || true

case "$1" in
 init) save_stock ;;
 status) status ;;
 restore) restore_stock ;;
 set)
  set_group "$2" "$3"
  rc=$?
  [ $rc -eq 0 ] && status
  exit $rc
  ;;
 *) echo "thermal_control.sh init|status|restore|set cpu-target|cpu-throttle|gpu-target|gpu-throttle|idle-throttle CELSIUS"; exit 2 ;;
esac
