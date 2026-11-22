// Package buildinfo holds the version of this bridge.
//
// The version is the version of the AntiGem app it was built with. It comes from version.json (the app's own
// version file): the app's Gradle build copies /version.json into this folder on every build, so the two cannot
// drift apart, and `go build` needs no extra flags. When the file has a beta newer than its stable release, the
// bridge is the beta's version, like the app (app/build.gradle.kts: the newer version_code wins). A build can still
// override it with -ldflags "-X gemini-server/pkg/buildinfo.Override=1.2.3".
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

// Version returns the bridge version, e.g. "1.0.19" or "2.0.4-beta.1" ("unknown" if the version file is unreadable).
func Version() string {
	if v := strings.TrimSpace(Override); v != "" {
		return v
	}
	var f struct {
		VersionCode int    `json:"version_code"`
		VersionName string `json:"version_name"`
		Beta        *struct {
			VersionCode int    `json:"version_code"`
			VersionName string `json:"version_name"`
		} `json:"beta"`
	}
	if err := json.Unmarshal(versionJSON, &f); err != nil {
		return "unknown"
	}
	name := strings.TrimSpace(f.VersionName)
	if f.Beta != nil && f.Beta.VersionCode > f.VersionCode && strings.TrimSpace(f.Beta.VersionName) != "" {
		name = strings.TrimSpace(f.Beta.VersionName)
	}
	if name == "" {
		return "unknown"
	}
	return name
}
