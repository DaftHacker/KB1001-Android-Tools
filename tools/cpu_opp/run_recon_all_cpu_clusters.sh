#!/usr/bin/env bash
# Host runner for the read-only KB1001 all-cluster CPU OPP recon.
set -euo pipefail

ADB="${ADB:-adb}"
SERIAL="${ADB_SERIAL:-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE_SCRIPT="$HERE/recon_all_cpu_clusters.sh"
REMOTE_SCRIPT="/data/local/tmp/kb1001_recon_all_cpu_clusters.sh"
REMOTE_OUT="/sdcard/Download/KB1001-CPU-OPP-AllClusters-Recon.txt"
LOCAL_OUT="${1:-KB1001-CPU-OPP-AllClusters-Recon.txt}"

adb_cmd() {
    if [[ -n "$SERIAL" ]]; then
        "$ADB" -s "$SERIAL" "$@"
    else
        "$ADB" "$@"
    fi
}

[[ -f "$DEVICE_SCRIPT" ]] || { echo "Missing $DEVICE_SCRIPT" >&2; exit 1; }

echo "[1/5] Device"
adb_cmd get-state
adb_cmd shell getprop ro.product.model

echo "[2/5] Push read-only recon"
adb_cmd push "$DEVICE_SCRIPT" "$REMOTE_SCRIPT" >/dev/null
adb_cmd shell chmod 0755 "$REMOTE_SCRIPT"

echo "[3/5] Run through one interactive MagiskSU shell"
# Feeding stdin avoids the MagiskSU -c argument parsing behavior observed on
# this tablet. The recon itself performs no writes except its report file.
printf "sh %s %s\nexit\n" "$REMOTE_SCRIPT" "$REMOTE_OUT" |
    adb_cmd shell su

echo "[4/5] Pull report"
adb_cmd pull "$REMOTE_OUT" "$LOCAL_OUT"

echo "[5/5] Sanity check"
grep -q '^RECON_COMPLETE=1$' "$LOCAL_OUT" || {
    echo "Recon did not complete; inspect $LOCAL_OUT" >&2
    exit 2
}
echo "PASS: $LOCAL_OUT"
