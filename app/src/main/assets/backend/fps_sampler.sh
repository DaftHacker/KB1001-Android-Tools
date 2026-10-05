#!/system/bin/sh

AUTO_STATE="/data/local/tmp/kb1001_game_boost.state"

read_state_package(){
 pkg=""
 mode=""
 [ -r "$AUTO_STATE" ] || return
 while IFS='=' read -r key value; do
  case "$key" in
   mode) mode="$value" ;;
   package) pkg="$value" ;;
  esac
 done < "$AUTO_STATE"
 [ "$mode" = game ] || pkg=""
}

foreground_package(){
 line="$(dumpsys window displays 2>/dev/null | grep -m1 -E 'mCurrentFocus=Window\{|mFocusedApp=ActivityRecord\{')"
 out="$(printf '%s\n' "$line" | sed -n 's/.* u[0-9][0-9]* \([^/ }]*\)\/.*/\1/p')"

 if [ -z "$out" ]; then
  line="$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity=ActivityRecord|mResumedActivity: ActivityRecord')"
  out="$(printf '%s\n' "$line" | sed -n 's/.* u[0-9][0-9]* \([^/ }]*\)\/.*/\1/p')"
 fi

 if [ -z "$out" ]; then
  line="$(dumpsys activity top 2>/dev/null | grep -m1 '^  *ACTIVITY ')"
  out="$(printf '%s\n' "$line" | sed -n 's/^  *ACTIVITY \([^/ }]*\)\/.*/\1/p')"
 fi

 printf '%s\n' "$out"
}


monotonic_ms(){
 awk '{printf "%.0f\n", $1*1000.0}' /proc/uptime 2>/dev/null
}

clean_sf_layer(){
 raw="$(printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
 case "$raw" in
  RequestedLayerState\{*)
   printf '%s' "$raw" | sed     -e 's/^RequestedLayerState{//'     -e 's/ parentId=.*$//'     -e 's/ relativeParentId=.*$//'     -e 's/ z=.*$//'     -e 's/}$//'
   ;;
  Layer\ \[*)
   printf '%s' "$raw" | sed 's/^Layer \[[^]]*\][[:space:]]*//'
   ;;
  *)
   printf '%s' "$raw"
   ;;
 esac
}


frametimeline_fps(){
 target="$1"
 dumpsys SurfaceFlinger --frametimeline -all 2>/dev/null | awk -v target="$target" '
  function trim(s){gsub(/^[ 	]+|[ 	]+$/,"",s);return s}
  function finish_frame(){
   if(!in_frame || present=="")return
   if(target=="" || matched){
    t[++n]=present+0
   }
  }

  /^Display Frame [0-9]+/ {
   finish_frame()
   in_frame=1
   present=""
   matched=0
   candidate=0
   next
  }

  in_frame && present=="" && /^[ 	]*Actual[ 	]*\|/ {
   parts=split($0,a,"|")
   if(parts>=4){
    v=trim(a[parts])
    if(v ~ /^[0-9]+([.][0-9]+)?$/)present=v
   }
   next
  }

  in_frame && /Layer - / {
   candidate=(target=="" || index(tolower($0),tolower(target))>0)
   next
  }

  in_frame && candidate && /Present State : Presented/ {
   matched=1
   candidate=0
   next
  }

  END {
   finish_frame()
   if(n<2){print -1;exit}

   span=t[n]-t[1]
   if(span<=0){print 0;exit}

   fps=int((((n-1)*1000.0)/span)+0.5)
   if(fps<0)fps=0
   if(fps>240)fps=240
   print fps
  }'
}

target_frametimeline_fps(){
 target="$1"
 [ -n "$target" ] || { frametimeline_fps ""; return; }

 fps="$(frametimeline_fps "$target")"
 case "$fps" in ''|*[!0-9-]*) fps=-1;; esac

 if [ "$fps" -lt 0 ] 2>/dev/null; then
  short="${target##*.}"
  if [ "${#short}" -ge 4 ]; then
   fps="$(frametimeline_fps "$short")"
   case "$fps" in ''|*[!0-9-]*) fps=-1;; esac
  fi
 fi

 echo "$fps"
}

gfxinfo_fps(){
 target="$1"
 [ -n "$target" ] || { echo -1; return; }

 fps="$(dumpsys gfxinfo "$target" framestats 2>/dev/null | awk -F, '
  /^Flags,IntendedVsync/ {
   completed=0
   for(i=1;i<=NF;i++) if($i=="FrameCompleted") completed=i
   next
  }
  completed>0 && $completed ~ /^[0-9]+$/ && $completed>0 { t[++n]=$completed }
  END {
   if(n<2){print -1;exit}
   last=t[n]
   start=n-1
   while(start>1 && (last-t[start-1])<=1000000000) start--
   frames=n-start
   span=last-t[start]
   if(span<=0 || frames<=0){print 0;exit}
   fps=int((frames*1000000000.0/span)+0.5)
   if(fps<0)fps=0
   if(fps>240)fps=240
   print fps
  }')"

 case "$fps" in ''|*[!0-9-]*) fps=-1;; esac
 echo "$fps"
}

timestats_layer(){
 target="$1"
 [ -n "$target" ] || { echo "-1|-1|"; return; }

 # Allwinner/A523 leaves packageName blank for these layers, so accept either
 # an explicit packageName match or the package embedded in layerName.
 # Prefer the actual SurfaceView/BLAST/BBQ render layer.
 dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | awk -v target="$target" '
  function trim(s){sub(/^[ \t]+/,"",s);sub(/[ \t]+$/,"",s);return s}
  function belongs(){
   return pkg==target || index(tolower(layer),tolower(target))>0
  }
  function finish(){
   if(!belongs() || frames<0)return
   p=(layer ~ /SurfaceView|BLAST|BBQ/) ? 2 : 1
   if(p>bestp || (p==bestp && frames>bestframes)){
    bestp=p
    bestframes=frames
    bestavg=avg
    bestlayer=layer
   }
  }
  /^layerName[ \t]*=/ {
   finish()
   layer=$0
   sub(/^[^=]*=[ \t]*/,"",layer)
   layer=trim(layer)
   pkg=""
   frames=-1
   avg=-1
   next
  }
  /^packageName[ \t]*=/ {
   pkg=$0
   sub(/^[^=]*=[ \t]*/,"",pkg)
   pkg=trim(pkg)
   next
  }
  /^totalFrames[ \t]*=/ {
   v=$0
   sub(/^[^=]*=[ \t]*/,"",v)
   if(v ~ /^[0-9]+$/)frames=v+0
   next
  }
  /^averageFPS[ \t]*=/ {
   v=$0
   sub(/^[^=]*=[ \t]*/,"",v)
   if(v ~ /^[0-9]+([.][0-9]+)?$/)avg=v+0
   next
  }
  END {
   finish()
   if(bestframes==""){print "-1|-1|";exit}
   printf "%d|%.3f|%s\n",bestframes,bestavg,bestlayer
  }'
}

timestats_reset(){
 dumpsys SurfaceFlinger --timestats -clear -enable >/dev/null 2>&1
}

timestats_diagnose(){
 target="$1"
 echo "--- TimeStats parsed sample ---"
 timestats_layer "$target"
 echo "--- TimeStats raw package context ---"
 dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | awk -v target="$target" '
  BEGIN{IGNORECASE=1}
  {
   lines[NR]=$0
   if(index(tolower($0),tolower(target))>0){
    start=NR-8
    if(start<1)start=1
    stop=NR+20
    for(i=start;i<=stop;i++)wanted[i]=1
   }
  }
  END{
   for(i=1;i<=NR;i++)if(wanted[i])print lines[i]
  }'
 echo "--- TimeStats raw head ---"
 dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | head -n 80
}

candidate_layers(){
 target="$1"
 [ -n "$target" ] || return

 layers="$(dumpsys SurfaceFlinger --list 2>/dev/null)"
 short="${target##*.}"

 # Preserve the exact SurfaceFlinger layer string. --latency takes a layer
 # name, and normalizing RequestedLayerState/Layer wrappers can make a valid
 # Android 15 layer impossible to query.
 printf '%s\n' "$layers" | awk -v target="$target" -v short="$short" '
  function lower(s){return tolower(s)}
  {
   raw=$0
   sub(/^[ \t]+/,"",raw)
   sub(/[ \t]+$/,"",raw)
   if(raw=="")next

   l=lower(raw)
   t=lower(target)
   s=lower(short)
   if(index(l,t)==0 && (length(s)<4 || index(l,s)==0))next

   if(l ~ /activityrecord|inputsink|background for|bounds for|dim layer|snapshot|transition|leash|task=/)next
   if(!seen[raw]++)print raw
  }'
}

layer_cycle_fps(){
 layer="$1"
 last_known="$2"
 case "$last_known" in ''|*[!0-9]*) last_known=0;; esac

 dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk -v last_known="$last_known" '
  NR==1 { next }
  NF>=3 {
   v=0
   if($2 ~ /^[0-9]+$/ && $2>0 && $2<9223372036854775807)v=$2
   else if($3 ~ /^[0-9]+$/ && $3>0 && $3<9223372036854775807)v=$3
   if(v<=0)next

   if(v>last_known){
    if(prev>0 && v>prev){
     dt=v-prev
     if(dt>0 && dt<=1000000000){
      total+=dt
      frames++
     }
    }
    if(v>newest)newest=v
   }

   # Keep the immediately preceding historical frame so the first genuinely
   # new frame in this cycle has a valid edge delta.
   prev=v
  }
  END {
   if(newest<last_known)newest=last_known

   if(frames>0 && total>0){
    avg_dt=total/frames
    fps=int((1000000000.0/avg_dt)+0.5)
    if(fps<1)fps=1
    if(fps>240)fps=240
    print fps "|" newest "|" frames
   }else{
    print "-1|" newest "|0"
   }
  }'
}

stream(){
 trap 'rm -f "$cycle_state" 2>/dev/null; exit 0' HUP INT TERM PIPE

 poll_ms="$1"
 case "$poll_ms" in ''|*[!0-9]*) poll_ms=250;; esac
 [ "$poll_ms" -lt 100 ] 2>/dev/null && poll_ms=100
 [ "$poll_ms" -gt 1000 ] 2>/dev/null && poll_ms=1000

 foreground_every=$(((1000 + poll_ms - 1)/poll_ms))
 candidate_every=$(((1500 + poll_ms - 1)/poll_ms))
 fallback_every=$(((1500 + poll_ms - 1)/poll_ms))
 [ "$foreground_every" -lt 1 ] && foreground_every=1
 [ "$candidate_every" -lt 1 ] && candidate_every=1
 [ "$fallback_every" -lt 1 ] && fallback_every=1

 state_file="/data/local/tmp/kb1001_fps_layers.state"
 cycle_state="/data/local/tmp/kb1001_fps_layers.$"
 pkg_cached=""
 candidates=""
 candidate_tick="$candidate_every"
 foreground_tick="$foreground_every"
 fallback_tick="$fallback_every"
 last_good_fps=-1
 miss_streak=0
 ts_miss_streak=0
 ts_last_frames=-1
 ts_last_ms=0
 ts_last_layer=""
 timestats_reset

 while true; do
  cycle_start_ms="$(monotonic_ms)"
  candidate_count=0
  read_state_package
  next_pkg="$pkg"

  # The game daemon is a useful hint, but FPS follows the actual foreground
  # application even when the app is not registered in the game library.
  foreground_tick=$((foreground_tick+1))
  if [ -z "$next_pkg" ] || [ "$foreground_tick" -ge "$foreground_every" ]; then
   candidate_pkg="$(foreground_package)"
   [ -n "$candidate_pkg" ] && next_pkg="$candidate_pkg"
   foreground_tick=0
  fi

  if [ "$next_pkg" != "$pkg_cached" ]; then
   pkg_cached="$next_pkg"
   candidates=""
   candidate_tick="$candidate_every"
   fallback_tick="$fallback_every"
   last_good_fps=-1
   miss_streak=0
   ts_last_frames=-1
   ts_last_ms=0
   ts_last_layer=""
   ts_miss_streak=0
   timestats_reset
   : > "$state_file"
  fi

  fps=-1
  sample_kind="unresolved"
  live_layers=0
  best_frames=0
  best_priority=-1
  best_fps=-1
  best_layer=""

  if [ -n "$pkg_cached" ]; then
   # Modern primary path: TimeStats gives package/layer presented-frame
   # counters without relying on legacy --latency history.
   ts_now_ms="$(monotonic_ms)"
   ts_sample="$(timestats_layer "$pkg_cached")"
   ts_frames="${ts_sample%%|*}"
   ts_rest="${ts_sample#*|}"
   ts_avg="${ts_rest%%|*}"
   ts_layer="${ts_rest#*|}"
   case "$ts_frames" in ''|*[!0-9-]*) ts_frames=-1;; esac

   if [ "$ts_frames" -ge 0 ] 2>/dev/null; then
    candidate_count=1

    # The A523 SurfaceFlinger publishes averageFPS for the selected BLAST
    # presentation layer. That is already a compositor-derived FPS value, so
    # display it directly instead of estimating FPS from our polling interval.
    ts_fps="$(awk -v v="$ts_avg" 'BEGIN{
     if(v ~ /^[0-9]+([.][0-9]+)?$/ && v>0){
      n=int(v+0.5); if(n>240)n=240; if(n<1)n=1; print n
     }else print -1
    }')"
    if [ "$ts_fps" -gt 0 ] 2>/dev/null; then
     fps="$ts_fps"
     best_fps="$ts_fps"
     best_layer="$ts_layer"
     sample_kind="live"
    fi

    # Frame deltas remain diagnostic metadata only. TimeStats is published in
    # batches on this vendor build, making poll-to-poll delta FPS misleading.
    if [ "$ts_last_frames" -ge 0 ] 2>/dev/null &&
       [ "$ts_frames" -ge "$ts_last_frames" ] 2>/dev/null &&
       [ "$ts_layer" = "$ts_last_layer" ]; then
     df=$((ts_frames-ts_last_frames))
     [ "$df" -gt 0 ] 2>/dev/null && best_frames="$df"
    fi
    ts_last_frames="$ts_frames"
    ts_last_ms="$ts_now_ms"
    ts_last_layer="$ts_layer"
    ts_miss_streak=0
   else
    # A layer can be absent for the first few hundred milliseconds after an
    # Activity/SurfaceView appears. Do not permanently disable TimeStats.
    ts_miss_streak=$((ts_miss_streak+1))
    ts_last_frames=-1
    ts_last_ms=0
    ts_last_layer=""
   fi

   # Legacy compatibility is only a periodic rescue path after repeated
   # TimeStats misses. TimeStats is retried every cycle so a late BLAST layer
   # is adopted as soon as SurfaceFlinger starts reporting it.
   if [ "$fps" -le 0 ] 2>/dev/null &&
      [ "$ts_miss_streak" -ge 3 ] 2>/dev/null &&
      [ $((ts_miss_streak%4)) -eq 0 ] 2>/dev/null; then
    candidate_tick=$((candidate_tick+1))
    if [ "$candidate_tick" -ge "$candidate_every" ] || [ -z "$candidates" ]; then
     candidates="$(candidate_layers "$pkg_cached")"
     candidate_tick=0
    fi

    # Prefer render surfaces in compatibility mode and cap probes to two.
    preferred="$(printf '%s\n' "$candidates" | grep -Ei 'SurfaceView|BLAST|BBQ' | head -n 2)"
    [ -n "$preferred" ] || preferred="$(printf '%s\n' "$candidates" | head -n 2)"
    candidates="$preferred"
    candidate_count="$(printf '%s\n' "$candidates" | awk 'NF{n++}END{print n+0}')"
    : > "$cycle_state"

    while IFS= read -r candidate; do
     [ -n "$candidate" ] || continue
     last_known="$(awk -F '\t' -v layer="$candidate" '$1==layer{print $2;exit}' "$state_file" 2>/dev/null)"
     case "$last_known" in ''|*[!0-9]*) last_known=0;; esac

     sample="$(layer_cycle_fps "$candidate" "$last_known")"
     layer_fps="${sample%%|*}"
     rest="${sample#*|}"
     newest="${rest%%|*}"
     new_frames="${rest#*|}"
     case "$layer_fps" in ''|*[!0-9-]*) layer_fps=-1;; esac
     case "$newest" in ''|*[!0-9]*) newest="$last_known";; esac
     case "$new_frames" in ''|*[!0-9]*) new_frames=0;; esac
     printf '%s\t%s\n' "$candidate" "$newest" >> "$cycle_state"

     [ "$layer_fps" -gt 0 ] 2>/dev/null || continue
     [ "$new_frames" -gt 0 ] 2>/dev/null || continue
     priority=1
     printf '%s' "$candidate" | grep -Eqi 'SurfaceView|BLAST|BBQ' && priority=2
     if [ "$priority" -gt "$best_priority" ] 2>/dev/null ||
        { [ "$priority" -eq "$best_priority" ] 2>/dev/null && [ "$new_frames" -gt "$best_frames" ] 2>/dev/null; } ||
        { [ "$priority" -eq "$best_priority" ] 2>/dev/null && [ "$new_frames" -eq "$best_frames" ] 2>/dev/null && [ "$layer_fps" -gt "$best_fps" ] 2>/dev/null; }; then
      best_priority="$priority"
      best_frames="$new_frames"
      best_fps="$layer_fps"
      best_layer="$candidate"
      fps="$layer_fps"
      sample_kind="live"
     fi
    done <<EOF
$candidates
EOF
    mv "$cycle_state" "$state_file" 2>/dev/null || cp "$cycle_state" "$state_file" 2>/dev/null
   fi

   if [ "$best_fps" -gt 0 ] 2>/dev/null; then
    fps="$best_fps"
    sample_kind="live"
   fi
  fi

  if [ "$fps" -gt 0 ] 2>/dev/null; then
   last_good_fps="$fps"
   miss_streak=0
   fallback_tick=0
  else
   miss_streak=$((miss_streak+1))
   fallback_tick=$((fallback_tick+1))

   # Fallbacks remain attributed to the foreground package. Do not let a
   # display-wide/system animation override a game that merely had one missed
   # SurfaceFlinger cycle.
   if [ "$fallback_tick" -ge "$fallback_every" ]; then
    fallback=-1
    if [ -n "$pkg_cached" ]; then
     fallback="$(gfxinfo_fps "$pkg_cached")"
     case "$fallback" in ''|*[!0-9-]*) fallback=-1;; esac
    fi

    if [ "$fallback" -le 0 ] 2>/dev/null; then
     fallback="$(target_frametimeline_fps "$pkg_cached")"
     case "$fallback" in ''|*[!0-9-]*) fallback=-1;; esac
    fi

    if [ "$fallback" -gt 0 ] 2>/dev/null; then
     fps="$fallback"
     last_good_fps="$fallback"
     miss_streak=0
     sample_kind="fallback"
    fi
    fallback_tick=0
   fi
  fi

  if [ "$fps" -le 0 ] 2>/dev/null; then
   # An absent or unchanged TimeStats counter is not proof of a stalled
   # game: SurfaceFlinger may expose its data late or intermittently.
   # Never manufacture a zero from an unsuccessful measurement.
   if [ "$last_good_fps" -gt 0 ] 2>/dev/null; then
    fps="$last_good_fps"
    sample_kind="hold"
   else
    fps=-1
    sample_kind="unresolved"
   fi
  fi

  cycle_end_ms="$(monotonic_ms)"
  cycle_ms=$((cycle_end_ms-cycle_start_ms))
  [ "$cycle_ms" -lt 0 ] 2>/dev/null && cycle_ms=0

  # Stream contract:
  # fps|package|kind|selected_layer|new_frames|candidate_count|cycle_ms
  # candidate_count and cycle_ms make resolver failures and blocking dumpsys
  # work directly observable in validation logs.
  printf '%s|%s|%s|%s|%s|%s|%s\n' \
    "$fps" "$pkg_cached" "$sample_kind" "$best_layer" "$best_frames" \
    "$candidate_count" "$cycle_ms" || exit 0
  sleep_sec="$(awk -v ms="$poll_ms" 'BEGIN{printf "%.3f",ms/1000.0}')"
  sleep "$sleep_sec"
 done
}

diagnose(){
 pkg="$(foreground_package)"
 echo "foreground=${pkg:-<none>}"

 timestats_diagnose "$pkg"
 echo "--- raw SurfaceFlinger matches ---"
 dumpsys SurfaceFlinger --list 2>/dev/null | grep -Fi "${pkg##*.}" || true
 echo "--- candidates ---"

 candidates="$(candidate_layers "$pkg")"
 if [ -z "$candidates" ]; then
  echo "candidates=0"
  return
 fi

 count="$(printf '%s\n' "$candidates" | grep -c .)"
 echo "candidates=$count"

 state_file="/data/local/tmp/kb1001_fps_layers.state"
 while IFS= read -r candidate; do
  [ -n "$candidate" ] || continue
  last_known="$(awk -F '\t' -v layer="$candidate" '$1==layer{print $2;exit}' "$state_file" 2>/dev/null)"
  case "$last_known" in ''|*[!0-9]*) last_known=0;; esac
  sample="$(layer_cycle_fps "$candidate" "$last_known")"
  echo "layer=$candidate"
  echo "  sample=$sample last_known=$last_known"
  echo "  latency_head:"
  dumpsys SurfaceFlinger --latency "$candidate" 2>/dev/null | head -n 6 | sed 's/^/    /'
 done <<EOF
$candidates
EOF
}

validator_snapshot(){
 out="$1"
 [ -n "$out" ] || out="/data/local/tmp/kb1001_fps_validator_snapshot.txt"
 {
  echo "boottime_ms=$(monotonic_ms)"
  echo "date=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)"
  echo "backend_version=$(cat /data/local/kb1001perf/backend.version 2>/dev/null)"
  if command -v sha256sum >/dev/null 2>&1; then
   echo "fps_sampler_sha256=$(sha256sum "$0" 2>/dev/null | awk '{print $1}')"
  fi
  diagnose
 } > "$out" 2>&1
 echo "$out"
}

case "$1" in
 stream) stream "$2" ;;
 diagnose) diagnose ;;
 validator-snapshot) validator_snapshot "$2" ;;
 *) exit 2 ;;
esac
