//go:build android && arm64

package dsh

import (
	"bytes"
	"embed"
	"os"
	"path/filepath"
)

// The official Node.js linux-arm64 build and node-pty's prebuilt addon need libstdc++, libgcc_s and libutil, which
// the app's minimal glibc runtime ($PREFIX/glibc/lib) does not ship. They are written next to it (into our own
// folder, so a running glibc process is never affected) and added to the loader's --library-path.
//
//go:embed glibclibs/*.so.*
var glibcLibs embed.FS

// writeGlibcLibs puts the embedded libraries into dir (only the ones missing or different).
func writeGlibcLibs(dir string) error {
	entries, err := glibcLibs.ReadDir("glibclibs")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	for _, e := range entries {
		data, err := glibcLibs.ReadFile("glibclibs/" + e.Name())
		if err != nil {
			return err
		}
		target := filepath.Join(dir, e.Name())
		if cur, err := os.ReadFile(target); err == nil && bytes.Equal(cur, data) {
			continue
		}
		tmp := target + ".tmp"
		if err := os.WriteFile(tmp, data, 0o755); err != nil {
			return err
		}
		if err := os.Rename(tmp, target); err != nil {
			return err
		}
	}
	return nil
}

// needsGlibc: Node runs through the app's glibc loader.
const needsGlibc = true
