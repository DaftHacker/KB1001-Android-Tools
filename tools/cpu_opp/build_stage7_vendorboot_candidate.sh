#!/usr/bin/env bash
set -euo pipefail

SOURCE_EXPECTED_SHA256="def940b0dbb58c68e2b815143f2829f5bef5688e6e62e7e211387fc582e45c87"

usage() {
  cat <<'EOF'
Usage:
  build_stage7_vendorboot_candidate.sh <validated_1560_vendor_boot.img> [output.img] [magiskboot]

Examples:
  ./tools/cpu_opp/build_stage7_vendorboot_candidate.sh \
      vendor_boot_patched_a.img \
      vendor_boot_stage7_candidate_a.img \
      /data/adb/magisk/magiskboot

When magiskboot is omitted, the script uses the MAGISKBOOT environment variable
or searches PATH.

NO FLASHING IS PERFORMED.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ $# -ge 1 && $# -le 3 ]] || { usage; exit 2; }

SOURCE="$(readlink -f "$1")"
OUTPUT="\${2:-vendor_boot_stage7_candidate_a.img}"
OUTPUT="$(readlink -m "$OUTPUT")"
MAGISKBOOT="\${3:-\${MAGISKBOOT:-magiskboot}}"

[[ -f "$SOURCE" ]] || die "source image not found: $SOURCE"
command -v sha256sum >/dev/null || die "sha256sum not found"
command -v python3 >/dev/null || die "python3 not found"
command -v dtc >/dev/null || die "dtc not found"

if [[ "$MAGISKBOOT" == */* ]]; then
  [[ -x "$MAGISKBOOT" ]] || die "magiskboot not executable: $MAGISKBOOT"
else
  command -v "$MAGISKBOOT" >/dev/null || die "magiskboot not found: $MAGISKBOOT"
  MAGISKBOOT="$(command -v "$MAGISKBOOT")"
fi

SCRIPT_DIR="$(cd "$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
PATCHER="$SCRIPT_DIR/build_stage7_candidate_dtb.py"
[[ -f "$PATCHER" ]] || die "companion patcher missing: $PATCHER"

SOURCE_SIZE="$(stat -c %s "$SOURCE")"
SOURCE_SHA="$(sha256sum "$SOURCE" | awk '{print $1}')"

echo "KB1001 A333 Stage 7 - offline vendor_boot candidate builder"
echo
echo "SAFETY:"
echo "  * NO adb"
echo "  * NO fastboot"
echo "  * NO block-device writes"
echo "  * NO clock/OPP changes"
echo
echo "source=$SOURCE"
echo "source_size=$SOURCE_SIZE"
echo "source_sha256=$SOURCE_SHA"

[[ "$SOURCE_SHA" == "$SOURCE_EXPECTED_SHA256" ]] ||
  die "source is not the exact validated CPU4-1560 vendor_boot image"

WORK="$(mktemp -d -t kb1001_stage7_XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

ORIG="$WORK/original"
VERIFY="$WORK/verify"
mkdir -p "$ORIG" "$VERIFY"

echo
echo "[1/6] Unpack exact validated 1560 source"
(
  cd "$ORIG"
  set +e
  "$MAGISKBOOT" unpack "$SOURCE"
  rc=$?
  set -e
  echo "magiskboot_unpack_rc=$rc"
)
[[ -s "$ORIG/dtb" ]] || die "unpack did not produce dtb"
[[ -s "$ORIG/header" ]] || die "unpack did not produce header"
[[ -s "$ORIG/bootconfig" ]] || die "unpack did not produce bootconfig"
[[ -s "$ORIG/vendor_ramdisk/ramdisk.cpio" ]] ||
  die "unpack did not produce vendor ramdisk"

sha256sum \
  "$ORIG/header" \
  "$ORIG/bootconfig" \
  "$ORIG/vendor_ramdisk/ramdisk.cpio" \
  "$ORIG/dtb" > "$WORK/source_components.sha256"

echo
echo "[2/6] Build candidate DTB"
python3 "$PATCHER" "$ORIG/dtb" "$WORK/dtb.candidate"
[[ -s "$WORK/dtb.candidate" ]] || die "candidate DTB was not produced"

echo
echo "[3/6] Replace only DTB and repack"
cp "$WORK/dtb.candidate" "$ORIG/dtb"
(
  cd "$ORIG"
  "$MAGISKBOOT" repack "$SOURCE" "$WORK/vendor_boot.candidate.img"
)
[[ -s "$WORK/vendor_boot.candidate.img" ]] || die "candidate image missing"
[[ "$(stat -c %s "$WORK/vendor_boot.candidate.img")" -eq "$SOURCE_SIZE" ]] ||
  die "candidate image size changed unexpectedly"

echo
echo "[4/6] Re-unpack candidate"
(
  cd "$VERIFY"
  set +e
  "$MAGISKBOOT" unpack "$WORK/vendor_boot.candidate.img"
  rc=$?
  set -e
  echo "candidate_unpack_rc=$rc"
)
[[ -s "$VERIFY/dtb" ]] || die "candidate re-unpack did not produce dtb"

echo
echo "[5/6] Verify unrelated components are byte-identical"
cmp -s "$ORIG/header" "$VERIFY/header" || die "header changed"
cmp -s "$ORIG/bootconfig" "$VERIFY/bootconfig" || die "bootconfig changed"
cmp -s "$ORIG/vendor_ramdisk/ramdisk.cpio" "$VERIFY/vendor_ramdisk/ramdisk.cpio" ||
  die "vendor ramdisk changed"
cmp -s "$WORK/dtb.candidate" "$VERIFY/dtb" ||
  die "embedded DTB does not match generated candidate"

dtc -I dtb -O dts -o "$WORK/verify.dts" "$VERIFY/dtb"

grep -A12 'opp@1608000000 {' "$WORK/verify.dts" |
  grep -q 'opp-microvolt-vf0403 = <0x118c30>;' ||
  die "CPU4 1608 vf0403 is not 1.15 V"
grep -A12 'opp@1608000000 {' "$WORK/verify.dts" |
  grep -q 'turbo-mode;' ||
  die "CPU4 1608 is not turbo-mode"

grep -A12 'opp@1776000000 {' "$WORK/verify.dts" |
  grep -q 'opp-microvolt-vf0403 = <0x118c30>;' ||
  die "CPU2-3 1776 vf0403 is not 1.15 V"
grep -A12 'opp@1776000000 {' "$WORK/verify.dts" |
  grep -q 'turbo-mode;' ||
  die "CPU2-3 1776 is not turbo-mode"

mkdir -p "$(dirname "$OUTPUT")"
cp "$WORK/vendor_boot.candidate.img" "$OUTPUT"

OUT_SHA="$(sha256sum "$OUTPUT" | awk '{print $1}')"
DTB_SHA="$(sha256sum "$VERIFY/dtb" | awk '{print $1}')"

echo
echo "[6/6] Candidate complete"
echo "output=$OUTPUT"
echo "output_size=$(stat -c %s "$OUTPUT")"
echo "output_sha256=$OUT_SHA"
echo "embedded_dtb_sha256=$DTB_SHA"
echo
echo "STAGE 7 CANDIDATE BUILD PASS"
echo "NO FLASH WAS PERFORMED."
echo
echo "UNVALIDATED TARGETS:"
echo "  CPU4 / A73       1608 MHz @ 1.15 V turbo"
echo "  CPU2-3 / A53     1776 MHz @ 1.15 V turbo"
echo
echo "Do not expose these as normal app OC modes until staged physical validation passes."
