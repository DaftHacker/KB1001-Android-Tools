#!/system/bin/sh
ui_print "****************************************"
ui_print " KB1001 Performance Manager v1.2"
ui_print "****************************************"
ui_print "- Safe GPU profiles from v1.1 retained"
ui_print "- Auto Game Boost added"
ui_print "- Default auto behavior when enabled:"
ui_print "    Dynamic 744 idle"
ui_print "    Performance 744 in selected games"
ui_print "- Extreme 792 remains SESSION-ONLY/manual"
ui_print "- Thermal protection is NOT disabled"
ui_print "- CPU/DDR tuning is NOT enabled yet"
ui_print ""

STATE=/data/adb/kb1001_gpu_profiles
mkdir -p "$STATE"
[ -f "$STATE/profile.conf" ] || echo dynamic744 > "$STATE/profile.conf"
if [ ! -f "$STATE/auto_boost.conf" ]; then
    cat > "$STATE/auto_boost.conf" <<EOC
# KB1001 automatic game boost
enabled=0
game_profile=performance744
idle_profile=dynamic744
poll_seconds=2
EOC
fi
[ -f "$STATE/games.list" ] || : > "$STATE/games.list"

for f in common.sh service.sh action.sh uninstall.sh game_boost.sh kb1001ctl; do
    set_perm "$MODPATH/$f" 0 0 0755
done
