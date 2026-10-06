#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

CPU_STATE="$STATE_DIR/cpu_stock.conf"
CPU_MODE="$STATE_DIR/cpu_mode.conf"
CPU_BOOT="$STATE_DIR/cpu_stock.boot_id"

boot_id(){ cat /proc/sys/kernel/random/boot_id 2>/dev/null; }

save_stock(){
 current_boot="$(boot_id)"
 saved_boot="$(cat "$CPU_BOOT" 2>/dev/null)"
 if [ -s "$CPU_STATE" ] && [ -n "$current_boot" ] && [ "$saved_boot" = "$current_boot" ]; then
  return 0
 fi
 rm -f "$CPU_STATE"
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
 [ -n "$current_boot" ] && printf '%s\n' "$current_boot" > "$CPU_BOOT"
 echo stock > "$CPU_MODE"
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

balanced(){
 save_stock || return 1
 rc=0
 while IFS='|' read -r p stock_gov stock_min stock_max; do
  case "$p" in /sys/devices/system/cpu/cpufreq/policy*) ;; *) continue;; esac
  [ -d "$p" ] || continue
  echo "$stock_max" > "$p/scaling_max_freq" 2>/dev/null || rc=1
  echo "$stock_min" > "$p/scaling_min_freq" 2>/dev/null || rc=1
  if grep -qw schedutil "$p/scaling_available_governors" 2>/dev/null; then
   echo schedutil > "$p/scaling_governor" 2>/dev/null || rc=1
  else
   rc=1
  fi
 done < "$CPU_STATE"
 [ $rc -eq 0 ] && echo balanced > "$CPU_MODE"
 return $rc
}

policy_path(){
 name="$1"
 case "$name" in policy[0-9]*) ;; *) return 1;; esac
 p="/sys/devices/system/cpu/cpufreq/$name"
 [ -d "$p" ] || return 1
 echo "$p"
}

policy_set(){
 name="$1"; field="$2"; value="$3"
 save_stock || return 1
 p="$(policy_path "$name")" || return 2

 case "$field" in
  min|max)
   case "$value" in ''|*[!0-9]*) return 2;; esac
   policy_has_freq "$p" "$value" || return 2
   cur_min="$(cat "$p/scaling_min_freq" 2>/dev/null)"
   cur_max="$(cat "$p/scaling_max_freq" 2>/dev/null)"
   case "$cur_min:$cur_max" in *[!0-9:]*|'':*) return 1;; esac

   if [ "$field" = min ]; then
    [ "$value" -le "$cur_max" ] 2>/dev/null || return 2
    node="$p/scaling_min_freq"
   else
    [ "$value" -ge "$cur_min" ] 2>/dev/null || return 2
    node="$p/scaling_max_freq"
   fi

   old="$(cat "$node" 2>/dev/null)"
   echo "$value" > "$node" 2>/dev/null || return 1
   actual="$(cat "$node" 2>/dev/null)"
   if [ "$actual" != "$value" ]; then
    echo "$old" > "$node" 2>/dev/null
    return 1
   fi
   ;;
  governor)
   grep -qw "$value" "$p/scaling_available_governors" 2>/dev/null || return 2
   node="$p/scaling_governor"
   old="$(cat "$node" 2>/dev/null)"
   echo "$value" > "$node" 2>/dev/null || return 1
   actual="$(cat "$node" 2>/dev/null)"
   if [ "$actual" != "$value" ]; then
    echo "$old" > "$node" 2>/dev/null
    return 1
   fi
   ;;
  *) return 2 ;;
 esac

 echo custom > "$CPU_MODE"
 echo "state=applied"
 echo "mode=custom"
 echo "policy=$name"
 echo "field=$field"
 echo "value=$value"
 return 0
}

policy_has_freq(){
 policy="$1"
 wanted="$2"
 grep -qw "$wanted" "$policy/scaling_available_frequencies" 2>/dev/null
}

opp_target_uv(){
 table="$1"; hz="$2"
 f="/sys/kernel/debug/opp/$table/opp:$hz/supply-0/u_volt_target"
 cat "$f" 2>/dev/null
}

opp_map(){
 table="$1"
 root="/sys/kernel/debug/opp/$table"
 [ -d "$root" ] || return 0
 out=""
 for d in "$root"/opp:*; do
  [ -d "$d" ] || continue
  rate="$(cat "$d/rate_hz" 2>/dev/null)"
  available="$(cat "$d/available" 2>/dev/null)"
  volt="$(cat "$d/supply-0/u_volt_target" 2>/dev/null)"
  case "$rate" in ''|*[!0-9]*) continue;; esac
  [ -n "$out" ] && out="$out,"
  out="$out$rate:${available:-?}:${volt:-0}"
 done
 echo "$out"
}

vf_profile(){
 # Strong runtime signature from the OPP framework. These three stock points
 # uniquely match the verified vf0403 voltage curve on this KB1001/A333.
 p0_912="$(opp_target_uv cpu0 912000000)"
 p2_1296="$(opp_target_uv cpu2 1296000000)"
 p4_1392="$(opp_target_uv cpu4 1392000000)"
 if [ "$p0_912" = 940000 ] && [ "$p2_1296" = 940000 ] && [ "$p4_1392" = 1070000 ]; then
  echo vf0403
  return
 fi

 # Fallback for kernels without OPP debugfs.
 p0="$(cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq 2>/dev/null)"
 p2="$(cat /sys/devices/system/cpu/cpufreq/policy2/cpuinfo_max_freq 2>/dev/null)"
 p4="$(cat /sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq 2>/dev/null)"
 if [ "$p0" = 1200000 ] && [ "$p2" = 1752000 ] && [ "$p4" = 1512000 ]; then
  echo vf0403
 else
  echo unknown
 fi
}

oc_status(){
 echo "vf_profile=$(vf_profile)"
 echo "vf_profile_source=runtime_opp_signature"
 echo "vf_version=$(cat /sys/class/cpufreq/vf_version 2>/dev/null)"
 echo "dvfs_code=$(cat /sys/class/cpufreq/dvfs_code 2>/dev/null)"
 [ -d /sys/kernel/debug/opp ] && echo "opp_debugfs=1" || echo "opp_debugfs=0"

 echo "a53_efficiency_stock_max_khz=$(cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq 2>/dev/null)"
 echo "a53_performance_stock_max_khz=$(cat /sys/devices/system/cpu/cpufreq/policy2/cpuinfo_max_freq 2>/dev/null)"
 echo "a73_prime_stock_max_khz=$(cat /sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq 2>/dev/null)"

 echo "policy0_opp_map=$(opp_map cpu0)"
 echo "policy2_opp_map=$(opp_map cpu2)"
 echo "policy4_opp_map=$(opp_map cpu4)"
 echo "gpu_opp_map=$(opp_map soc@3000000-1800000.gpu)"

 if policy_has_freq /sys/devices/system/cpu/cpufreq/policy4 1560000; then echo "a73_stage1_1560=available"; else echo "a73_stage1_1560=boot_opp_required"; fi
 if policy_has_freq /sys/devices/system/cpu/cpufreq/policy4 1608000; then echo "a73_stage2_1608=available"; else echo "a73_stage2_1608=boot_opp_required"; fi
 if policy_has_freq /sys/devices/system/cpu/cpufreq/policy2 1776000; then echo "a53_stage1_1776=available"; else echo "a53_stage1_1776=boot_opp_required"; fi
 echo "oc_apply_supported=0"
 echo "boot_opp_patch_state=not_installed"
}

status(){
 save_stock >/dev/null 2>&1 || true
 echo "CPU mode: $(cat "$CPU_MODE" 2>/dev/null)"
 echo "CPU stock boot: $(cat "$CPU_BOOT" 2>/dev/null)"
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
 init) save_stock ;;
 status) status ;;
 balanced) balanced ;;
 performance) performance ;;
 policy) policy_set "$2" "$3" "$4" ;;
 oc-status) oc_status ;;
 stock|restore) restore_stock ;;
 *) echo "cpu_control.sh init|status|balanced|performance|policy POLICY min|max|governor VALUE|oc-status|restore"; exit 2 ;;
esac
