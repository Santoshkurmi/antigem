//go:build linux || android || darwin || freebsd

package handlers

import "syscall"

// fsDiskSpace reports total and free bytes of the file system holding path.
func fsDiskSpace(path string) (total, free uint64) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, 0
	}
	bs := uint64(st.Bsize)
	return uint64(st.Blocks) * bs, uint64(st.Bavail) * bs
}

func fsWritable(path string) bool { return syscall.Access(path, 2) == nil }
