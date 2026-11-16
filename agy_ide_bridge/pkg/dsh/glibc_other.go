//go:build !(android && arm64)

package dsh

// writeGlibcLibs: only the Android/arm64 build runs Node through the app's glibc loader.
func writeGlibcLibs(dir string) error { return nil }

// needsGlibc: everywhere else the official Node.js build runs as it is.
const needsGlibc = false
