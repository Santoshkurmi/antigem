#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# AntiGem APK Build & Output Packager
# Builds the app (ai.antigem). --x86_64 builds the x86_64 APK instead of the arm64 one (gradle -Pabi=x86_64).
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_DIR="${ROOT_DIR}/output"

MODE="debug"
ABI="arm64"
for arg in "$@"; do
    case "$arg" in
        --release|-r) MODE="release" ;;
        --x86_64) ABI="x86_64" ;;
    esac
done
# file names: the arm64 build has none of these suffixes (the in-app updater asks for the name of its own build)
SUFFIX=""
ARCHIVE="antiGem"
BRIDGE_ARCH="arm64"
if [[ "${ABI}" == "x86_64" ]]; then
    SUFFIX="-x86_64"
    ARCHIVE="antiGem-x86_64"
    BRIDGE_ARCH="amd64"
fi

echo "================================================================="
echo "🚀 Building AntiGem Packages in [${MODE^^}] mode for ${ABI}"
echo "================================================================="

# Extract version from version.json
VERSION_NAME="1.0.0"
VERSION_CODE="1"
if [[ -f "${ROOT_DIR}/version.json" ]]; then
    VERSION_NAME=$(python3 -c "import json; print(json.load(open('${ROOT_DIR}/version.json')).get('version_name', '1.0.0'))" 2>/dev/null || echo "1.0.0")
    VERSION_CODE=$(python3 -c "import json; print(json.load(open('${ROOT_DIR}/version.json')).get('version_code', 1))" 2>/dev/null || echo "1")
fi

echo "📦 Version Name: v${VERSION_NAME} (Code: ${VERSION_CODE})"
mkdir -p "${OUTPUT_DIR}"

APKS_SRC="${ROOT_DIR}/app/build/outputs/apk"
if [[ "${MODE}" == "release" ]]; then
    echo "🔨 Compiling Release (:app:assembleRelease)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleRelease -Pabi="${ABI}"
    # the release asset; the in-app updater downloads releases/download/v<version>/AntiGem-v<version><suffix>.apk
    cp "${APKS_SRC}/release/${ARCHIVE}-release.apk" "${OUTPUT_DIR}/AntiGem-v${VERSION_NAME}${SUFFIX}.apk"
else
    echo "🔨 Compiling Debug (:app:assembleDebug)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleDebug -Pabi="${ABI}"
    cp "${APKS_SRC}/debug/${ARCHIVE}-debug.apk" "${OUTPUT_DIR}/AntiGem-v${VERSION_NAME}${SUFFIX}-debug.apk"

    # the APK ships the bridge xz-compressed; the uncompressed build is kept under app/build
    if [[ -f "${ROOT_DIR}/app/build/intermediates/agyIdeBridge/agy_ide_bridge" ]]; then
        cp "${ROOT_DIR}/app/build/intermediates/agyIdeBridge/agy_ide_bridge" "${OUTPUT_DIR}/agy_ide_bridge-v${VERSION_NAME}-android-${BRIDGE_ARCH}"
    fi
fi

echo ""
echo "================================================================="
echo "✅ Build Complete! Generated Artifacts in: ${OUTPUT_DIR}"
echo "================================================================="
ls -lh "${OUTPUT_DIR}"/* 2>/dev/null || true
echo "================================================================="
