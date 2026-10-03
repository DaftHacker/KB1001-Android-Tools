#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"

AUTO_CONF="$STATE_DIR/auto_boost.conf"
GAMES="$STATE_DIR/games.list"
AUTO_PID="/data/local/tmp/kb1001_game_boost.pid"
AUTO_STATE="/data/local/tmp/kb1001_game_boost.state"

conf_get() {
    key="$1"
    def="$2"
    value="$(grep -m1 "^${key}=" "$AUTO_CONF" 2>/dev/null | cut -d= -f2-)"
    [ -n "$value" ] && printf '%s' "$value" || printf '%s' "$def"
}

sanitize_profile() {
    case "$1" in
        stock|dynamic744|performance744) printf '%s' "$1" ;;
        *) printf '%s' "dynamic744" ;;
    esac
}

sanitize_game_profile() {
    case "$1" in
        dynamic744|performance744) printf '%s' "$1" ;;
        *) printf '%s' "performance744" ;;
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

is_registered_game() {
    pkg="$1"
    [ -n "$pkg" ] || return 1
    [ -f "$GAMES" ] || return 1
    grep -Fvx '#__never_match__' "$GAMES" 2>/dev/null | sed 's/[[:space:]]*$//' | grep -Fxq "$pkg"
}

write_state() {
    mode="$1"
    pkg="$2"
    profile="$3"
    {
        echo "mode=$mode"
        echo "package=$pkg"
        echo "profile=$profile"
        echo "updated=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)"
    } > "$AUTO_STATE"
}

apply_auto_profile() {
    target="$1"
    label="$2"
    apply_profile "$target" 2
    rc=$?
    if [ $rc -eq 0 ]; then
        log "AutoBoost: applied $target ($label)."
    elif [ $rc -eq 2 ]; then
        log "AutoBoost: $target waiting for a safe GPU suspend window."
    else
        log "AutoBoost: failed to apply $target ($label), rc=$rc."
    fi
    return $rc
}

run_daemon() {
    oldpid="$(cat "$AUTO_PID" 2>/dev/null)"
    if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
        exit 0
    fi

    echo $$ > "$AUTO_PID"
    trap 'rm -f "$AUTO_PID"; exit 0' INT TERM EXIT

    log "AutoBoost daemon started (pid=$$)."
    last_mode=""
    last_pkg=""
    retry_target=""

    while true; do
        enabled="$(conf_get enabled 0)"
        if [ "$enabled" != "1" ]; then
            if [ "$last_mode" = "game" ]; then
                restore="$(sanitize_profile "$(cat "$CONFIG" 2>/dev/null)")"
                apply_auto_profile "$restore" "auto disabled restore"
            fi
            write_state "disabled" "" "$(sanitize_profile "$(cat "$CONFIG" 2>/dev/null)")"
            last_mode="disabled"
            last_pkg=""
            retry_target=""
            sleep 3
            continue
        fi

        game_profile="$(sanitize_game_profile "$(conf_get game_profile performance744)")"
        idle_profile="$(sanitize_profile "$(conf_get idle_profile dynamic744)")"
        poll="$(conf_get poll_seconds 2)"
        case "$poll" in ''|*[!0-9]*) poll=2 ;; esac
        [ "$poll" -lt 1 ] && poll=1
        [ "$poll" -gt 10 ] && poll=10

        pkg="$(get_foreground_package)"

        if is_registered_game "$pkg"; then
            if [ "$last_mode" != "game" ] || [ "$last_pkg" != "$pkg" ] || [ "$retry_target" = "$game_profile" ]; then
                if apply_auto_profile "$game_profile" "game=$pkg"; then retry_target=""; else retry_target="$game_profile"; fi
            fi
            write_state "game" "$pkg" "$game_profile"
            last_mode="game"
            last_pkg="$pkg"
        else
            if [ "$last_mode" != "idle" ] || [ "$retry_target" = "$idle_profile" ]; then
                if apply_auto_profile "$idle_profile" "foreground=${pkg:-unknown}"; then retry_target=""; else retry_target="$idle_profile"; fi
            fi
            write_state "idle" "$pkg" "$idle_profile"
            last_mode="idle"
            last_pkg="$pkg"
        fi

        sleep "$poll"
    done
}

case "$1" in
    --daemon|daemon) run_daemon ;;
    foreground) get_foreground_package ;;
    *) echo "Usage: $0 --daemon|foreground"; exit 1 ;;
esac
