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
# antiGem IDE & AGY Projects Backup Packager
# ==============================================================================
set -euo pipefail

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
TMP_ROOT="${TMPDIR:-${PREFIX_DIR}/tmp}"
mkdir -p "$TMP_ROOT"

BACKUP_DIR="/sdcard/Download/Antigem/backups"
TIMESTAMP="$(date +"%Y-%m-%d-%H-%M-%S")"
ZIP_BASENAME="projects_backup_${TIMESTAMP}.zip"
OUTPUT_ZIP="${1:-${BACKUP_DIR}/${ZIP_BASENAME}}"
if [ -z "$OUTPUT_ZIP" ]; then
    OUTPUT_ZIP="${BACKUP_DIR}/${ZIP_BASENAME}"
fi
BACKUP_MODE="${2:-git}" # "git" (exclude ignored/node_modules) or "full" (raw exact archive)
shift 2 || true

echo "================================================"
echo "       antiGem Projects Backup Packager         "
echo "================================================"
echo "Output Archive : $OUTPUT_ZIP"
echo "Backup Mode    : $BACKUP_MODE (git-aware / full)"
echo "Temp Dir       : $TMP_ROOT"
echo "Timestamp      : $TIMESTAMP"
echo "================================================"

RAW_PATHS=()

# Collect project paths from remaining arguments or file
if [ $# -ge 2 ] && [ "$1" = "--paths-file" ]; then
    PATHS_FILE="$2"
    if [ -f "$PATHS_FILE" ]; then
        while IFS= read -r line || [ -n "$line" ]; do
            line="$(echo "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
            if [ -n "$line" ]; then
                RAW_PATHS+=("$line")
            fi
        done < "$PATHS_FILE"
    fi
else
    for arg in "$@"; do
        if [ -n "$arg" ]; then
            RAW_PATHS+=("$arg")
        fi
    done
fi

STAGE_DIR="${TMP_ROOT}/antigem_project_stage_${TIMESTAMP}_$$"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/projects"

# Use Python helper to normalize paths, filter out $HOME/root, detect hierarchies, and build manifest
MANIFEST_FILE="$STAGE_DIR/antigem_project_manifest.json"

python3 - <<PYEOF
import os
import sys
import json
import shutil
import subprocess

raw_paths = ${RAW_PATHS[@]+$(printf '%s\n' "${RAW_PATHS[@]}" | python3 -c 'import sys, json; print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))')}
home_dir = os.path.abspath("$HOME_DIR")
stage_dir = "$STAGE_DIR"
backup_mode = "$BACKUP_MODE"
manifest_file = "$MANIFEST_FILE"
timestamp = "$TIMESTAMP"

# 1. Normalize and filter paths (Exclude $HOME, /, /data/data/com.termux/files, empty)
valid_paths = set()
for p in raw_paths:
    p = os.path.expanduser(p).strip()
    if not p:
        continue
    abs_p = os.path.abspath(p)
    # Exclude root directory and $HOME itself
    if abs_p in ("/", home_dir, os.path.dirname(home_dir)):
        print(f"⚠️ Skipping root/home path '{abs_p}' (Project backups must be specific project folders).")
        continue
    if os.path.isdir(abs_p):
        valid_paths.add(abs_p)

if not valid_paths:
    print("❌ Error: No valid project directories selected for backup.")
    sys.exit(1)

# Sort paths by length so ancestors come first
sorted_paths = sorted(list(valid_paths), key=lambda x: (len(x.split(os.sep)), x))

# Determine roots vs nested paths
packaged_roots = [] # List of (root_path, safe_subdir)
manifest_entries = []

for idx, p in enumerate(sorted_paths, 1):
    proj_name = os.path.basename(p.rstrip(os.sep)) or f"project_{idx}"

    # Check if p is inside an already packaged root
    parent_root = None
    for r_path, r_subdir in packaged_roots:
        if p.startswith(r_path + os.sep):
            parent_root = (r_path, r_subdir)
            break

    if parent_root:
        # Child project is already inside parent_root
        r_path, r_subdir = parent_root
        rel_subpath = os.path.relpath(p, r_path)
        backup_subpath = os.path.join("projects", r_subdir, rel_subpath)
        print(f"  • Project '{proj_name}' ({p}) is inside already selected parent '{os.path.basename(r_path)}'.")
        print(f"    -> Referencing within parent archive at '{backup_subpath}' without duplicate copy.")
        manifest_entries.append({
            "name": proj_name,
            "original_path": p,
            "backup_subpath": backup_subpath,
            "parent_root": r_path
        })
    else:
        # Independent root project
        safe_subdir = f"proj_{len(packaged_roots) + 1}_{proj_name}"
        target_stage_proj = os.path.join(stage_dir, "projects", safe_subdir)
        os.makedirs(target_stage_proj, exist_ok=True)
        packaged_roots.append((p, safe_subdir))

        print(f"  • Packaging independent project [{len(packaged_roots)}] '{proj_name}' from '{p}'...")

        if backup_mode == "git" and os.path.isdir(os.path.join(p, ".git")):
            print("      Using git-aware packaging (excluding gitignore & untracked ignored items)...")
            try:
                # Use git ls-files to copy only tracked + non-ignored files
                git_proc = subprocess.Popen(
                    ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                    cwd=p,
                    stdout=subprocess.PIPE
                )
                tar_create = subprocess.Popen(
                    ["tar", "--null", "-T", "-", "-cf", "-"],
                    cwd=p,
                    stdin=git_proc.stdout,
                    stdout=subprocess.PIPE
                )
                tar_extract = subprocess.Popen(
                    ["tar", "-xf", "-"],
                    cwd=target_stage_proj,
                    stdin=tar_create.stdout
                )
                git_proc.stdout.close()
                tar_create.stdout.close()
                tar_extract.communicate()
            except Exception as e:
                print(f"      Git archive pipe fallback: {e}")
                # Fallback copy
                shutil.copytree(p, target_stage_proj, symlinks=True, dirs_exist_ok=True)

            # Copy essential .git config, HEAD, and refs metadata
            git_dir = os.path.join(p, ".git")
            stage_git_dir = os.path.join(target_stage_proj, ".git")
            os.makedirs(stage_git_dir, exist_ok=True)
            for meta_f in ("config", "HEAD", "description"):
                src_f = os.path.join(git_dir, meta_f)
                if os.path.isfile(src_f):
                    shutil.copy2(src_f, stage_git_dir)
            refs_dir = os.path.join(git_dir, "refs")
            if os.path.isdir(refs_dir):
                shutil.copytree(refs_dir, os.path.join(stage_git_dir, "refs"), symlinks=True, dirs_exist_ok=True)
        elif backup_mode == "git":
            print("      Non-git directory: copying with exclusion of node_modules and build caches...")
            def ignore_patterns(src, names):
                ignored = set()
                for name in names:
                    if name in ("node_modules", ".venv", "venv", "__pycache__", ".next", ".turbo", ".gradle", "target", "build", ".cache"):
                        ignored.add(name)
                    elif name.endswith((".sock", ".pyc", ".log")):
                        ignored.add(name)
                return ignored
            shutil.copytree(p, target_stage_proj, symlinks=True, ignore=ignore_patterns, dirs_exist_ok=True)
        else:
            print("      Full raw archive: copying all files, symlinks and permissions...")
            shutil.copytree(p, target_stage_proj, symlinks=True, dirs_exist_ok=True)

        manifest_entries.append({
            "name": proj_name,
            "original_path": p,
            "backup_subpath": os.path.join("projects", safe_subdir)
        })

manifest = {
    "version": 1,
    "timestamp": timestamp,
    "backup_mode": backup_mode,
    "total_projects": len(manifest_entries),
    "projects": manifest_entries
}

with open(manifest_file, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2)

print(f"\n✅ Successfully prepared {len(manifest_entries)} projects for packaging.")
PYEOF

echo ""
echo "[2/3] Compressing into zip archive..."
TEMP_ZIP="${TMP_ROOT}/antigem_projects_bundle_${TIMESTAMP}_$$.zip"
rm -f "$TEMP_ZIP"

TOTAL_ITEMS=$( { find "$STAGE_DIR" 2>/dev/null || true; } | wc -l)
TOTAL_ITEMS=$((TOTAL_ITEMS + 0))

(
    cd "$STAGE_DIR"
    ZIP_CMD=(zip -r -y "$TEMP_ZIP" .)
    { "${ZIP_CMD[@]}" 2>/dev/null || true; } | awk -v total="$TOTAL_ITEMS" '
    BEGIN {
        cols = 12;
        count = 0;
    }
    /^  adding: / {
        count++;
        pct = (total > 0) ? int(count * 100 / total) : 0;
        if (pct > 100) pct = 100;
        if (count % 20 == 0 || count == total || pct == 100) {
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
)

echo "[3/3] Saving final archive to storage..."
mkdir -p "$(dirname "$OUTPUT_ZIP")"
cp "$TEMP_ZIP" "$OUTPUT_ZIP"
rm -f "$TEMP_ZIP"
rm -rf "$STAGE_DIR"

ARCHIVE_SIZE=$(du -h "$OUTPUT_ZIP" 2>/dev/null | cut -f1 || echo "unknown")
echo ""
echo "🎉 Projects Backup Complete!"
echo "      ✅ Archive Size : $ARCHIVE_SIZE"
echo "      ✅ Saved to     : $OUTPUT_ZIP"
echo "STATUS_SUCCESS"
