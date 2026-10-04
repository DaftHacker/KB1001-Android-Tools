#!/system/bin/sh
ui_print "****************************************"
ui_print " KB1001 Performance Manager v1.3"
ui_print "****************************************"
ui_print "- Persistent root game detector"
ui_print "- In-game HUD integration"
ui_print "- Read-only CPU/GPU/thermal/devfreq logger"
ui_print "- Optional CSV session logging"
ui_print "- 792 MHz renamed Experimental 792"
ui_print "- Thermal protection remains enabled"
ui_print "- CPU/DDR tuning remains read-only"

STATE=/data/adb/kb1001_gpu_profiles
mkdir -p "$STATE"
[ -f "$STATE/profile.conf" ] || echo dynamic744 > "$STATE/profile.conf"

[ -f "$STATE/auto_boost.conf" ] || cat > "$STATE/auto_boost.conf" <<EOC
enabled=0
game_profile=performance744
idle_profile=dynamic744
poll_seconds=2
overlay_auto=0
EOC

[ -f "$STATE/logger.conf" ] || cat > "$STATE/logger.conf" <<EOC
interval_seconds=1
file_logging=0
file_path=
EOC

[ -f "$STATE/games.list" ] || : > "$STATE/games.list"

for f in common.sh service.sh uninstall.sh game_boost.sh perf_logger.sh kb1001ctl; do
    set_perm "$MODPATH/$f" 0 0 0755
done
