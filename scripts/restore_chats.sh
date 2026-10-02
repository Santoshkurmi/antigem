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
# AGY Chats & Credentials Restore
# ==============================================================================
set -euo pipefail

INPUT_ZIP="${1:-}"
PASSWORD="${2:-}"
TIMESTAMP="$(date +"%Y-%m-%d-%H-%M-%S")"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
GEMINI_DIR="${HOME_DIR}/.gemini"
GEMINI_BAK="${HOME_DIR}/.gemini.bak.${TIMESTAMP}"

echo "================================================"
echo "      AGY Chats & Credentials Restorer          "
echo "================================================"
echo "Input file : ${INPUT_ZIP:-<none>}"
echo "Target dir : $GEMINI_DIR"
echo "Backup dir : $GEMINI_BAK"
echo "================================================"

if [ -z "$INPUT_ZIP" ]; then
    echo "❌ Error: No backup archive specified."
    echo "Usage: restore_chats <path-to-zip> [password]"
    exit 1
fi

if [ ! -f "$INPUT_ZIP" ]; then
    echo "❌ Error: Backup archive not found at '$INPUT_ZIP'!"
    exit 1
fi

# 1. Pre-validation: Test archive and password BEFORE touching files
echo "[1/5] Verifying backup archive and password..."
TEST_CMD=(unzip -t -q)
if [ -n "$PASSWORD" ]; then
    TEST_CMD+=(-P "$PASSWORD")
fi
TEST_CMD+=("$INPUT_ZIP")

if ! "${TEST_CMD[@]}" >/dev/null 2>&1; then
    if [ -z "$PASSWORD" ]; then
        echo "❌ Error: This backup archive is encrypted with a password. Please provide the decryption password."
    else
        echo "❌ Error: Incorrect password! Failed to decrypt backup archive."
    fi
    exit 1
fi
echo "      ✅ Archive and password verified successfully."

# 2. Backup existing .gemini directory to .gemini.bak
if [ -d "$GEMINI_DIR" ]; then
    echo "[2/5] Existing .gemini directory found."
    echo "      Renaming current .gemini -> .gemini.bak (safety backup)..."
    rm -rf "$GEMINI_BAK"
    mv "$GEMINI_DIR" "$GEMINI_BAK"
    echo "      ✅ Existing config safely backed up to $GEMINI_BAK"
else
    echo "[2/5] No existing .gemini directory found (clean restore)."
fi

cd "$HOME_DIR"

# 3. Extract the archive
echo "[3/5] Extracting archive..."
UNZIP_CMD=(unzip -o -q)
if [ -n "$PASSWORD" ]; then
    UNZIP_CMD+=(-P "$PASSWORD")
fi
UNZIP_CMD+=("$INPUT_ZIP" -d "$HOME_DIR")

if ! "${UNZIP_CMD[@]}"; then
    echo "❌ Failed to extract archive!"
    if [ -d "$GEMINI_BAK" ] && [ ! -d "$GEMINI_DIR" ]; then
        echo "      Rolling back: Restoring previous .gemini from .gemini.bak..."
        mv "$GEMINI_BAK" "$GEMINI_DIR"
    fi
    exit 1
fi

# 4. Handle structure (if archive was zipped inside a subfolder or root)
if [ ! -d "$GEMINI_DIR" ]; then
    # If unzipped into current dir without .gemini prefix, check if files are in a subdir
    POSSIBLE_SUBDIR=$(find "$HOME_DIR" -maxdepth 2 -name "conversations" -o -name "conversation_summaries.db" 2>/dev/null | head -n 1)
    if [ -n "$POSSIBLE_SUBDIR" ]; then
        PARENT_DIR=$(dirname "$POSSIBLE_SUBDIR")
        if [ "$PARENT_DIR" != "$GEMINI_DIR" ] && [ -d "$PARENT_DIR" ]; then
            mv "$PARENT_DIR" "$GEMINI_DIR"
        fi
    fi
fi

# 5. Fix permissions
echo "[4/5] Securing restored file permissions..."
if [ -d "$GEMINI_DIR" ]; then
    chmod 700 "$GEMINI_DIR" 2>/dev/null || true
    chmod -R 700 "$GEMINI_DIR/antigravity-cli" 2>/dev/null || true
    chmod -R 700 "$GEMINI_DIR/antigravity" 2>/dev/null || true
    chmod -R 700 "$GEMINI_DIR/config" 2>/dev/null || true
    chmod 600 "$GEMINI_DIR"/*token* 2>/dev/null || true
    chmod 600 "$GEMINI_DIR"/installation_id 2>/dev/null || true
fi

# 6. Verification
echo "[5/5] Verifying restored conversations..."
CONV_COUNT=0
if [ -d "$GEMINI_DIR/antigravity-cli/conversations" ]; then
    CONV_COUNT=$(find "$GEMINI_DIR/antigravity-cli/conversations" -name "*.db" 2>/dev/null | wc -l)
elif [ -d "$GEMINI_DIR/antigravity/conversations" ]; then
    CONV_COUNT=$(find "$GEMINI_DIR/antigravity/conversations" -name "*.db" 2>/dev/null | wc -l)
fi

echo "================================================"
echo "🎉 Successfully restored $CONV_COUNT conversations into ~/.gemini!"
if [ -d "$GEMINI_BAK" ]; then
    echo "ℹ️  Previous state preserved at: ~/.gemini.bak"
fi
echo "================================================"
