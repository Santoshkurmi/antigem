package dsh

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// termuxPrefix is Termux's $PREFIX (…/files/usr), or "" when not on Android.
func (m *Manager) termuxPrefix() string {
	if runtime.GOOS != "android" {
		return ""
	}
	if p := os.Getenv("PREFIX"); p != "" {
		return p
	}
	return filepath.Join(filepath.Dir(m.HomeDir), "usr")
}

// glibcLoader is the app's bundled glibc loader (Android), "" elsewhere.
func (m *Manager) glibcLoader() string {
	prefix := m.termuxPrefix()
	if prefix == "" || !needsGlibc {
		return ""
	}
	return filepath.Join(prefix, "glibc", "lib", "ld-linux-aarch64.so.1")
}

func (m *Manager) glibcLibPath() string {
	return filepath.Join(m.termuxPrefix(), "glibc", "lib") + ":" + m.l.libDir()
}

// nodeBinary is the installed Node.js executable ("" when Node is not installed).
func (m *Manager) nodeBinary() string {
	v := m.l.readState().NodeVersion
	if v == "" {
		return ""
	}
	bin := filepath.Join(m.l.nodeDir(v), "bin", "node")
	if _, err := os.Stat(bin); err != nil {
		return ""
	}
	return bin
}

func (m *Manager) shimPath() string    { return filepath.Join(m.l.rtDir(), "bin", "node") }
func (m *Manager) fixExecPath() string { return filepath.Join(m.l.rtDir(), "fix-execpath.cjs") }
func (m *Manager) readerScript() string {
	return filepath.Join(m.l.rtDir(), "antigem-read.cjs")
}

// nodeCommand builds a command running the installed Node.js with args. On Android the glibc build is started by
// the app's glibc loader, without termux-exec (a bionic library cannot load into a glibc process); the execPath fix
// makes child Node processes use the launcher script and gives child shells termux-exec back. withShimOnPath puts
// that launcher first on PATH as `node` (npm lifecycle scripts run `node` from PATH).
func (m *Manager) nodeCommand(ctx context.Context, withShimOnPath bool, args ...string) (*exec.Cmd, error) {
	node := m.nodeBinary()
	if node == "" {
		return nil, errors.New("Node.js for DeepSeek Harness is not installed")
	}
	env := []string{}
	preload := ""
	hasTmp := false
	for _, e := range os.Environ() {
		switch {
		case strings.HasPrefix(e, "LD_PRELOAD="):
			preload = strings.TrimPrefix(e, "LD_PRELOAD=")
			continue
		case strings.HasPrefix(e, "NODE_OPTIONS="), strings.HasPrefix(e, "ANTIGEM_NODE_SHIM="), strings.HasPrefix(e, "ANTIGEM_LD_PRELOAD="):
			continue
		case strings.HasPrefix(e, "TMPDIR=") && len(e) > len("TMPDIR="):
			hasTmp = true
		}
		env = append(env, e)
	}
	prefix := m.termuxPrefix()
	if !hasTmp && prefix != "" {
		env = append(env, "TMPDIR="+filepath.Join(prefix, "tmp"))
	}
	var cmd *exec.Cmd
	if loader := m.glibcLoader(); loader != "" {
		if _, err := os.Stat(loader); err != nil {
			return nil, fmt.Errorf("DeepSeek Harness needs the app's glibc runtime, which is missing (%s): reopen the app to restore it", loader)
		}
		if err := writeGlibcLibs(m.l.libDir()); err != nil {
			return nil, fmt.Errorf("writing the glibc libraries: %w", err)
		}
		if err := m.writeRuntimeFiles(); err != nil {
			return nil, err
		}
		env = append(env, "NODE_OPTIONS=--require "+m.fixExecPath(), "ANTIGEM_NODE_SHIM="+m.shimPath())
		if preload != "" {
			env = append(env, "ANTIGEM_LD_PRELOAD="+preload)
		}
		cmd = exec.CommandContext(ctx, loader, append([]string{"--library-path", m.glibcLibPath(), node}, args...)...)
		if withShimOnPath {
			env = prependPath(env, filepath.Dir(m.shimPath()))
		}
	} else {
		if preload != "" {
			env = append(env, "LD_PRELOAD="+preload)
		}
		cmd = exec.CommandContext(ctx, node, args...)
		if withShimOnPath {
			env = prependPath(env, filepath.Dir(node))
		}
	}
	cmd.Env = env
	return cmd, nil
}

func prependPath(env []string, dir string) []string {
	for i, e := range env {
		if strings.HasPrefix(e, "PATH=") {
			env[i] = "PATH=" + dir + string(os.PathListSeparator) + strings.TrimPrefix(e, "PATH=")
			return env
		}
	}
	return append(env, "PATH="+dir)
}

// --- generated files -----------------------------------------------------------------------------

const fixExecPathJS = `// Written by AntiGem's bridge (rewritten on every start). Under the glibc loader process.execPath is the loader
// itself: point it at the node launcher, so child Node processes (npm scripts, workers, subagents) start the same
// way, and give child shells termux-exec back (the launcher drops it for the glibc process only).
if (process.platform === "linux" && process.env.ANTIGEM_NODE_SHIM) {
  try { process.execPath = process.env.ANTIGEM_NODE_SHIM; } catch {}
  if (process.env.ANTIGEM_LD_PRELOAD && !process.env.LD_PRELOAD) process.env.LD_PRELOAD = process.env.ANTIGEM_LD_PRELOAD;
}
`

// readerJS prints a session log (zstd-compressed JSON lines) as JSON lines, without what the app never shows (the
// system prompt, tool schemas, raw model streams). With --headers it prints only each file's first line.
const readerJS = `// Written by AntiGem's bridge. usage: node antigem-read.cjs <log.zstd> | --headers <log.zstd>...
"use strict";
const fs = require("fs"), zlib = require("zlib");
const DROP = new Set(["request/header", "request/context", "system/message", "developer/message"]);
const args = process.argv.slice(2);
function each(file, onLine, done) {
  let buf = "", ended = false;
  const finish = () => { if (ended) return; ended = true; if (buf.trim()) onLine(buf); done(); };
  const dec = zlib.createZstdDecompress();
  dec.setEncoding("utf8");
  dec.on("data", (c) => { buf += c; let i; while ((i = buf.indexOf("\n")) >= 0) { const l = buf.slice(0, i); buf = buf.slice(i + 1); if (onLine(l) === false) { buf = ""; dec.destroy(); finish(); return; } } });
  dec.on("end", finish);
  dec.on("error", finish); // a live session can end in an unfinished frame
  const src = fs.createReadStream(file);
  src.on("error", finish);
  src.pipe(dec);
}
if (args[0] === "--headers") {
  const files = args.slice(1);
  const next = () => {
    const f = files.shift();
    if (!f) return;
    each(f, (l) => { if (l.trim()) { process.stdout.write(JSON.stringify({ file: f, header: JSON.parse(l) }) + "\n"); return false; } }, next);
  };
  next();
} else {
  each(args[0], (l) => {
    if (!l.trim()) return;
    let e; try { e = JSON.parse(l); } catch { return; }
    if (DROP.has(e.type)) return;
    if (e.data && (e.type === "assistant/message" || e.type === "assistant/attempt")) delete e.data.stream;
    process.stdout.write(JSON.stringify(e) + "\n");
  }, () => {});
}
`

// controlPluginJS is the companion plugin loaded into dsh through the profile patch. The SDK protocol has no way to
// stop a running turn, switch a session's model, list models or change API keys; this plugin offers those on a
// Unix socket only the bridge uses (newline-delimited JSON: {"id","method","params"} → {"id","result"|"error"}).
//
// It also forwards live typing: the SDK sends an assistant message only when its model step is complete, but dsh's
// runtime emits every text/reasoning delta as "agent/assistant-stream" (dsh's headless mode prints reasoning from
// it). A connection that sends "subscribe" receives those, batched per chat every ~80 ms, as {"stream":[item…]}
// lines; item = {sessionId, type: "start"|"text"|"reasoning"|"block"|"end", text?, blockType?}.
const controlPluginJS = `// Written by AntiGem's bridge (rewritten on every start): control channel for what the SDK protocol lacks.
import net from "node:net";
import fs from "node:fs";
export const name = "antigem-dsh-control";
export const inject = ["agents"];
export function apply(ctx, config) {
  const sockPath = (config && config.socket) || process.env.ANTIGEM_DSH_CONTROL;
  if (!sockPath) return;
  const llm = () => { try { return ctx.get("llm"); } catch { return undefined; } };
  const agentOf = (sessionId) => {
    const agent = ctx.agents.get(sessionId);
    if (!agent) throw new Error("this chat is not loaded in dsh");
    return agent;
  };
  // --- live typing ---------------------------------------------------------------------------
  const streamSubs = new Set();
  let batch = [];
  let flushTimer = null;
  const flush = () => {
    flushTimer = null;
    if (!batch.length) return;
    const line = JSON.stringify({ stream: batch }) + "\n";
    batch = [];
    for (const s of streamSubs) { try { s.write(line); } catch {} }
  };
  const push = (item, now) => {
    if (!streamSubs.size) return;
    const last = batch[batch.length - 1];
    // consecutive deltas of one kind in one chat merge into one item
    if (last && item.text !== undefined && last.type === item.type && last.sessionId === item.sessionId) last.text += item.text;
    else batch.push(item);
    if (now) { if (flushTimer) { clearTimeout(flushTimer); } flush(); }
    else if (!flushTimer) flushTimer = setTimeout(flush, 80);
  };
  ctx.on("agent/assistant-stream", ({ agent, frame }) => {
    try {
      const sessionId = agent && agent.id;
      if (!sessionId || !frame) return;
      if (frame.type === "start") return push({ sessionId, type: "start" }, true);
      if (frame.type === "end") return push({ sessionId, type: "end" }, true);
      const c = frame.chunk;
      if (!c) return;
      if (c.type === "text-delta" && c.text) push({ sessionId, type: "text", text: c.text });
      else if (c.type === "reasoning-delta" && c.text) push({ sessionId, type: "reasoning", text: c.text });
      else if (c.type === "block-start") push({ sessionId, type: "block", blockType: c.blockType });
    } catch {}
  });

  const methods = {
    ping: () => ({ ok: true, pid: process.pid }),
    live: () => ctx.agents.list().map((a) => a.id),
    cancel: ({ sessionId }) => {
      const agent = ctx.agents.get(sessionId);
      if (!agent) return { cancelled: false };
      agent.cancel({ kind: "user" });
      return { cancelled: true };
    },
    setModel: ({ sessionId, provider, model, reasoningEffort }) => {
      const sel = { provider, model };
      if (reasoningEffort) sel.reasoningEffort = reasoningEffort;
      agentOf(sessionId).model.select(sel);
      return { ok: true };
    },
    currentModel: ({ sessionId }) => agentOf(sessionId).model.current() ?? null,
    setEnv: ({ vars }) => {
      for (const [k, v] of Object.entries(vars || {})) {
        if (v === null || v === undefined || v === "") delete process.env[k]; else process.env[k] = String(v);
      }
      return { ok: true };
    },
    models: async () => {
      const svc = llm();
      if (!svc) return [];
      const out = [];
      for (const p of svc.listProviders()) {
        let list = [];
        try { list = await svc.listModels(p.id); } catch (e) { out.push({ provider: p.id, providerName: p.name, error: String(e && e.message || e) }); continue; }
        for (const m of list) out.push({ provider: p.id, providerName: p.name, id: m.id, name: m.name, description: m.description, input: m.inputModalities, badge: m.badge });
      }
      return out;
    },
  };
  ctx.effect(() => {
    try { fs.rmSync(sockPath, { force: true }); } catch {}
    const server = net.createServer((sock) => {
      let buf = "";
      sock.setEncoding("utf8");
      sock.on("data", async (chunk) => {
        buf += chunk;
        let i;
        while ((i = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, i); buf = buf.slice(i + 1);
          let msg; try { msg = JSON.parse(line); } catch { continue; }
          if (msg.method === "subscribe") {
            streamSubs.add(sock);
            sock.on("close", () => streamSubs.delete(sock));
            sock.write(JSON.stringify({ id: msg.id, result: { ok: true } }) + "\n");
            continue;
          }
          let reply;
          try {
            const fn = methods[msg.method];
            if (!fn) throw new Error("unknown method " + msg.method);
            reply = { id: msg.id, result: (await fn(msg.params || {})) ?? null };
          } catch (e) { reply = { id: msg.id, error: { message: String((e && e.message) || e) } }; }
          sock.write(JSON.stringify(reply) + "\n");
        }
      });
      sock.on("error", () => {});
    });
    server.on("error", (e) => process.stderr.write("antigem-dsh-control: " + e.message + "\n"));
    server.listen(sockPath);
    return () => {
      for (const s of streamSubs) { try { s.destroy(); } catch {} }
      streamSubs.clear();
      if (flushTimer) clearTimeout(flushTimer);
      server.close();
      try { fs.rmSync(sockPath, { force: true }); } catch {}
    };
  }, "antigem-dsh-control");
}
`

// presets maps the app's permission modes to dsh permission presets. Android has no sandbox backend (no
// bubblewrap, no Landlock), so dsh would refuse every command in its sandboxed presets. Without a sandbox, dsh's
// "ask" policy only asks for what dsh itself flags (sandbox escapes, plugin installs), not for every command, so
// Android has one mode: full access (Termux itself is the app's sandbox), with those flagged actions still sent to
// the app for approval.
func (m *Manager) presets() map[string]string {
	if m.termuxPrefix() != "" {
		return map[string]string{"full": "full"}
	}
	return map[string]string{"ask": "workspace-write", "full": "danger-full-access"}
}

func jsonText(v interface{}) string {
	b, _ := json.Marshal(v)
	return string(b)
}

// piAiRoutes are the pi-ai provider routes to declare: every known provider with a key, plus the selected one. A
// pi-ai route exists in dsh only when its profile declares it ("no adapter registered for provider" otherwise);
// deepseek-official is dsh's own adapter and needs no declaration.
func piAiRoutes(cfg Config) map[string]string {
	out := map[string]string{}
	for _, p := range Providers {
		if p.ID == "deepseek-official" {
			continue
		}
		if cfg.Keys[p.Env] != "" || os.Getenv(p.Env) != "" || p.ID == cfg.Provider {
			out[p.ID] = p.Env
		}
	}
	return out
}

// profilePatch is the --patch layer the bridge adds over dsh's sdk profile (YAML; values are JSON, a YAML subset).
func (m *Manager) profilePatch(cfg Config) string {
	var b strings.Builder
	b.WriteString("# Written by AntiGem's bridge on every dsh start (edits are overwritten).\n\n")
	// model-generated chat titles: the SDK profile turns them off because the SDK shows no titles; the app does
	b.WriteString("- id: session-title-llm\n  disabled: false\n\n")
	if routes := piAiRoutes(cfg); len(routes) > 0 {
		providers := map[string]interface{}{}
		for id, env := range routes {
			providers[id] = map[string]string{"apiKeyEnv": env}
		}
		fmt.Fprintf(&b, "- id: llm-pi-ai\n  config:\n    providers: %s\n\n", jsonText(providers))
	}
	if m.termuxPrefix() != "" {
		b.WriteString("# Android: no sandbox backend exists; Termux is the sandbox. What dsh flags still asks the app.\n")
		b.WriteString("- id: sandbox-policy\n  config:\n    mode: danger-full-access\n    workspaceRoot: !!js process.cwd()\n\n")
		b.WriteString("- id: approval\n  config:\n    policy: ask\n\n")
		b.WriteString("- id: permission\n  config:\n    presets:\n")
		b.WriteString("      full: {sandbox: danger-full-access, approval: ask}\n\n")
	}
	b.WriteString("- insert:\n")
	b.WriteString("    - id: antigem-dsh-control\n      name: './antigem-dsh-control.mjs'\n")
	for _, s := range cfg.Mcp {
		if s.Disabled {
			continue
		}
		conf := map[string]interface{}{"serverName": s.Name, "transport": s.Transport}
		if s.Transport == "stdio" {
			conf["command"] = s.Command
			if len(s.Args) > 0 {
				conf["args"] = s.Args
			}
			if len(s.Env) > 0 {
				conf["env"] = s.Env
			}
		} else {
			conf["url"] = s.URL
			if len(s.Headers) > 0 {
				conf["headers"] = s.Headers
			}
		}
		fmt.Fprintf(&b, "    - id: %s\n      name: '@deepseek-ai/dsh-mcp-client'\n      config: %s\n", jsonText("antigem-mcp-"+s.Name), jsonText(conf))
	}
	return b.String()
}

// writeRuntimeFiles (re)writes everything the bridge generates for a dsh start.
func (m *Manager) writeRuntimeFiles() error {
	rt := m.l.rtDir()
	if err := os.MkdirAll(filepath.Join(rt, "bin"), 0o755); err != nil {
		return err
	}
	files := map[string]string{
		m.fixExecPath():  fixExecPathJS,
		m.readerScript(): readerJS,
		filepath.Join(rt, "antigem-dsh-control.mjs"): controlPluginJS,
		m.l.patchFile(): m.profilePatch(m.cfg.load()),
	}
	if loader := m.glibcLoader(); loader != "" {
		if node := m.nodeBinary(); node != "" {
			files[m.shimPath()] = fmt.Sprintf(`#!%s/bin/sh
# Node.js (official glibc build) launcher written by AntiGem's bridge for DeepSeek Harness.
if [ -n "$LD_PRELOAD" ]; then export ANTIGEM_LD_PRELOAD="$LD_PRELOAD"; fi
unset LD_PRELOAD
exec %s --library-path %s %s "$@"
`, m.termuxPrefix(), shellQuote(loader), shellQuote(m.glibcLibPath()), shellQuote(node))
		}
	}
	for path, content := range files {
		if cur, err := os.ReadFile(path); err == nil && string(cur) == content {
			continue
		}
		perm := os.FileMode(0o644)
		if path == m.shimPath() {
			perm = 0o755
		}
		if err := writeFileAtomic(path, []byte(content), perm); err != nil {
			return err
		}
	}
	return nil
}

// writeLauncher puts a `dsh` command in ~/.local/bin for the terminal (same runtime as the app uses).
func (m *Manager) writeLauncher() error {
	node := m.nodeBinary()
	if node == "" {
		return nil
	}
	exe := shellQuote(node)
	if m.glibcLoader() != "" {
		exe = shellQuote(m.shimPath())
	}
	sh := "/bin/sh"
	if p := m.termuxPrefix(); p != "" {
		sh = p + "/bin/sh"
	}
	script := fmt.Sprintf(`#!%s
# DeepSeek Harness launcher written by AntiGem's bridge (install/update from the app).
export NODE_OPTIONS="--require %s"
export ANTIGEM_NODE_SHIM=%s
exec %s --expose-internals %s "$@"
`, sh, m.fixExecPath(), shellQuote(m.shimPath()), exe, shellQuote(binJS(m.l.current())))
	if m.glibcLoader() == "" {
		script = fmt.Sprintf("#!%s\n# DeepSeek Harness launcher written by AntiGem's bridge (install/update from the app).\nexec %s --expose-internals %s \"$@\"\n",
			sh, exe, shellQuote(binJS(m.l.current())))
	}
	return writeFileAtomic(filepath.Join(m.HomeDir, ".local", "bin", "dsh"), []byte(script), 0o755)
}

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}
