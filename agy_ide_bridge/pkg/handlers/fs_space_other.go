//go:build !linux && !android && !darwin && !freebsd

package handlers

import "os"

func fsDiskSpace(path string) (total, free uint64) { return 0, 0 }

func fsWritable(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.Mode().Perm()&0200 != 0
}
