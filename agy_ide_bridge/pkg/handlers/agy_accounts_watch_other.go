//go:build !linux

package handlers

// agy keeps its login in the system keychain on macOS and Windows, so there is no file to watch there.

func (h *Handler) EnsureAgyAccountWatcher() {}

func (h *Handler) StopAgyAccountWatcher() {}
