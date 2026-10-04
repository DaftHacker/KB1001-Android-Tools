#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

AUTO_STATE="/data/local/tmp/kb1001_game_boost.state"
LOGGER_CONF="$STATE_DIR/logger.conf"
LOGGER_PID="/data/local/tmp/kb1001_perf_logger.pid"
SNAPSHOT="$STATE_DIR/telemetry.txt"
HISTORY="$STATE_DIR/telemetry_history.log"
PUBLIC_DIR="/storage/emulated/0/Android/data/com.dafthacker.kb1001perf/files/telemetry"
PUBLIC_SNAPSHOT="$PUBLIC_DIR/current.txt"
LOG_ROOT="/storage/emulated/0/Documents/KB1001Performance/logs"
UI_DEMAND="/data/local/tmp/kb1001_telemetry_ui"
HUD_DEMAND="/data/local/tmp/kb1001_telemetry_hud"

BAT_PATH=""
INPUT_PATH=""
CPU_AVAILABLE_CACHE=""
CPU_CAPACITY_CACHE=""

demand_active(){
 [ -e "$UI_DEMAND" ] || [ -e "$HUD_DEMAND" ]
}

detect_power_paths(){
 [ -n "$BAT_PATH" ] && return 0
 for ps in /sys/class/power_supply/*; do
  [ -d "$ps" ] || continue
  pst="$(cat "$ps/type" 2>/dev/null)"
  if [ "$pst" = Battery ] && [ -z "$BAT_PATH" ]; then BAT_PATH="$ps"; fi
  case "$pst" in USB|USB_C|Mains|Wireless) [ -z "$INPUT_PATH" ] && INPUT_PATH="$ps";; esac
 done
}

conf_get(){ v="$(grep -m1 "^$1=" "$LOGGER_CONF" 2>/dev/null|cut -d= -f2-)"; [ -n "$v" ]&&printf '%s' "$v"||printf '%s' "$2"; }
to_c(){ v="$1"; case "$v" in ''|*[!0-9-]*) echo "0.0";; *) if [ "$v" -gt 1000 ] 2>/dev/null; then awk "BEGIN{printf \"%.1f\",$v/1000}"; else awk "BEGIN{printf \"%.1f\",$v}"; fi;; esac; }

CPU_STAT_PREV="/data/local/tmp/kb1001_cpu_stat.$$.prev"
FPS_CACHE=0
FPS_CACHE_SOURCE=none
FPS_CACHE_LAYER=""
FPS_CACHE_PACKAGE=""
FPS_CACHE_TS=0

sample_cpu_util(){
 cur="$CPU_STAT_PREV.cur"
 grep '^cpu' /proc/stat 2>/dev/null > "$cur"
 if [ ! -s "$CPU_STAT_PREV" ]; then
  cp "$cur" "$CPU_STAT_PREV" 2>/dev/null
  sleep 0.12
  grep '^cpu' /proc/stat 2>/dev/null > "$cur"
 fi

 awk '
  NR==FNR {
   name=$1
   total=0
   for(i=2;i<=9;i++) total+=$i
   idle=$5+$6
   pt[name]=total
   pi[name]=idle
   next
  }
  {
   name=$1
   total=0
   for(i=2;i<=9;i++) total+=$i
   idle=$5+$6
   if(name in pt){
    dt=total-pt[name]
    di=idle-pi[name]
    pct=(dt>0)?int((((dt-di)*100.0/dt)+0.5)):0
    if(pct<0)pct=0
    if(pct>100)pct=100
    if(name=="cpu") overall=pct
    else cores=cores name ":" pct ";"
   }
  }
  END { printf "%d|%s",overall,cores }
 ' "$CPU_STAT_PREV" "$cur" 2>/dev/null

 mv "$cur" "$CPU_STAT_PREV" 2>/dev/null
}

clean_sf_layer(){
 printf '%s' "$1" | sed   -e 's/^RequestedLayerState{//'   -e 's/ parentId=.*$//'   -e 's/ relativeParentId=.*$//'   -e 's/ z=.*$//'   -e 's/}$//'
}

surface_fps(){
 layer="$1"
 dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk '
  NR==1 { next }
  NF>=3 && $2 ~ /^[0-9]+$/ && $2>0 && $2<9000000000000000000 { t[++n]=$2 }
  END {
   if(n<2){print 0;exit}
   start=n-59
   if(start<1)start=1
   span=t[n]-t[start]
   frames=n-start
   if(span<=0||frames<=0){print 0;exit}
   fps=int((frames*1000000000.0/span)+0.5)
   if(fps<0)fps=0
   if(fps>240)fps=240
   print fps
  }'
}

sample_fps(){
 pkg="$1"
 [ -n "$pkg" ] || { echo "0|none|"; return; }

 layers="$(dumpsys SurfaceFlinger --list 2>/dev/null)"

 # Native games frequently present through a BLAST-backed SurfaceView. Prefer
 # that actual rendering layer over Activity/background/container layers.
 line="$(printf '%s\n' "$layers" | grep -F "$pkg" | grep -E 'SurfaceView.*BLAST|BLAST.*SurfaceView' | head -1)"
 [ -n "$line" ] || line="$(printf '%s\n' "$layers" | grep -F "$pkg" | grep -E 'SurfaceView|BLAST' | head -1)"
 [ -n "$line" ] || line="$(printf '%s\n' "$layers" | grep -F "$pkg" | head -1)"

 if [ -z "$line" ]; then
  short="${pkg##*.}"
  if [ "${#short}" -ge 4 ]; then
   line="$(printf '%s\n' "$layers" | grep -Fi "$short" | grep -E 'SurfaceView.*BLAST|BLAST.*SurfaceView' | head -1)"
   [ -n "$line" ] || line="$(printf '%s\n' "$layers" | grep -Fi "$short" | grep -E 'SurfaceView|BLAST' | head -1)"
  fi
 fi

 if [ -n "$line" ]; then
  layer="$(clean_sf_layer "$line")"
  fps="$(surface_fps "$layer")"
  case "$fps" in ''|*[!0-9]*) fps=0;; esac

  if [ "$fps" -le 0 ]; then
   noid="$(printf '%s' "$layer" | sed 's/#[0-9][0-9]*$//')"
   if [ "$noid" != "$layer" ]; then
    fps="$(surface_fps "$noid")"
    case "$fps" in ''|*[!0-9]*) fps=0;; esac
    [ "$fps" -gt 0 ] && layer="$noid"
   fi
  fi

  if [ "$fps" -gt 0 ]; then
   safe_layer="$(printf '%s' "$layer" | tr '|\n' '__')"
   echo "$fps|surfaceflinger|$safe_layer"
   return
  fi
 fi

 fps="$(dumpsys gfxinfo "$pkg" framestats 2>/dev/null | awk -F, '
  /^Flags,IntendedVsync/ {
   completed=0
   for(i=1;i<=NF;i++) if($i=="FrameCompleted") completed=i
   next
  }
  completed>0 && $completed ~ /^[0-9]+$/ && $completed>0 { t[++n]=$completed }
  END {
   if(n<2){print 0;exit}
   start=n-59
   if(start<1)start=1
   span=t[n]-t[start]
   frames=n-start
   if(span<=0||frames<=0){print 0;exit}
   fps=int((frames*1000000000.0/span)+0.5)
   if(fps<0)fps=0
   if(fps>240)fps=240
   print fps
  }')"
 case "$fps" in ''|*[!0-9]*) fps=0;; esac
 echo "$fps|gfxinfo|"
}
sample(){
 HUD_ONLY=0
 if [ -e "$HUD_DEMAND" ] && [ ! -e "$UI_DEMAND" ] && [ "$(conf_get file_logging 0)" != 1 ]; then HUD_ONLY=1; fi
 MODE="$(grep -m1 '^mode=' "$AUTO_STATE" 2>/dev/null|cut -d= -f2-)"; [ -n "$MODE" ]||MODE=idle
 PACKAGE="$(grep -m1 '^package=' "$AUTO_STATE" 2>/dev/null|cut -d= -f2-)"
 PROFILE="$(grep -m1 '^profile=' "$AUTO_STATE" 2>/dev/null|cut -d= -f2-)"
 runtime_profile="$(cat "$RUNTIME_PROFILE" 2>/dev/null)"
 if [ -n "$runtime_profile" ]; then
  PROFILE="$runtime_profile"
 fi
 [ -n "$PROFILE" ]||PROFILE="$(cat "$CONFIG" 2>/dev/null)"

 raw="$(cat "$DEVFREQ/cur_freq" 2>/dev/null | head -1)"
 [ -n "$raw" ] || raw="$(cat "$FREQ" 2>/dev/null | head -1)"
 gpu_num="$(printf '%s' "$raw" | grep -o '[0-9][0-9]*' | head -1)"
 case "$gpu_num" in
   ''|*[!0-9]*) GPU_MHZ=0 ;;
   *)
     if [ "$gpu_num" -ge 10000000 ] 2>/dev/null; then
       GPU_MHZ=$((gpu_num/1000000))
     elif [ "$gpu_num" -ge 10000 ] 2>/dev/null; then
       GPU_MHZ=$((gpu_num/1000))
     else
       GPU_MHZ="$gpu_num"
     fi
     ;;
 esac
 GPU_VOLTAGE="$(cat "$SUNXI/sunxi_gpu_volt" 2>/dev/null|head -1)"
 GPU_RUNTIME="$(cat "$GPU/power/runtime_status" 2>/dev/null)"
 GPU_GOV="$(cat "$DEVFREQ/governor" 2>/dev/null)"
 GPU_DVFS="$(cat "$DVFS" 2>/dev/null)"

 vendor_gpu="$(cat "$FREQ" 2>/dev/null)"
 GPU_UTIL="$(printf '%s\n' "$vendor_gpu" | sed -n 's/.*Utilisation from last show:\([0-9][0-9]*\)%.*/\1/p' | head -1)"
 case "$GPU_UTIL" in ''|*[!0-9]*) GPU_UTIL=0;; esac

 cpu_util_sample="$(sample_cpu_util)"
 CPU_UTIL="$(printf '%s' "$cpu_util_sample" | cut -d'|' -f1)"
 CPU_CORE_UTIL="$(printf '%s' "$cpu_util_sample" | cut -d'|' -f2-)"
 case "$CPU_UTIL" in ''|*[!0-9]*) CPU_UTIL=0;; esac

 max=-999999; zones=""
 for z in /sys/class/thermal/thermal_zone*; do
  [ -d "$z" ]||continue
  type="$(cat "$z/type" 2>/dev/null|tr ' ,' '__')"; temp="$(cat "$z/temp" 2>/dev/null)"
  case "$temp" in ''|*[!0-9-]*) continue;; esac
  [ "$temp" -gt "$max" ]&&max="$temp"
  zones="${zones}${type}:$(to_c "$temp")C;"
 done
 [ "$max" = -999999 ]&&max=0
 THERMAL_MAX="$(to_c "$max")"

 THERMAL_THROTTLING=0
 cooling=""
 for cd in /sys/class/thermal/cooling_device*; do
  [ -d "$cd" ] || continue
  ctype="$(cat "$cd/type" 2>/dev/null | tr ' ,' '__')"
  ccur="$(cat "$cd/cur_state" 2>/dev/null)"
  cmax="$(cat "$cd/max_state" 2>/dev/null)"
  case "$ccur" in ''|*[!0-9]*) ccur=0;; esac
  case "$cmax" in ''|*[!0-9]*) cmax=0;; esac
  [ "$ccur" -gt 0 ] && THERMAL_THROTTLING=1
  cooling="${cooling}${ctype}:${ccur}/${cmax};"
 done

 cpu=""; cpu_detail=""
 if [ -z "$CPU_AVAILABLE_CACHE" ]; then
  cpu_available_build=""
  for p in /sys/devices/system/cpu/cpufreq/policy*; do
   [ -d "$p" ]||continue
   n="${p##*/}"; cpus="$(cat "$p/related_cpus" 2>/dev/null|tr ' ' '-')"
   avail="$(cat "$p/scaling_available_frequencies" 2>/dev/null | tr ' ' ',' | sed 's/,$//')"
   govs="$(cat "$p/scaling_available_governors" 2>/dev/null | tr ' ' ',')"
   cpu_available_build="${cpu_available_build}${n}[${cpus}]=${avail}@${govs};"
  done
  CPU_AVAILABLE_CACHE="$cpu_available_build"
 fi
 cpu_available="$CPU_AVAILABLE_CACHE"

 if [ -z "$CPU_CAPACITY_CACHE" ]; then
  cap_build=""
  for core in /sys/devices/system/cpu/cpu[0-9]*; do
   [ -d "$core" ] || continue
   cn="${core##*cpu}"
   cap="$(cat "$core/cpu_capacity_orig" 2>/dev/null)"
   [ -n "$cap" ] || cap="$(cat "$core/cpu_capacity" 2>/dev/null)"
   [ -n "$cap" ] || cap=0
   cap_build="${cap_build}cpu${cn}:${cap};"
  done
  CPU_CAPACITY_CACHE="$cap_build"
 fi
 cpu_capacity="$CPU_CAPACITY_CACHE"
 cpu_online="$(cat /sys/devices/system/cpu/online 2>/dev/null)"

 for p in /sys/devices/system/cpu/cpufreq/policy*; do
  [ -d "$p" ]||continue
  n="${p##*/}"; cur="$(cat "$p/scaling_cur_freq" 2>/dev/null)"; min="$(cat "$p/scaling_min_freq" 2>/dev/null)"; maxf="$(cat "$p/scaling_max_freq" 2>/dev/null)"; gov="$(cat "$p/scaling_governor" 2>/dev/null)"; cpus="$(cat "$p/related_cpus" 2>/dev/null|tr ' ' '-')"
  cm=$((${cur:-0}/1000)); mim=$((${min:-0}/1000)); mam=$((${maxf:-0}/1000))
  cpu="${cpu}${n}:${cm}MHz "
  cpu_detail="${cpu_detail}${n}[${cpus}]=${cm}/${mim}-${mam}@${gov};"
 done
 CPU_SUMMARY="$(echo "$cpu"|sed 's/[[:space:]]*$//')"

 devs=""
 if [ "$HUD_ONLY" != 1 ]; then
  for d in /sys/class/devfreq/*; do
   [ -d "$d" ]||continue; n="${d##*/}"
   devs="${devs}${n}:$(cat "$d/cur_freq" 2>/dev/null)/$(cat "$d/min_freq" 2>/dev/null)-$(cat "$d/max_freq" 2>/dev/null)@$(cat "$d/governor" 2>/dev/null);"
  done
 fi

 mem="$(awk '/MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)"; MEM_MB=$((${mem:-0}/1024))
 mem_total="$(awk '/MemTotal:/{print $2}' /proc/meminfo 2>/dev/null)"; MEM_TOTAL_MB=$((${mem_total:-0}/1024))
 LOADAVG="$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
 detect_power_paths
 batt="$(cat "$BAT_PATH/temp" 2>/dev/null)"; case "$batt" in ''|*[!0-9-]*) BATTERY_C=0.0;; *) BATTERY_C="$(awk "BEGIN{printf \"%.1f\",$batt/10}")";; esac
 BATTERY_STATUS="$(cat "$BAT_PATH/status" 2>/dev/null)"
 BATTERY_CAPACITY="$(cat "$BAT_PATH/capacity" 2>/dev/null)"
 if [ "$HUD_ONLY" = 1 ]; then
  BATTERY_CAPACITY_LEVEL=""
  BATTERY_HEALTH=""
  BATTERY_TECH=""
  BATTERY_MANUFACTURER=""
  BATTERY_CURRENT=""
  BATTERY_CURRENT_AVG=""
  BATTERY_CHARGE_CURRENT=""
  BATTERY_VOLTAGE=""
  BATTERY_CHARGE_COUNTER=""
  BATTERY_CHARGE_FULL=""
  BATTERY_CHARGE_FULL_DESIGN=""
  BATTERY_CYCLE_COUNT=""
  BATTERY_ENERGY_NOW=""
  BATTERY_ENERGY_FULL_DESIGN=""
  BATTERY_TIME_TO_FULL=""
  BATTERY_TIME_TO_EMPTY=""
  BATTERY_CHARGE_CONTROL=""
  BATTERY_CHARGE_CONTROL_MAX=""
  BATTERY_MFG_YEAR=""
  BATTERY_MFG_MONTH=""
  BATTERY_MFG_DAY=""
  POWER_TYPE="$(cat "$INPUT_PATH/type" 2>/dev/null)"
  POWER_ONLINE="$(cat "$INPUT_PATH/online" 2>/dev/null)"
  POWER_PRESENT=""
  POWER_TEMP=""
  POWER_VOLTAGE=""
  POWER_VOLTAGE_MIN_DESIGN=""
  POWER_CURRENT=""
  POWER_CURRENT_MAX=""
  POWER_INPUT_LIMIT=""
  POWER_VOLTAGE_MAX=""
  POWER_USB_TYPE=""
  POWER_SCOPE=""
 else
  BATTERY_CAPACITY_LEVEL="$(cat "$BAT_PATH/capacity_level" 2>/dev/null)"
  BATTERY_HEALTH="$(cat "$BAT_PATH/health" 2>/dev/null)"
  BATTERY_TECH="$(cat "$BAT_PATH/technology" 2>/dev/null)"
  BATTERY_MANUFACTURER="$(cat "$BAT_PATH/manufacturer" 2>/dev/null)"
  BATTERY_CURRENT="$(cat "$BAT_PATH/current_now" 2>/dev/null)"
  BATTERY_CURRENT_AVG="$(cat "$BAT_PATH/current_avg" 2>/dev/null)"
  BATTERY_CHARGE_CURRENT="$(cat "$BAT_PATH/constant_charge_current" 2>/dev/null)"
  BATTERY_VOLTAGE="$(cat "$BAT_PATH/voltage_now" 2>/dev/null)"
  BATTERY_CHARGE_COUNTER="$(cat "$BAT_PATH/charge_counter" 2>/dev/null)"
  BATTERY_CHARGE_FULL="$(cat "$BAT_PATH/charge_full" 2>/dev/null)"
  BATTERY_CHARGE_FULL_DESIGN="$(cat "$BAT_PATH/charge_full_design" 2>/dev/null)"
  BATTERY_CYCLE_COUNT="$(cat "$BAT_PATH/cycle_count" 2>/dev/null)"
  BATTERY_ENERGY_NOW="$(cat "$BAT_PATH/energy_now" 2>/dev/null)"
  BATTERY_ENERGY_FULL_DESIGN="$(cat "$BAT_PATH/energy_full_design" 2>/dev/null)"
  BATTERY_TIME_TO_FULL="$(cat "$BAT_PATH/time_to_full_now" 2>/dev/null)"
  BATTERY_TIME_TO_EMPTY="$(cat "$BAT_PATH/time_to_empty_now" 2>/dev/null)"
  BATTERY_CHARGE_CONTROL="$(cat "$BAT_PATH/charge_control_limit" 2>/dev/null)"
  BATTERY_CHARGE_CONTROL_MAX="$(cat "$BAT_PATH/charge_control_limit_max" 2>/dev/null)"
  BATTERY_MFG_YEAR="$(cat "$BAT_PATH/manufacture_year" 2>/dev/null)"
  BATTERY_MFG_MONTH="$(cat "$BAT_PATH/manufacture_month" 2>/dev/null)"
  BATTERY_MFG_DAY="$(cat "$BAT_PATH/manufacture_day" 2>/dev/null)"
  POWER_TYPE="$(cat "$INPUT_PATH/type" 2>/dev/null)"
  POWER_ONLINE="$(cat "$INPUT_PATH/online" 2>/dev/null)"
  POWER_PRESENT="$(cat "$INPUT_PATH/present" 2>/dev/null)"
  POWER_TEMP="$(cat "$INPUT_PATH/temp" 2>/dev/null)"
  POWER_VOLTAGE="$(cat "$INPUT_PATH/voltage_now" 2>/dev/null)"
  POWER_VOLTAGE_MIN_DESIGN="$(cat "$INPUT_PATH/voltage_min_design" 2>/dev/null)"
  POWER_CURRENT="$(cat "$INPUT_PATH/current_now" 2>/dev/null)"
  POWER_CURRENT_MAX="$(cat "$INPUT_PATH/current_max" 2>/dev/null)"
  POWER_INPUT_LIMIT="$(cat "$INPUT_PATH/input_current_limit" 2>/dev/null)"
  POWER_VOLTAGE_MAX="$(cat "$INPUT_PATH/voltage_max" 2>/dev/null)"
  POWER_USB_TYPE="$(cat "$INPUT_PATH/usb_type" 2>/dev/null)"
  POWER_SCOPE="$(cat "$INPUT_PATH/scope" 2>/dev/null)"
 fi

 FPS=0
 FPS_SOURCE=none
 FPS_LAYER=""
 if [ "$MODE" = game ] && [ -n "$PACKAGE" ] && { [ -e "$UI_DEMAND" ] || [ "$(conf_get file_logging 0)" = 1 ]; }; then
  now_s="$(date +%s)"
  fps_interval=3
  if [ "$PACKAGE" != "$FPS_CACHE_PACKAGE" ] || [ $((now_s-FPS_CACHE_TS)) -ge "$fps_interval" ]; then
   fps_sample="$(sample_fps "$PACKAGE")"
   FPS_CACHE="$(printf '%s' "$fps_sample" | cut -d'|' -f1)"
   FPS_CACHE_SOURCE="$(printf '%s' "$fps_sample" | cut -d'|' -f2)"
   FPS_CACHE_LAYER="$(printf '%s' "$fps_sample" | cut -d'|' -f3-)"
   case "$FPS_CACHE" in ''|*[!0-9]*) FPS_CACHE=0;; esac
   FPS_CACHE_PACKAGE="$PACKAGE"
   FPS_CACHE_TS="$now_s"
  fi
  FPS="$FPS_CACHE"
  FPS_SOURCE="$FPS_CACHE_SOURCE"
  FPS_LAYER="$FPS_CACHE_LAYER"
 fi

 PROFILE_REQUEST_STATE="$(grep -m1 '^state=' "$STATE_DIR/manual_profile.state" 2>/dev/null | cut -d= -f2-)"
 PROFILE_REQUEST_PROFILE="$(grep -m1 '^profile=' "$STATE_DIR/manual_profile.state" 2>/dev/null | cut -d= -f2-)"
 CPU_MODE="$(cat "$STATE_DIR/cpu_mode.conf" 2>/dev/null)"
 FILE_LOGGING="$(conf_get file_logging 0)"; FILE_PATH="$(conf_get file_path "")"

 tmp="$SNAPSHOT.tmp.$$"
 {
  echo "timestamp=$(date '+%Y-%m-%d %H:%M:%S')"; echo "mode=$MODE"; echo "package=$PACKAGE"; echo "profile=$PROFILE"
  echo "gpu_clock_mhz=$GPU_MHZ"; echo "gpu_util_pct=$GPU_UTIL"; echo "gpu_voltage=$GPU_VOLTAGE"; echo "gpu_runtime=$GPU_RUNTIME"; echo "gpu_governor=$GPU_GOV"; echo "gpu_dvfs=$GPU_DVFS"
  echo "thermal_max_c=$THERMAL_MAX"; echo "thermal_zones=$zones"; echo "thermal_throttling=$THERMAL_THROTTLING"; echo "cooling_devices=$cooling"; echo "battery_temp_c=$BATTERY_C"
  echo "cpu_mode=$CPU_MODE"; echo "cpu_util_pct=$CPU_UTIL"; echo "cpu_core_util=$CPU_CORE_UTIL"; echo "cpu_core_capacity=$cpu_capacity"; echo "cpu_online=$cpu_online"; echo "cpu_summary=$CPU_SUMMARY"; echo "cpu_policies=$cpu_detail"; echo "cpu_available=$cpu_available"; echo "devfreq=$devs"
  echo "battery_status=$BATTERY_STATUS"; echo "battery_capacity=$BATTERY_CAPACITY"; echo "battery_capacity_level=$BATTERY_CAPACITY_LEVEL"; echo "battery_health=$BATTERY_HEALTH"; echo "battery_technology=$BATTERY_TECH"; echo "battery_manufacturer=$BATTERY_MANUFACTURER"
  echo "battery_current=$BATTERY_CURRENT"; echo "battery_current_avg=$BATTERY_CURRENT_AVG"; echo "battery_charge_current=$BATTERY_CHARGE_CURRENT"; echo "battery_voltage=$BATTERY_VOLTAGE"
  echo "battery_charge_counter=$BATTERY_CHARGE_COUNTER"; echo "battery_charge_full=$BATTERY_CHARGE_FULL"; echo "battery_charge_full_design=$BATTERY_CHARGE_FULL_DESIGN"; echo "battery_cycle_count=$BATTERY_CYCLE_COUNT"; echo "battery_energy_now=$BATTERY_ENERGY_NOW"; echo "battery_energy_full_design=$BATTERY_ENERGY_FULL_DESIGN"
  echo "battery_time_to_full_s=$BATTERY_TIME_TO_FULL"; echo "battery_time_to_empty_s=$BATTERY_TIME_TO_EMPTY"; echo "battery_charge_control=$BATTERY_CHARGE_CONTROL"; echo "battery_charge_control_max=$BATTERY_CHARGE_CONTROL_MAX"
  echo "battery_mfg_year=$BATTERY_MFG_YEAR"; echo "battery_mfg_month=$BATTERY_MFG_MONTH"; echo "battery_mfg_day=$BATTERY_MFG_DAY"
  echo "power_type=$POWER_TYPE"; echo "power_online=$POWER_ONLINE"; echo "power_present=$POWER_PRESENT"; echo "power_temp=$POWER_TEMP"; echo "power_voltage=$POWER_VOLTAGE"; echo "power_voltage_min_design=$POWER_VOLTAGE_MIN_DESIGN"
  echo "power_current=$POWER_CURRENT"; echo "power_current_max=$POWER_CURRENT_MAX"; echo "power_input_limit=$POWER_INPUT_LIMIT"; echo "power_voltage_max=$POWER_VOLTAGE_MAX"; echo "power_usb_type=$POWER_USB_TYPE"; echo "power_scope=$POWER_SCOPE"
  echo "fps=$FPS"; echo "fps_source=$FPS_SOURCE"; echo "fps_layer=$FPS_LAYER"
  echo "profile_request_state=$PROFILE_REQUEST_STATE"; echo "profile_request_profile=$PROFILE_REQUEST_PROFILE"
  echo "mem_available_mb=$MEM_MB"; echo "mem_total_mb=$MEM_TOTAL_MB"; echo "loadavg=$LOADAVG"; echo "file_logging=$FILE_LOGGING"; echo "file_path=$FILE_PATH"
 } > "$tmp" && mv "$tmp" "$SNAPSHOT"
 chmod 0644 "$SNAPSHOT" 2>/dev/null

 if demand_active && [ -d "$PUBLIC_DIR" ]; then
  cp "$SNAPSHOT" "$PUBLIC_SNAPSHOT.tmp" 2>/dev/null && mv "$PUBLIC_SNAPSHOT.tmp" "$PUBLIC_SNAPSHOT"
  chmod 0644 "$PUBLIC_SNAPSHOT" 2>/dev/null
 fi

 if [ "$FILE_LOGGING" = 1 ]; then
  echo "$(date '+%H:%M:%S') | $MODE | $PROFILE | FPS $FPS | CPU ${CPU_UTIL}% $CPU_SUMMARY | GPU ${GPU_UTIL}% ${GPU_MHZ}MHz | TEMP ${THERMAL_MAX}C | RAM ${MEM_MB}MB | $PACKAGE" >> "$HISTORY"
  lines="$(wc -l < "$HISTORY" 2>/dev/null)"; [ "${lines:-0}" -gt 600 ]&&tail -n 300 "$HISTORY" > "$HISTORY.tmp"&&mv "$HISTORY.tmp" "$HISTORY"


  path="$FILE_PATH"
  if [ -z "$path" ]; then
   mkdir -p "$LOG_ROOT" 2>/dev/null
   path="$LOG_ROOT/kb1001-$(date '+%Y%m%d-%H%M%S').csv"
   { grep -v '^file_path=' "$LOGGER_CONF" 2>/dev/null; echo "file_path=$path"; } > "$LOGGER_CONF.tmp.$$"&&mv "$LOGGER_CONF.tmp.$$" "$LOGGER_CONF"
   echo "timestamp,mode,package,profile,fps,cpu_util_pct,gpu_util_pct,gpu_mhz,gpu_voltage,thermal_max_c,thermal_throttling,battery_c,cpu,mem_available_mb,loadavg" > "$path"
  fi
  safe_cpu="$(printf '%s' "$CPU_SUMMARY"|tr ',' ';')"
  echo "$(date '+%Y-%m-%d %H:%M:%S'),$MODE,$PACKAGE,$PROFILE,$FPS,$CPU_UTIL,$GPU_UTIL,$GPU_MHZ,$GPU_VOLTAGE,$THERMAL_MAX,$THERMAL_THROTTLING,$BATTERY_C,$safe_cpu,$MEM_MB,$LOADAVG" >> "$path" 2>/dev/null
 fi
}

run(){
 old="$(cat "$LOGGER_PID" 2>/dev/null)"
 case "$old" in
  ''|*[!0-9]*) ;;
  *) [ "$old" != "$$" ] && kill -0 "$old" 2>/dev/null && exit 0 ;;
 esac

 echo $$ > "$LOGGER_PID"
 trap 'rm -f "$LOGGER_PID" "$CPU_STAT_PREV" "$CPU_STAT_PREV.cur"; exit 0' INT TERM EXIT
 log "Performance telemetry daemon started (pid=$$)."

 while true; do
  sample

  if [ -e "$UI_DEMAND" ]; then
   i=1
  elif [ "$(conf_get file_logging 0)" = 1 ]; then
   i="$(conf_get interval_seconds 1)"
   case "$i" in 1|2|3|4|5|6|7|8|9|10) ;; *) i=1;; esac
  elif [ -e "$HUD_DEMAND" ]; then
   # HUD-only mode is deliberately slower to reduce game-side CPU/compositor overhead.
   i=2
  else
   # Background/minimized with no recording: tiny maintenance cadence only.
   i=30
  fi

  sleep "$i"
 done
}
case "$1" in
 --daemon|daemon) run;;
 --once|once) sample; rm -f "$CPU_STAT_PREV" "$CPU_STAT_PREV.cur";;
 *) exit 1;;
esac
