package hub

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// Android x86_64 (emulators, Intel/AMD Chromebooks): Google publishes agy for Android on arm64 only (its installer asks
// for android_amd64, which does not exist), so the bridge installs agy's Linux x64 build and runs it the way the app
// runs Claude Code: through the app's glibc loader, with the app's libantigem_sigsys.so for the syscalls Android's
// x86_64 seccomp filter blocks. The agy path the app uses (~/.local/bin/agy) is a small launcher; agy itself is
// agy.va39 next to it, the file ResolvePatchTarget patches.

// UsesAgyLinuxBuild reports whether agy is the Linux build behind a launcher here (Android x86_64).
func UsesAgyLinuxBuild() bool { return runtime.GOOS == "android" && runtime.GOARCH == "amd64" }

// AgyLinuxBinary is agy itself next to the launcher at path.
func AgyLinuxBinary(path string) string { return filepath.Join(filepath.Dir(path), "agy.va39") }

// agy's own updater is off in the launcher: run under the loader it would take the loader for itself and replace it.
// `agy update` installs the newest Linux x64 build instead (Google's linux_amd64 manifest, checked against its sha512).
const agyLauncherScript = `#!{{PREFIX}}/bin/sh
# agy launcher written by the AntiGem bridge (it rewrites this file: edits are lost).
# Google publishes no Android x86_64 build of agy, so this runs its Linux x64 build, agy.va39 next to this file,
# through the app's glibc loader with libantigem_sigsys.so, which rewrites the syscalls Android blocks on x86_64.
# agy's own updater stays off (run under the loader it would replace the loader): ` + "`agy update`" + ` installs the
# newest Linux x64 build here instead.
BIN="{{DIR}}/agy.va39"
GLIBC_LIB="{{PREFIX}}/glibc/lib"
if [ "$1" = update ]; then
    BASE=https://antigravity-cli-auto-updater-974169037036.us-central1.run.app
    manifest=$(curl -fsSL "$BASE/manifests/linux_amd64.json") || { echo "Could not reach agy's release server." >&2; exit 1; }
    field() { printf '%s' "$manifest" | tr -d '\n' | sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'; }
    version=$(field version); url=$(field url); sha512=$(field sha512)
    if [ -z "$url" ] || [ -z "$sha512" ]; then echo "agy's release manifest could not be read." >&2; exit 1; fi
    if [ -f "$BIN" ] && [ "$("$0" --version 2>/dev/null | head -n 1)" = "$version" ]; then echo "agy $version is the latest version."; exit 0; fi
    tmp=$(mktemp -d "${TMPDIR:-{{PREFIX}}/tmp}/agy-update.XXXXXX") || exit 1
    trap 'rm -rf "$tmp"' EXIT
    echo "Downloading agy $version (Linux x64 build)…"
    curl -fL --progress-bar -o "$tmp/agy.tar.gz" "$url" || { echo "The download failed." >&2; exit 1; }
    if [ "$(sha512sum "$tmp/agy.tar.gz" | cut -d' ' -f1)" != "$sha512" ]; then echo "Checksum mismatch: the download is damaged." >&2; exit 1; fi
    tar -xzf "$tmp/agy.tar.gz" -C "$tmp" antigravity || exit 1
    chmod 755 "$tmp/antigravity" && mv -f "$tmp/antigravity" "$BIN" || exit 1
    echo "Installed agy $version."
    exit 0
fi
if [ ! -f "$BIN" ]; then
    echo "agy is not installed ($BIN is missing): install it in AntiGem (Settings > Antigravity > Account & CLI)." >&2
    exit 127
fi
unset LD_PRELOAD
export LD_AUDIT="$GLIBC_LIB/libantigem_sigsys.so" AGY_CLI_DISABLE_AUTO_UPDATE=1
exec "$GLIBC_LIB/ld-linux-x86-64.so.2" --library-path "$GLIBC_LIB" "$BIN" "$@"
`

// EnsureAgyLauncher writes the launcher at path when it is missing or outdated. A file there that is not a script
// (an agy installed some other way) is left alone.
func EnsureAgyLauncher(path string) error {
	prefix := os.Getenv("PREFIX")
	if prefix == "" {
		home, _ := os.UserHomeDir()
		prefix = filepath.Join(filepath.Dir(home), "usr")
	}
	script := strings.NewReplacer("{{PREFIX}}", prefix, "{{DIR}}", filepath.Dir(path)).Replace(agyLauncherScript)
	if old, err := os.ReadFile(path); err == nil {
		if string(old) == script {
			return nil
		}
		if !strings.HasPrefix(string(old), "#!") {
			return fmt.Errorf("%s is not AntiGem's agy launcher; move it away to install agy here", path)
		}
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(script), 0o755); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
