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

conf_get(){ v="$(grep -m1 "^$1=" "$LOGGER_CONF" 2>/dev/null|cut -d= -f2-)"; [ -n "$v" ]&&printf '%s' "$v"||printf '%s' "$2"; }
to_c(){ v="$1"; case "$v" in ''|*[!0-9-]*) echo "0.0";; *) if [ "$v" -gt 1000 ] 2>/dev/null; then awk "BEGIN{printf \"%.1f\",$v/1000}"; else awk "BEGIN{printf \"%.1f\",$v}"; fi;; esac; }

sample_fps(){
 pkg="$1"
 [ -n "$pkg" ] || { echo 0; return; }

 layer="$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F "$pkg" | grep -E 'SurfaceView|BLAST|Activity' | head -1)"
 [ -n "$layer" ] || layer="$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F "$pkg" | head -1)"
 [ -n "$layer" ] || { echo 0; return; }

 up="$(cut -d. -f1 /proc/uptime 2>/dev/null)"
 case "$up" in ''|*[!0-9]*) echo 0; return;; esac
 now_ns=$((up * 1000000000))
 cutoff=$((now_ns - 2000000000))

 dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk -v cutoff="$cutoff" '
  NR==1 { next }
  NF>=3 && $2 ~ /^[0-9]+$/ && $2 > 0 && $2 >= cutoff { count++ }
  END {
   if (count > 0) {
    fps = int((count / 2.0) + 0.5)
    if (fps > 240) fps = 240
    print fps
   } else print 0
  }'
}

sample(){
 MODE="$(grep -m1 '^mode=' "$AUTO_STATE" 2>/dev/null|cut -d= -f2-)"; [ -n "$MODE" ]||MODE=idle
 PACKAGE="$(grep -m1 '^package=' "$AUTO_STATE" 2>/dev/null|cut -d= -f2-)"
 PROFILE="$(grep -m1 '^profile=' "$AUTO_STATE" 2>/dev/null|cut -d= -f2-)"
 runtime_profile="$(cat "$RUNTIME_PROFILE" 2>/dev/null)"
 if [ -f "$SESSION_EXTREME" ] && [ -n "$runtime_profile" ]; then
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

 cpu=""; cpu_detail=""; cpu_available=""
 for p in /sys/devices/system/cpu/cpufreq/policy*; do
  [ -d "$p" ]||continue
  n="${p##*/}"; cur="$(cat "$p/scaling_cur_freq" 2>/dev/null)"; min="$(cat "$p/scaling_min_freq" 2>/dev/null)"; maxf="$(cat "$p/scaling_max_freq" 2>/dev/null)"; gov="$(cat "$p/scaling_governor" 2>/dev/null)"; cpus="$(cat "$p/related_cpus" 2>/dev/null|tr ' ' '-')"
  avail="$(cat "$p/scaling_available_frequencies" 2>/dev/null | tr ' ' ',' | sed 's/,$//')"
  govs="$(cat "$p/scaling_available_governors" 2>/dev/null | tr ' ' ',')"
  cm=$((${cur:-0}/1000)); mim=$((${min:-0}/1000)); mam=$((${maxf:-0}/1000))
  cpu="${cpu}${n}:${cm}MHz "
  cpu_detail="${cpu_detail}${n}[${cpus}]=${cm}/${mim}-${mam}@${gov};"
  cpu_available="${cpu_available}${n}[${cpus}]=${avail}@${govs};"
 done
 CPU_SUMMARY="$(echo "$cpu"|sed 's/[[:space:]]*$//')"

 devs=""
 for d in /sys/class/devfreq/*; do
  [ -d "$d" ]||continue; n="${d##*/}"
  devs="${devs}${n}:$(cat "$d/cur_freq" 2>/dev/null)/$(cat "$d/min_freq" 2>/dev/null)-$(cat "$d/max_freq" 2>/dev/null)@$(cat "$d/governor" 2>/dev/null);"
 done

 mem="$(awk '/MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)"; MEM_MB=$((${mem:-0}/1024))
 LOADAVG="$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
 batt="$(cat /sys/class/power_supply/battery/temp 2>/dev/null)"; case "$batt" in ''|*[!0-9-]*) BATTERY_C=0.0;; *) BATTERY_C="$(awk "BEGIN{printf \"%.1f\",$batt/10}")";; esac
 FPS=0
 if [ "$MODE" = game ] && [ -n "$PACKAGE" ]; then
  FPS="$(sample_fps "$PACKAGE")"
  case "$FPS" in ''|*[!0-9]*) FPS=0;; esac
 fi

 PROFILE_REQUEST_STATE="$(grep -m1 '^state=' "$STATE_DIR/manual_profile.state" 2>/dev/null | cut -d= -f2-)"
 PROFILE_REQUEST_PROFILE="$(grep -m1 '^profile=' "$STATE_DIR/manual_profile.state" 2>/dev/null | cut -d= -f2-)"
 FILE_LOGGING="$(conf_get file_logging 0)"; FILE_PATH="$(conf_get file_path "")"

 tmp="$SNAPSHOT.tmp.$$"
 {
  echo "timestamp=$(date '+%Y-%m-%d %H:%M:%S')"; echo "mode=$MODE"; echo "package=$PACKAGE"; echo "profile=$PROFILE"
  echo "gpu_clock_mhz=$GPU_MHZ"; echo "gpu_voltage=$GPU_VOLTAGE"; echo "gpu_runtime=$GPU_RUNTIME"; echo "gpu_governor=$GPU_GOV"; echo "gpu_dvfs=$GPU_DVFS"
  echo "thermal_max_c=$THERMAL_MAX"; echo "thermal_zones=$zones"; echo "battery_temp_c=$BATTERY_C"
  echo "cpu_summary=$CPU_SUMMARY"; echo "cpu_policies=$cpu_detail"; echo "cpu_available=$cpu_available"; echo "devfreq=$devs"
  echo "fps=$FPS"; echo "profile_request_state=$PROFILE_REQUEST_STATE"; echo "profile_request_profile=$PROFILE_REQUEST_PROFILE"
  echo "mem_available_mb=$MEM_MB"; echo "loadavg=$LOADAVG"; echo "file_logging=$FILE_LOGGING"; echo "file_path=$FILE_PATH"
 } > "$tmp" && mv "$tmp" "$SNAPSHOT"
 chmod 0644 "$SNAPSHOT" 2>/dev/null

 if [ -d "$PUBLIC_DIR" ]; then cp "$SNAPSHOT" "$PUBLIC_SNAPSHOT.tmp" 2>/dev/null&&mv "$PUBLIC_SNAPSHOT.tmp" "$PUBLIC_SNAPSHOT"; chmod 0644 "$PUBLIC_SNAPSHOT" 2>/dev/null; fi

 echo "$(date '+%H:%M:%S') | $MODE | $PROFILE | FPS $FPS | GPU ${GPU_MHZ}MHz | TEMP ${THERMAL_MAX}C | CPU $CPU_SUMMARY | RAM ${MEM_MB}MB | $PACKAGE" >> "$HISTORY"
 lines="$(wc -l < "$HISTORY" 2>/dev/null)"; [ "${lines:-0}" -gt 600 ]&&tail -n 300 "$HISTORY" > "$HISTORY.tmp"&&mv "$HISTORY.tmp" "$HISTORY"

 if [ "$FILE_LOGGING" = 1 ]; then
  path="$FILE_PATH"
  if [ -z "$path" ]; then
   mkdir -p "$LOG_ROOT" 2>/dev/null
   path="$LOG_ROOT/kb1001-$(date '+%Y%m%d-%H%M%S').csv"
   { grep -v '^file_path=' "$LOGGER_CONF" 2>/dev/null; echo "file_path=$path"; } > "$LOGGER_CONF.tmp.$$"&&mv "$LOGGER_CONF.tmp.$$" "$LOGGER_CONF"
   echo "timestamp,mode,package,profile,fps,gpu_mhz,gpu_voltage,thermal_max_c,battery_c,cpu,mem_available_mb,loadavg" > "$path"
  fi
  safe_cpu="$(printf '%s' "$CPU_SUMMARY"|tr ',' ';')"
  echo "$(date '+%Y-%m-%d %H:%M:%S'),$MODE,$PACKAGE,$PROFILE,$FPS,$GPU_MHZ,$GPU_VOLTAGE,$THERMAL_MAX,$BATTERY_C,$safe_cpu,$MEM_MB,$LOADAVG" >> "$path" 2>/dev/null
 fi
}

run(){
 old="$(cat "$LOGGER_PID" 2>/dev/null)"; [ -n "$old" ]&&kill -0 "$old" 2>/dev/null&&exit 0
 echo $$ > "$LOGGER_PID"; trap 'rm -f "$LOGGER_PID"; exit 0' INT TERM EXIT
 log "Performance telemetry daemon started (pid=$$)."
 while true; do sample; i="$(conf_get interval_seconds 1)"; case "$i" in 1|2|3|4|5|6|7|8|9|10) ;; *) i=1;; esac; sleep "$i"; done
}
case "$1" in --daemon|daemon) run;; --once|once) sample;; *) exit 1;; esac
