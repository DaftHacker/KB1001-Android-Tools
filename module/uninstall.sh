#!/system/bin/sh
for f in /data/local/tmp/kb1001_game_boost.pid /data/local/tmp/kb1001_perf_logger.pid; do
    pid="$(cat "$f" 2>/dev/null)"
    [ -n "$pid" ] && kill "$pid" 2>/dev/null
done
rm -rf /data/adb/kb1001_gpu_profiles
rm -f /data/local/tmp/kb1001_gpu_extreme_active
rm -f /data/local/tmp/kb1001_gpu_profiles.log
rm -f /data/local/tmp/kb1001_game_boost.pid
rm -f /data/local/tmp/kb1001_game_boost.state
rm -f /data/local/tmp/kb1001_perf_logger.pid
