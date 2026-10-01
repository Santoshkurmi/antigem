#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# AntiGem Multi-Package APK Build & Output Packager
# Builds standard (com.antigem) and termux (com.termux) flavors.
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

if [[ "${MODE}" == "release" ]]; then
    echo "🔨 Compiling Standard Release (:app:assembleStandardRelease)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleStandardRelease

    echo "🔨 Compiling Termux Release (:app:assembleTermuxRelease)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleTermuxRelease

    APKS_SRC="${ROOT_DIR}/app/build/outputs/apk"
    find "${APKS_SRC}/standard/release" -name "*.apk" -exec cp {} "${OUTPUT_DIR}/antiGem-standard-v${VERSION_NAME}-release.apk" \;
    find "${APKS_SRC}/termux/release" -name "*.apk" -exec cp {} "${OUTPUT_DIR}/antiGem-termux-v${VERSION_NAME}-release.apk" \;

    if [[ -f "${ROOT_DIR}/app/src/main/assets/bin/agy_ide_bridge" ]]; then
        cp "${ROOT_DIR}/app/src/main/assets/bin/agy_ide_bridge" "${OUTPUT_DIR}/agy_ide_bridge-v${VERSION_NAME}-android-arm64"
    fi
else
    echo "🔨 Compiling Standard Debug (:app:assembleStandardDebug)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleStandardDebug

    echo "🔨 Compiling Termux Debug (:app:assembleTermuxDebug)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleTermuxDebug

    APKS_SRC="${ROOT_DIR}/app/build/outputs/apk"
    find "${APKS_SRC}/standard/debug" -name "*.apk" -exec cp {} "${OUTPUT_DIR}/antiGem-standard-v${VERSION_NAME}-debug.apk" \;
    find "${APKS_SRC}/termux/debug" -name "*.apk" -exec cp {} "${OUTPUT_DIR}/antiGem-termux-v${VERSION_NAME}-debug.apk" \;

    if [[ -f "${ROOT_DIR}/app/src/main/assets/bin/agy_ide_bridge" ]]; then
        cp "${ROOT_DIR}/app/src/main/assets/bin/agy_ide_bridge" "${OUTPUT_DIR}/agy_ide_bridge-v${VERSION_NAME}-android-arm64"
    fi
fi

echo ""
echo "================================================================="
echo "✅ Build Complete! Generated Artifacts in: ${OUTPUT_DIR}"
echo "================================================================="
ls -lh "${OUTPUT_DIR}"/* 2>/dev/null || true
echo "================================================================="
