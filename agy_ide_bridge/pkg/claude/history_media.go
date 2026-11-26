package claude

import (
	"bytes"
	"crypto/sha1"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

// A transcript keeps every picture as base64: images pasted into prompts, screenshots and image reads inside tool
// results (and the same image again in toolUseResult). An app asking for /history with media=refs gets the entries
// without that data, so a long chat full of screenshots stays small to send and to parse:
//
//   - an image or document in a prompt is saved once under the chat's attachments folder and its source becomes
//     {"type":"path","media_type":…,"path":…}: the app shows it through /attachments/raw like any attachment;
//   - an image inside a tool result becomes {"type":"omitted","media_type":…} (the app shows those as "[image]");
//   - inline data in toolUseResult (an image Read's file.base64, base64 blocks of MCP results) is dropped (the app
//     never reads it).
//
// Without media=refs the entries are sent exactly as stored.

var base64Marker = []byte(`"base64"`)

// historyWithMediaRefs is raw without its inline media (see above); raw itself when it has none or can't be read.
func (m *Manager) historyWithMediaRefs(sessionID string, raw []byte) []byte {
	if !bytes.Contains(raw, base64Marker) {
		return raw
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber() // numbers go back exactly as they were
	var entry map[string]interface{}
	if dec.Decode(&entry) != nil {
		return raw
	}
	changed := false
	isUser := entry["type"] == "user"
	if msg, ok := entry["message"].(map[string]interface{}); ok {
		if content, ok := msg["content"].([]interface{}); ok {
			for _, c := range content {
				block, ok := c.(map[string]interface{})
				if !ok {
					continue
				}
				switch block["type"] {
				case "image", "document":
					if isUser && m.saveInlineMedia(sessionID, block) {
						changed = true
					}
				case "tool_result":
					if inner, ok := block["content"].([]interface{}); ok {
						for _, ic := range inner {
							if ib, ok := ic.(map[string]interface{}); ok && dropBase64Source(ib) {
								changed = true
							}
						}
					}
				}
			}
		}
	}
	if tur, ok := entry["toolUseResult"]; ok && scrubBase64(tur) {
		changed = true
	}
	if !changed {
		return raw
	}
	var out bytes.Buffer
	enc := json.NewEncoder(&out)
	enc.SetEscapeHTML(false)
	if enc.Encode(entry) != nil {
		return raw
	}
	return bytes.TrimRight(out.Bytes(), "\n")
}

// dropBase64Source replaces a tool result image's data with a marker; false when it has no inline data.
func dropBase64Source(block map[string]interface{}) bool {
	src, ok := block["source"].(map[string]interface{})
	if !ok || src["type"] != "base64" {
		return false
	}
	block["source"] = map[string]interface{}{"type": "omitted", "media_type": src["media_type"]}
	return true
}

// scrubBase64 drops inline file data from a tool's structured result: a "base64" field, or the data of a
// {"type":"base64","data":…} block. True when something was dropped.
func scrubBase64(v interface{}) bool {
	changed := false
	switch t := v.(type) {
	case map[string]interface{}:
		if _, ok := t["base64"].(string); ok {
			delete(t, "base64")
			changed = true
		}
		if t["type"] == "base64" {
			if _, ok := t["data"].(string); ok {
				delete(t, "data")
				t["type"] = "omitted"
				changed = true
			}
		}
		for _, c := range t {
			if scrubBase64(c) {
				changed = true
			}
		}
	case []interface{}:
		for _, c := range t {
			if scrubBase64(c) {
				changed = true
			}
		}
	}
	return changed
}

// saveInlineMedia saves a prompt's inline image or document as a file of the chat and points its source at it.
func (m *Manager) saveInlineMedia(sessionID string, block map[string]interface{}) bool {
	src, ok := block["source"].(map[string]interface{})
	if !ok || src["type"] != "base64" {
		return false
	}
	data, _ := src["data"].(string)
	mediaType, _ := src["media_type"].(string)
	if data == "" || !sessionIDRe.MatchString(sessionID) {
		return false
	}
	path, err := m.storeInlineMedia(sessionID, data, mediaType)
	if err != nil {
		return false
	}
	block["source"] = map[string]interface{}{"type": "path", "media_type": mediaType, "path": path}
	return true
}

// storeInlineMedia writes base64 data once to the chat's attachments folder (named after its content, so the same
// picture is one file however often the history is read) and returns its path.
func (m *Manager) storeInlineMedia(sessionID, data, mediaType string) (string, error) {
	sum := sha1.Sum([]byte(data))
	dir := filepath.Join(m.attachmentsRoot(), sessionID)
	path := filepath.Join(dir, "inline-"+hex.EncodeToString(sum[:10])+inlineMediaExt(mediaType))
	if st, err := os.Stat(path); err == nil && st.Size() > 0 {
		return path, nil
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(data))
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(dir, ".inline-*.tmp")
	if err != nil {
		return "", err
	}
	_, werr := tmp.Write(raw)
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		_ = os.Remove(tmp.Name())
		if werr != nil {
			return "", werr
		}
		return "", cerr
	}
	_ = os.Chmod(tmp.Name(), 0o600)
	if err := os.Rename(tmp.Name(), path); err != nil {
		_ = os.Remove(tmp.Name())
		return "", err
	}
	return path, nil
}

func inlineMediaExt(mediaType string) string {
	switch strings.ToLower(strings.TrimSpace(mediaType)) {
	case "image/png":
		return ".png"
	case "image/jpeg", "image/jpg":
		return ".jpg"
	case "image/gif":
		return ".gif"
	case "image/webp":
		return ".webp"
	case "application/pdf":
		return ".pdf"
	case "text/plain":
		return ".txt"
	}
	return ".bin"
}
