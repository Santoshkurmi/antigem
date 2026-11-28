package cli

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// client runs a tool on the running engine.
func client(cmd string, args []string) error {
	data := defaultDataDir()
	kv := map[string]any{}
	for _, a := range args {
		if strings.HasPrefix(a, "--data=") {
			data = strings.TrimPrefix(a, "--data=")
			continue
		}
		k, v, ok := strings.Cut(a, "=")
		if !ok {
			return fmt.Errorf("arguments are key=value (got %q)", a)
		}
		kv[k] = parseValue(v)
	}
	b, err := os.ReadFile(filepath.Join(data, "run.json"))
	if err != nil {
		return errors.New("the engine is not running (start it with: netcap serve)")
	}
	var info runInfo
	if err := json.Unmarshal(b, &info); err != nil {
		return err
	}
	name := cmd
	if cmd == "tools" {
		name = "net_status"
	}
	if !strings.HasPrefix(name, "net_") {
		name = "net_" + name
	}
	if cmd == "tools" {
		fmt.Println("status capture list get wait summarize diff ws dns export decode rules intercept send script findings cert session")
		return nil
	}
	body, _ := json.Marshal(kv)
	req, _ := http.NewRequest(http.MethodPost, "http://"+info.API+"/v1/tool/"+name, bytes.NewReader(body))
	req.Header.Set("X-Netcap-Token", info.Token)
	req.Header.Set("Content-Type", "application/json")
	hc := &http.Client{Timeout: 150 * time.Second}
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	os.Stdout.Write(out)
	if len(out) > 0 && out[len(out)-1] != '\n' {
		fmt.Println()
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return nil
}

// parseValue reads JSON values (lists, numbers, true/false) and keeps everything else as text.
func parseValue(v string) any {
	if strings.HasPrefix(v, "[") || strings.HasPrefix(v, "{") {
		var x any
		if json.Unmarshal([]byte(v), &x) == nil {
			return x
		}
	}
	if n, err := strconv.Atoi(v); err == nil {
		return float64(n)
	}
	if v == "true" || v == "false" {
		return v == "true"
	}
	return v
}
