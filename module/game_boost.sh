#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

AUTO_CONF="$STATE_DIR/auto_boost.conf"
GAMES="$STATE_DIR/games.list"
OVERLAY_DISABLED="$STATE_DIR/overlay_disabled.list"
METRICS_GAMES="$STATE_DIR/metrics_enabled.list"
FPS_GAMES="$STATE_DIR/fps_enabled.list"
AUTO_PID="/data/local/tmp/kb1001_game_boost.pid"
AUTO_STATE="/data/local/tmp/kb1001_game_boost.state"

conf_get(){ v="$(grep -m1 "^$1=" "$AUTO_CONF" 2>/dev/null|cut -d= -f2-)"; [ -n "$v" ]&&printf '%s' "$v"||printf '%s' "$2"; }
sanitize_profile(){ case "$1" in stock|dynamic744|performance744) printf '%s' "$1";; *) printf dynamic744;; esac; }
sanitize_game_profile(){
    case "$1" in
        dynamic744|performance744|extreme792_dynamic|extreme792_full) printf '%s' "$1" ;;
        *) printf performance744 ;;
    esac
}

get_foreground_package() {
    line="$(dumpsys window displays 2>/dev/null | grep -m1 -E 'mCurrentFocus=Window\{|mFocusedApp=ActivityRecord\{')"
    pkg="$(printf '%s\n' "$line" | sed -n 's/.* u[0-9][0-9]* \([^/ }]*\)\/.*/\1/p')"
    if [ -z "$pkg" ]; then
        line="$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity=ActivityRecord|mResumedActivity: ActivityRecord')"
        pkg="$(printf '%s\n' "$line" | sed -n 's/.* u[0-9][0-9]* \([^/ }]*\)\/.*/\1/p')"
    fi
    if [ -z "$pkg" ]; then
        line="$(dumpsys activity top 2>/dev/null | grep -m1 '^  *ACTIVITY ')"
        pkg="$(printf '%s\n' "$line" | sed -n 's/^  *ACTIVITY \([^/ }]*\)\/.*/\1/p')"
    fi
    printf '%s\n' "$pkg"
}

is_registered_game(){ [ -n "$1" ] && grep -Ev '^[[:space:]]*(#|$)' "$GAMES" 2>/dev/null | sed 's/[[:space:]]*$//' | grep -Fxq "$1"; }
metrics_allowed(){ [ -n "$1" ] && grep -Fxq "$1" "$METRICS_GAMES" 2>/dev/null; }
fps_allowed(){ [ -n "$1" ] && grep -Fxq "$1" "$FPS_GAMES" 2>/dev/null; }
any_overlay_configured(){
 grep -q '[^[:space:]#]' "$METRICS_GAMES" 2>/dev/null ||
 grep -q '[^[:space:]#]' "$FPS_GAMES" 2>/dev/null
}
write_state(){
    new_mode="$1"; new_pkg="$2"; new_profile="$3"
    old_mode="$(grep -m1 '^mode=' "$AUTO_STATE" 2>/dev/null | cut -d= -f2-)"
    old_pkg="$(grep -m1 '^package=' "$AUTO_STATE" 2>/dev/null | cut -d= -f2-)"
    old_profile="$(grep -m1 '^profile=' "$AUTO_STATE" 2>/dev/null | cut -d= -f2-)"
    [ "$new_mode" = "$old_mode" ] && [ "$new_pkg" = "$old_pkg" ] && [ "$new_profile" = "$old_profile" ] && return 0
    {
        echo "mode=$new_mode"
        echo "package=$new_pkg"
        echo "profile=$new_profile"
        echo "updated=$(date '+%Y-%m-%d %H:%M:%S')"
    } > "$AUTO_STATE"
}

metrics_show() {
    am start-foreground-service -a kb1001.auto_metrics -n com.dafthacker.kb1001perf/.OverlayService >/dev/null 2>&1 ||
      am startservice -a kb1001.auto_metrics -n com.dafthacker.kb1001perf/.OverlayService >/dev/null 2>&1 || true
}

metrics_hide() {
    am stopservice -n com.dafthacker.kb1001perf/.OverlayService >/dev/null 2>&1 || true
}

fps_show() {
    am start-foreground-service -a kb1001.auto_fps -n com.dafthacker.kb1001perf/.FpsOverlayService >/dev/null 2>&1 ||
      am startservice -a kb1001.auto_fps -n com.dafthacker.kb1001perf/.FpsOverlayService >/dev/null 2>&1 || true
}

fps_hide() {
    am stopservice -n com.dafthacker.kb1001perf/.FpsOverlayService >/dev/null 2>&1 || true
}

apply_auto_profile() {
    target="$1"; label="$2"
    apply_profile "$target" 2
    rc=$?
    if [ $rc -eq 0 ]; then log "AutoBoost: applied $target ($label)."
    else log "AutoBoost: apply $target deferred/failed ($label), rc=$rc."; fi
    return $rc
}

run_daemon() {
    old="$(cat "$AUTO_PID" 2>/dev/null)"
    [ -n "$old" ] && kill -0 "$old" 2>/dev/null && exit 0
    echo $$ > "$AUTO_PID"
    trap 'rm -f "$AUTO_PID"; exit 0' INT TERM EXIT

    last_mode=""
    last_pkg=""
    retry_target=""
    auto_metrics_visible=0
    auto_fps_visible=0
    log "Game detection daemon started."

    while true; do
        boost_enabled="$(conf_get enabled 0)"

        if [ "$boost_enabled" != 1 ] && ! any_overlay_configured; then
            if [ "$auto_metrics_visible" = 1 ]; then
                metrics_hide
                auto_metrics_visible=0
            fi
            if [ "$auto_fps_visible" = 1 ]; then
                fps_hide
                auto_fps_visible=0
            fi
            current="$(cat "$CONFIG" 2>/dev/null)"
            write_state disabled "" "$(sanitize_profile "${current:-dynamic744}")"
            last_mode=disabled
            last_pkg=""
            retry_target=""
            sleep 3
            continue
        fi

        game_profile="$(sanitize_game_profile "$(conf_get game_profile performance744)")"
        idle_profile="$(sanitize_profile "$(conf_get idle_profile dynamic744)")"
        poll="$(conf_get poll_seconds 2)"
        case "$poll" in 1|2|3|4|5|6|7|8|9|10) ;; *) poll=2;; esac

        pkg="$(get_foreground_package)"
        if is_registered_game "$pkg"; then
            if [ "$boost_enabled" = 1 ]; then
                if [ "$last_mode" != game ] || [ "$last_pkg" != "$pkg" ] || [ "$retry_target" = "$game_profile" ]; then
                    if apply_auto_profile "$game_profile" "game=$pkg"; then
                        retry_target=""
                    else
                        retry_target="$game_profile"
                    fi
                fi
                active_profile="$game_profile"
            else
                active_profile="$(cat "$CONFIG" 2>/dev/null)"
                [ -n "$active_profile" ] || active_profile=dynamic744
            fi

            # Publish the target before starting overlays so the dedicated FPS
            # sampler can resolve the correct SurfaceFlinger layer immediately.
            write_state game "$pkg" "$active_profile"

            if metrics_allowed "$pkg"; then
                if [ "$auto_metrics_visible" != 1 ] || [ "$last_pkg" != "$pkg" ]; then
                    metrics_show
                    auto_metrics_visible=1
                fi
            elif [ "$auto_metrics_visible" = 1 ]; then
                metrics_hide
                auto_metrics_visible=0
            fi

            if fps_allowed "$pkg"; then
                if [ "$auto_fps_visible" != 1 ] || [ "$last_pkg" != "$pkg" ]; then
                    fps_show
                    auto_fps_visible=1
                fi
            elif [ "$auto_fps_visible" = 1 ]; then
                fps_hide
                auto_fps_visible=0
            fi

            last_mode=game
            last_pkg="$pkg"
        else
            if [ "$auto_metrics_visible" = 1 ]; then
                metrics_hide
                auto_metrics_visible=0
            fi
            if [ "$auto_fps_visible" = 1 ]; then
                fps_hide
                auto_fps_visible=0
            fi

            if [ "$boost_enabled" = 1 ]; then
                if [ "$last_mode" != idle ] || [ "$retry_target" = "$idle_profile" ]; then
                    if apply_auto_profile "$idle_profile" "foreground=${pkg:-unknown}"; then
                        retry_target=""
                    else
                        retry_target="$idle_profile"
                    fi
                fi
                active_profile="$idle_profile"
            else
                active_profile="$(cat "$CONFIG" 2>/dev/null)"
                [ -n "$active_profile" ] || active_profile=dynamic744
            fi

            write_state idle "$pkg" "$active_profile"
            last_mode=idle
            last_pkg="$pkg"
        fi

        if [ "$last_mode" = game ] && [ "$poll" -lt 5 ] 2>/dev/null; then
            sleep 5
        else
            sleep "$poll"
        fi
    done
}

case "$1" in
  --daemon|daemon) run_daemon ;;
  foreground) get_foreground_package ;;
  *) exit 1 ;;
esac
