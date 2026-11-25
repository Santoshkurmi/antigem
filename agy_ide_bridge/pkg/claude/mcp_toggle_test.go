package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestMcpTurnedOff(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, home, "")
	_ = os.MkdirAll(filepath.Join(home, ".claude"), 0o755)
	_ = os.WriteFile(m.settingsPath(), []byte(`{"model":"opus","deniedMcpServers":[{"serverUrl":"https://x"}]}`), 0o600)
	_ = os.WriteFile(filepath.Join(home, ".claude.json"), []byte(`{"mcpServers":{"beta":{"type":"stdio","command":"true"}}}`), 0o600)

	if err := m.setMcpTurnedOff("beta", true); err != nil {
		t.Fatal(err)
	}
	if err := m.setMcpTurnedOff("beta", true); err != nil { // twice: no duplicate
		t.Fatal(err)
	}
	if !m.turnedOffMcp()["beta"] {
		t.Fatal("beta not off")
	}
	got := m.withTurnedOffMcp([]map[string]interface{}{{"name": "alpha", "status": "connected"}}, "")
	if len(got) != 2 || got[1]["name"] != "beta" || got[1]["status"] != "disabled" || got[1]["scope"] != "user" || got[1]["config"] == nil {
		t.Fatalf("list: %v", got)
	}
	if err := m.setMcpTurnedOff("beta", false); err != nil {
		t.Fatal(err)
	}
	var st map[string]interface{}
	data, _ := os.ReadFile(m.settingsPath())
	_ = json.Unmarshal(data, &st)
	denied, _ := st["deniedMcpServers"].([]interface{})
	if st["model"] != "opus" || len(denied) != 1 { // the user's own entry stays
		t.Fatalf("settings: %s", data)
	}
}
