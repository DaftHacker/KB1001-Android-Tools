#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

AUTO_CONF="$STATE_DIR/auto_boost.conf"

echo "========================================"
echo " KB1001 Performance Manager v1.3"
echo "========================================"
echo

wait_for_sysfs || { echo "GPU controls are not available."; exit 1; }

if [ -f "$SESSION_EXTREME" ]; then current="experimental792"
else current="$(cat "$CONFIG" 2>/dev/null)"; [ -n "$current" ] || current=dynamic744; fi

case "$current" in
 dynamic744) target=performance744; persistent=performance744; label="Performance 744 (pinned)" ;;
 performance744) target=experimental792; persistent=dynamic744; label="Experimental 792 (session only)" ;;
 experimental792|extreme792) target=stock; persistent=stock; label="Stock 696" ;;
 *) target=dynamic744; persistent=dynamic744; label="Dynamic 744" ;;
esac

echo "Current profile: $current"
echo "Next profile:    $label"
[ "$target" = experimental792 ] && echo "792 MHz is not assumed to be the device ceiling; use telemetry/stress testing."

echo "$persistent" > "$CONFIG"
if [ "$target" = experimental792 ]; then apply_profile extreme792 120
else apply_profile "$target" 120; fi
rc=$?

echo
[ $rc -eq 0 ] && echo "Applied: $label" || echo "Profile application failed (rc=$rc)."
show_status
echo
echo "Persistent boot profile: $(cat "$CONFIG" 2>/dev/null)"
echo "Controller: su -c '$MODDIR/kb1001ctl status'"
exit $rc
