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
# Termux Complete Bootstrap Packager (usr + home + dotfiles)
# ==============================================================================
set -euo pipefail

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
FILES_DIR="$(dirname "$PREFIX_DIR")"

TIMESTAMP="$(date +"%Y-%m-%d-%H-%M-%S")"
ZIP_BASENAME="bootrapz_${TIMESTAMP}.zip"
OUTPUT_ZIP="${1:-/sdcard/${ZIP_BASENAME}}"
DOWNLOAD_ZIP="/sdcard/Download/${ZIP_BASENAME}"

echo "=== Termux Full Environment Packager ==="
echo "Files root : $FILES_DIR"
echo "Prefix     : $PREFIX_DIR"
echo "Home       : $HOME_DIR"
echo "Output     : $OUTPUT_ZIP"
echo "Timestamp  : $TIMESTAMP"
echo "========================================"

cd "$FILES_DIR"

echo "[1/3] Archiving usr/ and home/ (preserving unix permissions & symlinks)..."
TEMP_ZIP="${FILES_DIR}/bootstrap-bundle.zip"
rm -f "$TEMP_ZIP"

EXCLUDE_PATTERNS=(
    "*.sock"
    "*/cache/*"   
    "*/.cache/*"
    "home/.ssh/*"
    "usr/etc/ssh/ssh_host_*"
    "etc/ssh/ssh_host_*"
    "home/.gemini/jetski-standalone-oauth-token*"
    "home/.gemini/*oauth*"
    "home/.gemini/*token*"
    "home/.gemini/antigravity/conversations/*"
    "home/.gemini/antigravity/brain/*"
    "home/.gemini/antigravity/annotations/*"
    "home/.gemini/antigravity/cli.log"
    "home/.gemini/antigravity/*.pb*"
    "home/.gemini/antigravity/antigravity_state.pbtxt"
    "home/.bash_history"
    "home/.zsh_history"
)

# Count items for accurate 0-100% progress tracking
echo "      Calculating total files..."
TOTAL_FILES=$(find usr home \
    \( ! -name "*.sock" \
       -a ! -path "*/cache/*" \
       -a ! -path "*/.cache/*" \
       -a ! -path "home/.ssh/*" \
       -a ! -name "ssh_host_*" \
       -a ! -path "home/.gemini/jetski-standalone-oauth-token*" \
       -a ! -path "home/.gemini/*oauth*" \
       -a ! -path "home/.gemini/*token*" \
       -a ! -path "home/.gemini/antigravity/conversations/*" \
       -a ! -path "home/.gemini/antigravity/brain/*" \
       -a ! -path "home/.gemini/antigravity/annotations/*" \
       -a ! -name "cli.log" \
       -a ! -name "*.pbtxt" \
       -a ! -name "*.pb" \
       -a ! -name ".bash_history" \
       -a ! -name ".zsh_history" \
    \) 2>/dev/null | wc -l)
echo "      Total entries to package: $TOTAL_FILES"

# Run zip with live progress bar and security exclusions
zip -r -y "$TEMP_ZIP" usr home -x "${EXCLUDE_PATTERNS[@]}" | awk -v total="$TOTAL_FILES" '
BEGIN {
    cols = 35;
    count = 0;
}
/^  adding: / {
    count++;
    pct = (total > 0) ? int(count * 100 / total) : 0;
    if (pct > 100) pct = 100;
    if (count % 25 == 0 || count == total || pct == 100) {
        filled = int(pct * cols / 100);
        bar = "";
        for (i = 0; i < filled; i++) bar = bar "=";
        if (filled < cols) {
            bar = bar ">";
            for (i = length(bar); i < cols; i++) bar = bar " ";
        }
        printf("\r\033[K      [\033[32m%s\033[0m] \033[1;33m%3d%%\033[0m (%d/%d)", bar, pct, count, total);
        fflush();
    }
}
END {
    bar = "";
    for (i = 0; i < cols; i++) bar = bar "=";
    printf("\r\033[K      [\033[32m%s\033[0m] \033[1;32m100%%\033[0m (%d/%d)\n", bar, count, count);
}
'

echo "[2/3] Exporting archive to SDCard storage..."
if cp "$TEMP_ZIP" "$OUTPUT_ZIP" 2>/dev/null; then
    echo "      Saved to $OUTPUT_ZIP"
else
    echo "      Note: Direct copy to /sdcard restricted by sandbox. Moving via Termux storage permissions..."
    cp "$TEMP_ZIP" "$HOME/storage/shared/${ZIP_BASENAME}" 2>/dev/null || true
fi

# Copy to Download if accessible
cp "$TEMP_ZIP" "$DOWNLOAD_ZIP" 2>/dev/null || cp "$TEMP_ZIP" "$HOME/storage/downloads/${ZIP_BASENAME}" 2>/dev/null || true

rm -f "$TEMP_ZIP"

echo "[3/3] Done! Bootstrap archive ready:"
ls -lh "$OUTPUT_ZIP" 2>/dev/null || ls -lh "$HOME/storage/shared/${ZIP_BASENAME}" 2>/dev/null || true
