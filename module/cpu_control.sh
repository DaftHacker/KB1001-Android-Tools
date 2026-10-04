#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

CPU_STATE="$STATE_DIR/cpu_stock.conf"
CPU_MODE="$STATE_DIR/cpu_mode.conf"

save_stock(){
 [ -s "$CPU_STATE" ] && return 0
 tmp="$CPU_STATE.tmp.$$"
 : > "$tmp"
 for p in /sys/devices/system/cpu/cpufreq/policy*; do
  [ -d "$p" ] || continue
  gov="$(cat "$p/scaling_governor" 2>/dev/null)"
  min="$(cat "$p/scaling_min_freq" 2>/dev/null)"
  max="$(cat "$p/scaling_max_freq" 2>/dev/null)"
  [ -n "$gov" ] && [ -n "$min" ] && [ -n "$max" ] || continue
  echo "${p}|${gov}|${min}|${max}" >> "$tmp"
 done
 [ -s "$tmp" ] || { rm -f "$tmp"; return 1; }
 mv "$tmp" "$CPU_STATE"
 [ -f "$CPU_MODE" ] || echo stock > "$CPU_MODE"
}

restore_stock(){
 save_stock || return 1
 rc=0
 while IFS='|' read -r p gov min max; do
  case "$p" in /sys/devices/system/cpu/cpufreq/policy*) ;; *) continue;; esac
  [ -d "$p" ] || continue
  echo "$max" > "$p/scaling_max_freq" 2>/dev/null || rc=1
  echo "$min" > "$p/scaling_min_freq" 2>/dev/null || rc=1
  if grep -qw "$gov" "$p/scaling_available_governors" 2>/dev/null; then
   echo "$gov" > "$p/scaling_governor" 2>/dev/null || rc=1
  fi
 done < "$CPU_STATE"
 [ $rc -eq 0 ] && echo stock > "$CPU_MODE"
 return $rc
}

performance(){
 save_stock || return 1
 rc=0
 for p in /sys/devices/system/cpu/cpufreq/policy*; do
  [ -d "$p" ] || continue
  max="$(cat "$p/cpuinfo_max_freq" 2>/dev/null)"
  [ -n "$max" ] || max="$(cat "$p/scaling_max_freq" 2>/dev/null)"
  case "$max" in ''|*[!0-9]*) rc=1; continue;; esac

  echo "$max" > "$p/scaling_max_freq" 2>/dev/null || rc=1
  if grep -qw performance "$p/scaling_available_governors" 2>/dev/null; then
   echo performance > "$p/scaling_governor" 2>/dev/null || rc=1
  fi
  echo "$max" > "$p/scaling_min_freq" 2>/dev/null || rc=1
 done
 [ $rc -eq 0 ] && echo performance > "$CPU_MODE"
 return $rc
}

status(){
 echo "CPU mode: $(cat "$CPU_MODE" 2>/dev/null)"
 for p in /sys/devices/system/cpu/cpufreq/policy*; do
  [ -d "$p" ] || continue
  n="${p##*/}"
  cpus="$(cat "$p/related_cpus" 2>/dev/null | tr ' ' '-')"
  cur="$(cat "$p/scaling_cur_freq" 2>/dev/null)"
  min="$(cat "$p/scaling_min_freq" 2>/dev/null)"
  max="$(cat "$p/scaling_max_freq" 2>/dev/null)"
  gov="$(cat "$p/scaling_governor" 2>/dev/null)"
  avail="$(cat "$p/scaling_available_frequencies" 2>/dev/null)"
  govs="$(cat "$p/scaling_available_governors" 2>/dev/null)"
  echo "$n [$cpus]: cur=$cur min=$min max=$max gov=$gov"
  echo "$n frequencies: $avail"
  echo "$n governors: $govs"
 done
}

save_stock >/dev/null 2>&1 || true

case "$1" in
 status) status ;;
 performance) performance ;;
 stock|restore) restore_stock ;;
 *) echo "cpu_control.sh status|performance|restore"; exit 2 ;;
esac
