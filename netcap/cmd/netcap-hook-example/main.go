// Command netcap-hook-example is a small external hook: it reads the engine's messages (one JSON object per line)
// on stdin and answers on stdout. Start it through the engine:
//
//	netcap hook --mode modify --filter "host:api.example.com" -- netcap-hook-example
//
// It adds the header X-Hook: example to requests, turns "debug":false into true in JSON answers, counts the
// endpoints it saw and reports server errors as findings. Anything printed that is not JSON is logged by the engine
// (net_script action=list shows the last lines).
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

type side struct {
	Status  int         `json:"status"`
	Headers [][2]string `json:"headers"`
	Body    string      `json:"body"`
}

type message struct {
	Type     string `json:"type"`
	ID       string `json:"id"`
	Mode     string `json:"mode"`
	Flow     string `json:"flow"`
	Method   string `json:"method"`
	URL      string `json:"url"`
	Host     string `json:"host"`
	Path     string `json:"path"`
	Request  *side  `json:"request"`
	Response *side  `json:"response"`
	Status   int    `json:"status"`
	Dir      string `json:"dir"`
	Data     string `json:"data"`
}

func header(s *side, name string) string {
	if s == nil {
		return ""
	}
	for _, kv := range s.Headers {
		if strings.EqualFold(kv[0], name) {
			return kv[1]
		}
	}
	return ""
}

func main() {
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 64<<10), 16<<20)
	out := json.NewEncoder(os.Stdout)
	seen := map[string]int{}
	for in.Scan() {
		var m message
		if json.Unmarshal(in.Bytes(), &m) != nil {
			continue
		}
		switch m.Type {
		case "hello":
			fmt.Println("example hook ready, mode", m.Mode)
		case "request":
			seen[m.Method+" "+m.Host+m.Path]++
			out.Encode(map[string]any{"id": m.ID, "action": "modify", "patch": map[string]any{
				"set_headers": map[string]string{"X-Hook": "example"}}})
		case "response":
			if strings.Contains(header(m.Response, "Content-Type"), "json") && strings.Contains(m.Response.Body, `"debug":false`) {
				out.Encode(map[string]any{"id": m.ID, "action": "modify", "tag": "hook-debug",
					"patch": map[string]any{"json_set": map[string]any{".debug": true}}})
				continue
			}
			out.Encode(map[string]any{"id": m.ID, "action": "continue"})
		case "message":
			out.Encode(map[string]any{"id": m.ID, "action": "continue"})
		case "complete":
			if m.Status >= 500 {
				out.Encode(map[string]any{"type": "finding", "severity": "warn", "flow": m.Flow,
					"title": fmt.Sprintf("server error %d on %s %s", m.Status, m.Method, m.Path)})
			}
		}
		if n := len(seen); n > 0 && n%50 == 0 {
			fmt.Println("endpoints seen:", n)
		}
	}
}
