#!/system/bin/sh
# KB1001 / Allwinner A333 CPU OPP full-cluster recon
# READ ONLY: no governor, boost, cpufreq, regulator, clock, DT, or OPP writes.

set -u

OUT="${1:-/sdcard/Download/KB1001-CPU-OPP-AllClusters-Recon.txt}"
TMP="${OUT}.tmp"

exec >"$TMP" 2>&1

section() {
    echo
    echo "================================================================"
    echo "$1"
    echo "================================================================"
}

readf() {
    p="$1"
    if [ -r "$p" ]; then
        printf '%s=' "$p"
        cat "$p" 2>/dev/null
    fi
}

dump_dir_files() {
    d="$1"
    [ -d "$d" ] || return 0
    echo "--- $d ---"
    for f in "$d"/*; do
        [ -f "$f" ] && [ -r "$f" ] || continue
        case "$f" in
            */stats/*|*/trans_table|*/time_in_state) continue ;;
        esac
        printf '%s: ' "${f##*/}"
        cat "$f" 2>/dev/null
    done
}

echo "KB1001 CPU OPP ALL-CLUSTER RECON"
echo "read_only=1"
echo "timestamp=$(date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || date)"
echo "uname=$(uname -a 2>/dev/null)"
echo "id=$(id 2>/dev/null)"
echo "android_release=$(getprop ro.build.version.release 2>/dev/null)"
echo "build_fingerprint=$(getprop ro.build.fingerprint 2>/dev/null)"
echo "hardware=$(getprop ro.hardware 2>/dev/null)"
echo "soc_model=$(getprop ro.soc.model 2>/dev/null)"
echo "soc_manufacturer=$(getprop ro.soc.manufacturer 2>/dev/null)"
echo "kernel_cmdline=$(cat /proc/cmdline 2>/dev/null)"

section "CPU TOPOLOGY"
for c in /sys/devices/system/cpu/cpu[0-9]*; do
    [ -d "$c" ] || continue
    n="${c##*cpu}"
    echo "--- cpu$n ---"
    readf "$c/online"
    readf "$c/topology/physical_package_id"
    readf "$c/topology/cluster_id"
    readf "$c/topology/core_id"
    readf "$c/cpu_capacity"
    readf "$c/cpufreq/scaling_cur_freq"
done

section "CPUFREQ DRIVER / GLOBAL BOOST"
readf /sys/devices/system/cpu/cpufreq/boost
readf /sys/devices/system/cpu/cpufreq/policy0/boost
readf /sys/devices/system/cpu/cpufreq/policy2/boost
readf /sys/devices/system/cpu/cpufreq/policy4/boost

section "CPUFREQ POLICIES"
for p in /sys/devices/system/cpu/cpufreq/policy*; do
    [ -d "$p" ] || continue
    echo "--- ${p##*/} ---"
    for f in         affected_cpus related_cpus scaling_driver scaling_governor         scaling_available_governors scaling_available_frequencies         scaling_boost_frequencies scaling_min_freq scaling_max_freq         scaling_cur_freq cpuinfo_min_freq cpuinfo_max_freq         cpuinfo_cur_freq energy_performance_available_preferences         energy_performance_preference; do
        readf "$p/$f"
    done
done

section "OPP DEBUGFS - FULL DISCOVERY"
OPPROOT=/sys/kernel/debug/opp
if [ -d "$OPPROOT" ]; then
    echo "opp_root=$OPPROOT"
    find "$OPPROOT" -maxdepth 5 -type f -print 2>/dev/null | sort
    echo
    echo "--- OPP file contents ---"
    find "$OPPROOT" -maxdepth 5 -type f 2>/dev/null | sort | while IFS= read -r f; do
        [ -r "$f" ] || continue
        printf '%s: ' "$f"
        cat "$f" 2>/dev/null
    done
else
    echo "opp_debugfs=unavailable"
fi

section "CLOCKS"
CLK=/sys/kernel/debug/clk
if [ -d "$CLK" ]; then
    for n in pll-cpu0 pll-cpu1 pll-cpu2 cpu0 cpu1 cpu2; do
        [ -d "$CLK/$n" ] || continue
        echo "--- clock $n ---"
        for f in clk_rate clk_enable_count clk_prepare_count clk_accuracy clk_phase clk_flags; do
            readf "$CLK/$n/$f"
        done
    done
    echo "--- cpu/pll clock summary ---"
    find "$CLK" -maxdepth 2 -type f \( -name clk_rate -o -name clk_enable_count \) 2>/dev/null |
        grep -E '/(pll-cpu|cpu[0-9]?)/' | sort | while IFS= read -r f; do
            printf '%s: ' "$f"; cat "$f" 2>/dev/null
        done
else
    echo "clock_debugfs=unavailable"
fi

section "REGULATORS"
REG=/sys/kernel/debug/regulator
if [ -d "$REG" ]; then
    find "$REG" -maxdepth 3 -type f 2>/dev/null | sort | while IFS= read -r f; do
        case "$f" in
            *microvolt*|*voltage*|*state*|*enable*|*name*)
                [ -r "$f" ] || continue
                printf '%s: ' "$f"; cat "$f" 2>/dev/null ;;
        esac
    done
else
    echo "regulator_debugfs=unavailable"
fi

section "THERMAL SNAPSHOT"
for z in /sys/class/thermal/thermal_zone*; do
    [ -d "$z" ] || continue
    printf '%s ' "${z##*/}"
    printf 'type='; cat "$z/type" 2>/dev/null
    printf 'temp='; cat "$z/temp" 2>/dev/null
done

section "LIVE DEVICE TREE CPU / OPP DISCOVERY"
DT=/sys/firmware/devicetree/base
if [ -d "$DT" ]; then
    echo "--- CPU operating-points references ---"
    find "$DT/cpus" -maxdepth 4 -type f 2>/dev/null | sort | while IFS= read -r f; do
        case "${f##*/}" in
            compatible|device_type|reg|clock-names|operating-points-v2|capacity-dmips-mhz)
                printf '%s bytes=' "$f"; wc -c <"$f" 2>/dev/null ;;
        esac
    done

    echo "--- probable OPP table paths ---"
    find "$DT" -maxdepth 4 -type d 2>/dev/null |
        grep -Ei '(opp|operating)' | sort

    echo "--- probable OPP node properties (hex) ---"
    find "$DT" -maxdepth 6 -type f 2>/dev/null |
        grep -Ei '/opp|opp-' |
        grep -E '/(opp-hz|opp-microvolt|opp-microvolt-vf[0-9]+|turbo-mode|status)$' |
        sort | while IFS= read -r f; do
            printf '%s size=' "$f"; wc -c <"$f" 2>/dev/null
            if command -v od >/dev/null 2>&1; then
                printf ' hex='
                od -An -tx1 "$f" 2>/dev/null | tr -d ' \n'
                echo
            fi
        done
else
    echo "live_device_tree=unavailable"
fi

section "CPUFREQ / DVFS KERNEL MESSAGES"
dmesg 2>/dev/null | grep -Ei 'cpufreq|dvfs|opp|pll-cpu|axp.*dcdc|regulator' | tail -n 300

section "SUMMARY MARKERS"
for p in 0 2 4; do
    base="/sys/devices/system/cpu/cpufreq/policy$p"
    [ -d "$base" ] || continue
    echo "policy$p.cpus=$(cat "$base/related_cpus" 2>/dev/null)"
    echo "policy$p.available=$(cat "$base/scaling_available_frequencies" 2>/dev/null)"
    echo "policy$p.boost=$(cat "$base/scaling_boost_frequencies" 2>/dev/null)"
    echo "policy$p.current=$(cat "$base/scaling_cur_freq" 2>/dev/null)"
    echo "policy$p.max=$(cat "$base/scaling_max_freq" 2>/dev/null)"
done
echo "global_boost=$(cat /sys/devices/system/cpu/cpufreq/boost 2>/dev/null)"
echo "RECON_COMPLETE=1"

mv "$TMP" "$OUT"
chmod 0644 "$OUT" 2>/dev/null || true
echo "$OUT"
