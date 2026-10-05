#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

AUTO_CONF="$STATE_DIR/auto_boost.conf"
LOGGER_CONF="$STATE_DIR/logger.conf"

rm -f "$SESSION_EXTREME"
rm -f /data/local/tmp/kb1001_telemetry_ui /data/local/tmp/kb1001_telemetry_hud
sh "$MODDIR/cpu_control.sh" init >/dev/null 2>&1 || log "WARNING: could not capture CPU boot state."
log "KB1001 Performance Manager boot service started."

wait_for_sysfs || { log "ERROR: GPU sysfs controls did not appear."; exit 1; }

[ -f "$LOGGER_CONF" ] || cat > "$LOGGER_CONF" <<EOC
interval_seconds=1
file_logging=0
file_path=
EOC

nohup sh "$MODDIR/perf_logger.sh" --daemon >/dev/null 2>&1 &

profile="$(cat "$CONFIG" 2>/dev/null)"
case "$profile" in stock|performance696) ;; *) profile=stock; echo "$profile" > "$CONFIG";; esac

enabled="$(grep -m1 '^enabled=' "$AUTO_CONF" 2>/dev/null | cut -d= -f2-)"
metrics_games="$STATE_DIR/metrics_enabled.list"
fps_games="$STATE_DIR/fps_enabled.list"
metrics_overlay_enabled=0
fps_overlay_enabled=0
manual_metrics_enabled=0
manual_fps_enabled=0
grep -q '[^[:space:]#]' "$metrics_games" 2>/dev/null && metrics_overlay_enabled=1
grep -q '[^[:space:]#]' "$fps_games" 2>/dev/null && fps_overlay_enabled=1
[ "$(cat "$STATE_DIR/manual_metrics_overlay" 2>/dev/null)" = 1 ] && manual_metrics_enabled=1
[ "$(cat "$STATE_DIR/manual_fps_overlay" 2>/dev/null)" = 1 ] && manual_fps_enabled=1
if [ "$enabled" = 1 ]; then
    idle="$(grep -m1 '^idle_profile=' "$AUTO_CONF" 2>/dev/null | cut -d= -f2-)"
    case "$idle" in stock|performance696) profile="$idle";; esac
fi

tries=0
while [ $tries -lt 900 ]; do
    apply_profile "$profile" 2
    rc=$?
    if [ $rc -eq 0 ]; then
        if [ "$enabled" = 1 ] ||
           [ "$metrics_overlay_enabled" = 1 ] ||
           [ "$fps_overlay_enabled" = 1 ] ||
           [ "$manual_metrics_enabled" = 1 ] ||
           [ "$manual_fps_enabled" = 1 ]; then
            nohup sh "$MODDIR/game_boost.sh" --daemon >/dev/null 2>&1 &
        fi
        exit 0
    fi
    [ $rc -eq 1 ] && exit 1
    sleep 2
    tries=$((tries + 1))
done

log "Boot service timed out waiting for a safe GPU-suspend window."
exit 1
