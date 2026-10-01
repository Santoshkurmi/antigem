#!/data/data/com.termux/files/usr/bin/bash
# If invoked by a different shell or missing shebang interpreter, auto-detect and switch to bash
if [ -z "${BASH_VERSION:-}" ]; then
    if [ -n "${PREFIX:-}" ] && [ -x "$PREFIX/bin/bash" ]; then
        exec "$PREFIX/bin/bash" "$0" "$@"
    elif [ -x "/data/data/com.termux/files/usr/bin/bash" ]; then
        exec "/data/data/com.termux/files/usr/bin/bash" "$0" "$@"
    elif command -v bash >/dev/null 2>&1; then
        exec bash "$0" "$@"
    fi
fi

# ==============================================================================
# AGY Chats, Credentials & Config Packager
# ==============================================================================
set -euo pipefail

HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
GEMINI_DIR="${HOME_DIR}/.gemini"
BACKUP_DIR="/sdcard/Download/Antigem/backups"
TIMESTAMP="$(date +"%Y-%m-%d-%H-%M-%S")"
ZIP_BASENAME="agy_chats_backup_${TIMESTAMP}.zip"
OUTPUT_ZIP="${1:-${BACKUP_DIR}/${ZIP_BASENAME}}"
ZIP_PASSWORD="${2:-}"

echo "================================================"
echo "      AGY Chats & Credentials Packager          "
echo "================================================"
echo "Source dir : $GEMINI_DIR"
echo "Output     : $OUTPUT_ZIP"
echo "Timestamp  : $TIMESTAMP"
if [ -n "$ZIP_PASSWORD" ]; then
    echo "Encryption : Password Protected (AES/ZipCrypto)"
else
    echo "Encryption : None (Plain Zip)"
fi
echo "================================================"

if [ ! -d "$GEMINI_DIR" ]; then
    echo "❌ Error: ~/.gemini directory does not exist at $GEMINI_DIR!"
    exit 1
fi

cd "$HOME_DIR"

TEMP_ZIP="${HOME_DIR}/agy-chats-bundle-temp.zip"
rm -f "$TEMP_ZIP"

EXCLUDE_PATTERNS=(
    "*.sock"
    "*/cache/*"
    "*/.cache/*"
    "*/log/*"
    "*.log"
    "*/crashes/*"
    "*/implicit/*"
    ".gemini/bin/*"
    ".gemini/*/bin/*"
)

echo "[1/3] Calculating files in ~/.gemini..."
TOTAL_FILES=$( { find .gemini \
    \( ! -name "*.sock" \
       -a ! -path "*/cache/*" \
       -a ! -path "*/.cache/*" \
       -a ! -path "*/log/*" \
       -a ! -name "*.log" \
       -a ! -path "*/crashes/*" \
       -a ! -path "*/implicit/*" \
       -a ! -path ".gemini/bin/*" \
       -a ! -path ".gemini/*/bin/*" \
    \) 2>/dev/null || true; } | wc -l)
TOTAL_FILES=$((TOTAL_FILES + 0))
echo "      Total items to backup: $TOTAL_FILES"

echo "[2/3] Creating compressed archive..."
ZIP_CMD=(zip -r -y)
if [ -n "$ZIP_PASSWORD" ]; then
    ZIP_CMD+=(-P "$ZIP_PASSWORD")
fi
ZIP_CMD+=("$TEMP_ZIP" ".gemini" -x "${EXCLUDE_PATTERNS[@]}")

{ "${ZIP_CMD[@]}" 2>/dev/null || true; } | awk -v total="$TOTAL_FILES" '
BEGIN {
    cols = 12;
    count = 0;
}
/^  adding: / {
    count++;
    pct = (total > 0) ? int(count * 100 / total) : 0;
    if (pct > 100) pct = 100;
    if (count % 15 == 0 || count == total || pct == 100) {
        filled = int(pct * cols / 100);
        bar = "";
        for (i = 0; i < filled; i++) bar = bar "=";
        if (filled < cols) {
            bar = bar ">";
            for (i = length(bar); i < cols; i++) bar = bar " ";
        }
        printf("\r\033[K [\033[32m%s\033[0m] \033[1;33m%3d%%\033[0m (%d/%d)", bar, pct, count, total);
        fflush();
    }
}
END {
    bar = "";
    for (i = 0; i < cols; i++) bar = bar "=";
    printf("\r\033[K [\033[32m%s\033[0m] \033[1;32m100%%\033[0m (%d/%d)\n", bar, count, count);
}
'

echo "[3/3] Exporting backup archive to Antigem backups storage..."
mkdir -p "$(dirname "$OUTPUT_ZIP")"
cp "$TEMP_ZIP" "$OUTPUT_ZIP"
rm -f "$TEMP_ZIP"

echo ""
echo "🎉 Chat & Auth Backup Complete!"
echo "      ✅ Saved to: $OUTPUT_ZIP"
