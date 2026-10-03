#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/out"
NAME="KB1001-GPU-Profiles-v1.2-AutoBoost.zip"
rm -rf "$OUT"
mkdir -p "$OUT"
(
  cd "$ROOT/module"
  zip -r -9 "$OUT/$NAME" . -x '*.DS_Store'
)
echo "$OUT/$NAME"
