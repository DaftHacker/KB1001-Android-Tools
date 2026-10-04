#!/system/bin/sh
am stopservice -n com.dafthacker.kb1001perf/.OverlayService >/dev/null 2>&1 || true
am stopservice -n com.dafthacker.kb1001perf/.FpsOverlayService >/dev/null 2>&1 || true
ps -A -o PID,ARGS 2>/dev/null | awk '/perf_logger[.]sh --daemon/ {print $1}' | while read -r p; do
    case "$p" in ''|*[!0-9]*) continue;; esac
    kill "$p" 2>/dev/null || true
done

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
rm -f /data/local/tmp/kb1001_cpu_stat.*
