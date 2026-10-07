#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

CPU_STATE="$STATE_DIR/cpu_stock.conf"
CPU_MODE="$STATE_DIR/cpu_mode.conf"
CPU_BOOT="$STATE_DIR/cpu_stock.boot_id"

boot_id(){ cat /proc/sys/kernel/random/boot_id 2>/dev/null; }

CPUFREQ_ROOT="/sys/devices/system/cpu/cpufreq"
BOOST_NODE="$CPUFREQ_ROOT/boost"
POLICY0="$CPUFREQ_ROOT/policy0"
POLICY2="$CPUFREQ_ROOT/policy2"
POLICY4="$CPUFREQ_ROOT/policy4"
OC_1560_VENDOR_BOOT_SHA256="def940b0dbb58c68e2b815143f2829f5bef5688e6e62e7e211387fc582e45c87"
OC_1608_VENDOR_BOOT_SHA256="3107c282f462fc680dfafd82fb1f2a88c8b93c79cdb6904828ec061fa32b10f7"
OC_1776_VENDOR_BOOT_SHA256="739866913b6daa5dd5d306cc4f7812208499cc86ba89268616b8bf6b8a9fe40b"
OC_1296_VENDOR_BOOT_SHA256="0a08e53afe9947019ccd9c1708f104032b694b691aded08b67f46968a90d2251"
OC_1344_VENDOR_BOOT_SHA256="ef34d4990e5ab68efb69eeb1f1b80c90917971c4814066a77c575ec0330e179c"
OC_STOCK_VENDOR_BOOT_SHA256="11efaf3483b2ef4250ab78b6a160e64adf80d82d3965f156554189ec8083d402"

stock_max_for_policy(){
 p="$1"
 max=0
 for f in $(cat "$p/scaling_available_frequencies" 2>/dev/null); do
  case "$f" in ''|*[!0-9]*) continue;; esac
  [ "$f" -gt "$max" ] 2>/dev/null && max="$f"
 done
 [ "$max" -gt 0 ] 2>/dev/null || return 1
 echo "$max"
}

boost_disable_safe(){
 if [ -w "$BOOST_NODE" ] && [ "$(cat "$BOOST_NODE" 2>/dev/null)" = 1 ]; then
  # This platform's global boost switch expands each policy's max to its turbo
  # ceiling. Move every saved policy low and re-apply its normal ceiling before
  # disabling boost so no cluster can transiently run a turbo OPP during exit.
  while IFS='|' read -r p gov min max; do
   case "$p" in "$CPUFREQ_ROOT"/policy*) ;; *) continue;; esac
   [ -d "$p" ] || continue
   grep -qw powersave "$p/scaling_available_governors" 2>/dev/null || return 1
   grep -qw 408000 "$p/scaling_available_frequencies" 2>/dev/null || return 1
   echo powersave > "$p/scaling_governor" 2>/dev/null || return 1
   echo 408000 > "$p/scaling_min_freq" 2>/dev/null || return 1
   echo "$max" > "$p/scaling_max_freq" 2>/dev/null || return 1
  done < "$CPU_STATE"
  sleep 1
  echo 0 > "$BOOST_NODE" 2>/dev/null || return 1
 fi
 return 0
}

boost_enable_for_target(){
 target_policy="$1"
 [ -w "$BOOST_NODE" ] || return 1
 [ -d "$target_policy" ] || return 1

 # Preserve the live state of every policy so enabling an OC does not silently
 # replace a user's current Balanced/custom settings on non-target clusters.
 live_state="$STATE_DIR/cpu_boost_live.$(cut -d' ' -f1 /proc/self/stat 2>/dev/null)"
 : > "$live_state" || return 1
 for p in "$CPUFREQ_ROOT"/policy*; do
  [ -d "$p" ] || continue
  gov="$(cat "$p/scaling_governor" 2>/dev/null)"
  min="$(cat "$p/scaling_min_freq" 2>/dev/null)"
  max="$(cat "$p/scaling_max_freq" 2>/dev/null)"
  [ -n "$gov" ] && [ -n "$min" ] && [ -n "$max" ] || { rm -f "$live_state"; return 1; }
  echo "$p|$gov|$min|$max" >> "$live_state" || { rm -f "$live_state"; return 1; }
 done
 [ -s "$live_state" ] || { rm -f "$live_state"; return 1; }

 # Enabling global boost expands scaling_max_freq on every policy. Hold all
 # policies at the lowest OPP first so that expansion cannot cause a transient
 # turbo jump.
 while IFS='|' read -r p gov min max; do
  case "$p" in "$CPUFREQ_ROOT"/policy*) ;; *) continue;; esac
  [ -d "$p" ] || continue
  grep -qw powersave "$p/scaling_available_governors" 2>/dev/null || { rm -f "$live_state"; return 1; }
  grep -qw 408000 "$p/scaling_available_frequencies" 2>/dev/null || { rm -f "$live_state"; return 1; }
  echo powersave > "$p/scaling_governor" 2>/dev/null || { rm -f "$live_state"; return 1; }
  echo 408000 > "$p/scaling_min_freq" 2>/dev/null || { rm -f "$live_state"; return 1; }
  echo 408000 > "$p/scaling_max_freq" 2>/dev/null || { rm -f "$live_state"; return 1; }
 done < "$live_state"

 echo 1 > "$BOOST_NODE" 2>/dev/null || { rm -f "$live_state"; return 1; }
 [ "$(cat "$BOOST_NODE" 2>/dev/null)" = 1 ] || { rm -f "$live_state"; return 1; }

 # Re-clamp immediately after the global boost toggle. Keep the target low until
 # oc_apply() sets its validated turbo ceiling; restore every non-target policy
 # to the exact state it had immediately before the OC request.
 while IFS='|' read -r p gov min max; do
  case "$p" in "$CPUFREQ_ROOT"/policy*) ;; *) continue;; esac
  [ -d "$p" ] || continue
  if [ "$p" = "$target_policy" ]; then
   echo 408000 > "$p/scaling_min_freq" 2>/dev/null || { rm -f "$live_state"; return 1; }
   echo 408000 > "$p/scaling_max_freq" 2>/dev/null || { rm -f "$live_state"; return 1; }
   continue
  fi
  echo "$max" > "$p/scaling_max_freq" 2>/dev/null || { rm -f "$live_state"; return 1; }
  echo "$min" > "$p/scaling_min_freq" 2>/dev/null || { rm -f "$live_state"; return 1; }
  if grep -qw "$gov" "$p/scaling_available_governors" 2>/dev/null; then
   echo "$gov" > "$p/scaling_governor" 2>/dev/null || { rm -f "$live_state"; return 1; }
  else
   rm -f "$live_state"
   return 1
  fi
 done < "$live_state"

 rm -f "$live_state"
 return 0
}

current_vendor_boot_sha256(){
 sha256sum /dev/block/by-name/vendor_boot_a 2>/dev/null | awk '{print $1}'
}

opp_ready(){
 table="$1"; hz="$2"; expected_uv="$3"
 d="/sys/kernel/debug/opp/$table/opp:$hz"
 [ -d "$d" ] || return 1
 [ "$(cat "$d/available" 2>/dev/null)" = Y ] || return 1
 [ "$(cat "$d/turbo" 2>/dev/null)" = Y ] || return 1
 [ "$(cat "$d/supply-0/u_volt_target" 2>/dev/null)" = "$expected_uv" ] || return 1
 return 0
}

oc_1560_support_present(){
 [ -r "$BOOST_NODE" ] || return 1
 [ -d "$POLICY4" ] || return 1
 sha="$(current_vendor_boot_sha256)"
 case "$sha" in
  "$OC_1560_VENDOR_BOOT_SHA256"|"$OC_1608_VENDOR_BOOT_SHA256"|"$OC_1776_VENDOR_BOOT_SHA256"|"$OC_1296_VENDOR_BOOT_SHA256"|"$OC_1344_VENDOR_BOOT_SHA256") ;;
  *) return 1 ;;
 esac
 grep -qw 1560000 "$POLICY4/scaling_boost_frequencies" 2>/dev/null || return 1
 opp_ready cpu4 1560000000 1150000
}

oc_1608_support_present(){
 [ -r "$BOOST_NODE" ] || return 1
 [ -d "$POLICY4" ] || return 1
 sha="$(current_vendor_boot_sha256)"
 case "$sha" in
  "$OC_1608_VENDOR_BOOT_SHA256"|"$OC_1776_VENDOR_BOOT_SHA256"|"$OC_1296_VENDOR_BOOT_SHA256"|"$OC_1344_VENDOR_BOOT_SHA256") ;;
  *) return 1 ;;
 esac
 grep -qw 1608000 "$POLICY4/scaling_boost_frequencies" 2>/dev/null || return 1
 opp_ready cpu4 1608000000 1150000
}

oc_1776_support_present(){
 [ -r "$BOOST_NODE" ] || return 1
 [ -d "$POLICY2" ] || return 1
 sha="$(current_vendor_boot_sha256)"
 case "$sha" in
  "$OC_1776_VENDOR_BOOT_SHA256"|"$OC_1296_VENDOR_BOOT_SHA256"|"$OC_1344_VENDOR_BOOT_SHA256") ;;
  *) return 1 ;;
 esac
 grep -qw 1776000 "$POLICY2/scaling_boost_frequencies" 2>/dev/null || return 1
 opp_ready cpu2 1776000000 1150000
}

oc_1296_support_present(){
 [ -r "$BOOST_NODE" ] || return 1
 [ -d "$POLICY0" ] || return 1
 sha="$(current_vendor_boot_sha256)"
 case "$sha" in
  "$OC_1296_VENDOR_BOOT_SHA256"|"$OC_1344_VENDOR_BOOT_SHA256") ;;
  *) return 1 ;;
 esac
 grep -qw 1296000 "$POLICY0/scaling_boost_frequencies" 2>/dev/null || return 1
 opp_ready cpu0 1296000000 1100000
}

oc_1344_support_present(){
 [ -r "$BOOST_NODE" ] || return 1
 [ -d "$POLICY0" ] || return 1
 [ "$(current_vendor_boot_sha256)" = "$OC_1344_VENDOR_BOOT_SHA256" ] || return 1
 grep -qw 1344000 "$POLICY0/scaling_boost_frequencies" 2>/dev/null || return 1
 opp_ready cpu0 1344000000 1150000
}

oc_support_present(){
 oc_1560_support_present
}


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
 boost_disable_safe || return 1
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
 boost_disable_safe || return 1
 rc=0
 for p in /sys/devices/system/cpu/cpufreq/policy*; do
  [ -d "$p" ] || continue
  max="$(stock_max_for_policy "$p" 2>/dev/null)"
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
 boost_disable_safe || return 1
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
 boost_disable_safe || return 1
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
 # Primary source: vendor cpufreq class exposes the raw DVFS selector.
 # Verified on this KB1001: dvfs_code 0x0034 -> vf_mapping_table index 0x0403.
 code="$(cat /sys/class/cpufreq/dvfs_code 2>/dev/null | tr 'A-F' 'a-f')"
 case "$code" in
  0x0034|0x34) echo vf0403; return ;;
 esac

 # Fallback: match the runtime OPP voltage signature.
 p0_912="$(opp_target_uv cpu0 912000000)"
 p2_1296="$(opp_target_uv cpu2 1296000000)"
 p4_1392="$(opp_target_uv cpu4 1392000000)"
 if [ "$p0_912" = 940000 ] && [ "$p2_1296" = 940000 ] && [ "$p4_1392" = 1070000 ]; then
  echo vf0403
  return
 fi

 # Last fallback for kernels without selector/OPP debugfs.
 p0="$(cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq 2>/dev/null)"
 p2="$(cat /sys/devices/system/cpu/cpufreq/policy2/cpuinfo_max_freq 2>/dev/null)"
 p4="$(cat /sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq 2>/dev/null)"
 if [ "$p0" = 1200000 ] && [ "$p2" = 1752000 ] && [ "$p4" = 1512000 ]; then
  echo vf0403
 else
  echo unknown
 fi
}

oc_apply(){
 mode="$1"
 save_stock || return 1
 [ "$(vf_profile)" = vf0403 ] || return 3

 case "$mode" in
  dynamic1296)
   target_policy="$POLICY0"
   target_name=policy0
   target=1296000
   governor=schedutil
   state_mode=oc_dynamic1296
   oc_1296_support_present || return 3
   ;;
  performance1296)
   target_policy="$POLICY0"
   target_name=policy0
   target=1296000
   governor=performance
   state_mode=oc_performance1296
   oc_1296_support_present || return 3
   ;;
  dynamic1344)
   target_policy="$POLICY0"
   target_name=policy0
   target=1344000
   governor=schedutil
   state_mode=oc_dynamic1344
   oc_1344_support_present || return 3
   ;;
  performance1344)
   target_policy="$POLICY0"
   target_name=policy0
   target=1344000
   governor=performance
   state_mode=oc_performance1344
   oc_1344_support_present || return 3
   ;;
  dynamic1560)
   target_policy="$POLICY4"
   target_name=policy4
   target=1560000
   governor=schedutil
   state_mode=oc_dynamic1560
   oc_1560_support_present || return 3
   ;;
  performance1560)
   target_policy="$POLICY4"
   target_name=policy4
   target=1560000
   governor=performance
   state_mode=oc_performance1560
   oc_1560_support_present || return 3
   ;;
  dynamic1608)
   target_policy="$POLICY4"
   target_name=policy4
   target=1608000
   governor=schedutil
   state_mode=oc_dynamic1608
   oc_1608_support_present || return 3
   ;;
  performance1608)
   target_policy="$POLICY4"
   target_name=policy4
   target=1608000
   governor=performance
   state_mode=oc_performance1608
   oc_1608_support_present || return 3
   ;;
  dynamic1776)
   target_policy="$POLICY2"
   target_name=policy2
   target=1776000
   governor=schedutil
   state_mode=oc_dynamic1776
   oc_1776_support_present || return 3
   ;;
  performance1776)
   target_policy="$POLICY2"
   target_name=policy2
   target=1776000
   governor=performance
   state_mode=oc_performance1776
   oc_1776_support_present || return 3
   ;;
  *) return 2 ;;
 esac

 grep -qw "$governor" "$target_policy/scaling_available_governors" 2>/dev/null || return 2

 if ! boost_enable_for_target "$target_policy"; then
  restore_stock >/dev/null 2>&1 || true
  return 1
 fi

 if ! grep -qw "$target" "$target_policy/scaling_boost_frequencies" 2>/dev/null; then
  restore_stock >/dev/null 2>&1 || true
  return 3
 fi

 echo 408000 > "$target_policy/scaling_min_freq" 2>/dev/null || { restore_stock >/dev/null 2>&1 || true; return 1; }
 echo "$target" > "$target_policy/scaling_max_freq" 2>/dev/null || { restore_stock >/dev/null 2>&1 || true; return 1; }
 [ "$(cat "$target_policy/scaling_max_freq" 2>/dev/null)" = "$target" ] || { restore_stock >/dev/null 2>&1 || true; return 1; }

 echo "$governor" > "$target_policy/scaling_governor" 2>/dev/null || { restore_stock >/dev/null 2>&1 || true; return 1; }
 echo "$state_mode" > "$CPU_MODE"

 echo "state=applied"
 echo "mode=$mode"
 echo "boost=$(cat "$BOOST_NODE" 2>/dev/null)"
 echo "target_policy=$target_name"
 echo "target_max=$(cat "$target_policy/scaling_max_freq" 2>/dev/null)"
 echo "target_governor=$(cat "$target_policy/scaling_governor" 2>/dev/null)"
 echo "policy0_max=$(cat "$POLICY0/scaling_max_freq" 2>/dev/null)"
 echo "policy2_max=$(cat "$POLICY2/scaling_max_freq" 2>/dev/null)"
 echo "policy4_max=$(cat "$POLICY4/scaling_max_freq" 2>/dev/null)"
 return 0
}

oc_disable(){
 restore_stock || return $?
 echo "state=applied"
 echo "mode=stock"
 echo "boost=$(cat "$BOOST_NODE" 2>/dev/null)"
 return 0
}

oc_status(){
 echo "vf_profile=$(vf_profile)"
 dvfs_code_now="$(cat /sys/class/cpufreq/dvfs_code 2>/dev/null | tr 'A-F' 'a-f')"
 if [ "$dvfs_code_now" = "0x0034" ] || [ "$dvfs_code_now" = "0x34" ]; then
  echo "vf_profile_source=dvfs_code"
 else
  echo "vf_profile_source=runtime_opp_signature"
 fi

 vf_ver="$(cat /sys/class/cpufreq/vf_version 2>/dev/null)"
 if [ -z "$vf_ver" ] && [ -r /sys/firmware/devicetree/base/vf_mapping_table/vf-version ]; then
  vf_ver="$(tr -d '\000' < /sys/firmware/devicetree/base/vf_mapping_table/vf-version 2>/dev/null)"
 fi
 [ -n "$vf_ver" ] || vf_ver=unknown
 echo "vf_version=$vf_ver"

 dvfs_code="$(cat /sys/class/cpufreq/dvfs_code 2>/dev/null)"
 [ -n "$dvfs_code" ] || dvfs_code=unreadable
 echo "dvfs_code=$dvfs_code"
 [ -d /sys/kernel/debug/opp ] && echo "opp_debugfs=1" || echo "opp_debugfs=0"

 echo "a53_efficiency_stock_max_khz=$(stock_max_for_policy "$CPUFREQ_ROOT/policy0" 2>/dev/null)"
 echo "a53_performance_stock_max_khz=$(stock_max_for_policy "$CPUFREQ_ROOT/policy2" 2>/dev/null)"
 echo "a73_prime_stock_max_khz=$(stock_max_for_policy "$CPUFREQ_ROOT/policy4" 2>/dev/null)"

 echo "policy0_opp_map=$(opp_map cpu0)"
 echo "policy2_opp_map=$(opp_map cpu2)"
 echo "policy4_opp_map=$(opp_map cpu4)"
 echo "gpu_opp_map=$(opp_map soc@3000000-1800000.gpu)"

 current_vendor_boot_sha256="$(current_vendor_boot_sha256)"
 echo "boot_opp_carrier=vendor_boot_a"
 echo "vendor_boot_stock_sha256=$OC_STOCK_VENDOR_BOOT_SHA256"
 echo "vendor_boot_stage6_1560_sha256=$OC_1560_VENDOR_BOOT_SHA256"
 echo "vendor_boot_stage7_1608_sha256=$OC_1608_VENDOR_BOOT_SHA256"
 echo "vendor_boot_stage8_1776_sha256=$OC_1776_VENDOR_BOOT_SHA256"
 echo "vendor_boot_stage9_1296_sha256=$OC_1296_VENDOR_BOOT_SHA256"
 echo "vendor_boot_stage10_1344_sha256=$OC_1344_VENDOR_BOOT_SHA256"
 echo "vendor_boot_patched_sha256=$OC_1344_VENDOR_BOOT_SHA256"
 echo "vendor_boot_current_sha256=$current_vendor_boot_sha256"
 case "$current_vendor_boot_sha256" in
  "$OC_1344_VENDOR_BOOT_SHA256") echo "vendor_boot_state=verified_cpu0_1344_patch" ;;
  "$OC_1296_VENDOR_BOOT_SHA256") echo "vendor_boot_state=verified_cpu0_1296_patch" ;;
  "$OC_1776_VENDOR_BOOT_SHA256") echo "vendor_boot_state=verified_cpu2_1776_patch" ;;
  "$OC_1608_VENDOR_BOOT_SHA256") echo "vendor_boot_state=verified_cpu4_1608_patch" ;;
  "$OC_1560_VENDOR_BOOT_SHA256") echo "vendor_boot_state=verified_cpu4_1560_patch" ;;
  "$OC_STOCK_VENDOR_BOOT_SHA256") echo "vendor_boot_state=verified_stock" ;;
  *) echo "vendor_boot_state=unknown_or_modified" ;;
 esac

 echo "boost=$(cat "$BOOST_NODE" 2>/dev/null)"
 echo "policy0_boost_frequencies=$(cat "$POLICY0/scaling_boost_frequencies" 2>/dev/null)"
 echo "policy0_scaling_max_khz=$(cat "$POLICY0/scaling_max_freq" 2>/dev/null)"
 echo "policy0_cpuinfo_max_khz=$(cat "$POLICY0/cpuinfo_max_freq" 2>/dev/null)"
 echo "policy2_boost_frequencies=$(cat "$POLICY2/scaling_boost_frequencies" 2>/dev/null)"
 echo "policy2_scaling_max_khz=$(cat "$POLICY2/scaling_max_freq" 2>/dev/null)"
 echo "policy2_cpuinfo_max_khz=$(cat "$POLICY2/cpuinfo_max_freq" 2>/dev/null)"
 echo "policy4_boost_frequencies=$(cat "$POLICY4/scaling_boost_frequencies" 2>/dev/null)"
 echo "policy4_scaling_max_khz=$(cat "$POLICY4/scaling_max_freq" 2>/dev/null)"
 echo "policy4_cpuinfo_max_khz=$(cat "$POLICY4/cpuinfo_max_freq" 2>/dev/null)"
 echo "cluster0_regulator=axp2202-dcdc1"
 echo "cluster1_regulator=axp1530-dcdc2"
 echo "cluster2_regulator=axp1530-dcdc1"

 if oc_1296_support_present; then
  echo "a53_efficiency_stage1_1296=validated_available"
  echo "a53_efficiency_stage1_voltage_uv=1100000"
  echo "a53_efficiency_stage1_validation=stage9c_light_load_pass"
  echo "oc_1296_apply_supported=1"
  if [ "$current_vendor_boot_sha256" = "$OC_1344_VENDOR_BOOT_SHA256" ]; then
   echo "boot_opp_patch_state=installed_stage10_1344"
  else
   echo "boot_opp_patch_state=installed_stage9_1296"
  fi
  echo "boot_opp_install_blocker=none"
  echo "oc_policy0_max_validated_khz=1296000"
 elif [ "$(cat /sys/kernel/debug/opp/cpu0/opp:1296000000/available 2>/dev/null)" = Y ]; then
  echo "a53_efficiency_stage1_1296=present_unverified"
  echo "oc_1296_apply_supported=0"
 else
  echo "a53_efficiency_stage1_1296=boot_opp_required"
  echo "oc_1296_apply_supported=0"
 fi

 if oc_1344_support_present; then
  echo "a53_efficiency_stage2_1344=validated_available"
  echo "a53_efficiency_stage2_voltage_uv=1150000"
  echo "a53_efficiency_stage2_validation=stage10c_light_load_pass"
  echo "oc_1344_apply_supported=1"
  echo "boot_opp_patch_state=installed_stage10_1344"
  echo "boot_opp_install_blocker=none"
  echo "oc_policy0_max_validated_khz=1344000"
 elif [ "$(cat /sys/kernel/debug/opp/cpu0/opp:1344000000/available 2>/dev/null)" = Y ]; then
  echo "a53_efficiency_stage2_1344=present_unverified"
  echo "oc_1344_apply_supported=0"
 else
  echo "a53_efficiency_stage2_1344=boot_opp_required"
  echo "oc_1344_apply_supported=0"
 fi

 if oc_1560_support_present; then
  echo "a73_stage1_1560=validated_available"
  echo "a73_stage1_voltage_uv=1150000"
  echo "a73_stage1_validation=stage6c_light_load_pass"
  echo "oc_apply_supported=1"
 else
  if [ "$(cat /sys/kernel/debug/opp/cpu4/opp:1560000000/available 2>/dev/null)" = Y ]; then
   echo "a73_stage1_1560=present_unverified"
  else
   echo "a73_stage1_1560=boot_opp_required"
  fi
  echo "oc_apply_supported=0"
 fi

 if oc_1608_support_present; then
  echo "a73_stage2_1608=validated_available"
  echo "a73_stage2_voltage_uv=1150000"
  echo "a73_stage2_validation=stage7c_light_load_pass"
  echo "oc_1608_apply_supported=1"
  echo "oc_max_validated_khz=1608000"
  echo "boot_opp_patch_state=installed_stage7_1608"
  echo "boot_opp_install_blocker=none"
 elif oc_1560_support_present; then
  if [ "$(cat /sys/kernel/debug/opp/cpu4/opp:1608000000/available 2>/dev/null)" = Y ]; then
   echo "a73_stage2_1608=present_unverified"
  else
   echo "a73_stage2_1608=boot_opp_required"
  fi
  echo "oc_1608_apply_supported=0"
  echo "oc_max_validated_khz=1560000"
  echo "boot_opp_patch_state=installed_stage6_1560"
  echo "boot_opp_install_blocker=stage7_1608_patch_required"
 else
  if [ "$(cat /sys/kernel/debug/opp/cpu4/opp:1608000000/available 2>/dev/null)" = Y ]; then
   echo "a73_stage2_1608=present_unverified"
  else
   echo "a73_stage2_1608=boot_opp_required"
  fi
  echo "oc_1608_apply_supported=0"
  echo "oc_max_validated_khz=0"
  echo "boot_opp_patch_state=not_installed"
  if [ "$current_vendor_boot_sha256" = "$OC_STOCK_VENDOR_BOOT_SHA256" ]; then
   echo "boot_opp_install_blocker=verified_patch_install_required"
  else
   echo "boot_opp_install_blocker=unknown_vendor_boot"
  fi
 fi

 if oc_1776_support_present; then
  echo "a53_stage1_1776=validated_available"
  echo "a53_stage1_voltage_uv=1150000"
  echo "a53_stage1_validation=stage8c_light_load_pass"
  echo "oc_1776_apply_supported=1"
  if [ "$current_vendor_boot_sha256" = "$OC_1344_VENDOR_BOOT_SHA256" ]; then
   echo "boot_opp_patch_state=installed_stage10_1344"
  elif [ "$current_vendor_boot_sha256" = "$OC_1296_VENDOR_BOOT_SHA256" ]; then
   echo "boot_opp_patch_state=installed_stage9_1296"
  else
   echo "boot_opp_patch_state=installed_stage8_1776"
  fi
  echo "boot_opp_install_blocker=none"
  echo "oc_max_validated_khz=1776000"
 elif [ "$(cat /sys/kernel/debug/opp/cpu2/opp:1776000000/available 2>/dev/null)" = Y ]; then
  echo "a53_stage1_1776=present_unverified"
  echo "oc_1776_apply_supported=0"
 else
  echo "a53_stage1_1776=boot_opp_required"
  echo "oc_1776_apply_supported=0"
 fi

 echo "a73_stage2_candidate_voltage_uv=1150000"
 echo "a53_stage1_candidate_voltage_uv=1150000"
 echo "a53_efficiency_stage2_candidate_voltage_uv=1150000"
 echo "higher_opp_validation_required=1"
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
 oc) case "$2" in dynamic1296|performance1296|dynamic1344|performance1344|dynamic1560|performance1560|dynamic1608|performance1608|dynamic1776|performance1776) oc_apply "$2" ;; off|stock) oc_disable ;; *) exit 2 ;; esac ;;
 stock|restore) restore_stock ;;
 *) echo "cpu_control.sh init|status|balanced|performance|policy POLICY min|max|governor VALUE|oc-status|oc dynamic1296|performance1296|dynamic1344|performance1344|dynamic1560|performance1560|dynamic1608|performance1608|dynamic1776|performance1776|off|restore"; exit 2 ;;
esac
