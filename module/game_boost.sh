#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

AUTO_CONF="$STATE_DIR/auto_boost.conf"
GAMES="$STATE_DIR/games.list"
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
write_state(){ { echo "mode=$1"; echo "package=$2"; echo "profile=$3"; echo "updated=$(date '+%Y-%m-%d %H:%M:%S')"; } > "$AUTO_STATE"; }

overlay_show() {
    [ "$(conf_get overlay_auto 0)" = 1 ] || return 0
    am start-foreground-service -n com.dafthacker.kb1001perf/.OverlayService >/dev/null 2>&1 ||
      am startservice -n com.dafthacker.kb1001perf/.OverlayService >/dev/null 2>&1 || true
}

overlay_hide() {
    [ "$(conf_get overlay_auto 0)" = 1 ] || return 0
    am stopservice -n com.dafthacker.kb1001perf/.OverlayService >/dev/null 2>&1 || true
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
    log "Game detection daemon started (pid=$$)."

    while true; do
        boost_enabled="$(conf_get enabled 0)"
        overlay_enabled="$(conf_get overlay_auto 0)"

        if [ "$boost_enabled" != 1 ] && [ "$overlay_enabled" != 1 ]; then
            [ "$last_mode" = game ] && overlay_hide
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

            if [ "$last_mode" != game ] || [ "$last_pkg" != "$pkg" ]; then
                overlay_show
            fi

            write_state game "$pkg" "$active_profile"
            last_mode=game
            last_pkg="$pkg"
        else
            [ "$last_mode" = game ] && overlay_hide

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

        sleep "$poll"
    done
}

case "$1" in
  --daemon|daemon) run_daemon ;;
  foreground) get_foreground_package ;;
  *) exit 1 ;;
esac
