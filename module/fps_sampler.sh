#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

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
 line="$(dumpsys window displays 2>/dev/null | grep -m1 -E 'mCurrentFocus=Window\\{|mFocusedApp=ActivityRecord\\{')"
 out="$(printf '%s\\n' "$line" | sed -n 's/.* u[0-9][0-9]* \\([^/ }]*\\)\\/.*/\\1/p')"
 if [ -z "$out" ]; then
  line="$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity=ActivityRecord|mResumedActivity: ActivityRecord')"
  out="$(printf '%s\\n' "$line" | sed -n 's/.* u[0-9][0-9]* \\([^/ }]*\\)\\/.*/\\1/p')"
 fi
 printf '%s\\n' "$out"
}

discover_layer(){
 target="$1"
 [ -n "$target" ] || return
 layers="$(dumpsys SurfaceFlinger --list 2>/dev/null)"
 line="$(printf '%s\\n' "$layers" | grep -F "$target" | grep -E 'SurfaceView.*BLAST|BLAST.*SurfaceView' | head -1)"
 [ -n "$line" ] || line="$(printf '%s\\n' "$layers" | grep -F "$target" | grep -E 'SurfaceView|BLAST' | head -1)"
 [ -n "$line" ] || line="$(printf '%s\\n' "$layers" | grep -F "$target" | head -1)"

 if [ -z "$line" ]; then
  short="${target##*.}"
  if [ "${#short}" -ge 4 ]; then
   line="$(printf '%s\\n' "$layers" | grep -Fi "$short" | grep -E 'SurfaceView.*BLAST|BLAST.*SurfaceView' | head -1)"
   [ -n "$line" ] || line="$(printf '%s\\n' "$layers" | grep -Fi "$short" | grep -E 'SurfaceView|BLAST' | head -1)"
  fi
 fi

 printf '%s' "$line"
}

surface_fps(){
 layer="$1"
 previous="$2"
 dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk -v previous="$previous" '
  NR==1 { next }
  NF>=3 && $2 ~ /^[0-9]+$/ && $2>0 && $2<9000000000000000000 { t[++n]=$2 }
  END {
   if(n<2){print "0|0";exit}
   last=t[n]
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

stream(){
 trap 'exit 0' HUP INT TERM PIPE

 pkg=""
 layer=""
 last_present=0
 zero_count=0
 discover_tick=0

 while true; do
  read_state_package
  next_pkg="$pkg"

  if [ -z "$next_pkg" ]; then
   discover_tick=$((discover_tick+1))
   if [ "$discover_tick" -ge 4 ] || [ -z "$pkg_cached" ]; then
    next_pkg="$(foreground_package)"
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
   zero_count=0
  fi

  if [ -n "$pkg_cached" ] && [ -z "$layer" ]; then
   layer="$(discover_layer "$pkg_cached")"
  fi

  fps=0
  if [ -n "$layer" ]; then
   pair="$(surface_fps "$layer" "$last_present")"
   fps="${pair%%|*}"
   last_present="${pair#*|}"
   case "$fps" in ''|*[!0-9]*) fps=0;; esac

   if [ "$fps" -le 0 ]; then
    zero_count=$((zero_count+1))
   else
    zero_count=0
   fi

   if [ "$zero_count" -ge 4 ]; then
    layer=""
    last_present=0
    zero_count=0
   fi
  fi

  printf '%s\\n' "$fps" || exit 0
  sleep 0.5
 done
}

case "$1" in
 stream) stream ;;
 *) exit 2 ;;
esac
