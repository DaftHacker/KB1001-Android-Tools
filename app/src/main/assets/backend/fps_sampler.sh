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

clean_sf_layer(){
 printf '%s' "$1" | sed   -e 's/^RequestedLayerState{//'   -e 's/ parentId=.*$//'   -e 's/ relativeParentId=.*$//'   -e 's/ z=.*$//'   -e 's/}$//'
}

discover_layer(){
 target="$1"
 [ -n "$target" ] || return

 layers="$(dumpsys SurfaceFlinger --list 2>/dev/null)"

 line="$(printf '%s\n' "$layers" | grep -F "$target" | grep -E 'SurfaceView.*BLAST|BLAST.*SurfaceView' | head -1)"
 [ -n "$line" ] || line="$(printf '%s\n' "$layers" | grep -F "$target" | grep -E 'SurfaceView|BLAST' | head -1)"
 [ -n "$line" ] || line="$(printf '%s\n' "$layers" | grep -F "$target" | head -1)"

 if [ -z "$line" ]; then
  short="${target##*.}"
  if [ "${#short}" -ge 4 ]; then
   line="$(printf '%s\n' "$layers" | grep -Fi "$short" | grep -E 'SurfaceView.*BLAST|BLAST.*SurfaceView' | head -1)"
   [ -n "$line" ] || line="$(printf '%s\n' "$layers" | grep -Fi "$short" | grep -E 'SurfaceView|BLAST' | head -1)"
   [ -n "$line" ] || line="$(printf '%s\n' "$layers" | grep -Fi "$short" | head -1)"
  fi
 fi

 [ -n "$line" ] && clean_sf_layer "$line"
}

surface_fps(){
 layer="$1"
 previous="$2"

 dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk -v previous="$previous" '
  NR==1 { next }
  NF>=3 && $2 ~ /^[0-9]+$/ && $2>0 && $2<9000000000000000000 { t[++n]=$2 }
  END {
   if(n<2){print "-1|0";exit}

   last=t[n]

   # The history can remain unchanged between 500 ms polls. Preserve a valid
   # source and report 0 new FPS rather than treating the layer as missing.
   if(previous!="" && previous!="0" && last==previous){print "0|" last;exit}

   start=n-1
   while(start>1 && (last-t[start-1])<=1000000000) start--

   frames=n-start
   span=last-t[start]
   if(span<=0 || frames<=0){print "0|" last;exit}

   fps=int((frames*1000000000.0/span)+0.5)
   if(fps<0)fps=0
   if(fps>240)fps=240
   print fps "|" last
  }'
}

query_layer(){
 current_layer="$1"
 previous="$2"

 pair="$(surface_fps "$current_layer" "$previous")"
 fps="${pair%%|*}"
 present="${pair#*|}"

 case "$fps" in ''|*[!0-9-]*) fps=-1;; esac
 if [ "$fps" -ge 0 ] 2>/dev/null; then
  printf '%s|%s|%s\n' "$fps" "$present" "$current_layer"
  return
 fi

 noid="$(printf '%s' "$current_layer" | sed 's/#[0-9][0-9]*$//')"
 if [ "$noid" != "$current_layer" ]; then
  pair="$(surface_fps "$noid" "$previous")"
  fps="${pair%%|*}"
  present="${pair#*|}"
  case "$fps" in ''|*[!0-9-]*) fps=-1;; esac
  if [ "$fps" -ge 0 ] 2>/dev/null; then
   printf '%s|%s|%s\n' "$fps" "$present" "$noid"
   return
  fi
 fi

 printf '%s|%s|%s\n' "-1" "0" "$current_layer"
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
 last_present=0
 bad_layer_count=0
 discover_tick=0
 gfx_tick=0
 last_good_fps=-1
 hold_ticks=0

 while true; do
  read_state_package
  game_state=0
  [ -n "$pkg" ] && game_state=1
  next_pkg="$pkg"

  # When automatic game state is unavailable/stale (for example a manually
  # enabled FPS counter), rediscover the foreground package at only 0.5 Hz.
  # The fast path below samples the already-resolved layer more often without
  # increasing expensive foreground discovery work.
  if [ -z "$next_pkg" ]; then
   discover_tick=$((discover_tick+1))
   if [ "$discover_tick" -ge 8 ] || [ -z "$pkg_cached" ]; then
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
   last_present=0
   bad_layer_count=0
   last_good_fps=-1
   hold_ticks=0
  fi

  if [ -n "$pkg_cached" ] && [ -z "$layer" ]; then
   layer="$(discover_layer "$pkg_cached")"
   last_present=0
  fi

  fps=-1
  if [ -n "$layer" ]; then
   result="$(query_layer "$layer" "$last_present")"
   fps="${result%%|*}"
   rest="${result#*|}"
   last_present="${rest%%|*}"
   resolved_layer="${rest#*|}"
   [ -n "$resolved_layer" ] && layer="$resolved_layer"

   case "$fps" in ''|*[!0-9-]*) fps=-1;; esac

   if [ "$fps" -lt 0 ] 2>/dev/null; then
    bad_layer_count=$((bad_layer_count+1))
   else
    bad_layer_count=0
    if [ "$fps" -gt 0 ] 2>/dev/null; then
     last_good_fps="$fps"
     hold_ticks=0
    elif [ "$last_good_fps" -ge 0 ] 2>/dev/null && [ "$hold_ticks" -lt 6 ]; then
     # Avoid flashing 0 between normal frame-history refreshes.
     fps="$last_good_fps"
     hold_ticks=$((hold_ticks+1))
    fi
   fi

   if [ "$bad_layer_count" -ge 2 ]; then
    layer=""
    last_present=0
    bad_layer_count=0
   fi
  fi

  # If layer latency is unavailable, use gfxinfo only once per second.
  # This keeps normal SurfaceFlinger sampling cheap while making the counter
  # useful on launcher/system apps that do not expose usable layer latency.
  if [ "$fps" -lt 0 ] 2>/dev/null; then
   gfx_tick=$((gfx_tick+1))
   if [ "$gfx_tick" -ge 4 ]; then
    gfx_fps=-1
    if [ -n "$pkg_cached" ]; then
     gfx_fps="$(gfxinfo_fps "$pkg_cached")"
     case "$gfx_fps" in ''|*[!0-9-]*) gfx_fps=-1;; esac
    fi

    if [ "$gfx_fps" -ge 0 ] 2>/dev/null; then
     fps="$gfx_fps"
    else
     timeline_fps="$(target_frametimeline_fps "$pkg_cached")"
     case "$timeline_fps" in ''|*[!0-9-]*) timeline_fps=-1;; esac

     if [ "$timeline_fps" -ge 0 ] 2>/dev/null; then
      fps="$timeline_fps"
     elif [ "$game_state" != 1 ]; then
      # For launcher/System UI, display-wide FrameTimeline FPS is still useful.
      # Never substitute display refresh for an attributed game's FPS.
      timeline_fps="$(frametimeline_fps "")"
      case "$timeline_fps" in ''|*[!0-9-]*) timeline_fps=-1;; esac
      [ "$timeline_fps" -ge 0 ] 2>/dev/null && fps="$timeline_fps"
     fi
    fi

    [ "$fps" -gt 0 ] 2>/dev/null && last_good_fps="$fps"
    gfx_tick=0
   elif [ "$last_good_fps" -ge 0 ] 2>/dev/null; then
    fps="$last_good_fps"
   fi
  else
   gfx_tick=0
  fi

  printf '%s\n' "$fps" || exit 0
  sleep 0.25
 done
}

case "$1" in
 stream) stream ;;
 *) exit 2 ;;
esac
