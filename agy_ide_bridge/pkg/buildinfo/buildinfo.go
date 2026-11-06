// Package buildinfo holds the version of this bridge.
//
// The version is the version of the AntiGem app it was built with. It comes from version.json (the app's own
// version file): the app's Gradle build copies /version.json into this folder on every build, so the two cannot
// drift apart, and `go build` needs no extra flags. A build can still override it with
// -ldflags "-X gemini-server/pkg/buildinfo.Override=1.2.3".
package buildinfo

import (
	_ "embed"
	"encoding/json"
	"strings"
)

//go:embed version.json
var versionJSON []byte

// Override is set at link time (optional).
var Override string

// Version returns the bridge version, e.g. "1.0.19" ("unknown" if the version file is unreadable).
func Version() string {
	if v := strings.TrimSpace(Override); v != "" {
		return v
	}
	var f struct {
		VersionName string `json:"version_name"`
	}
	if err := json.Unmarshal(versionJSON, &f); err != nil || strings.TrimSpace(f.VersionName) == "" {
		return "unknown"
	}
	return strings.TrimSpace(f.VersionName)
}
