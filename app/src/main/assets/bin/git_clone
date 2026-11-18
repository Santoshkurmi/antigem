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
# antiGem Git Repository Cloner
# Usage: git_clone <public https url> [folder name]
# Clones into $HOME/<folder name>; the name defaults to the repository's name.
# ==============================================================================
set -uo pipefail

trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

HOME_DIR="${HOME:-/data/data/ai.antigem/files/home}"
REPO_URL="$(trim "${1:-}")"
FOLDER_NAME="$(trim "${2:-}")"

echo "================================================"
echo "        antiGem Git Repository Cloner           "
echo "================================================"

if [ -z "$REPO_URL" ]; then
    echo "❌ Error: No repository URL given."
    exit 1
fi
case "$REPO_URL" in
    https://*) ;;
    *)
        echo "❌ Error: Only public https:// URLs are supported (got: $REPO_URL)"
        exit 1
        ;;
esac

if [ -z "$FOLDER_NAME" ]; then
    FOLDER_NAME="$(basename "${REPO_URL%/}")"
    FOLDER_NAME="${FOLDER_NAME%.git}"
fi
if [ -z "$FOLDER_NAME" ] || [ "$FOLDER_NAME" = "." ] || [ "$FOLDER_NAME" = ".." ] || ! [[ "$FOLDER_NAME" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "❌ Error: Invalid project name '$FOLDER_NAME' (use letters, numbers, '.', '_' or '-')."
    exit 1
fi

TARGET_DIR="${HOME_DIR}/${FOLDER_NAME}"
echo "Repository : $REPO_URL"
echo "Target     : $TARGET_DIR"
echo "================================================"

if ! command -v git >/dev/null 2>&1; then
    echo "❌ Error: git is not installed in this environment."
    exit 1
fi

if [ -e "$TARGET_DIR" ]; then
    if [ ! -d "$TARGET_DIR" ]; then
        echo "❌ Error: '$TARGET_DIR' already exists and is not a folder."
        exit 1
    fi
    if [ -n "$(ls -A "$TARGET_DIR" 2>/dev/null)" ]; then
        echo "❌ Error: Folder '$TARGET_DIR' already exists and is not empty. Choose another project name."
        exit 1
    fi
    echo "⚠️ Folder '$TARGET_DIR' exists but is empty, removing it before cloning."
    rmdir "$TARGET_DIR" || { echo "❌ Error: Could not remove empty folder '$TARGET_DIR'."; exit 1; }
fi

# public repos only: fail instead of waiting for a username/password prompt
export GIT_TERMINAL_PROMPT=0

echo "[git] git clone --progress $REPO_URL $TARGET_DIR"
# git draws progress with carriage returns; split them into lines for the log
git clone --progress "$REPO_URL" "$TARGET_DIR" 2>&1 | tr '\r' '\n'
STATUS=$?

if [ $STATUS -ne 0 ]; then
    echo "❌ Error: git clone failed (exit $STATUS)."
    exit $STATUS
fi

echo "✅ Cloned into $TARGET_DIR"
echo "ANTIGEM_CLONED_PATH=$TARGET_DIR"
