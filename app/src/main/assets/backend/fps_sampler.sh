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


layer_last_present(){
 layer="$1"
 dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk '
  NR==1 { next }
  NF>=3 {
   v=0
   if($2 ~ /^[0-9]+$/ && $2>0 && $2<9223372036854775807)v=$2
   else if($3 ~ /^[0-9]+$/ && $3>0 && $3<9223372036854775807)v=$3
   if(v>last)last=v
  }
  END { print last+0 }'
}

discover_layer(){
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

 [ -n "$matches" ] || return

 best=""
 best_present=0
 fallback=""

 while IFS= read -r raw; do
  [ -n "$raw" ] || continue
  candidate="$(clean_sf_layer "$raw")"
  [ -n "$candidate" ] || continue

  if [ -z "$fallback" ]; then
   fallback="$candidate"
  elif printf '%s' "$candidate" | grep -Eq 'SurfaceView.*BLAST|BLAST.*SurfaceView'; then
   fallback="$candidate"
  fi

  last="$(layer_last_present "$candidate")"
  case "$last" in ''|*[!0-9]*) last=0;; esac
  if [ "$last" -gt "$best_present" ] 2>/dev/null; then
   best_present="$last"
   best="$candidate"
  fi
 done <<EOF
$matches
EOF

 [ -n "$best" ] && printf '%s\n' "$best" || printf '%s\n' "$fallback"
}


surface_fps(){
 layer="$1"

 dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk '
  NR==1 { next }
  NF>=3 {
   v=0
   if($2 ~ /^[0-9]+$/ && $2>0 && $2<9223372036854775807)v=$2
   else if($3 ~ /^[0-9]+$/ && $3>0 && $3<9223372036854775807)v=$3
   if(v>0 && (n==0 || v!=t[n]))t[++n]=v
  }
  END {
   if(n<2){print "-1|-1|0";exit}

   last=t[n]
   prior=t[n-1]
   dt=last-prior
   if(dt<=0){print "-1|-1|" last;exit}

   current=int((1000000000.0/dt)+0.5)

   cutoff=last-1000000000
   first=n
   while(first>1 && t[first-1]>=cutoff)first--
   frames=n-first
   span=last-t[first]

   if(frames>0 && span>0)avg=int((frames*1000000000.0/span)+0.5)
   else avg=current

   if(current<0)current=0
   if(current>240)current=240
   if(avg<0)avg=0
   if(avg>240)avg=240

   print current "|" avg "|" last
  }'
}


query_layer(){
 current_layer="$1"

 metrics="$(surface_fps "$current_layer")"
 current="${metrics%%|*}"
 rest="${metrics#*|}"
 avg="${rest%%|*}"
 last="${rest#*|}"

 case "$current" in ''|*[!0-9-]*) current=-1;; esac
 case "$avg" in ''|*[!0-9-]*) avg=-1;; esac

 if [ "$current" -ge 0 ] 2>/dev/null; then
  printf '%s|%s|%s|%s\n' "$current" "$avg" "$last" "$current_layer"
  return
 fi

 noid="$(printf '%s' "$current_layer" | sed 's/#[0-9][0-9]*$//')"
 if [ "$noid" != "$current_layer" ]; then
  metrics="$(surface_fps "$noid")"
  current="${metrics%%|*}"
  rest="${metrics#*|}"
  avg="${rest%%|*}"
  last="${rest#*|}"
  case "$current" in ''|*[!0-9-]*) current=-1;; esac
  case "$avg" in ''|*[!0-9-]*) avg=-1;; esac
  if [ "$current" -ge 0 ] 2>/dev/null; then
   printf '%s|%s|%s|%s\n' "$current" "$avg" "$last" "$noid"
   return
  fi
 fi

 printf '%s|%s|%s|%s\n' "-1" "-1" "0" "$current_layer"
}


layer_frame_count(){
 layer="$1"
 base="$(printf '%s' "$layer" | sed 's/#[0-9][0-9]*$//')"
 [ -n "$base" ] || { echo -1; return; }
 dumpsys SurfaceFlinger 2>/dev/null | awk -v target="$base" '
  index($0,target)>0 && $0 !~ /Background for/ && match($0,/frame=[0-9]+/) {
   s=substr($0,RSTART,RLENGTH)
   sub(/^frame=/,"",s)
   print s
   exit
  }
  END { if(NR==0) print -1 }'
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

monotonic_ns(){
 awk '{printf "%.0f\n", $1*1000000000.0}' /proc/uptime 2>/dev/null
}

best_live_fps(){
 candidates="$1"
 now_ns="$(monotonic_ns)"
 case "$now_ns" in ''|*[!0-9]*) now_ns=0;; esac

 best_fps=-1
 best_priority=-1
 best_last=0
 best_layer=""
 live_count=0

 while IFS= read -r candidate; do
  [ -n "$candidate" ] || continue

  metrics="$(surface_fps "$candidate")"
  fps="${metrics%%|*}"
  rest="${metrics#*|}"
  rest="${rest#*|}"
  last="$rest"

  case "$fps" in ''|*[!0-9-]*) fps=-1;; esac
  case "$last" in ''|*[!0-9]*) last=0;; esac

  [ "$fps" -gt 0 ] 2>/dev/null || continue
  [ "$last" -gt 0 ] 2>/dev/null || continue
  [ "$now_ns" -gt 0 ] 2>/dev/null || continue

  age_ns=$((now_ns-last))
  [ "$age_ns" -ge 0 ] 2>/dev/null || continue
  [ "$age_ns" -le 1500000000 ] 2>/dev/null || continue

  live_count=$((live_count+1))
  priority=1
  if printf '%s' "$candidate" | grep -Eqi 'SurfaceView|BLAST|BBQ'; then
   priority=2
  fi

  if [ "$priority" -gt "$best_priority" ] 2>/dev/null ||
     { [ "$priority" -eq "$best_priority" ] 2>/dev/null &&
       [ "$fps" -gt "$best_fps" ] 2>/dev/null; } ||
     { [ "$priority" -eq "$best_priority" ] 2>/dev/null &&
       [ "$fps" -eq "$best_fps" ] 2>/dev/null &&
       [ "$last" -gt "$best_last" ] 2>/dev/null; }; then
   best_priority="$priority"
   best_fps="$fps"
   best_last="$last"
   best_layer="$candidate"
  fi
 done <<EOF
$candidates
EOF

 printf '%s|%s|%s\n' "$best_fps" "$live_count" "$best_layer"
}

stream(){
 trap 'exit 0' HUP INT TERM PIPE

 pkg_cached=""
 candidates=""
 candidate_tick=4
 foreground_tick=4
 fallback_tick=4
 last_good_fps=-1
 last_live_ms=0
 had_valid=0

 while true; do
  read_state_package
  game_state=0
  [ -n "$pkg" ] && game_state=1
  next_pkg="$pkg"

  # Verify foreground state frequently enough that manual FPS mode follows
  # app switches without relying on the game daemon.
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
   last_live_ms=0
   had_valid=0
  fi

  fps=-1
  live_count=0

  if [ -n "$pkg_cached" ]; then
   candidate_tick=$((candidate_tick+1))
   if [ "$candidate_tick" -ge 4 ] || [ -z "$candidates" ]; then
    candidates="$(candidate_layers "$pkg_cached")"
    candidate_tick=0
   fi

   if [ -n "$candidates" ]; then
    live="$(best_live_fps "$candidates")"
    fps="${live%%|*}"
    rest="${live#*|}"
    live_count="${rest%%|*}"
    case "$fps" in ''|*[!0-9-]*) fps=-1;; esac
    case "$live_count" in ''|*[!0-9]*) live_count=0;; esac
   fi
  fi

  now_ms="$(monotonic_ms)"
  case "$now_ms" in ''|*[!0-9]*) now_ms=0;; esac

  if [ "$fps" -gt 0 ] 2>/dev/null; then
   last_good_fps="$fps"
   last_live_ms="$now_ms"
   had_valid=1
   fallback_tick=0
  else
   fallback_tick=$((fallback_tick+1))

   # Only run heavier attributed fallbacks when no foreground render layer
   # is actively presenting.
   if [ "$fallback_tick" -ge 4 ]; then
    fallback=-1

    if [ -n "$pkg_cached" ]; then
     fallback="$(gfxinfo_fps "$pkg_cached")"
     case "$fallback" in ''|*[!0-9-]*) fallback=-1;; esac
    fi

    if [ "$fallback" -le 0 ] 2>/dev/null; then
     fallback="$(target_frametimeline_fps "$pkg_cached")"
     case "$fallback" in ''|*[!0-9-]*) fallback=-1;; esac
    fi

    if [ "$fallback" -le 0 ] 2>/dev/null && [ "$game_state" != 1 ]; then
     fallback="$(frametimeline_fps "")"
     case "$fallback" in ''|*[!0-9-]*) fallback=-1;; esac
    fi

    if [ "$fallback" -gt 0 ] 2>/dev/null; then
     fps="$fallback"
     last_good_fps="$fps"
     last_live_ms="$now_ms"
     had_valid=1
    fi
    fallback_tick=0
   fi
  fi

  if [ "$fps" -le 0 ] 2>/dev/null; then
   # Do not invent 0 FPS before we have ever seen this foreground app render.
   # Once a valid stream existed, a sustained one-second lack of presents is
   # a real stall; briefly preserve the last value while layers transition.
   if [ "$had_valid" = 1 ] && [ "$last_live_ms" -gt 0 ] 2>/dev/null &&
      [ "$now_ms" -gt 0 ] 2>/dev/null; then
    quiet_ms=$((now_ms-last_live_ms))
    if [ "$quiet_ms" -ge 1000 ] 2>/dev/null; then
     fps=0
    elif [ "$last_good_fps" -gt 0 ] 2>/dev/null; then
     fps="$last_good_fps"
    fi
   else
    fps=-1
   fi
  fi

  printf '%s\n' "$fps" || exit 0
  sleep 0.25
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

 now_ns="$(monotonic_ns)"
 while IFS= read -r candidate; do
  [ -n "$candidate" ] || continue
  metrics="$(surface_fps "$candidate")"
  fps="${metrics%%|*}"
  rest="${metrics#*|}"
  avg="${rest%%|*}"
  last="${rest#*|}"
  age_ms=-1
  case "$last:$now_ns" in
   *[!0-9:]*|0:*|*:0) ;;
   *) age_ms=$(((now_ns-last)/1000000)) ;;
  esac
  echo "layer=$candidate"
  echo "  fps=$fps avg=$avg age_ms=$age_ms"
 done <<EOF
$candidates
EOF

 best="$(best_live_fps "$candidates")"
 echo "best=$best"
}

case "$1" in
 stream) stream ;;
 diagnose) diagnose ;;
 *) exit 2 ;;
esac
