// Package hookclient connects a command to the engine as an external hook: the engine's JSON messages go to the
// command's stdin one per line, the lines the command prints go back (netcap hook … -- cmd args).
package hookclient

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"sync"

	"golang.org/x/net/websocket"
)

// Options of a hook connection.
type Options struct {
	API     string // host:port of the engine's API
	Token   string
	Name    string
	Filter  string
	Mode    string // observe, modify
	Phases  string // comma list
	Timeout string // e.g. 200ms
	NoBody  bool
	Stderr  io.Writer
}

// URL is the WebSocket address for o.
func (o Options) URL() string {
	q := url.Values{}
	for k, v := range map[string]string{"name": o.Name, "filter": o.Filter, "mode": o.Mode, "phases": o.Phases, "timeout": o.Timeout} {
		if v != "" {
			q.Set(k, v)
		}
	}
	if o.NoBody {
		q.Set("body", "0")
	}
	return "ws://" + o.API + "/v1/hooks?" + q.Encode()
}

// Dial opens the WebSocket.
func Dial(o Options) (*websocket.Conn, error) {
	cfg, err := websocket.NewConfig(o.URL(), "http://"+o.API)
	if err != nil {
		return nil, err
	}
	cfg.Header = http.Header{"X-Netcap-Token": {o.Token}}
	ws, err := websocket.DialConfig(cfg)
	if err != nil {
		return nil, fmt.Errorf("connecting to the engine: %w", err)
	}
	ws.MaxPayloadBytes = 16 << 20
	return ws, nil
}

// Run starts cmd and relays lines until the command or the engine ends.
func Run(ctx context.Context, o Options, cmd []string) error {
	if len(cmd) == 0 {
		return errors.New("give the command after --, e.g. netcap hook --mode modify -- python3 hook.py")
	}
	ws, err := Dial(o)
	if err != nil {
		return err
	}
	defer ws.Close()
	// the first message says whether the engine took the options
	var hello string
	if err := websocket.Message.Receive(ws, &hello); err != nil {
		return fmt.Errorf("the engine closed the hook: %w", err)
	}
	if strings.Contains(hello, `"type":"error"`) {
		return errors.New(hello)
	}
	stderr := o.Stderr
	if stderr == nil {
		stderr = os.Stderr
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	c := exec.CommandContext(ctx, cmd[0], cmd[1:]...)
	c.Stderr = stderr
	stdin, err := c.StdinPipe()
	if err != nil {
		return err
	}
	stdout, err := c.StdoutPipe()
	if err != nil {
		return err
	}
	if err := c.Start(); err != nil {
		return err
	}
	var wmu sync.Mutex
	writeLine := func(s string) error {
		wmu.Lock()
		defer wmu.Unlock()
		_, err := io.WriteString(stdin, strings.TrimRight(s, "\n")+"\n")
		return err
	}
	_ = writeLine(hello)
	go func() { // engine → command
		defer stdin.Close()
		for {
			var msg string
			if err := websocket.Message.Receive(ws, &msg); err != nil {
				cancel()
				return
			}
			if writeLine(msg) != nil {
				return
			}
		}
	}()
	sc := bufio.NewScanner(stdout) // command → engine
	sc.Buffer(make([]byte, 64<<10), 16<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		if !strings.HasPrefix(line, "{") { // plain text: a log line
			b := fmt.Sprintf(`{"type":"log","text":%q}`, line)
			line = b
		}
		if err := websocket.Message.Send(ws, line); err != nil {
			break
		}
	}
	ws.Close()
	err = c.Wait()
	if ctx.Err() != nil {
		return nil
	}
	return err
}
