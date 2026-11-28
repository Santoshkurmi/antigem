//go:build !linux

package script

// watcher reloads the scripts when a file in their folders changes (polling every 2 s).
type watcher struct {
	stop    chan struct{}
	resetCh chan struct{}
}

func newWatcher(dirs func() []string, onChange func()) *watcher {
	w := &watcher{stop: make(chan struct{}), resetCh: make(chan struct{}, 1)}
	go pollLoop(dirs, onChange, w.stop, w.resetCh)
	return w
}

func (w *watcher) reset() {
	select {
	case w.resetCh <- struct{}{}:
	default:
	}
}

func (w *watcher) close() { close(w.stop) }
