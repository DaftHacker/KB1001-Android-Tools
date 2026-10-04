#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/out"
NAME="KB1001-Performance-Manager-v1.3-HUD-Logger.zip"
rm -rf "$OUT"
mkdir -p "$OUT"
(
  cd "$ROOT/module"
  zip -r -9 "$OUT/$NAME" . -x '*.DS_Store'
)
unzip -t "$OUT/$NAME" >/dev/null
echo "$OUT/$NAME"
