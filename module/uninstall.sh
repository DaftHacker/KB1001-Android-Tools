#!/system/bin/sh
PIDFILE=/data/local/tmp/kb1001_game_boost.pid
pid="$(cat "$PIDFILE" 2>/dev/null)"
[ -n "$pid" ] && kill "$pid" 2>/dev/null
rm -rf /data/adb/kb1001_gpu_profiles
rm -f /data/local/tmp/kb1001_gpu_extreme_active
rm -f /data/local/tmp/kb1001_gpu_profiles.log
rm -f /data/local/tmp/kb1001_game_boost.pid
rm -f /data/local/tmp/kb1001_game_boost.state
