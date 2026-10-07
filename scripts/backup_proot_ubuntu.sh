#!/bin/bash
# ==============================================================================
# AntiGem Ubuntu (PRoot) Rootfs Packager - run INSIDE the Ubuntu container
# Entries are stored relative to the rootfs root (etc/, usr/, root/, ...).
# ==============================================================================
set -euo pipefail
trap 'echo -e "\n\033[1;31m[ERROR]\033[0m Backup failed at line $LINENO: \x27$BASH_COMMAND\x27" >&2' ERR

BACKUP_DIR="/sdcard/Download/Antigem/backups"
TIMESTAMP="$(date +"%Y-%m-%d-%H-%M-%S")"
OUTPUT_ZIP="${1:-${BACKUP_DIR}/ubuntu_${TIMESTAMP}.zip}"
TEMP_ZIP="/tmp/ubuntu-rootfs-bundle-${TIMESTAMP}.zip"

echo "=== Ubuntu PRoot Rootfs Packager ==="
echo "Rootfs     : /"
echo "Output     : $OUTPUT_ZIP"
echo "Timestamp  : $TIMESTAMP"
echo "========================================"

if [ ! -f /etc/os-release ] || [ -n "${PREFIX:-}" ]; then
    echo -e "\n\033[1;31m[ERROR]\033[0m This script must be run inside the Ubuntu container." >&2
    exit 1
fi

if ! command -v zip >/dev/null 2>&1; then
    echo -e "\n\033[1;31m[ERROR]\033[0m 'zip' utility is not installed. Please install it with 'apt install zip'." >&2
    exit 1
fi

cd /

echo "[0/3] Cleaning APT download cache (apt-get clean)..."
if ! apt-get clean 2>/dev/null; then
    echo -e "      \033[1;33m[WARN]\033[0m apt-get clean failed (is apt/dpkg running?); cached .deb files are still skipped."
fi

# Virtual / Android bind-mounted paths: keep the empty directory, skip contents
SKIP_CONTENT_DIRS=(proc sys dev tmp sdcard storage apex system system_ext vendor odm product data linkerconfig antigem)
# Android bind-mounted files at rootfs root: skip entirely
SKIP_FILES=(plat_property_contexts property_contexts)

EXCLUDE_PATTERNS=(
    "*.sock"
    "*/.cache/*"
    "root/.ssh/*"
    "etc/ssh/ssh_host_*"
    "root/.gemini/jetski-standalone-oauth-token*"
    "root/.gemini/*oauth*"
    "root/.gemini/*token*"
    "root/.bash_history"
    "root/.zsh_history"
    "root/.antigem_bridge/*token*"
    "support/*"
    "var/cache/apt/*.bin"
    "var/cache/apt/archives/*.deb"
    "var/lib/apt/lists/*_*"
    "*.proot.l2s.*"
)
TARGETS=()
EMPTY_DIRS=()
for entry in .[!.]* ..?* *; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    skip=0
    for f in "${SKIP_FILES[@]}"; do
        [ "$entry" = "$f" ] && skip=1
    done
    for d in "${SKIP_CONTENT_DIRS[@]}"; do
        if [ "$entry" = "$d" ]; then
            skip=1
            [ -d "$entry" ] && [ ! -L "$entry" ] && EMPTY_DIRS+=("$entry")
        fi
    done
    [ "$skip" -eq 1 ] && continue
    TARGETS+=("$entry")
done

echo "[1/3] Archiving rootfs (preserving unix permissions & symlinks)..."
rm -f "$TEMP_ZIP"

echo "      Calculating total files..."
FIND_PRUNE=()
for d in "${SKIP_CONTENT_DIRS[@]}"; do
    FIND_PRUNE+=(-path "./$d/*" -prune -o)
done
TOTAL_FILES=$( { find . "${FIND_PRUNE[@]}" \
    \( ! -path "." \
       -a ! -name "*.sock" \
       -a ! -path "*/.cache/*" \
       -a ! -path "./root/.ssh/*" \
       -a ! -path "./etc/ssh/ssh_host_*" \
       -a ! -path "./root/.gemini/jetski-standalone-oauth-token*" \
       -a ! -path "./root/.gemini/*oauth*" \
       -a ! -path "./root/.gemini/*token*" \
       -a ! -path "./root/.bash_history" \
       -a ! -path "./root/.zsh_history" \
       -a ! -path "./root/.antigem_bridge/*token*" \
       -a ! -path "./support/*" \
       -a ! -path "./var/cache/apt/*.bin" \
       -a ! -path "./var/lib/apt/lists/*_*" \
       -a ! -path "./var/cache/apt/archives/*.deb" \
       -a ! -name ".proot.l2s.*" \
       -a ! -path "./plat_property_contexts" \
       -a ! -path "./property_contexts" \
    \) -print 2>/dev/null || true; } | wc -l)
TOTAL_FILES=$((TOTAL_FILES + 0))
echo "      Total entries to package: $TOTAL_FILES"

{ zip -r -y "$TEMP_ZIP" "${TARGETS[@]}" -x "${EXCLUDE_PATTERNS[@]}" 2>/dev/null || true; } | awk -v total="$TOTAL_FILES" '
BEGIN {
    cols = 12;
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

if [ "${#EMPTY_DIRS[@]}" -gt 0 ]; then
    zip -y "$TEMP_ZIP" "${EMPTY_DIRS[@]}" >/dev/null
fi

if [ ! -s "$TEMP_ZIP" ]; then
    echo -e "\n\033[1;31m[ERROR]\033[0m zip produced no archive." >&2
    exit 1
fi

echo "[2/3] Exporting archive to Antigem backups storage..."
mkdir -p "$(dirname "$OUTPUT_ZIP")"
cp "$TEMP_ZIP" "$OUTPUT_ZIP"
rm -f "$TEMP_ZIP"

echo "[3/3] Done! Ubuntu rootfs archive ready:"
echo "      ✅ Saved to: $OUTPUT_ZIP"
echo "      ℹ️  APT package lists are not included: run 'apt update' once after restoring."
