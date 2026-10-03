#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

AUTO_CONF="$STATE_DIR/auto_boost.conf"

echo "========================================"
echo " KB1001 Performance Manager v1.2"
echo "========================================"
echo

if ! wait_for_sysfs; then
    echo "GPU controls are not available."
    exit 1
fi

auto="$(grep -m1 '^enabled=' "$AUTO_CONF" 2>/dev/null | cut -d= -f2-)"
if [ "$auto" = "1" ]; then
    echo "Auto Game Boost is ENABLED."
    echo "The Action button keeps its manual profile cycle, but AutoBoost may"
    echo "change the profile again when foreground-app state changes."
    echo
fi

if [ -f "$SESSION_EXTREME" ]; then
    current="extreme792"
else
    current="$(cat "$CONFIG" 2>/dev/null)"
    [ -n "$current" ] || current="dynamic744"
fi

case "$current" in
    dynamic744) target="performance744"; persistent="performance744"; label="Performance 744 (pinned)" ;;
    performance744) target="extreme792"; persistent="dynamic744"; label="Extreme 792 TEST (session only)" ;;
    extreme792) target="stock"; persistent="stock"; label="Stock 696" ;;
    stock|*) target="dynamic744"; persistent="dynamic744"; label="Dynamic 744" ;;
esac

echo "Current profile: $current"
echo "Next profile:    $label"
echo
if [ "$target" = "extreme792" ]; then
    echo "WARNING: Extreme 792 is manual/session-only and never used by AutoBoost."
    echo "Reboot fallback is Dynamic 744. Thermal protection remains enabled."
    echo
fi

echo "$persistent" > "$CONFIG"
apply_profile "$target" 120
rc=$?

echo
[ $rc -eq 0 ] && echo "Applied: $label" || echo "Profile application failed (rc=$rc)."
echo
show_status
echo
echo "Persistent boot profile: $(cat "$CONFIG" 2>/dev/null)"
[ -f "$SESSION_EXTREME" ] && echo "Session override: Extreme 792 ACTIVE"
echo "Controller: su -c '$MODDIR/kb1001ctl status'"
echo "Log: $LOG"
exit $rc
