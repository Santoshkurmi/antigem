#!/usr/bin/env bash
# Builds the Linux (VPS) builds of the IDE bridge and bundles them into the app, so Remote mode can upload
# the right one over SFTP (chosen by `uname -m` on the server).
#
#   ./scripts/build_server_bridge.sh          -> app/src/main/assets/bin/agy_ide_bridge_linux_{amd64,arm64}
#
# The Android build of the bridge (scripts/agy_ide_bridge_builder.sh) is a different binary and is not
# used on a VPS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/bin"
mkdir -p "$OUT"

cd "$ROOT/agy_ide_bridge"
for arch in amd64 arm64; do
  echo "Building linux/$arch ..."
  CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build -trimpath -ldflags "-s -w" -o "$OUT/agy_ide_bridge_linux_$arch" .
  chmod +x "$OUT/agy_ide_bridge_linux_$arch"
  ls -lh "$OUT/agy_ide_bridge_linux_$arch"
done
echo "Done. Rebuild the app so the binaries are packaged."
