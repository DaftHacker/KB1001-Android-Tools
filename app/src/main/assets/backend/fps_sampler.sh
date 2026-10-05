#!/system/bin/sh

AUTO_STATE="/data/local/tmp/kb1001_game_boost.state"

read_state_package(){
 pkg=""
 [ -r "$AUTO_STATE" ] || return
 while IFS='=' read -r key value; do
  case "$key" in
   package) pkg="$value" ;;
  esac
 done < "$AUTO_STATE"
}

foreground_package(){
 line="$(dumpsys window displays 2>/dev/null | grep -m1 -E 'mCurrentFocus=Window\\{|mFocusedApp=ActivityRecord\\{')"
 out="$(printf '%s\\n' "$line" | sed -n 's/.* u[0-9][0-9]* \\([^/ }]*\\)\\/.*/\\1/p')"

 if [ -z "$out" ]; then
  line="$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity=ActivityRecord|mResumedActivity: ActivityRecord')"
  out="$(printf '%s\\n' "$line" | sed -n 's/.* u[0-9][0-9]* \\([^/ }]*\\)\\/.*/\\1/p')"
 fi

 if [ -z "$out" ]; then
  line="$(dumpsys activity top 2>/dev/null | grep -m1 '^  *ACTIVITY ')"
  out="$(printf '%s\\n' "$line" | sed -n 's/^  *ACTIVITY \\([^/ }]*\\)\\/.*/\\1/p')"
 fi

 printf '%s\\n' "$out"
}

monotonic_ms(){
 awk '{printf "%.0f\\n", $1*1000.0}' /proc/uptime 2>/dev/null
}

clean_sf_layer(){
 raw="$(printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
 case "$raw" in
  RequestedLayerState\{*)
   printf '%s' "$raw" | sed \
    -e 's/^RequestedLayerState{//' \
    -e 's/ parentId=.*$//' \
    -e 's/ relativeParentId=.*$//' \
    -e 's/ z=.*$//' \
    -e 's/}$//'
   ;;
  Layer\ \[*)
   printf '%s' "$raw" | sed 's/^Layer \[[^]]*\][[:space:]]*//'
   ;;
  *)
   printf '%s' "$raw"
   ;;
 esac
}

# Keep the layer-selection behavior that previously worked well on this device:
# package-matched SurfaceView/BLAST first, then progressively broader matches.
discover_layer(){
 target="$1"
 [ -n "$target" ] || return

 layers="$(dumpsys SurfaceFlinger --list 2>/dev/null)"
 matches="$(printf '%s\\n' "$layers" | grep -F "$target" |
  grep -Ev 'ActivityRecord|InputSink|Background for|Bounds for|Dim layer|Snapshot|Transition|leash|Task=')"

 line="$(printf '%s\\n' "$matches" | grep -E 'SurfaceView.*BLAST|BLAST.*SurfaceView' | head -1)"
 [ -n "$line" ] || line="$(printf '%s\\n' "$matches" | grep -E 'SurfaceView|BLAST' | head -1)"
 [ -n "$line" ] || line="$(printf '%s\\n' "$matches" | head -1)"

 if [ -z "$line" ]; then
  short="${target##*.}"
  if [ "${#short}" -ge 4 ]; then
   line="$(printf '%s\\n' "$layers" | grep -Fi "$short" | grep -E 'SurfaceView.*BLAST|BLAST.*SurfaceView' | head -1)"
   [ -n "$line" ] || line="$(printf '%s\\n' "$layers" | grep -Fi "$short" | grep -E 'SurfaceView|BLAST' | head -1)"
   [ -n "$line" ] || line="$(printf '%s\\n' "$layers" | grep -Fi "$short" | head -1)"
  fi
 fi

 [ -n "$line" ] && clean_sf_layer "$line"
}

# AOSP FrameTracker --latency rows are:
# desiredPresentTime, actualPresentTime, frameReadyTime.
# Current FPS uses the newest two distinct ACTUAL presentation timestamps only.
surface_current(){
 layer="$1"

 dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk '
  NR==1 { next }
  NF>=3 && $2 ~ /^[0-9]+$/ && $2>0 && $2<9223372036854775807 {
   if(n==0 || $2!=t[n]) t[++n]=$2
  }
  END {
   if(n<2){print "-1|0";exit}
   last=t[n]
   prior=t[n-1]
   dt=last-prior
   if(dt<=0){print "-1|" last;exit}

   fps=int((1000000000.0/dt)+0.5)
   if(fps<0)fps=0
   if(fps>240)fps=240
   print fps "|" last
  }'
}

query_current(){
 current_layer="$1"

 pair="$(surface_current "$current_layer")"
 fps="${pair%%|*}"
 present="${pair#*|}"

 case "$fps" in ''|*[!0-9-]*) fps=-1;; esac
 if [ "$fps" -ge 0 ] 2>/dev/null; then
  printf '%s|%s|%s\\n' "$fps" "$present" "$current_layer"
  return
 fi

 noid="$(printf '%s' "$current_layer" | sed 's/#[0-9][0-9]*$//')"
 if [ "$noid" != "$current_layer" ]; then
  pair="$(surface_current "$noid")"
  fps="${pair%%|*}"
  present="${pair#*|}"
  case "$fps" in ''|*[!0-9-]*) fps=-1;; esac
  if [ "$fps" -ge 0 ] 2>/dev/null; then
   printf '%s|%s|%s\\n' "$fps" "$present" "$noid"
   return
  fi
 fi

 printf '%s|%s|%s\\n' "-1" "0" "$current_layer"
}

timestats_enable(){
 dumpsys SurfaceFlinger --timestats -clear -enable >/dev/null 2>&1
}

# Mirrors the known-good TimeStats monitors: dump+clear an accumulation window,
# filter to the foreground package, prefer SurfaceView/BLAST, then highest frame count.
timestats_average(){
 target="$1"
 [ -n "$target" ] || { echo -1; return; }

 dumpsys SurfaceFlinger --timestats -dump -clear -maxlayers 64 2>/dev/null | awk -v target="$target" '
  function rank(name){
   if(name ~ /SurfaceView/ && name ~ /BLAST/) return 3
   if(name ~ /SurfaceView/) return 2
   if(name ~ /BLAST|BBQ/) return 1
   return 0
  }
  function finish(){
   if(name=="" || index(name,target)==0 || frames<2 || fps<0)return
   r=rank(name)
   if(!found || r>bestRank || (r==bestRank && frames>bestFrames)){
    found=1
    bestRank=r
    bestFrames=frames
    bestFps=fps
   }
  }
  /^[[:space:]]*layerName[[:space:]]*=/ {
   finish()
   line=$0
   sub(/^[[:space:]]*layerName[[:space:]]*=[[:space:]]*/,"",line)
   name=line
   waiting=(name=="")
   frames=-1
   fps=-1
   next
  }
  waiting && $0 !~ /^[[:space:]]*$/ {
   name=$0
   sub(/^[[:space:]]*/,"",name)
   sub(/[[:space:]]*$/,"",name)
   waiting=0
   next
  }
  name!="" && /^[[:space:]]*totalFrames[[:space:]]*=/ {
   line=$0
   sub(/^.*=[[:space:]]*/,"",line)
   frames=line+0
   next
  }
  name!="" && /^[[:space:]]*averageFPS[[:space:]]*=/ {
   line=$0
   sub(/^.*=[[:space:]]*/,"",line)
   fps=line+0
   next
  }
  END {
   finish()
   if(!found){print -1;exit}
   out=int(bestFps+0.5)
   if(out<0)out=0
   if(out>240)out=240
   print out
  }'
}

stream(){
 trap 'dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1; exit 0' HUP INT TERM PIPE EXIT

 timestats_enable

 pkg_cached=""
 layer=""
 bad_layer_count=0
 last_discover_ms=0
 last_foreground_ms=0
 last_average_ms=0
 current=-1
 average=-1

 while true; do
  # Daemon state is only a hint. FPS must work for any foreground app,
  # whether or not it is registered as a game.
  read_state_package
  next_pkg="$pkg"

  now_ms="$(monotonic_ms)"
  case "$now_ms" in ''|*[!0-9]*) now_ms=0;; esac

  # Verify the real foreground independently at startup and about once per second.
  # This prevents stale game-daemon state from pinning the FPS sampler to an old app.
  if [ -z "$pkg_cached" ] || [ "$now_ms" -le 0 ] 2>/dev/null ||
     [ $((now_ms-last_foreground_ms)) -ge 1000 ] 2>/dev/null; then
   candidate="$(foreground_package)"
   [ -n "$candidate" ] && next_pkg="$candidate"
   last_foreground_ms="$now_ms"
  fi

  [ -n "$next_pkg" ] || next_pkg="$pkg_cached"

  if [ "$next_pkg" != "$pkg_cached" ]; then
   pkg_cached="$next_pkg"
   layer=""
   bad_layer_count=0
   current=-1
   average=-1
   timestats_enable
   last_average_ms="$now_ms"
  fi

  if [ -n "$pkg_cached" ] && [ -z "$layer" ]; then
   layer="$(discover_layer "$pkg_cached")"
  fi

  if [ -n "$layer" ]; then
   result="$(query_current "$layer")"
   measured="${result%%|*}"
   rest="${result#*|}"
   present="${rest%%|*}"
   resolved_layer="${rest#*|}"
   [ -n "$resolved_layer" ] && layer="$resolved_layer"

   case "$measured" in ''|*[!0-9-]*) measured=-1;; esac
   if [ "$measured" -ge 0 ] 2>/dev/null; then
    current="$measured"
    bad_layer_count=0
   else
    bad_layer_count=$((bad_layer_count+1))
    if [ "$bad_layer_count" -ge 2 ]; then
     layer=""
     bad_layer_count=0
    fi
   fi
  fi

  # TimeStats average follows the external reference cadence (~500 ms).
  if [ -n "$pkg_cached" ] &&
     { [ "$last_average_ms" -le 0 ] 2>/dev/null ||
       [ $((now_ms-last_average_ms)) -ge 500 ] 2>/dev/null; }; then
   avg="$(timestats_average "$pkg_cached")"
   case "$avg" in ''|*[!0-9-]*) avg=-1;; esac
   if [ "$avg" -ge 0 ] 2>/dev/null; then
    average="$avg"

    # Secondary presented-FPS path. If the exact per-layer latency query cannot
    # resolve, TimeStats still reports the package layer's real presented rate.
    [ "$current" -lt 0 ] 2>/dev/null && current="$avg"
   fi
   last_average_ms="$now_ms"
  fi

  printf '%s|%s\\n' "$current" "$average" || exit 0

  # Fixed-delay after the command work. 50 ms gives fast observation without
  # overlapping dumpsys processes or the multi-layer probing that hurt startup.
  sleep 0.05
 done
}

case "$1" in
 stream) stream ;;
 *) exit 2 ;;
esac
