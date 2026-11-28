#!/usr/bin/env bash
# The Network Interceptor engine (netcap/) that release APKs download on first use, one file per APK CPU:
#   https://github.com/<repo>/releases/download/netcap-v<version>/netcap-<version>-android-<arm64|amd64>.xz
# <version> is "version" in netcap/release.json: bump it when netcap changes. Each APK pins that version and the
# SHA-256 of its file (app/build.gradle.kts → BuildConfig.NETCAP_*; NetcapManager refuses anything else).
#
#   bash scripts/netcap_release/netcap_release.sh ci           the release workflow: publishes netcap-v<version> when
#                                                               it doesn't exist yet (built here), else downloads it and
#                                                               checks the engine didn't change without a version bump;
#                                                               then writes the files' hashes into netcap/release.json
#                                                               for the APK build (not committed)
#   bash scripts/netcap_release/netcap_release.sh pin          the same hashes for a local release build: downloads the
#                                                               published files and writes their hashes
#   bash scripts/netcap_release/netcap_release.sh build        builds both files into out/ (nothing published)
#   bash scripts/netcap_release/netcap_release.sh pin --local  hashes of the files in out/ (testing -Pnetcap=download
#                                                               against your own server)
#
# A published version is never rebuilt: older apps keep downloading the exact files they were built for. The build
# is reproducible (-trimpath -buildvcs=false, the same Go): the workflow compares a fresh arm64 build with the
# published one, so a netcap change without a version bump stops the release. Files go to out/ (git-ignored).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PIN="$ROOT/netcap/release.json"
OUT="$HERE/out"
REPO="${NETCAP_REPO:-Santoshkurmi/antigem}"
ARCHES=(arm64 amd64)

VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$PIN")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "netcap/release.json: version must look like 1.2.3, not $VERSION" >&2; exit 1; }
TAG="netcap-v$VERSION"
file_of() { echo "netcap-$VERSION-android-$1.xz"; }

# the NDK's clang for android/amd64 (Go links it only through cgo), found like app/build.gradle.kts does
find_clang() {
  local roots=() sdk=""
  [[ -n "${ANDROID_NDK_HOME:-}" ]] && roots+=("$ANDROID_NDK_HOME")
  [[ -n "${ANDROID_NDK_LATEST_HOME:-}" ]] && roots+=("$ANDROID_NDK_LATEST_HOME")
  if [[ -f "$ROOT/local.properties" ]]; then sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)"; fi
  [[ -z "$sdk" ]] && sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then while read -r d; do roots+=("$sdk/ndk/$d"); done < <(ls "$sdk/ndk" | sort -rV); fi
  local host="linux-x86_64"
  [[ "$(uname)" == "Darwin" ]] && host="darwin-x86_64"
  for r in "${roots[@]}"; do
    local c="$r/toolchains/llvm/prebuilt/$host/bin/x86_64-linux-android24-clang"
    [[ -x "$c" ]] && { echo "$c"; return; }
  done
  echo "the amd64 build needs the Android NDK: set ANDROID_NDK_HOME or install one in <sdk>/ndk" >&2
  exit 1
}

build_one() { # <arch> <output .xz>
  local arch="$1" out="$2" raw
  raw="$(mktemp)"
  local env=(GOOS=android GOARCH=arm64 CGO_ENABLED=0)
  [[ "$arch" == "amd64" ]] && env=(GOOS=android GOARCH=amd64 CGO_ENABLED=1 CC="$(find_clang)")
  echo "building netcap $VERSION for android/$arch ($(go env GOVERSION))"
  (cd "$ROOT/netcap" && env "${env[@]}" go build -trimpath -buildvcs=false \
    -ldflags="-s -w -X antigem/netcap/cli.Version=$VERSION" -o "$raw" ./cmd/netcap)
  xz -6 -T1 -c "$raw" > "$out"
  rm -f "$raw"
}

build_all() {
  rm -rf "$OUT" && mkdir -p "$OUT"
  for a in "${ARCHES[@]}"; do build_one "$a" "$OUT/$(file_of "$a")"; done
}

fetch() { # the published files into out/
  rm -rf "$OUT" && mkdir -p "$OUT"
  for a in "${ARCHES[@]}"; do
    gh release download "$TAG" --repo "$REPO" --pattern "$(file_of "$a")" --dir "$OUT" \
      || { echo "the release $TAG has no $(file_of "$a")" >&2; exit 1; }
  done
}

published() { gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; }

sha() { sha256sum "$1" | cut -d' ' -f1; }

write_pin() { # hashes of the files in out/ → netcap/release.json
  python3 - "$PIN" "$OUT" "$VERSION" <<'PY'
import hashlib, json, os, sys
pin, out, version = sys.argv[1:4]
data = json.load(open(pin))
data["assets"] = {}
for arch in ("arm64", "amd64"):
    blob = open(os.path.join(out, f"netcap-{version}-android-{arch}.xz"), "rb").read()
    data["assets"][arch] = {"sha256": hashlib.sha256(blob).hexdigest(), "size": len(blob)}
    print(f"  pinned {arch}: {len(blob)} bytes, sha256 {data['assets'][arch]['sha256']}")
with open(pin, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
PY
}

case "${1:-}" in
  ci)
    if published; then
      echo "$TAG is published: using its files"
      fetch
      fresh="$(mktemp)"
      build_one arm64 "$fresh"
      if [[ "$(sha "$fresh")" != "$(sha "$OUT/$(file_of arm64)")" ]]; then
        echo "::error::netcap changed since $TAG was published (its code, its dependencies or the Go version): bump \"version\" in netcap/release.json" >&2
        exit 1
      fi
      rm -f "$fresh"
    else
      echo "$TAG is not published yet: building and publishing it"
      build_all
      target=()
      [[ -n "${GITHUB_SHA:-}" ]] && target=(--target "$GITHUB_SHA")
      gh release create "$TAG" --repo "$REPO" "${target[@]}" --latest=false --title "Network Interceptor engine $VERSION" \
        --notes "The AntiGem app downloads these files when the Network Interceptor is first used and checks their SHA-256. Not an app release." \
        "$OUT/$(file_of arm64)" "$OUT/$(file_of amd64)"
    fi
    write_pin
    ;;
  pin)
    if [[ "${2:-}" == "--local" ]]; then
      for a in "${ARCHES[@]}"; do [[ -f "$OUT/$(file_of "$a")" ]] || { echo "no out/$(file_of "$a"): run build first" >&2; exit 1; }; done
    else
      published || { echo "$TAG is not published yet (the release workflow publishes it)" >&2; exit 1; }
      fetch
    fi
    write_pin
    echo "netcap/release.json now pins these files: don't commit the hashes (the release workflow writes its own)"
    ;;
  build)
    build_all
    for a in "${ARCHES[@]}"; do echo "  $(file_of "$a"): $(stat -c %s "$OUT/$(file_of "$a")") bytes, sha256 $(sha "$OUT/$(file_of "$a")")"; done
    ;;
  *)
    sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
