#!/usr/bin/env bash
set -euo pipefail

SOURCE_EXPECTED_SHA256="def940b0dbb58c68e2b815143f2829f5bef5688e6e62e7e211387fc582e45c87"
REMOTE_MAGISKBOOT="/data/adb/magisk/magiskboot"

usage() {
  cat <<'EOF'
Usage:
  build_stage7_vendorboot_candidate.sh <validated_1560_vendor_boot.img> [output.img]

Environment:
  ADB         adb executable (default: adb)
  ADB_SERIAL  optional adb serial/address when more than one device is connected

Example:
  ADB=/home/user/Android/Sdk/platform-tools/adb \
  ./tools/cpu_opp/build_stage7_vendorboot_candidate.sh \
      vendor_boot_patched_a.img \
      vendor_boot_stage7_candidate_a.img

This script uses the tablet's /data/adb/magisk/magiskboot through a root ADB
shell. It NEVER writes a block device and NEVER flashes anything.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ $# -ge 1 && $# -le 2 ]] || { usage; exit 2; }

SOURCE="$(readlink -f "$1")"
OUTPUT="\${2:-vendor_boot_stage7_candidate_a.img}"
OUTPUT="$(readlink -m "$OUTPUT")"
ADB="\${ADB:-adb}"

[[ -f "$SOURCE" ]] || die "source image not found: $SOURCE"
command -v "$ADB" >/dev/null || die "adb not found: $ADB"
command -v sha256sum >/dev/null || die "sha256sum not found"
command -v python3 >/dev/null || die "python3 not found"
command -v dtc >/dev/null || die "dtc not found"

ADB_ARGS=()
if [[ -n "\${ADB_SERIAL:-}" ]]; then
  ADB_ARGS=(-s "$ADB_SERIAL")
fi

adb_cmd() {
  "$ADB" "\${ADB_ARGS[@]}" "$@"
}

root_sh() {
  local cmd="$1"
  adb_cmd shell su -c "$cmd"
}

SCRIPT_DIR="$(cd "$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
PATCHER="$SCRIPT_DIR/build_stage7_candidate_dtb.py"
[[ -f "$PATCHER" ]] || die "companion patcher missing: $PATCHER"

SOURCE_SIZE="$(stat -c %s "$SOURCE")"
SOURCE_SHA="$(sha256sum "$SOURCE" | awk '{print $1}')"

echo "KB1001 A333 Stage 7 - offline/ADB vendor_boot candidate builder"
echo
echo "SAFETY:"
echo "  * reads/writes only temporary files under /data/local/tmp"
echo "  * NO /dev/block writes"
echo "  * NO fastboot"
echo "  * NO flashing"
echo "  * NO runtime clock/OPP changes"
echo
echo "source=$SOURCE"
echo "source_size=$SOURCE_SIZE"
echo "source_sha256=$SOURCE_SHA"

[[ "$SOURCE_SHA" == "$SOURCE_EXPECTED_SHA256" ]] ||
  die "source is not the exact validated CPU4-1560 vendor_boot image"

[[ "$(adb_cmd get-state 2>/dev/null)" == "device" ]] ||
  die "ADB device is not ready"

ROOT_ID="$(root_sh 'id -u' | tr -d '\r' | tail -n1)"
[[ "$ROOT_ID" == "0" ]] || die "ADB root shell unavailable"

root_sh "test -x '$REMOTE_MAGISKBOOT'" >/dev/null ||
  die "device magiskboot not executable: $REMOTE_MAGISKBOOT"

WORK="$(mktemp -d -t kb1001_stage7_host_XXXXXX)"
STAMP="$(date +%Y%m%d_%H%M%S)"
REMOTE="/data/local/tmp/kb1001_stage7_$STAMP"

cleanup() {
  root_sh "rm -rf '$REMOTE'" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

mkdir -p "$WORK/original" "$WORK/verify"
root_sh "rm -rf '$REMOTE' && mkdir -p '$REMOTE/original' '$REMOTE/verify'"

echo
echo "[1/7] Push exact validated 1560 source"
adb_cmd push "$SOURCE" "$REMOTE/source.img" >/dev/null
REMOTE_SOURCE_SHA="$(root_sh "sha256sum '$REMOTE/source.img' | awk '{print \\\$1}'" | tr -d '\r')"
[[ "$REMOTE_SOURCE_SHA" == "$SOURCE_EXPECTED_SHA256" ]] ||
  die "remote source hash mismatch"

echo
echo "[2/7] Unpack source with device MagiskBoot"
set +e
UNPACK_OUT="$(root_sh "cd '$REMOTE/original' && '$REMOTE_MAGISKBOOT' unpack '$REMOTE/source.img'" 2>&1)"
UNPACK_RC=$?
set -e
printf '%s\n' "$UNPACK_OUT"
echo "source_unpack_rc=$UNPACK_RC"

root_sh "test -s '$REMOTE/original/dtb' &&
         test -s '$REMOTE/original/header' &&
         test -s '$REMOTE/original/bootconfig' &&
         test -s '$REMOTE/original/vendor_ramdisk/ramdisk.cpio'" >/dev/null ||
  die "source unpack did not expose all required vendor_boot components"

adb_cmd pull "$REMOTE/original/dtb" "$WORK/original/dtb" >/dev/null

echo
echo "[3/7] Build next-stage candidate DTB on host"
python3 "$PATCHER" "$WORK/original/dtb" "$WORK/dtb.candidate"
[[ -s "$WORK/dtb.candidate" ]] || die "candidate DTB was not produced"

echo
echo "[4/7] Replace only DTB and repack on device"
adb_cmd push "$WORK/dtb.candidate" "$REMOTE/original/dtb" >/dev/null
root_sh "cd '$REMOTE/original' &&
         '$REMOTE_MAGISKBOOT' repack '$REMOTE/source.img' '$REMOTE/candidate.img'"

REMOTE_SIZE="$(root_sh "stat -c %s '$REMOTE/candidate.img'" | tr -d '\r')"
[[ "$REMOTE_SIZE" == "$SOURCE_SIZE" ]] ||
  die "candidate image size changed unexpectedly: $REMOTE_SIZE"

echo
echo "[5/7] Re-unpack candidate and compare unrelated components"
set +e
VERIFY_OUT="$(root_sh "cd '$REMOTE/verify' && '$REMOTE_MAGISKBOOT' unpack '$REMOTE/candidate.img'" 2>&1)"
VERIFY_RC=$?
set -e
printf '%s\n' "$VERIFY_OUT"
echo "candidate_unpack_rc=$VERIFY_RC"

root_sh "cmp -s '$REMOTE/original/header' '$REMOTE/verify/header' &&
         cmp -s '$REMOTE/original/bootconfig' '$REMOTE/verify/bootconfig' &&
         cmp -s '$REMOTE/original/vendor_ramdisk/ramdisk.cpio' '$REMOTE/verify/vendor_ramdisk/ramdisk.cpio'" >/dev/null ||
  die "an unrelated vendor_boot component changed during repack"

adb_cmd pull "$REMOTE/verify/dtb" "$WORK/verify/dtb" >/dev/null
cmp -s "$WORK/dtb.candidate" "$WORK/verify/dtb" ||
  die "repacked image contains a different DTB"

echo
echo "[6/7] Verify candidate DTB semantics"
dtc -I dtb -O dts -o "$WORK/verify.dts" "$WORK/verify/dtb"

check_node() {
  local hz="$1"
  local expected_count="$2"
  local block
  block="$(grep -A16 "opp@$hz {" "$WORK/verify.dts" || true)"
  [[ -n "$block" ]] || die "missing OPP node $hz"
  grep -q 'opp-microvolt-vf0403 = <0x118c30>;' <<<"$block" ||
    die "OPP $hz vf0403 is not 1.15 V"
  grep -q 'turbo-mode;' <<<"$block" ||
    die "OPP $hz is not turbo-mode"
}

check_node 1560000000 1
check_node 1608000000 1
check_node 1776000000 1

echo
echo "[7/7] Pull verified candidate"
mkdir -p "$(dirname "$OUTPUT")"
adb_cmd pull "$REMOTE/candidate.img" "$OUTPUT" >/dev/null

OUT_SIZE="$(stat -c %s "$OUTPUT")"
OUT_SHA="$(sha256sum "$OUTPUT" | awk '{print $1}')"
DTB_SHA="$(sha256sum "$WORK/verify/dtb" | awk '{print $1}')"

[[ "$OUT_SIZE" == "$SOURCE_SIZE" ]] || die "pulled candidate size mismatch"

echo
echo "STAGE 7 CANDIDATE BUILD PASS"
echo "output=$OUTPUT"
echo "output_size=$OUT_SIZE"
echo "output_sha256=$OUT_SHA"
echo "embedded_dtb_sha256=$DTB_SHA"
echo
echo "NO FLASH WAS PERFORMED."
echo
echo "UNVALIDATED TARGETS:"
echo "  CPU4 / A73       1608 MHz @ 1.15 V turbo"
echo "  CPU2-3 / A53     1776 MHz @ 1.15 V turbo"
echo
echo "These remain locked in Performance Manager until staged physical validation passes."
