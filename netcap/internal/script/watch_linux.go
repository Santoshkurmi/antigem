//go:build linux

package script

import (
	"os"
	"time"

	"golang.org/x/sys/unix"
)

// watcher reloads the scripts when a file in their folders changes (inotify; no work while nothing changes).
type watcher struct {
	dirs     func() []string
	onChange func()
	stop     chan struct{}
	resetCh  chan struct{}
}

func newWatcher(dirs func() []string, onChange func()) *watcher {
	w := &watcher{dirs: dirs, onChange: onChange, stop: make(chan struct{}), resetCh: make(chan struct{}, 1)}
	go w.loop()
	return w
}

func (w *watcher) reset() {
	select {
	case w.resetCh <- struct{}{}:
	default:
	}
}

func (w *watcher) close() { close(w.stop) }

const watchMask = unix.IN_CLOSE_WRITE | unix.IN_MOVED_TO | unix.IN_MOVED_FROM | unix.IN_DELETE | unix.IN_CREATE

func (w *watcher) loop() {
	for {
		fd, err := unix.InotifyInit1(unix.IN_CLOEXEC | unix.IN_NONBLOCK)
		if err != nil {
			pollLoop(w.dirs, w.onChange, w.stop, w.resetCh)
			return
		}
		f := os.NewFile(uintptr(fd), "inotify")
		for _, d := range w.dirs() {
			_, _ = unix.InotifyAddWatch(fd, d, watchMask)
		}
		go w.onChange() // a file written while the watch was not set up yet
		events := make(chan struct{}, 1)
		done := make(chan struct{})
		go func() {
			defer close(done)
			buf := make([]byte, 8192)
			for {
				n, err := f.Read(buf)
				if err != nil {
					return
				}
				if n > 0 {
					select {
					case events <- struct{}{}:
					default:
					}
				}
			}
		}()
		var debounce <-chan time.Time
		again := false
	inner:
		for {
			select {
			case <-w.stop:
				f.Close()
				<-done
				return
			case <-w.resetCh:
				again = true
				break inner
			case <-done:
				break inner
			case <-events:
				debounce = time.After(150 * time.Millisecond)
			case <-debounce:
				debounce = nil
				w.onChange()
			}
		}
		f.Close()
		<-done
		if !again {
			pollLoop(w.dirs, w.onChange, w.stop, w.resetCh)
			return
		}
	}
}
