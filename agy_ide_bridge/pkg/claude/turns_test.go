package claude

import "testing"

// feed writes prompts and output lines in order, the way Write / appendLine see them (event sequences recorded from
// claude 2.1.312 in stream-json mode).
func feed(s *Session, steps ...string) {
	for _, l := range steps {
		if len(l) > 2 && l[:2] == "W:" {
			s.openCommandFromWrite(l[2:])
			continue
		}
		s.trackTurnLocked([]byte(l))
	}
}

// openCommandFromWrite mirrors the user-line bookkeeping of Write without a process.
func (s *Session) openCommandFromWrite(uuid string) {
	if uuid == "" {
		s.anonTurns++
	} else {
		if s.openCommands == nil {
			s.openCommands = map[string]struct{}{}
		}
		s.openCommands[uuid] = struct{}{}
	}
	s.recountTurnsLocked()
}

func lc(uuid, state string) string {
	return `{"type":"command_lifecycle","command_uuid":"` + uuid + `","state":"` + state + `"}`
}

const result = `{"type":"result","subtype":"success"}`

func TestTurnsSteeringOneResultForTwoPrompts(t *testing.T) {
	s := &Session{}
	// a prompt sent mid-turn is injected at a tool boundary: two prompts, one result
	feed(s, "W:a", lc("a", "queued"), lc("a", "started"), "W:b", lc("b", "queued"), lc("b", "started"),
		lc("b", "completed"), result, lc("a", "completed"))
	if s.pendingTurns != 0 {
		t.Fatalf("pendingTurns = %d, want 0 (busy stuck)", s.pendingTurns)
	}
}

func TestTurnsQueuedAfterTurn(t *testing.T) {
	s := &Session{}
	feed(s, "W:a", lc("a", "queued"), lc("a", "started"), "W:b", lc("b", "queued"))
	if s.pendingTurns != 2 {
		t.Fatalf("pendingTurns = %d, want 2", s.pendingTurns)
	}
	feed(s, result, lc("a", "completed"), lc("b", "started"))
	if s.pendingTurns != 1 {
		t.Fatalf("pendingTurns = %d, want 1 (b still running)", s.pendingTurns)
	}
	feed(s, result, lc("b", "completed"))
	if s.pendingTurns != 0 {
		t.Fatalf("pendingTurns = %d, want 0", s.pendingTurns)
	}
}

func TestTurnsInterruptRunsQueued(t *testing.T) {
	s := &Session{}
	feed(s, "W:a", lc("a", "queued"), lc("a", "started"), "W:b", lc("b", "queued"),
		result, lc("a", "cancelled"), lc("b", "started"))
	if s.pendingTurns != 1 {
		t.Fatalf("pendingTurns = %d, want 1 (b runs after the interrupt)", s.pendingTurns)
	}
}

func TestTurnsOldCliWithoutLifecycle(t *testing.T) {
	s := &Session{}
	feed(s, "W:a", result)
	if s.pendingTurns != 0 {
		t.Fatalf("pendingTurns = %d, want 0", s.pendingTurns)
	}
	feed(s, "W:", result)
	if s.pendingTurns != 0 {
		t.Fatalf("pendingTurns = %d, want 0 (prompt without uuid)", s.pendingTurns)
	}
}
