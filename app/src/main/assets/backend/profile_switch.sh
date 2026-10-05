#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

REQ="$STATE_DIR/manual_profile.request"
STATE="$STATE_DIR/manual_profile.state"
PID="/data/local/tmp/kb1001_manual_profile.pid"

valid_profile(){
 case "$1" in
  stock|performance696|dynamic744|performance744|extreme792_dynamic|extreme792_full) return 0 ;;
  custom_200|custom_300|custom_400|custom_600|custom_696|custom_744|custom_792) return 0 ;;
  *) return 1 ;;
 esac
}

worker(){
 old="$(cat "$PID" 2>/dev/null)"
 [ -n "$old" ] && kill -0 "$old" 2>/dev/null && exit 0

 echo $$ > "$PID"
 trap 'rm -f "$PID"; exit 0' INT TERM EXIT

 last_seq=""
 idle_loops=0

 while true; do
  line="$(cat "$REQ" 2>/dev/null)"
  seq="$(printf '%s' "$line" | cut -d'|' -f1)"
  mode="$(printf '%s' "$line" | cut -d'|' -f2)"
  profile="$(printf '%s' "$line" | cut -d'|' -f3)"

  if [ -z "$seq" ] || [ "$seq" = "$last_seq" ]; then
   idle_loops=$((idle_loops+1))
   [ "$idle_loops" -ge 40 ] && exit 0
   sleep 0.25
   continue
  fi

  idle_loops=0
  valid_profile "$profile" || {
   {
    echo "state=error"
    echo "seq=$seq"
    echo "profile=$profile"
    echo "message=invalid profile"
   } > "$STATE"
   last_seq="$seq"
   continue
  }

  # Give rapid UI taps a tiny debounce window, then re-read the request.
  sleep 0.15
  latest_line="$(cat "$REQ" 2>/dev/null)"
  latest_seq="$(printf '%s' "$latest_line" | cut -d'|' -f1)"
  [ "$latest_seq" = "$seq" ] || continue

  {
   echo "state=applying"
   echo "seq=$seq"
   echo "profile=$profile"
  } > "$STATE"

  # Timeout 0 means never sit inside a long suspend wait. If a rebuild is
  # needed, return "waiting" and retry only while this is still the latest request.
  apply_profile "$profile" 0
  rc=$?

  newest="$(cut -d'|' -f1 "$REQ" 2>/dev/null)"
  [ "$newest" = "$seq" ] || continue

  if [ $rc -eq 0 ]; then
   case "$mode" in
    persist)
     case "$profile" in
      stock|performance696) echo "$profile" > "$CONFIG" ;;
     esac
     ;;
   esac
   {
    echo "state=applied"
    echo "seq=$seq"
    echo "profile=$profile"
   } > "$STATE"
   last_seq="$seq"
  elif [ $rc -eq 2 ]; then
   {
    echo "state=waiting"
    echo "seq=$seq"
    echo "profile=$profile"
    echo "message=waiting for safe GPU idle window"
   } > "$STATE"
   sleep 0.25
  else
   {
    echo "state=error"
    echo "seq=$seq"
    echo "profile=$profile"
    echo "message=apply failed rc=$rc"
   } > "$STATE"
   last_seq="$seq"
  fi
 done
}

request(){
 mode="$1"
 profile="$2"
 valid_profile "$profile" || exit 2
 case "$mode" in persist|apply) ;; *) exit 2 ;; esac

 seq="$(date +%s%N 2>/dev/null)"
 case "$seq" in ''|*N*) seq="$(date +%s)-$$" ;; esac

 echo "$seq|$mode|$profile" > "$REQ"

 old="$(cat "$PID" 2>/dev/null)"
 if [ -z "$old" ] || ! kill -0 "$old" 2>/dev/null; then
  rm -f "$PID"
  nohup sh "$0" --worker >/dev/null 2>&1 &
 fi

 echo "requested=$profile"
}

case "$1" in
 request) request "$2" "$3" ;;
 status) cat "$STATE" 2>/dev/null ;;
 --worker|worker) worker ;;
 *) echo "profile_switch.sh request persist|apply PROFILE | status"; exit 2 ;;
esac
