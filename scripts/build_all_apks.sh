#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# AntiGem APK Build & Output Packager
# Builds the app (ai.antigem).
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_DIR="${ROOT_DIR}/output"

MODE="debug"
if [[ "${1:-}" == "--release" || "${1:-}" == "-r" ]]; then
    MODE="release"
fi

echo "================================================================="
echo "🚀 Building AntiGem Packages in [${MODE^^}] mode"
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
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleRelease
    # the release asset; the in-app updater downloads releases/download/v<version>/AntiGem-v<version>.apk
    find "${APKS_SRC}/release" -name "*.apk" -exec cp {} "${OUTPUT_DIR}/AntiGem-v${VERSION_NAME}.apk" \;
else
    echo "🔨 Compiling Debug (:app:assembleDebug)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleDebug
    find "${APKS_SRC}/debug" -name "*.apk" -exec cp {} "${OUTPUT_DIR}/AntiGem-v${VERSION_NAME}-debug.apk" \;

    # the APK ships the bridge xz-compressed; the uncompressed build is kept under app/build
    if [[ -f "${ROOT_DIR}/app/build/intermediates/agyIdeBridge/agy_ide_bridge" ]]; then
        cp "${ROOT_DIR}/app/build/intermediates/agyIdeBridge/agy_ide_bridge" "${OUTPUT_DIR}/agy_ide_bridge-v${VERSION_NAME}-android-arm64"
    fi
fi

echo ""
echo "================================================================="
echo "✅ Build Complete! Generated Artifacts in: ${OUTPUT_DIR}"
echo "================================================================="
ls -lh "${OUTPUT_DIR}"/* 2>/dev/null || true
echo "================================================================="
