#!/system/bin/sh

GPU="/sys/devices/platform/soc@3000000/1800000.gpu"
SUNXI="$GPU/sunxi_gpu"
OPP="$SUNXI/gpu_opp_ops"
DVFS="$SUNXI/sunxi_gpu_dvfs"
FREQ="$SUNXI/sunxi_gpu_freq"
INFO="$SUNXI/sunxi_gpu_info"
SCENE="$SUNXI/scene_ctrl"
DEVFREQ="/sys/class/devfreq/1800000.gpu"

STATE_DIR="/data/local/kb1001perf/state"
CONFIG="$STATE_DIR/profile.conf"
SESSION_EXTREME="/data/local/tmp/kb1001_gpu_extreme_active"
RUNTIME_PROFILE="/data/local/tmp/kb1001_gpu_runtime_profile"
LOG="/data/local/tmp/kb1001perf.log"

STOCK_TABLE="696 960 600 960 400 960 300 960 200 960"
DYN744_TABLE="744 960 696 960 600 960 400 960 300 960 200 960"
EXT792_TABLE="792 960 744 960 696 960 600 960 400 960 200 960"

mkdir -p "$STATE_DIR" 2>/dev/null

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null) $*" >> "$LOG"
    size="$(wc -c < "$LOG" 2>/dev/null)"
    case "$size" in ''|*[!0-9]*) return;; esac
    if [ "$size" -gt 262144 ]; then
        tail -n 500 "$LOG" > "$LOG.tmp.$$" 2>/dev/null && mv "$LOG.tmp.$$" "$LOG"
    fi
}

wait_for_sysfs() {
    i=0
    while [ $i -lt 180 ]; do
        [ -e "$OPP" ] && [ -e "$DVFS" ] && return 0
        sleep 1
        i=$((i + 1))
    done
    return 1
}

wait_for_suspend() {
    timeout="${1:-120}"
    i=0
    [ -e "$SCENE" ] && echo 0 > "$SCENE" 2>/dev/null
    [ -e "$DVFS" ] && echo 1 > "$DVFS" 2>/dev/null

    status="$(cat "$GPU/power/runtime_status" 2>/dev/null)"
    [ "$status" = "suspended" ] && return 0

    while [ $i -lt "$timeout" ]; do
        sleep 1
        status="$(cat "$GPU/power/runtime_status" 2>/dev/null)"
        [ "$status" = "suspended" ] && return 0
        i=$((i + 1))
    done
    return 1
}

available_has() {
    grep -qw "$1" "$DEVFREQ/available_frequencies" 2>/dev/null
}

wait_devfreq() {
    i=0
    while [ $i -lt 20 ]; do
        [ -e "$DEVFREQ/available_frequencies" ] && return 0
        sleep 1
        i=$((i + 1))
    done
    return 1
}

set_dynamic_policy() {
    max="$1"
    if [ -e "$DEVFREQ/available_governors" ] && grep -qw "simple_ondemand" "$DEVFREQ/available_governors" 2>/dev/null; then
        echo simple_ondemand > "$DEVFREQ/governor" 2>>"$LOG"
    fi
    echo 200000000 > "$DEVFREQ/min_freq" 2>>"$LOG"
    echo "$max" > "$DEVFREQ/max_freq" 2>>"$LOG"
    [ -e "$SCENE" ] && echo 0 > "$SCENE" 2>>"$LOG"
    echo 1 > "$DVFS" 2>>"$LOG"
}

restore_runtime_stock() {
    log "Attempting runtime stock-table recovery."
    echo "$STOCK_TABLE" > "$OPP" 2>>"$LOG" || return 1
    echo enable > "$OPP" 2>>"$LOG" || return 1
    wait_devfreq || return 1
    set_dynamic_policy 696000000
    return 0
}

rebuild_table() {
    table="$1"
    expected="$2"
    timeout="${3:-120}"

    if ! wait_for_suspend "$timeout"; then
        log "Timed out waiting for GPU runtime suspend."
        return 2
    fi

    log "GPU suspended; rebuilding runtime OPP table for max ${expected}Hz."

    if ! echo disable > "$OPP" 2>>"$LOG"; then
        log "gpu_opp_ops disable rejected; GPU likely woke during transition."
        return 2
    fi

    if ! echo "$table" > "$OPP" 2>>"$LOG"; then
        log "Failed to install requested OPP table. Restoring stock runtime table."
        restore_runtime_stock
        return 1
    fi

    if ! echo enable > "$OPP" 2>>"$LOG"; then
        log "Failed to re-enable devfreq. Restoring stock runtime table."
        echo disable > "$OPP" 2>/dev/null
        restore_runtime_stock
        return 1
    fi

    if ! wait_devfreq; then
        log "Devfreq sysfs did not return. Attempting stock runtime recovery."
        restore_runtime_stock
        return 1
    fi

    if ! available_has "$expected"; then
        log "Expected frequency ${expected}Hz is missing. Restoring stock runtime table."
        echo disable > "$OPP" 2>/dev/null
        restore_runtime_stock
        return 1
    fi

    return 0
}

ensure_stock_table() {
    if available_has 696000000 && ! available_has 744000000 && ! available_has 792000000; then
        set_dynamic_policy 696000000
        return 0
    fi
    rebuild_table "$STOCK_TABLE" 696000000 "${1:-120}" || return $?
    set_dynamic_policy 696000000
}

ensure_744_table() {
    if available_has 744000000 && ! available_has 792000000; then
        set_dynamic_policy 744000000
        return 0
    fi
    rebuild_table "$DYN744_TABLE" 744000000 "${1:-120}" || return $?
    set_dynamic_policy 744000000
}

ensure_792_table() {
    if available_has 792000000; then
        set_dynamic_policy 792000000
        return 0
    fi
    rebuild_table "$EXT792_TABLE" 792000000 "${1:-120}" || return $?
    set_dynamic_policy 792000000
}

apply_custom_mhz() {
    mhz="$1"
    timeout="${2:-2}"

    case "$mhz" in
        200|300|400|600|696|744|792) ;;
        *)
            log "Unsupported custom GPU frequency: ${mhz}MHz."
            return 3
            ;;
    esac

    hz=$((mhz * 1000000))

    if ! available_has "$hz"; then
        case "$mhz" in
            792) ensure_792_table "$timeout" || return $? ;;
            744) ensure_744_table "$timeout" || return $? ;;
            *) ensure_stock_table "$timeout" || return $? ;;
        esac
    fi

    [ -e "$SCENE" ] && echo 0 > "$SCENE" 2>>"$LOG"
    echo 0 > "$DVFS" 2>>"$LOG" || return 1
    echo "$mhz" > "$FREQ" 2>>"$LOG" || return 1

    if [ "$mhz" = 792 ]; then
        touch "$SESSION_EXTREME"
    else
        rm -f "$SESSION_EXTREME"
    fi

    echo "custom_$mhz" > "$RUNTIME_PROFILE"
    log "Applied SESSION-ONLY custom GPU clock: ${mhz}MHz pinned."
    return 0
}

apply_profile() {
    profile="$1"
    timeout="${2:-120}"

    case "$profile" in
        stock)
            rm -f "$SESSION_EXTREME"
            ensure_stock_table "$timeout"
            rc=$?
            if [ $rc -eq 0 ]; then
                echo stock > "$RUNTIME_PROFILE"
                log "Applied Stock 696 profile."
            fi
            return $rc
            ;;
        performance696)
            rm -f "$SESSION_EXTREME"
            ensure_stock_table "$timeout" || return $?
            [ -e "$SCENE" ] && echo 0 > "$SCENE" 2>>"$LOG"
            echo 0 > "$DVFS" 2>>"$LOG" || return 1
            echo 696 > "$FREQ" 2>>"$LOG" || return 1
            echo performance696 > "$RUNTIME_PROFILE"
            log "Applied Performance 696 profile (validated stock OPP pinned, vendor DVFS off)."
            return 0
            ;;
        dynamic744)
            rm -f "$SESSION_EXTREME"
            ensure_744_table "$timeout"
            rc=$?
            if [ $rc -eq 0 ]; then
                echo dynamic744 > "$RUNTIME_PROFILE"
                log "Applied Dynamic 744 profile."
            fi
            return $rc
            ;;
        performance744)
            rm -f "$SESSION_EXTREME"
            ensure_744_table "$timeout" || return $?
            [ -e "$SCENE" ] && echo 0 > "$SCENE" 2>>"$LOG"
            echo 0 > "$DVFS" 2>>"$LOG" || return 1
            echo 744 > "$FREQ" 2>>"$LOG" || return 1
            echo performance744 > "$RUNTIME_PROFILE"
            log "Applied Performance 744 profile (744 MHz pinned, vendor DVFS off)."
            return 0
            ;;
        extreme792|extreme792_dynamic)
            ensure_792_table "$timeout" || return $?
            touch "$SESSION_EXTREME"
            echo extreme792_dynamic > "$RUNTIME_PROFILE"
            log "Applied SESSION-ONLY Extreme 792 Dynamic profile."
            return 0
            ;;
        performance792|extreme792_full)
            ensure_792_table "$timeout" || return $?
            [ -e "$SCENE" ] && echo 0 > "$SCENE" 2>>"$LOG"
            echo 0 > "$DVFS" 2>>"$LOG" || return 1
            echo 792 > "$FREQ" 2>>"$LOG" || return 1
            touch "$SESSION_EXTREME"
            echo extreme792_full > "$RUNTIME_PROFILE"
            log "Applied SESSION-ONLY Extreme 792 Full Throttle profile (792 MHz pinned)."
            return 0
            ;;
        custom_*)
            apply_custom_mhz "${profile#custom_}" "$timeout"
            return $?
            ;;
        *)
            log "Unknown profile '$profile'; falling back to validated Stock 696."
            apply_profile stock "$timeout"
            ;;
    esac
}

show_status() {
    echo "Available: $(cat "$DEVFREQ/available_frequencies" 2>/dev/null)"
    echo "Current:   $(cat "$FREQ" 2>/dev/null | head -1)"
    echo "Min:       $(cat "$DEVFREQ/min_freq" 2>/dev/null)"
    echo "Max:       $(cat "$DEVFREQ/max_freq" 2>/dev/null)"
    echo "Governor:  $(cat "$DEVFREQ/governor" 2>/dev/null)"
    echo "DVFS:      $(cat "$DVFS" 2>/dev/null)"
    echo "Voltage:   $(cat "$SUNXI/sunxi_gpu_volt" 2>/dev/null)"
    echo "Runtime:   $(cat "$GPU/power/runtime_status" 2>/dev/null)"
}
