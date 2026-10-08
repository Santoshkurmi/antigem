package claude

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"
	"time"
)

// controlProc is a short-lived `claude` process used only for control requests (login, usage, model info,
// MCP status). It writes no transcript.
type controlProc struct {
	cmd     *exec.Cmd
	stdin   io.WriteCloser
	mu      sync.Mutex
	waiters map[string]chan ctrlResult
	nextID  int
	done    chan struct{}
	closed  bool
}

type ctrlResult struct {
	resp json.RawMessage
	err  error
}

func (m *Manager) startControlProc(cwd string) (*controlProc, error) {
	bin, err := m.ResolveBinary()
	if err != nil {
		return nil, err
	}
	if fi, err := os.Stat(cwd); cwd == "" || err != nil || !fi.IsDir() {
		cwd = m.HomeDir
	}
	cmd := exec.Command(bin, "--output-format", "stream-json", "--verbose", "--input-format", "stream-json",
		"--no-session-persistence", "--setting-sources=user,project,local")
	cmd.Dir = cwd
	cmd.Env = os.Environ()
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	p := &controlProc{cmd: cmd, stdin: stdin, waiters: map[string]chan ctrlResult{}, done: make(chan struct{})}
	go p.read(stdout)
	go func() {
		_ = cmd.Wait()
		p.failAll(errors.New("claude process exited"))
		close(p.done)
	}()
	return p, nil
}

func (p *controlProc) read(r io.Reader) {
	br := bufio.NewReaderSize(r, 1<<20)
	for {
		line, err := br.ReadBytes('\n')
		if len(line) > 0 {
			var msg struct {
				Type     string `json:"type"`
				Response struct {
					Subtype   string          `json:"subtype"`
					RequestID string          `json:"request_id"`
					Response  json.RawMessage `json:"response"`
					Error     string          `json:"error"`
				} `json:"response"`
			}
			if json.Unmarshal(line, &msg) == nil && msg.Type == "control_response" {
				p.mu.Lock()
				ch := p.waiters[msg.Response.RequestID]
				delete(p.waiters, msg.Response.RequestID)
				p.mu.Unlock()
				if ch != nil {
					if msg.Response.Subtype == "success" {
						ch <- ctrlResult{resp: msg.Response.Response}
					} else {
						ch <- ctrlResult{err: errors.New(msg.Response.Error)}
					}
				}
			}
		}
		if err != nil {
			return
		}
	}
}

func (p *controlProc) failAll(err error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for id, ch := range p.waiters {
		ch <- ctrlResult{err: err}
		delete(p.waiters, id)
	}
	p.closed = true
}

// request sends one control request and waits for its response. timeout 0 waits until the process exits.
func (p *controlProc) request(body map[string]interface{}, timeout time.Duration) (json.RawMessage, error) {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil, errors.New("claude process exited")
	}
	p.nextID++
	id := fmt.Sprintf("bridge_%d", p.nextID)
	ch := make(chan ctrlResult, 1)
	p.waiters[id] = ch
	line, _ := json.Marshal(map[string]interface{}{"type": "control_request", "request_id": id, "request": body})
	_, werr := p.stdin.Write(append(line, '\n'))
	p.mu.Unlock()
	if werr != nil {
		return nil, werr
	}
	var timer <-chan time.Time
	if timeout > 0 {
		t := time.NewTimer(timeout)
		defer t.Stop()
		timer = t.C
	}
	select {
	case r := <-ch:
		return r.resp, r.err
	case <-timer:
		p.mu.Lock()
		delete(p.waiters, id)
		p.mu.Unlock()
		return nil, fmt.Errorf("%v timed out", body["subtype"])
	}
}

func (p *controlProc) close() {
	_ = p.stdin.Close()
	if p.cmd.Process != nil {
		_ = p.cmd.Process.Kill()
	}
}

// oneShot runs `initialize` followed by the given requests in a throwaway process and returns the responses of
// the given requests in order.
func (m *Manager) oneShot(cwd string, timeout time.Duration, reqs ...map[string]interface{}) ([]json.RawMessage, error) {
	p, err := m.startControlProc(cwd)
	if err != nil {
		return nil, err
	}
	defer p.close()
	if _, err := p.request(map[string]interface{}{"subtype": "initialize"}, timeout); err != nil {
		return nil, err
	}
	out := make([]json.RawMessage, 0, len(reqs))
	for _, r := range reqs {
		resp, err := p.request(r, timeout)
		if err != nil {
			return nil, err
		}
		out = append(out, resp)
	}
	return out, nil
}
