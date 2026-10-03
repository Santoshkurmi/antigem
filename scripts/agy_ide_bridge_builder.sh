#!/usr/bin/env bash
# ==============================================================================
# antiGem Go IDE Server - Proto Compiler & Multi-Platform Builder / Deployer
# ==============================================================================

set -eo pipefail

# --- Color Palette & Formatting ---
BOLD='\033[1m'
DIM='\033[2m'
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
MAGENTA='\033[0;35m'
ORANGE='\033[38;5;208m'
NC='\033[0m' # No Color

# --- Directory Resolution ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$PROJECT_ROOT/agy_ide_bridge"
LOCAL_BINARY="$SERVER_DIR/agy_ide_bridge"
ANDROID_BINARY="$SERVER_DIR/agy_ide_bridge_android"

# Add Go bin to PATH
export PATH="$PATH:$HOME/go/bin"

clear 2>/dev/null || true

cat << 'EOF'
 ▄▄▄▄▄▄▄▄▄▄▄  ▄▄▄▄▄▄▄▄▄▄▄  ▄▄▄▄▄▄▄▄▄▄▄  ▄▄▄▄▄▄▄▄▄▄▄  ▄▄▄▄▄▄▄▄▄▄▄  ▄▄▄▄▄▄▄▄▄▄▄ 
▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌
▐░█▀▀▀▀▀▀▀█░▌▐░█▀▀▀▀▀▀▀█░▌▐░█▀▀▀▀▀▀▀▀▀ ▐░█▀▀▀▀▀▀▀▀▀ ▐░█▀▀▀▀▀▀▀▀▀ ▐░█▀▀▀▀▀▀▀█░▌
▐░▌       ▐░▌▐░▌       ▐░▌▐░▌          ▐░▌          ▐░▌          ▐░▌       ▐░▌
▐░█▄▄▄▄▄▄▄█░▌▐░█▄▄▄▄▄▄▄█░▌▐░▌          ▐░▌ ▄▄▄▄▄▄▄▄ ▐░█▄▄▄▄▄▄▄▄▄ ▐░█▄▄▄▄▄▄▄█░▌
▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░▌          ▐░▌▐░░░░░░░░▌▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌
▐░█▀▀▀▀▀▀▀█░▌▐░█▀▀▀▀▀▀▀█░▌▐░▌          ▐░▌ ▀▀▀▀▀▀█░▌▐░█▀▀▀▀▀▀▀▀▀ ▐░█▀▀▀▀█░░░░▌
▐░▌       ▐░▌▐░▌       ▐░▌▐░▌          ▐░▌       ▐░▌▐░▌          ▐░▌   ▐░▌░░░▌
▐░▌       ▐░▌▐░▌       ▐░▌▐░█▄▄▄▄▄▄▄▄▄ ▐░█▄▄▄▄▄▄▄█░▌▐░█▄▄▄▄▄▄▄▄▄ ▐░▌    ▐░▌░░▌
▐░▌       ▐░▌▐░▌       ▐░▌▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░░░░░░░░░░░▌▐░▌     ▐░░░▌
 ▀         ▀  ▀         ▀  ▀▀▀▀▀▀▀▀▀▀▀  ▀▀▀▀▀▀▀▀▀▀▀  ▀▀▀▀▀▀▀▀▀▀▀  ▀       ▀▀▀ 
EOF

echo -e "${ORANGE}${BOLD}⚡ antiGem Go IDE Server - Proto & Server Builder${NC}"
echo -e "${DIM}================================================================${NC}"

# --- Step 1: Pre-flight Checks ---
echo -e "\n${CYAN}🔍 [1/5] Checking Build Environment...${NC}"

if ! command -v go &>/dev/null; then
    echo -e "${RED}❌ Error: 'go' compiler is not installed or not found in PATH.${NC}"
    exit 1
fi
GO_VER=$(go version | awk '{print $3}')
echo -e "   ${GREEN}✔${NC} Go Compiler: ${BOLD}$GO_VER${NC}"

if ! command -v python3 &>/dev/null; then
    echo -e "${RED}❌ Error: 'python3' is required to compile protos.${NC}"
    exit 1
fi
echo -e "   ${GREEN}✔${NC} Python 3:    ${BOLD}$(python3 --version)${NC}"

# --- Step 2: Compile & Sync Protobufs ---
echo -e "\n${CYAN}📦 [2/5] Compiling & Syncing Protobuf Definitions...${NC}"
python3 "$PROJECT_ROOT/proto_generator/filter_proto.py" --copy
echo -e "   ${GREEN}✔ Protobuf generation complete.${NC}"

# --- Step 3: Compile Local Host Binary ---
echo -e "\n${CYAN}💻 [3/5] Compiling Local Host Binary (for current PC)...${NC}"
(
    cd "$SERVER_DIR"
    go build -ldflags="-s -w" -o "$LOCAL_BINARY" main.go
)
if [ -f "$LOCAL_BINARY" ]; then
    chmod +x "$LOCAL_BINARY"
    LOCAL_SIZE=$(ls -lh "$LOCAL_BINARY" | awk '{print $5}')
    echo -e "   ${GREEN}✔ Local Binary Built: ${BOLD}$(basename "$LOCAL_BINARY")${NC} (${YELLOW}$LOCAL_SIZE${NC})"
else
    echo -e "${RED}❌ Failed to build local binary.${NC}"
    exit 1
fi

# --- Step 4: Compile Android ARM64 Binary (with Android NDK CGO) ---
echo -e "\n${CYAN}🛠️  [4/5] Compiling Go Server for Android ARM64 (Android NDK CGO)...${NC}"
NDK_CLANG=$(find /home/cat/android-sdk/ndk -name "aarch64-linux-android*-clang" 2>/dev/null | grep -E "android(24|28|30|34)-clang" | head -n 1 || true)
(
    cd "$SERVER_DIR"
    if [ -n "$NDK_CLANG" ] && [ -x "$NDK_CLANG" ]; then
        echo -e "   ${DIM}Using NDK Clang: $NDK_CLANG${NC}"
        CC="$NDK_CLANG" CGO_ENABLED=1 GOOS=android GOARCH=arm64 go build -ldflags="-s -w" -o "$ANDROID_BINARY" main.go
    else
        echo -e "   ${YELLOW}⚠️  NDK Clang not found, falling back to CGO_ENABLED=0${NC}"
        CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -ldflags="-s -w" -o "$ANDROID_BINARY" main.go
    fi
)
if [ -f "$ANDROID_BINARY" ]; then
    ANDROID_SIZE=$(ls -lh "$ANDROID_BINARY" | awk '{print $5}')
    echo -e "   ${GREEN}✔ Android Binary Built: ${BOLD}$(basename "$ANDROID_BINARY")${NC} (${YELLOW}$ANDROID_SIZE${NC})"
else
    echo -e "${RED}❌ Failed to build Android binary.${NC}"
    exit 1
fi

# --- Step 5: ADB Device Detection & Deployment ---
echo -e "\n${CYAN}📲 [5/5] Checking Connected Android Devices for Auto-Deploy...${NC}"

if ! command -v adb &>/dev/null; then
    echo -e "${YELLOW}⚠️  'adb' is not installed. Skipping automatic device deployment.${NC}"
else
    DEVICE_LIST=$(adb devices | grep -v "List of devices" | grep "device$" || true)
    if [ -z "$DEVICE_LIST" ]; then
        echo -e "${YELLOW}⚠️  No ADB device connected in 'device' mode. Skipping auto-push.${NC}"
    else
        DEVICE_ID=$(echo "$DEVICE_LIST" | head -n 1 | awk '{print $1}')
        DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model 2>/dev/null || echo "Android Device")
        echo -e "   ${GREEN}✔${NC} Target Device: ${BOLD}$DEVICE_MODEL${NC} (${DIM}$DEVICE_ID${NC})"
        echo -e "   • Pushing to ${BOLD}/sdcard/agy_ide_bridge${NC}..."
        adb -s "$DEVICE_ID" push "$ANDROID_BINARY" /sdcard/agy_ide_bridge >/dev/null
        echo -e "   ${GREEN}✔ Binary deployed to device storage!${NC}"
    fi
fi

# --- Summary ---
echo -e "\n${DIM}================================================================${NC}"
echo -e "${GREEN}${BOLD}🎉 COMPLETE! Everything built successfully.${NC}"
echo -e "${DIM}================================================================${NC}"
echo -e "• To run locally on your PC:"
echo -e "  ${ORANGE}${BOLD}cd agy_ide_bridge && ./agy_ide_bridge --proxy${NC}"
echo -e ""
echo -e "• Inside Termux on your phone:"
echo -e "  ${ORANGE}${BOLD}pkill -f agy_ide_bridge; cp /sdcard/agy_ide_bridge \$PREFIX/bin/agy_ide_bridge && chmod +x \$PREFIX/bin/agy_ide_bridge && agy_ide_bridge -f${NC}\n"