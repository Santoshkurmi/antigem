// Package runlog copies everything the bridge prints into one log file per start.
package runlog

import (
	"io"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"time"
)

// RunLog copies everything the bridge prints (stdout and stderr) into one file per start:
// ~/logs/agy-ide-bridge_<date>_<time>.log. The console output is unchanged; the file has no color codes.
type RunLog struct {
	Path string
	file *os.File
	done chan struct{}
	outW *os.File
}

var ansiCodes = regexp.MustCompile(`\x1b\[[0-9;?]*[A-Za-z]`)

const keepRunLogs = 30

// Start starts this run's log file ("" path and nil when it cannot be created; the bridge runs on).
func Start(homeDir string) *RunLog {
	dir := filepath.Join(homeDir, "logs")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil
	}
	path := filepath.Join(dir, "agy-ide-bridge_"+time.Now().Format("2006-01-02_15-04-05")+".log")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return nil
	}
	r, w, err := os.Pipe()
	if err != nil {
		_ = f.Close()
		return nil
	}
	console := os.Stdout
	os.Stdout = w
	os.Stderr = w
	log.SetOutput(w) // the log package keeps the stderr it started with
	l := &RunLog{Path: path, file: f, done: make(chan struct{}), outW: w}
	go func() {
		defer close(l.done)
		buf := make([]byte, 32<<10)
		for {
			n, err := r.Read(buf)
			if n > 0 {
				_, _ = console.Write(buf[:n]) // a closed console (tmux / tee gone) must not stop the file
				_, _ = f.Write(ansiCodes.ReplaceAll(buf[:n], nil))
			}
			if err != nil {
				if err != io.EOF {
					_, _ = f.WriteString("\n[run log] read error: " + err.Error() + "\n")
				}
				return
			}
		}
	}()
	pruneRunLogs(dir)
	return l
}

// Close writes what is still pending to the file (call right before exiting).
func (l *RunLog) Close() {
	if l == nil {
		return
	}
	_ = l.outW.Close()
	select {
	case <-l.done:
	case <-time.After(time.Second):
	}
	_ = l.file.Close()
}

// pruneRunLogs keeps the newest keepRunLogs files.
func pruneRunLogs(dir string) {
	files, _ := filepath.Glob(filepath.Join(dir, "agy-ide-bridge_*.log"))
	if len(files) <= keepRunLogs {
		return
	}
	sort.Strings(files) // the names start with the date: oldest first
	for _, f := range files[:len(files)-keepRunLogs] {
		_ = os.Remove(f)
	}
}
