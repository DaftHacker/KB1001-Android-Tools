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


stream(){
 trap 'exit 0' HUP INT TERM PIPE

 pkg_cached=""
 layer=""
 bad_layer_count=0
 zero_fps_count=0
 discover_tick=0
 fallback_tick=0

 while true; do
  read_state_package
  next_pkg="$pkg"

  if [ -z "$next_pkg" ]; then
   # Keep expensive foreground discovery near 0.5 Hz while the validated
   # SurfaceFlinger latency path samples at ~25 Hz.
   discover_tick=$((discover_tick+1))
   if [ "$discover_tick" -ge 50 ] || [ -z "$pkg_cached" ]; then
    candidate="$(foreground_package)"
    [ -n "$candidate" ] && next_pkg="$candidate" || next_pkg="$pkg_cached"
    discover_tick=0
   else
    next_pkg="$pkg_cached"
   fi
  else
   discover_tick=0
  fi

  if [ "$next_pkg" != "$pkg_cached" ]; then
   pkg_cached="$next_pkg"
   layer=""
   bad_layer_count=0
   zero_fps_count=0
   fallback_tick=0
  fi

  if [ -n "$pkg_cached" ] && [ -z "$layer" ]; then
   layer="$(discover_layer "$pkg_cached")"
  fi

  current=-1
  avg=-1

  if [ -n "$layer" ]; then
   result="$(query_layer "$layer")"
   current="${result%%|*}"
   rest="${result#*|}"
   avg="${rest%%|*}"
   rest="${rest#*|}"
   last_present="${rest%%|*}"
   resolved_layer="${rest#*|}"
   [ -n "$resolved_layer" ] && layer="$resolved_layer"

   case "$current" in ''|*[!0-9-]*) current=-1;; esac
   case "$avg" in ''|*[!0-9-]*) avg=-1;; esac

   if [ "$current" -lt 0 ] 2>/dev/null; then
    bad_layer_count=$((bad_layer_count+1))
   else
    bad_layer_count=0
    if [ "$current" -eq 0 ] 2>/dev/null; then
     zero_fps_count=$((zero_fps_count+1))
    else
     zero_fps_count=0
    fi
   fi

   if [ "$bad_layer_count" -ge 2 ] || [ "$zero_fps_count" -ge 3 ]; then
    layer=""
    bad_layer_count=0
    zero_fps_count=0
   fi
  fi

  if [ "$current" -lt 0 ] 2>/dev/null; then
   # Keep gfxinfo/FrameTimeline fallback near 1 Hz; only the cheap, already
   # resolved per-layer latency query runs at the fast cadence.
   fallback_tick=$((fallback_tick+1))
   if [ "$fallback_tick" -ge 25 ]; then
    current="$(gfxinfo_fps "$pkg_cached")"
    case "$current" in ''|*[!0-9-]*) current=-1;; esac

    if [ "$current" -lt 0 ] 2>/dev/null; then
     current="$(target_frametimeline_fps "$pkg_cached")"
     case "$current" in ''|*[!0-9-]*) current=-1;; esac
    fi

    if [ "$current" -ge 0 ] 2>/dev/null; then
     avg="$current"
    fi
    fallback_tick=0
   fi
  else
   fallback_tick=0
  fi

  printf '%s|%s\n' "$current" "$avg" || exit 0
  sleep 0.04
 done
}

diagnose(){
 pkg="$(foreground_package)"
 echo "foreground=${pkg:-<none>}"

 layer="$(discover_layer "$pkg")"
 echo "layer=${layer:-<none>}"

 if [ -n "$layer" ]; then
  sample="$(dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | tail -n 12)"
  rows="$(printf '%s\n' "$sample" | awk 'NF>=3 && (($2 ~ /^[0-9]+$/ && $2>0) || ($3 ~ /^[0-9]+$/ && $3>0)){n++} END{print n+0}')"
  echo "latency_rows=$rows"
  printf '%s\n' "$sample"
 else
  echo "latency_rows=0"
 fi
}

case "$1" in
 stream) stream ;;
 diagnose) diagnose ;;
 *) exit 2 ;;
esac
