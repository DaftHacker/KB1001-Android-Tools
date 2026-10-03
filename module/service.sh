#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

AUTO_CONF="$STATE_DIR/auto_boost.conf"

rm -f "$SESSION_EXTREME"
log "KB1001 Performance Manager boot service started."

if ! wait_for_sysfs; then
    log "ERROR: Allwinner GPU sysfs controls did not appear."
    exit 1
fi

profile="$(cat "$CONFIG" 2>/dev/null)"
case "$profile" in
    stock|dynamic744|performance744) ;;
    *) profile="dynamic744"; echo "$profile" > "$CONFIG" ;;
esac

enabled="$(grep -m1 '^enabled=' "$AUTO_CONF" 2>/dev/null | cut -d= -f2-)"
if [ "$enabled" = "1" ]; then
    idle="$(grep -m1 '^idle_profile=' "$AUTO_CONF" 2>/dev/null | cut -d= -f2-)"
    case "$idle" in stock|dynamic744|performance744) profile="$idle" ;; esac
fi

tries=0
while [ $tries -lt 900 ]; do
    apply_profile "$profile" 2
    rc=$?
    if [ $rc -eq 0 ]; then
        if [ "$enabled" = "1" ]; then
            nohup "$MODDIR/game_boost.sh" --daemon >/dev/null 2>&1 &
            log "AutoBoost start requested from boot service."
        fi
        exit 0
    fi
    [ $rc -eq 1 ] && exit 1
    sleep 2
    tries=$((tries + 1))
done

log "Boot service timed out waiting for a safe GPU-suspend window."
exit 1
