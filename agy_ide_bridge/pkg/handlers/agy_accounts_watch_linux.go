//go:build linux

package handlers

import (
	"bytes"
	"log"
	"os"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

// agyAccountDebounce: one check for a burst of events (agy and the mini app write, rename and chmod in a row).
const agyAccountDebounce = 700 * time.Millisecond

// agyWatcher: the running watcher, if any (one per bridge).
var agyWatcher struct {
	sync.Mutex
	stop func() // non-nil while running
	gen  int    // which start the running watcher belongs to
}

// EnsureAgyAccountWatcher starts the watcher unless it runs already (then it does nothing, so calling it on every
// "hub online" is fine). It is only called once agy's hub is online: agy is installed then and ~/.gemini exists, so
// nothing is watched on a phone without agy.
func (h *Handler) EnsureAgyAccountWatcher() {
	agyWatcher.Lock()
	defer agyWatcher.Unlock()
	if agyWatcher.stop != nil {
		return
	}
	agyWatcher.gen++
	agyWatcher.stop = h.startAgyAccountWatcher(agyWatcher.gen)
}

// StopAgyAccountWatcher stops the watcher (bridge shutdown).
func (h *Handler) StopAgyAccountWatcher() {
	agyWatcher.Lock()
	defer agyWatcher.Unlock()
	if agyWatcher.stop != nil {
		agyWatcher.stop()
		agyWatcher.stop = nil
	}
}

// startAgyAccountWatcher watches ~/.gemini (that one folder, not its subfolders: agy's logs and chats in
// antigravity-cli never wake it) and broadcasts agy_account_changed when the signed-in login changes.
//
// It costs nothing while idle: one goroutine parked in the runtime poller on an inotify descriptor (no polling,
// no ticking timers). Returns nil when it can't run (no ~/.gemini yet). If ~/.gemini is removed it stops itself;
// the next "hub online" starts it again.
func (h *Handler) startAgyAccountWatcher(gen int) (stop func()) {
	if !agyAccountsSupported() || h.Cfg == nil || h.Cfg.HomeDir == "" {
		return nil
	}
	fd, err := unix.InotifyInit1(unix.IN_CLOEXEC | unix.IN_NONBLOCK)
	if err != nil {
		log.Printf("agy accounts: watcher not started: %v", err)
		return nil
	}
	geminiWD, err := unix.InotifyAddWatch(fd, h.agyGeminiDir(), unix.IN_CLOSE_WRITE|unix.IN_MOVED_TO|unix.IN_MOVED_FROM|
		unix.IN_CREATE|unix.IN_DELETE|unix.IN_DELETE_SELF|unix.IN_MOVE_SELF|unix.IN_ONLYDIR)
	if err != nil {
		_ = unix.Close(fd)
		return nil // no ~/.gemini: nothing to watch until agy runs again
	}
	// non-blocking fd: os.NewFile makes it pollable, so Read parks in the runtime poller and Close wakes it
	file := os.NewFile(uintptr(fd), "agy-accounts-inotify")

	var (
		mu     sync.Mutex
		last   string
		timer  *time.Timer
		closed bool
	)
	last, _ = h.agyLiveIdentity()
	check := func() {
		mu.Lock()
		defer mu.Unlock()
		if !closed {
			h.notifyAgyAccountIfChanged(&last)
		}
	}
	schedule := func() {
		mu.Lock()
		defer mu.Unlock()
		if closed {
			return
		}
		if timer == nil {
			timer = time.AfterFunc(agyAccountDebounce, check)
		} else {
			timer.Reset(agyAccountDebounce)
		}
	}

	var once sync.Once
	stop = func() {
		once.Do(func() {
			mu.Lock()
			closed = true
			if timer != nil {
				timer.Stop()
			}
			mu.Unlock()
			_ = file.Close() // ends the goroutine and drops the kernel watch
		})
	}

	go func() {
		defer func() {
			stop()
			// forget this watcher unless it was already replaced or stopped
			agyWatcher.Lock()
			if agyWatcher.gen == gen && agyWatcher.stop != nil {
				agyWatcher.stop = nil
			}
			agyWatcher.Unlock()
		}()
		buf := make([]byte, 16*1024)
		for {
			n, err := file.Read(buf)
			if err != nil {
				return // closed by stop (or the descriptor failed)
			}
			for off := 0; off+unix.SizeofInotifyEvent <= n; {
				ev := (*unix.InotifyEvent)(unsafe.Pointer(&buf[off]))
				nameEnd := off + unix.SizeofInotifyEvent + int(ev.Len)
				if nameEnd > n {
					break
				}
				name := string(bytes.TrimRight(buf[off+unix.SizeofInotifyEvent:nameEnd], "\x00"))
				off = nameEnd

				switch {
				case ev.Mask&unix.IN_Q_OVERFLOW != 0:
					schedule() // the kernel dropped events: check the file instead of trusting what arrived
				case int(ev.Wd) == geminiWD && ev.Mask&(unix.IN_DELETE_SELF|unix.IN_MOVE_SELF|unix.IN_IGNORED) != 0:
					check() // ~/.gemini went away: tell the app (signed out), then stop until agy runs again
					return
				case int(ev.Wd) == geminiWD && name == agyTokenFileName:
					schedule()
				}
			}
		}
	}()
	return stop
}
