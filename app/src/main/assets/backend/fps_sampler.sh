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

candidate_layers(){
 target="$1"
 [ -n "$target" ] || return

 layers="$(dumpsys SurfaceFlinger --list 2>/dev/null)"
 matches="$(printf '%s\n' "$layers" |
  grep -F "$target" |
  grep -Ev 'ActivityRecord|InputSink|Background for|Bounds for|Dim layer|Snapshot|Transition|leash|Task=')"

 if [ -z "$matches" ]; then
  short="${target##*.}"
  if [ "${#short}" -ge 4 ]; then
   matches="$(printf '%s\n' "$layers" |
    grep -Fi "$short" |
    grep -Ev 'ActivityRecord|InputSink|Background for|Bounds for|Dim layer|Snapshot|Transition|leash|Task=')"
  fi
 fi

 while IFS= read -r raw; do
  [ -n "$raw" ] || continue
  clean_sf_layer "$raw"
  printf '\n'
 done <<EOF | awk 'NF && !seen[$0]++'
$matches
EOF
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

 state_file="/data/local/tmp/kb1001_fps_layers.state"
 cycle_state="/data/local/tmp/kb1001_fps_layers.$$"
 pkg_cached=""
 candidates=""
 candidate_tick=4
 foreground_tick=4
 fallback_tick=4
 last_good_fps=-1
 miss_streak=0

 while true; do
  read_state_package
  next_pkg="$pkg"

  # The game daemon is a useful hint, but FPS follows the actual foreground
  # application even when the app is not registered in the game library.
  foreground_tick=$((foreground_tick+1))
  if [ -z "$next_pkg" ] || [ "$foreground_tick" -ge 4 ]; then
   candidate_pkg="$(foreground_package)"
   [ -n "$candidate_pkg" ] && next_pkg="$candidate_pkg"
   foreground_tick=0
  fi

  if [ "$next_pkg" != "$pkg_cached" ]; then
   pkg_cached="$next_pkg"
   candidates=""
   candidate_tick=4
   fallback_tick=4
   last_good_fps=-1
   miss_streak=0
   : > "$state_file"
  fi

  fps=-1
  live_layers=0
  best_frames=0
  best_priority=-1
  best_fps=-1
  best_layer=""

  if [ -n "$pkg_cached" ]; then
   candidate_tick=$((candidate_tick+1))
   if [ "$candidate_tick" -ge 4 ] || [ -z "$candidates" ]; then
    candidates="$(candidate_layers "$pkg_cached")"
    candidate_tick=0
   fi

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

    live_layers=$((live_layers+1))
    priority=1
    if printf '%s' "$candidate" | grep -Eqi 'SurfaceView|BLAST|BBQ'; then
     priority=2
    fi

    # Prefer real render surfaces. Within that class, the layer that actually
    # emitted the most new frames during this sampling cycle is the best match
    # for what the foreground app is visibly presenting.
    if [ "$priority" -gt "$best_priority" ] 2>/dev/null ||
       { [ "$priority" -eq "$best_priority" ] 2>/dev/null &&
         [ "$new_frames" -gt "$best_frames" ] 2>/dev/null; } ||
       { [ "$priority" -eq "$best_priority" ] 2>/dev/null &&
         [ "$new_frames" -eq "$best_frames" ] 2>/dev/null &&
         [ "$layer_fps" -gt "$best_fps" ] 2>/dev/null; }; then
     best_priority="$priority"
     best_frames="$new_frames"
     best_fps="$layer_fps"
     best_layer="$candidate"
    fi
   done <<EOF
$candidates
EOF

   mv "$cycle_state" "$state_file" 2>/dev/null || cp "$cycle_state" "$state_file" 2>/dev/null

   if [ "$best_fps" -gt 0 ] 2>/dev/null; then
    fps="$best_fps"
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
   if [ "$fallback_tick" -ge 2 ]; then
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
    fi
    fallback_tick=0
   fi
  fi

  if [ "$fps" -le 0 ] 2>/dev/null; then
   # One or two missed cycles are resolution noise, not 0 FPS. Preserve the
   # last real value. Only a sustained lack of new foreground presents becomes
   # a visible zero.
   if [ "$last_good_fps" -gt 0 ] 2>/dev/null && [ "$miss_streak" -lt 3 ] 2>/dev/null; then
    fps="$last_good_fps"
   elif [ "$last_good_fps" -gt 0 ] 2>/dev/null && [ "$miss_streak" -ge 3 ] 2>/dev/null; then
    fps=0
   else
    fps=-1
   fi
  fi

  printf '%s|%s\n' "$fps" "$pkg_cached" || exit 0
  sleep 0.50
 done
}

diagnose(){
 pkg="$(foreground_package)"
 echo "foreground=${pkg:-<none>}"

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
 done <<EOF
$candidates
EOF
}

case "$1" in
 stream) stream ;;
 diagnose) diagnose ;;
 *) exit 2 ;;
esac
