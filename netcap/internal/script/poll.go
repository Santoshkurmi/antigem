package script

import (
	"fmt"
	"os"
	"strings"
	"time"
)

// pollLoop checks the folders every 2 s (where inotify is missing).
func pollLoop(dirs func() []string, onChange func(), stop, reset chan struct{}) {
	sig := func() string {
		var b strings.Builder
		for _, d := range dirs() {
			entries, _ := os.ReadDir(d)
			for _, e := range entries {
				if info, err := e.Info(); err == nil {
					fmt.Fprintf(&b, "%s/%s %d %d\n", d, e.Name(), info.Size(), info.ModTime().UnixNano())
				}
			}
		}
		return b.String()
	}
	last := sig()
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-reset:
			last = sig()
		case <-t.C:
			if s := sig(); s != last {
				last = s
				onChange()
			}
		}
	}
}
