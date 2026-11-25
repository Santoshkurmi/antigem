#!/data/data/ai.antigem/files/usr/bin/bash
# If invoked by a different shell or missing shebang interpreter, auto-detect and switch to bash
if [ -z "${BASH_VERSION:-}" ]; then
    if [ -n "${PREFIX:-}" ] && [ -x "$PREFIX/bin/bash" ]; then
        exec "$PREFIX/bin/bash" "$0" "$@"
    elif [ -x "/data/data/ai.antigem/files/usr/bin/bash" ]; then
        exec "/data/data/ai.antigem/files/usr/bin/bash" "$0" "$@"
    elif command -v bash >/dev/null 2>&1; then
        exec bash "$0" "$@"
    fi
fi

# ==============================================================================
# Termux Complete Bootstrap Packager (usr + home + dotfiles)
#
# Usage: backup_bootstrap [--include-secrets] [output.zip]
#   --include-secrets  also back up SSH keys, sign-ins (agent and GitHub logins) and shell history
#
# The zip is written as <output>.partial, tested entry by entry (unzip -t), and only then renamed to <output>:
# a file named *.zip in the backups folder is always a complete, readable backup. Restore it in AntiGem's
# installer ("From a ZIP file"); a backup made in the com.termux app is rewritten for ai.antigem there.
# Works in both apps: paths come from $PREFIX/$HOME, else from where this script is installed.
# ==============================================================================
set -euo pipefail
trap 'echo -e "\n\033[1;31m[ERROR]\033[0m Backup failed at line $LINENO: \x27$BASH_COMMAND\x27" >&2' ERR

SCRIPT_PREFIX="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd || true)"
PREFIX_DIR="${PREFIX:-$SCRIPT_PREFIX}"
FILES_DIR="$(dirname "$PREFIX_DIR")"
HOME_DIR="${HOME:-$FILES_DIR/home}"

INCLUDE_SECRETS=0
OUTPUT_ZIP=""
for arg in "$@"; do
    case "$arg" in
        --include-secrets) INCLUDE_SECRETS=1 ;;
        *) OUTPUT_ZIP="$arg" ;;
    esac
done

BACKUP_DIR="/sdcard/Download/Antigem/backups"
TIMESTAMP="$(date +"%Y-%m-%d-%H-%M-%S")"
OUTPUT_ZIP="${OUTPUT_ZIP:-${BACKUP_DIR}/bootrapz_${TIMESTAMP}.zip}"
PARTIAL_ZIP="${OUTPUT_ZIP}.partial"
ZIP_LOG="${FILES_DIR}/.backup_bootstrap.$$.log" # outside usr/ and home/, so not archived

echo "=== Termux Full Environment Packager ==="
echo "Files root : $FILES_DIR"
echo "Prefix     : $PREFIX_DIR"
echo "Home       : $HOME_DIR"
echo "Output     : $OUTPUT_ZIP"
echo "Timestamp  : $TIMESTAMP"
if [ "$INCLUDE_SECRETS" = 1 ]; then
    echo "Secrets    : INCLUDED (SSH keys, sign-ins, shell history)"
else
    echo "Secrets    : left out (SSH and GPG keys, agent and cloud logins, AntiGem's keys and tokens, agent MCP"
    echo "             configs, bridge logs, shell history). Make the backup with secrets to keep them."
fi
echo "========================================"

missing=""
command -v zip >/dev/null 2>&1 || missing="$missing zip"
command -v unzip >/dev/null 2>&1 || missing="$missing unzip"
if [ -n "$missing" ]; then
    echo -e "\n\033[1;31m[ERROR]\033[0m Needed to make and check the backup:$missing. Install with: pkg install$missing" >&2
    exit 1
fi

if [ ! -d "$FILES_DIR/usr" ] || [ ! -d "$FILES_DIR/home" ]; then
    echo -e "\n\033[1;31m[ERROR]\033[0m '$FILES_DIR' has no usr/ and home/ to back up." >&2
    exit 1
fi

# Until the backup is complete and checked, only the .partial file exists; it is removed on any failure
cleanup() {
    rm -f "$PARTIAL_ZIP" "$ZIP_LOG"
}
trap cleanup EXIT

cd "$FILES_DIR"

# Always left out: sockets, caches and temporary files
EXCLUDE_PATTERNS=(
    "*.sock"
    "*/.cache/*"
    "usr/tmp/*"
)
# Left out unless --include-secrets: whoever has them can sign in as the user, or reach this phone's tools.
# Patterns starting with */ also match the homes inside proot-distro rootfs (usr/var/lib/proot-distro/...).
if [ "$INCLUDE_SECRETS" != 1 ]; then
    EXCLUDE_PATTERNS+=(
        # logins and keys
        "*/.ssh/*"
        "usr/etc/ssh/ssh_host_*"
        "home/.gemini/jetski-standalone-oauth-token*"
        "home/.gemini/*oauth*"
        "home/.gemini/*token*"
        "*/.claude/.credentials.json"
        "*/.codex/auth.json"
        "*/.local/share/opencode/auth.json"
        "*/.local/share/opencode/mcp-auth.json"
        "*/.config/gh/hosts.yml"
        "*/.git-credentials"
        "*/.netrc"
        "*/.npmrc"
        "*/.pypirc"
        "*/.gnupg/*"
        "*/.docker/config.json"
        "*/.aws/*"
        "*/.config/gcloud/*"
        "*/.kube/config"
        # AntiGem's own keys: ADB key, bridge token, root-exec key, provider API keys
        "home/.android/adbkey"
        "home/.android/adbkey.pub"
        "home/.antigem_bridge/csrf_token.txt"
        "home/.antigem_bridge/phone_tools_key.txt"
        "home/.antigem_bridge/opencode/custom_providers.json"
        "home/.antigem_bridge/opencode/opencode.json"
        "home/.config/antigem/device-access"
        "home/.claude/agy_providers*.json"
        "home/.claude/agy_providers/*.settings.json"
        # agent configs with this phone's tools key (X-Device-Id) in their MCP entries
        "home/.claude.json"
        "home/.gemini/config/mcp_config.json"
        "home/.gemini/antigravity/mcp_config.json"
        "home/.codex/config.toml"
        # bridge logs print the bridge token and the secret header
        "home/logs/agy-ide-bridge_*.log"
        # shell and REPL history (commands often hold tokens)
        "*/.bash_history"
        "*/.zsh_history"
        "*/.python_history"
        "*/.node_repl_history"
        "*/.psql_history"
        "*/.mysql_history"
        "*/.sqlite_history"
        "*/.local/share/fish/fish_history"
    )
fi

echo "[1/4] Archiving usr/ and home/ (preserving unix permissions & symlinks)..."

# Count items for accurate 0-100% progress tracking (same exclusions as zip)
echo "      Calculating total files..."
FIND_EXCLUDES=()
for p in "${EXCLUDE_PATTERNS[@]}"; do FIND_EXCLUDES+=(! -path "$p"); done
TOTAL_FILES=$( { find usr home "${FIND_EXCLUDES[@]}" 2>/dev/null || true; } | wc -l)
TOTAL_FILES=$((TOTAL_FILES + 0))
echo "      Total entries to package: $TOTAL_FILES"

mkdir -p "$(dirname "$OUTPUT_ZIP")"
rm -f "$PARTIAL_ZIP"

# Run zip with a live progress bar; its exit status is checked below (warnings go to $ZIP_LOG).
# The ERR trap runs even without set -e, so it is off while zip runs.
trap - ERR
set +e
zip -r -y "$PARTIAL_ZIP" usr home -x "${EXCLUDE_PATTERNS[@]}" 2>"$ZIP_LOG" | awk -v total="$TOTAL_FILES" '
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
ZIP_STATUS=${PIPESTATUS[0]}
set -e
trap 'echo -e "\n\033[1;31m[ERROR]\033[0m Backup failed at line $LINENO: \x27$BASH_COMMAND\x27" >&2' ERR

# 0: done; 18: some files could not be read (the rest is archived): a warning, listed below
if [ "$ZIP_STATUS" != 0 ] && [ "$ZIP_STATUS" != 18 ]; then
    echo -e "\n\033[1;31m[ERROR]\033[0m zip failed (exit $ZIP_STATUS), no backup was saved:" >&2
    tail -n 20 "$ZIP_LOG" >&2 || true
    exit 1
fi
if [ "$ZIP_STATUS" = 18 ]; then
    echo -e "\033[1;33m[WARNING]\033[0m Some files could not be read and are not in the backup:"
    grep -i "could not open\|not found\|permission" "$ZIP_LOG" | head -n 20 || true
fi

echo "[2/4] Checking the archive (every file is read back and its checksum compared)..."
if ! unzip -tqq "$PARTIAL_ZIP" >"$ZIP_LOG" 2>&1; then
    echo -e "\n\033[1;31m[ERROR]\033[0m The new archive is damaged, no backup was saved:" >&2
    tail -n 20 "$ZIP_LOG" >&2 || true
    exit 1
fi
unzip -Z1 "$PARTIAL_ZIP" >"$ZIP_LOG" 2>/dev/null || true
if ! grep -qx -e "usr/bin/bash" -e "usr/bin/sh" "$ZIP_LOG"; then
    echo -e "\n\033[1;31m[ERROR]\033[0m The archive has no usr/bin/bash or usr/bin/sh: it could not be restored, no backup was saved." >&2
    exit 1
fi

echo "[3/4] Saving..."
sync "$PARTIAL_ZIP" 2>/dev/null || sync
mv -f "$PARTIAL_ZIP" "$OUTPUT_ZIP"
trap - EXIT
rm -f "$ZIP_LOG"

SIZE="$(du -h "$OUTPUT_ZIP" 2>/dev/null | cut -f1)"
echo "[4/4] Done! Backup checked and saved (${SIZE:-?}):"
echo "      ✅ $OUTPUT_ZIP"
if [ "$INCLUDE_SECRETS" = 1 ]; then
    echo -e "      \033[1;31m⚠ This backup contains your SSH keys and sign-ins. Never share it with anyone:\033[0m"
    echo -e "      \033[1;31m  whoever has it can sign in to your accounts and servers.\033[0m"
fi
