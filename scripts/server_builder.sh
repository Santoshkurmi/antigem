#!/usr/bin/env bash
# ==============================================================================
# antiGem Go IDE Server - Interactive Android ARM64 Builder & ADB Deployer
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
SERVER_DIR="$PROJECT_ROOT/server"
OUTPUT_BINARY="$SERVER_DIR/server_android"

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

echo -e "${ORANGE}${BOLD}⚡ antiGem Go IDE Server Builder & Deployer${NC}"
echo -e "${DIM}================================================================${NC}"

# --- Pre-flight Checks ---
echo -e "\n${CYAN}🔍 [1/4] Checking Build Environment...${NC}"

if ! command -v go &>/dev/null; then
    echo -e "${RED}❌ Error: 'go' compiler is not installed or not found in PATH.${NC}"
    exit 1
fi
GO_VER=$(go version | awk '{print $3}')
echo -e "   ${GREEN}✔${NC} Go Compiler: ${BOLD}$GO_VER${NC}"

if [ ! -d "$SERVER_DIR" ]; then
    echo -e "${RED}❌ Error: Server source directory not found at '$SERVER_DIR'.${NC}"
    exit 1
fi
echo -e "   ${GREEN}✔${NC} Source Path: ${DIM}$SERVER_DIR${NC}"

# --- Compilation ---
echo -e "\n${CYAN}🛠️  [2/4] Compiling Go Server for Android ARM64...${NC}"
echo -e "   ${DIM}Target: GOOS=android GOARCH=arm64 (CGO_ENABLED=0)${NC}"

START_TIME=$(date +%s%N 2>/dev/null || date +%s)

(
    cd "$SERVER_DIR"
    CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -ldflags="-s -w" -o "$OUTPUT_BINARY" main.go
)

END_TIME=$(date +%s%N 2>/dev/null || date +%s)
ELAPSED_MS=$(( (END_TIME - START_TIME) / 1000000 )) 2>/dev/null || ELAPSED_MS=0

if [ ! -f "$OUTPUT_BINARY" ]; then
    echo -e "${RED}❌ Build failed: '$OUTPUT_BINARY' was not created.${NC}"
    exit 1
fi

BIN_SIZE=$(ls -lh "$OUTPUT_BINARY" | awk '{print $5}')
SHA_HASH=$(sha256sum "$OUTPUT_BINARY" | awk '{print $1}' | cut -c 1-12)

echo -e "   ${GREEN}✔ Build Successful!${NC}"
echo -e "   • Binary:    ${BOLD}$(basename "$OUTPUT_BINARY")${NC}"
echo -e "   • Size:      ${YELLOW}$BIN_SIZE${NC}"
echo -e "   • SHA-256:   ${DIM}${SHA_HASH}...${NC}"
if [ "$ELAPSED_MS" -gt 0 ]; then
    echo -e "   • Duration:  ${DIM}${ELAPSED_MS}ms${NC}"
fi

# --- ADB Device Detection ---
echo -e "\n${CYAN}📲 [3/4] Checking Connected Android Devices...${NC}"

if ! command -v adb &>/dev/null; then
    echo -e "${YELLOW}⚠️  Warning: 'adb' is not installed. Skipping automatic phone deployment.${NC}"
    echo -e "   Binary is available locally at: ${BOLD}$OUTPUT_BINARY${NC}"
    exit 0
fi

DEVICE_LIST=$(adb devices | grep -v "List of devices" | grep "device$" || true)

if [ -z "$DEVICE_LIST" ]; then
    echo -e "${YELLOW}⚠️  No ADB device connected in 'device' mode.${NC}"
    echo -e "   Connect your phone with USB Debugging enabled to auto-deploy."
    echo -e "   Binary is available locally at: ${BOLD}$OUTPUT_BINARY${NC}"
    exit 0
fi

DEVICE_ID=$(echo "$DEVICE_LIST" | head -n 1 | awk '{print $1}')
DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model 2>/dev/null || echo "Android Device")
DEVICE_ABI=$(adb -s "$DEVICE_ID" shell getprop ro.product.cpu.abi 2>/dev/null || echo "arm64-v8a")

echo -e "   ${GREEN}✔${NC} Target Device: ${BOLD}$DEVICE_MODEL${NC} (${DIM}$DEVICE_ID${NC}, ABI: ${YELLOW}$DEVICE_ABI${NC})"

# --- Deployment ---
echo -e "\n${CYAN}🚀 [4/4] Deploying Binary to Device Storage...${NC}"

# 1. Ensure target dirs exist
adb -s "$DEVICE_ID" shell "mkdir -p /sdcard/test" 2>/dev/null || true

# 2. Push to /sdcard/server
echo -e "   • Pushing to ${BOLD}/sdcard/server${NC}..."
adb -s "$DEVICE_ID" push "$OUTPUT_BINARY" /sdcard/server >/dev/null

# 3. Push to /sdcard/test/server and /sdcard/test/server_arm64
echo -e "   • Pushing to ${BOLD}/sdcard/test/server${NC}..."
adb -s "$DEVICE_ID" push "$OUTPUT_BINARY" /sdcard/test/server >/dev/null
adb -s "$DEVICE_ID" push "$OUTPUT_BINARY" /sdcard/test/server_arm64 >/dev/null

echo -e "   ${GREEN}✔ All files deployed successfully!${NC}"

# --- Summary & Instructions ---
echo -e "\n${DIM}================================================================${NC}"
echo -e "${GREEN}${BOLD}🎉 COMPLETE! Ready to run on your phone.${NC}"
echo -e "${DIM}================================================================${NC}"
echo -e "Inside ${BOLD}Termux${NC}, run this one-liner to update and start:"
echo -e ""
echo -e "  ${ORANGE}${BOLD}pkill -f server; cp /sdcard/server ~/server && chmod +x ~/server && ~/server -f${NC}"
echo -e ""
echo -e "${DIM}Or copy from /sdcard/test/server:${NC}"
echo -e "  ${DIM}cp /sdcard/test/server ~/server && chmod +x ~/server && ~/server -f${NC}\n"