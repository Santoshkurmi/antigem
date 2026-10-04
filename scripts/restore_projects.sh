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
# antiGem IDE & AGY Projects Restorer
# ==============================================================================
set -euo pipefail

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
TMP_ROOT="${TMPDIR:-${PREFIX_DIR}/tmp}"
mkdir -p "$TMP_ROOT"

TIMESTAMP="$(date +"%Y-%m-%d-%H-%M-%S")"
INPUT_ZIP="${1:-}"
CONFLICT_STRATEGY="${2:-bak}" # "bak" (move existing to .bak.<timestamp>) or "overwrite"
CUSTOM_RESTORE_DIR="${3:-}"

echo "================================================"
echo "       antiGem Projects Archive Restorer        "
echo "================================================"
echo "Input Archive     : ${INPUT_ZIP:-<none>}"
echo "Conflict Strategy : $CONFLICT_STRATEGY (bak / overwrite)"
echo "Temp Dir          : $TMP_ROOT"
if [ -n "$CUSTOM_RESTORE_DIR" ]; then
    echo "Custom Root Dir   : $CUSTOM_RESTORE_DIR"
fi
echo "================================================"

if [ -z "$INPUT_ZIP" ]; then
    echo "❌ Error: No backup archive specified."
    echo "Usage: restore_projects <path-to-zip> [bak|overwrite] [custom_restore_dir]"
    exit 1
fi

if [ ! -f "$INPUT_ZIP" ]; then
    echo "❌ Error: Backup archive not found at '$INPUT_ZIP'!"
    exit 1
fi

STAGE_DIR="${TMP_ROOT}/antigem_restore_stage_${TIMESTAMP}_$$"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

# 1. Pre-validation: Test archive integrity
echo "[1/4] Verifying archive integrity..."
if ! unzip -t -q "$INPUT_ZIP" >/dev/null 2>&1; then
    echo "❌ Error: Invalid or corrupted zip archive!"
    rm -rf "$STAGE_DIR"
    exit 1
fi
echo "      ✅ Archive integrity verified."

# 2. Extract manifest
echo "[2/4] Reading project manifest..."
unzip -q "$INPUT_ZIP" "antigem_project_manifest.json" -d "$STAGE_DIR" 2>/dev/null || true
MANIFEST_FILE="$STAGE_DIR/antigem_project_manifest.json"

if [ ! -f "$MANIFEST_FILE" ]; then
    echo "⚠️ Warning: 'antigem_project_manifest.json' not found in archive. Attempting direct folder extraction..."
    # Fallback for standard zip archive without manifest
    TARGET_DIR="${CUSTOM_RESTORE_DIR:-${HOME_DIR}/restored_project_${TIMESTAMP}}"
    if [ -d "$TARGET_DIR" ] && [ "$CONFLICT_STRATEGY" = "bak" ]; then
        mv "$TARGET_DIR" "${TARGET_DIR}.bak.${TIMESTAMP}"
    fi
    mkdir -p "$TARGET_DIR"
    unzip -o -q "$INPUT_ZIP" -d "$TARGET_DIR"
    echo "RESTORED_PROJECT:${TARGET_DIR}:$(basename "$TARGET_DIR")"
    echo "🎉 Direct Extraction Complete to $TARGET_DIR"
    rm -rf "$STAGE_DIR"
    exit 0
fi

# 3. Extract entire archive to stage directory for controlled placement
echo "[3/4] Extracting project archive contents..."
unzip -o -q "$INPUT_ZIP" -d "$STAGE_DIR"

# 4. Restore each project to its designated directory
echo "[4/4] Restoring project workspaces..."

# Run python parser
python3 - <<PYEOF
import json
import os
import shutil
import sys

stage_dir = "$STAGE_DIR"
manifest_path = "$MANIFEST_FILE"
conflict_strategy = "$CONFLICT_STRATEGY"
custom_root = "$CUSTOM_RESTORE_DIR"
timestamp = "$TIMESTAMP"

try:
    with open(manifest_path, 'r', encoding='utf-8') as f:
        data = json.load(f)
except Exception as e:
    print(f"❌ Failed to parse manifest: {e}")
    sys.exit(1)

projects = data.get("projects", [])
if not projects:
    print("❌ No projects listed in manifest!")
    sys.exit(1)

# Sort projects so parents are restored before children
sorted_projects = sorted(projects, key=lambda x: (len(x.get("original_path", "").split(os.sep)), x.get("name", "")))
restored_roots = []

for idx, proj in enumerate(sorted_projects, 1):
    name = proj.get("name", f"project_{idx}")
    orig_path = proj.get("original_path", "")
    subpath = proj.get("backup_subpath", "")

    # Determine destination path
    if custom_root:
        dest_path = os.path.join(custom_root, name)
    elif orig_path:
        dest_path = orig_path
    else:
        dest_path = os.path.expanduser(f"~/{name}")

    dest_path = os.path.abspath(dest_path)

    # Check if dest_path is already inside an already restored parent
    is_under_restored_parent = False
    for parent_p in restored_roots:
        if dest_path.startswith(parent_p + os.sep):
            is_under_restored_parent = True
            break

    if is_under_restored_parent:
        print(f"  • [{idx}/{len(sorted_projects)}] '{name}' is inside restored parent workspace -> Registered at '{dest_path}'")
        print(f"RESTORED_PROJECT:{dest_path}:{name}")
        continue

    source_path = os.path.join(stage_dir, subpath)
    if not os.path.exists(source_path):
        print(f"⚠️ Source directory for {name} missing in archive: {source_path}")
        continue

    print(f"  • Restoring [{idx}/{len(sorted_projects)}] '{name}' -> '{dest_path}'")

    if os.path.exists(dest_path):
        if conflict_strategy == "bak":
            bak_path = f"{dest_path}.bak.{timestamp}"
            print(f"    📦 Existing directory backed up to: {bak_path}")
            shutil.move(dest_path, bak_path)
            os.makedirs(dest_path, exist_ok=True)
            for item in os.listdir(source_path):
                s = os.path.join(source_path, item)
                d = os.path.join(dest_path, item)
                if os.path.isdir(s):
                    shutil.copytree(s, d, symlinks=True, dirs_exist_ok=True)
                else:
                    shutil.copy2(s, d)
        else:
            print(f"    ⚡ Overwriting files in existing directory: {dest_path}")
            os.makedirs(dest_path, exist_ok=True)
            for item in os.listdir(source_path):
                s = os.path.join(source_path, item)
                d = os.path.join(dest_path, item)
                if os.path.isdir(s):
                    shutil.copytree(s, d, symlinks=True, dirs_exist_ok=True)
                else:
                    shutil.copy2(s, d)
    else:
        os.makedirs(os.path.dirname(dest_path), exist_ok=True)
        shutil.copytree(source_path, dest_path, symlinks=True, dirs_exist_ok=True)

    restored_roots.append(dest_path)
    print(f"    ✅ Successfully restored '{name}'")
    print(f"RESTORED_PROJECT:{dest_path}:{name}")

PYEOF

# Cleanup staging directory
rm -rf "$STAGE_DIR"

echo ""
echo "🎉 All projects restored successfully!"
echo "STATUS_SUCCESS"
