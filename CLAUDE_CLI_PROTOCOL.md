# Claude Code CLI — Host Protocol Reference

How the official **Claude Code for VS Code** extension (v2.1.292) drives the `claude` CLI. Everything here was
reverse-engineered from `~/.vscode/extensions/anthropic.claude-code-2.1.292-linux-x64/` (`extension.js`,
`webview/index.js`, `package.json`) and checked with real chats against CLI `2.1.292` using Sonnet 5.5 and Haiku.

Goal: build a Claude wrapper in this app that works like the VS Code extension — one long-lived CLI process per
chat, live switching of model / effort / thinking / mode, usage and cache timers, images and files, voice, history,
login/logout — without starting `claude` again for every message.

Labels used below:
- ✅ **tested** — run live; the payload shown is real output (trimmed).
- 📖 **code** — read from the extension source, not run.

---

## Contents
1. [Architecture in one page](#1-architecture-in-one-page)
2. [Spawning the CLI](#2-spawning-the-cli)
3. [Wire format](#3-wire-format)
4. [A real turn, line by line](#4-a-real-turn-line-by-line)
5. [All stdout events](#5-all-stdout-events-cli--host)
6. [Host → CLI control requests](#6-host--cli-control-requests)
7. [CLI → Host control requests (permissions, questions, plans)](#7-cli--host-control-requests)
8. [Feature guides](#8-feature-guides)
   - 8.1 Models · 8.2 Thinking & effort · 8.3 Permission modes · 8.4 Prompt-cache timer · 8.5 Usage, limits, cost, context
   - 8.6 Images, files, @-mentions, editor context · 8.7 Voice dictation · 8.8 Stop, queue, interrupt
   - 8.9 Subagents & background tasks · 8.10 Slash commands & compaction · 8.11 Sessions & history
   - 8.12 Login / logout · 8.13 Settings, permission rules, MCP, plugins · 8.14 File diffs
9. [VS Code extension settings → what they do](#9-vs-code-extension-settings--what-they-do)
10. [UI action → protocol cheat sheet](#10-ui-action--protocol-cheat-sheet)
11. [Notes for the Android / Kotlin implementation](#11-notes-for-the-android--kotlin-implementation)

---

## 1. Architecture in one page

```
 ┌────────────── VS Code extension ──────────────┐
 │ webview (React UI)                            │
 │      │ postMessage: launch_claude, io_message, │
 │      │ interrupt_claude, set_model, ...        │
 │ extension host (Node) ── bundles Claude Agent SDK
 └──────┼─────────────────────────────┼──────────┘
        │ stdin/stdout NDJSON          │ WebSocket (voice only)
        ▼                              ▼
  claude CLI process            wss://api.anthropic.com/api/ws/speech_to_text/voice_stream
  (one per chat tab)
        │
        ├── reads/writes ~/.claude/projects/<cwd>/<sessionId>.jsonl   (history)
        ├── ~/.claude/settings.json, .claude/settings.local.json       (settings)
        └── ~/.claude/.credentials.json                                (OAuth tokens)
```

- **No socket, no HTTP server, no gRPC.** The extension spawns `claude` and exchanges **newline-delimited JSON** on
  stdin/stdout. This is the Agent SDK "stream-json" protocol.
- **One process per chat tab.** It stays alive across turns; new user messages are written to its stdin.
- **Control messages** (`control_request` / `control_response`) share the same pipe. Model switch, stop, usage, mode,
  login and so on are all control requests.
- **The CLI asks the host** (`can_use_tool`) whenever a tool needs approval. Claude's questions (`AskUserQuestion`)
  and plan approval (`ExitPlanMode`) arrive the same way.
- **A new process is only needed** for a new chat, to open an old one (`--resume`), or to fork one.
- **Done outside the chat process:**
  - chat history → read the `.jsonl` transcripts directly
  - auth status / logout → `claude auth …`
  - MCP add/remove → `claude mcp …`
  - plugins → `claude plugin …`
  - permission rules → `claude edit-permission-rules`
  - voice → its own WebSocket

---

## 2. Spawning the CLI

### 2.1 Exact command line (✅ captured from the live VS Code process)

```bash
claude \
  --output-format stream-json --verbose --input-format stream-json \
  --max-thinking-tokens 31999 \
  --permission-prompt-tool stdio \
  --setting-sources=user,project,local \
  --permission-mode acceptEdits \
  --include-partial-messages \
  --debug --debug-to-stderr \
  --enable-auth-status \
  --no-chrome \
  --replay-user-messages
```

- **cwd** = the project folder. Transcripts are stored per cwd (§8.11).
- **stdio** = 3 pipes. stderr carries only debug logs; drain it, but don't parse it.
- **env:** the extension passes the normal environment plus `claudeCode.environmentVariables`.
  `CLAUDE_CODE_ENTRYPOINT=claude-vscode` marks the session as a VS Code one (`system:init.startup_timing.entrypoint`).
  Use your own value, e.g. `antigem`.
- **Close:** close stdin (graceful, after the current turn), or `SIGTERM`.

### 2.2 Flags

| Flag | Meaning |
|---|---|
| `--input-format stream-json` + `--output-format stream-json` + `--verbose` | **required**: NDJSON in and out, process stays alive |
| `--permission-prompt-tool stdio` | **required for a UI**: approvals come to you as `can_use_tool` |
| `--include-partial-messages` | token-by-token `stream_event`s (typing effect) |
| `--replay-user-messages` | echoes your user messages back with `isReplay:true` (confirms delivery and order) |
| `--enable-auth-status` | emits `auth_status` events |
| `--model <v>` | `default` \| `opus` \| `sonnet` \| `haiku` \| full id (e.g. `claude-sonnet-5-5`) |
| `--fallback-model <id>` | used on overload |
| `--effort low\|medium\|high\|xhigh\|max` | initial effort |
| `--thinking adaptive\|disabled`, `--max-thinking-tokens N` | initial thinking |
| `--thinking-display summarized` | **send readable thinking text** (default `updates` sends only token estimates, §8.2) |
| `--permission-mode <m>` | `default`, `acceptEdits`, `plan`, `auto`, `dontAsk`, `bypassPermissions` |
| `--allow-dangerously-skip-permissions` | needed before `bypassPermissions` can be used |
| `--resume=<id>` / `--continue` | reopen a chat / the latest chat in this cwd |
| `--fork-session` | with `--resume`: copy into a new session id |
| `--resume-session-at=<msgUuid>` | with `--resume`: cut history after that message (rewind the chat) |
| `--session-id=<uuid>` | choose the new session's id |
| `--no-session-persistence` | write no transcript |
| `--setting-sources=user,project,local` | which settings files to load |
| `--add-dir <p>` | extra allowed directory (repeatable) |
| `--allowedTools`, `--disallowedTools`, `--tools` | tool lists |
| `--mcp-config '<json>'`, `--strict-mcp-config` | extra MCP servers |
| `--max-turns N`, `--max-budget-usd X` | limits |
| `--no-chrome` | disable the Chrome integration |

---

## 3. Wire format

One JSON object per line, `\n`-terminated, in both directions.

**User message (host → CLI)** ✅
```json
{"type":"user","uuid":"<uuid>","session_id":"","parent_tool_use_id":null,
 "message":{"role":"user","content":[{"type":"text","text":"Hello"}]}}
```

**Control request (both directions)**
```json
{"type":"control_request","request_id":"<unique>","request":{"subtype":"<name>", ...params}}
```

**Control response (both directions)**
```json
{"type":"control_response","response":{"subtype":"success","request_id":"<same>","response":{...}}}
{"type":"control_response","response":{"subtype":"error","request_id":"<same>","error":"text","error_code":"bypass_not_launched"}}
```

**Cancel a pending control request:** `{"type":"control_cancel_request","request_id":"<id>"}`

**Ignore:** `{"type":"keep_alive"}`

---

## 4. A real turn, line by line

✅ From test 1 (`"Reply with exactly: hello"` on Sonnet). `<<` = CLI→host, `>>` = host→CLI.

```text
>> {"type":"control_request","request_id":"i1","request":{"subtype":"initialize"}}
<< {"type":"control_response","response":{"subtype":"success","request_id":"i1","response":{"models":[...],"commands":[...],...}}}
>> {"type":"user","uuid":"372d…","message":{"role":"user","content":[{"type":"text","text":"Reply with exactly: hello"}]},...}
<< {"type":"auth_status","isAuthenticating":false,"output":[],...}                  (first turn only)
<< {"type":"command_lifecycle","command_uuid":"372d…","state":"queued"}
<< {"type":"command_lifecycle","command_uuid":"372d…","state":"started"}
<< {"type":"system","subtype":"init","session_id":"356b…","model":"claude-sonnet-5-5","permissionMode":"default",...}
<< {"type":"system","subtype":"status","status":"requesting","user_message_uuids":["372d…"]}
<< {"type":"rate_limit_event","rate_limit_info":{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":0.08,...}}}}
<< {"type":"user","message":{...,"text":"Reply with exactly: hello"},"uuid":"372d…","isReplay":true}
<< {"type":"stream_event","event":{"type":"message_start","message":{"model":"claude-sonnet-5-5","usage":{...cache_creation...}}},"ttft_ms":1801}
<< {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}
<< {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}}
<< {"type":"assistant","message":{"id":"msg_011C…","content":[{"type":"text","text":"hello"}],"usage":{...}},"uuid":"f88e…"}
<< {"type":"stream_event","event":{"type":"content_block_stop","index":0}}
<< {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{...}}}
<< {"type":"stream_event","event":{"type":"message_stop"}}
<< {"type":"command_lifecycle","command_uuid":"372d…","state":"completed"}
<< {"type":"result","subtype":"success","is_error":false,"result":"hello","num_turns":1,"total_cost_usd":0.1265,...}
```

With tools, the turn continues after the first `assistant` (`tool_use`): a `can_use_tool` request (if approval is
needed), then `user` (`tool_result`), then the next `message_start`, until `result`.

---

## 5. All stdout events (CLI → host)

✅ Every type below was seen during the tests unless marked 📖.

### 5.1 Chat content

| Event | Key fields | Use |
|---|---|---|
| `stream_event` | `event` = Anthropic streaming event; `parent_tool_use_id`; on `message_start` also `ttft_ms`, `thinking_display` | live rendering |
| ↳ `message_start` | `message.model`, `message.id`, **`message.usage`** (incl. `cache_creation.ephemeral_1h_input_tokens` / `ephemeral_5m_input_tokens`) | start a bubble; **cache timer** (§8.4) |
| ↳ `content_block_start` | `content_block.type`: `text` \| `thinking` \| `tool_use` (`id`, `name`) | |
| ↳ `content_block_delta` | `delta.type`: `text_delta` (`text`), `thinking_delta` (`thinking`, `estimated_tokens`), `signature_delta`, `input_json_delta` (`partial_json`) | append |
| ↳ `content_block_stop`, `message_delta` (`stop_reason`, `usage`), `message_stop` | | |
| `assistant` | full message per content block: `message.content[]` = `text` / `thinking` / `tool_use`; `parent_tool_use_id` (≠null → subagent); `message.model == "<synthetic>"` → local slash-command output | final render |
| `user` | (a) your own message echoed back, `isReplay:true`; (b) tool results `content:[{"type":"tool_result","tool_use_id","content","is_error"}]` **plus `tool_use_result`** (structured: diff patch, stdout/stderr, answers…, §8.14); (c) `"[Request interrupted by user]"`; (d) slash-command echo `<command-name>/context</command-name>…` | |
| `result` | end of turn: `subtype` (`success` \| `error_during_execution` \| `error_max_turns` …), `is_error`, `result` (final text), `terminal_reason` (`completed` \| `aborted_streaming` …), `stop_reason`, `num_turns`, `duration_ms`, `duration_api_ms`, `ttft_ms`, `total_cost_usd` (session total), `usage`, `modelUsage` (per model: tokens, `costUSD`, `contextWindow`, `maxOutputTokens`), `permission_denials[]`, `fast_mode_state`, `session_id`, `user_message_uuids` | stop spinner, show cost |

### 5.2 State and progress

| Event | Example / fields | Use |
|---|---|---|
| `system:init` | `session_id`, `model`, `cwd`, `permissionMode`, `tools[]`, `mcp_servers[]`, `slash_commands[]`, `agents[]`, `skills[]`, `plugins[]`, `output_style`, `apiKeySource`, `claude_code_version`, `fast_mode_state`, `memory_paths`, `capabilities[]` — **sent at the start of every turn** | save `session_id` |
| `system:status` | `{"status":"requesting"}` (request sent) · `{"status":"compacting"}` · `{"status":null,"compact_result":"success"}` · **`{"permissionMode":"plan"}`** (mode changed, also after ExitPlanMode) | spinner, mode chip |
| `system:thinking_tokens` | `{"estimated_tokens":213,"estimated_tokens_delta":163}` | "Thinking… 213 tokens" |
| `system:compact_boundary` | `compact_metadata:{trigger:"manual"\|"auto",pre_tokens:35277,post_tokens:2910,duration_ms}` | divider |
| `system:task_started` / `task_updated` / `task_notification` | subagent lifecycle (§8.9) | agent cards |
| `system:permission_denied` | `{"tool_name":"Bash","decision_reason_type":"mode","message":"…don't ask mode…"}` | show the auto-deny |
| `system:session_title_changed` | `{"title":"Protocol test session"}` | tab title |
| `system:control_request_progress` | `{"request_id":"req_…","status":"started"}` (slow control requests, e.g. `side_question`) | |
| `system:commands_changed` 📖 | `commands:[...]` | refresh the slash menu |
| `system:session_state_changed` 📖 | `state`: `idle` \| `running` \| `requires_action` | |
| `command_lifecycle` | `{"command_uuid":"<your user uuid>","state":"queued"\|"started"\|"completed"}` | per-message status (queued badge) |
| `rate_limit_event` | `rate_limit_info:{status:"allowed",rateLimitType:"five_hour",resetsAt:1791441600,overageStatus,isUsingOverage,unifiedWindows:{five_hour:{utilization:0.08,resetsAt},seven_day:{…}}}` | live usage bar (§8.5) |
| `auth_status` | `{"isAuthenticating":false,"output":[]}` | login progress |
| `control_request` | CLI asks the host (§7) | |
| `control_response` | answer to one of your requests | |
| `keep_alive` 📖 | — | ignore |

---

## 6. Host → CLI control requests

`{"type":"control_request","request_id":ID,"request":{"subtype":..., ...}}`. "→" shows `response.response`.

### 6.1 Session

| subtype | params | → response | |
|---|---|---|---|
| `initialize` | optional: `hooks`, `systemPrompt`, `appendSystemPrompt`, `agents`, `title`, `sdkMcpServers`, `supportedDialogKinds`, `promptSuggestions` | `models[]`, `unavailable_models[]`, `commands[]`, `agents[]`, `account{email,organization,subscriptionType,apiProvider}`, `output_style`, `available_output_styles[]`, `current_permission_mode`, `fast_mode_state`, `fast_mode_disabled_reason`, `session_state`, `pid` | ✅ |
| `get_status` | — | `{"sections":[{"title":"Session","rows":[{"label":"Version","value":"2.1.292"},…]}]}` | ✅ |
| `generate_session_title` | `description`, `persist` | `{"title":"Testing protocol"}` | ✅ |
| `rename_session` | `title`, `source:"host"`, `session_id` | `{}` (+ `system:session_title_changed`) | ✅ |
| `export_conversation` | — | `{"text":"\n❯ Run exactly…\n● The command was rejected…","default_filename":…}` | ✅ |
| `side_question` | `question`, `history?` | `{"response":"None","synthetic":false}` (does not enter the chat) | ✅ |
| `set_cwd` | `path`, `trust_accepted?` | | 📖 |

### 6.2 Turn control

| subtype | params | → response | |
|---|---|---|---|
| `interrupt` | `cancel_queued?` | `{"still_queued":[]}`; the turn ends with `result:error_during_execution`, `terminal_reason:"aborted_streaming"` | ✅ |
| `cancel_async_message` | `message_uuid` | `{"cancelled":true}` | 📖 |
| `stop_task` | `task_id` | stop one subagent / background task | 📖 |
| `background_tasks` | `tool_use_id` | `{"backgrounded":true}` — send a running Bash to the background | 📖 |
| `get_task_output` | `task_id` | task output | 📖 |
| `rewind_files` | `user_message_id`, `dry_run` | `{"canRewind":true,"filesChanged":[".../hello.txt"],"insertions":1,"deletions":1}` | ✅ (dry run) |

### 6.3 Model, thinking, effort, mode

| subtype | params | → response | |
|---|---|---|---|
| `set_model` | `model`: `default`/`opus`/`sonnet`/`haiku`/id | `{}` — the next turn uses the new model, no restart | ✅ |
| `set_max_thinking_tokens` | `max_thinking_tokens` (`0` = off), `thinking_display` (`"summarized"` \| `null`) | `{}` | ✅ |
| `apply_flag_settings` | `settings:{effortLevel:"low"}` (also `model`, `outputStyle`, `ultracode`…) | `{}`; check with `get_settings().applied` | ✅ |
| `set_permission_mode` | `mode` | `{"mode":"plan"}` (+ `system:status`); `bypassPermissions` without the launch flag → error `bypass_not_launched` | ✅ |

### 6.4 Settings

| subtype | params | → response | |
|---|---|---|---|
| `get_settings` | — | `{"effective":{…merged settings…},"sources":[{source:"userSettings",settings}],"applied":{"model":"claude-sonnet-5-5","effort":"low",…}}` | ✅ |
| `update_settings` | `source:"userSettings"\|"localSettings"`, `settings` | writes the file (the extension saves `effortLevel` this way) | 📖 |
| `list_permission_rules` | — | `{"state":{"rules":[{"behavior":"allow","source":"userSettings","rule":"Bash(git log *)","description":{…},"editability":"persistent"}]}}` | ✅ |
| `get_hooks_listing`, `get_memory_dialog`, `get_skills_dialog`, `get_sandbox_dialog` | — | data for those screens | 📖 |
| `reload_plugins`, `reload_skills`, `reload_output_styles` | — | rescan from disk | 📖 |

### 6.5 Usage and context

| subtype | → response | |
|---|---|---|
| `get_usage` (`skip_behaviors?`) | `{"session":{"total_cost_usd","total_duration_ms","total_lines_added","model_usage":{…}},"subscription_type":"pro","rate_limits":{"five_hour":{"utilization":3,"resets_at":"…"},"seven_day":{…},"limits":[{"kind":"session","percent":3,"resets_at"},{"kind":"weekly_all","percent":7}],"extra_usage":{…},"spend":{…}}}` — marked experimental in the SDK | ✅ |
| `get_context_usage` | `{"categories":[{"name":"System prompt","tokens":2433},{"name":"Messages","tokens":11928},{"name":"Free space",…},{"name":"Autocompact buffer","tokens":33000}],"totalTokens":35277,"maxTokens":1000000,"percentage":4,"gridRows":[…]}` | ✅ |

### 6.6 Login (inside a process) — see §8.12

| subtype | params | → response | |
|---|---|---|---|
| `claude_authenticate` | `loginWithClaudeAi: true` (subscription) / `false` (Console) | `{"manualUrl":"https://claude.com/cai/oauth/authorize?…redirect_uri=https://platform.claude.com/oauth/code/callback…","automaticUrl":"…redirect_uri=http://localhost:42981/callback…"}` | ✅ |
| `claude_oauth_wait_for_completion` | — | resolves when login finishes | 📖 |
| `claude_oauth_callback` | `authorizationCode`, `state` | finish with a pasted code | 📖 |

### 6.7 MCP

`mcp_status` ✅ → `{"mcpServers":[{"name","status":"connected","serverInfo","config","scope","tools":[{"name","annotations"}]}]}`.
📖 `mcp_toggle {serverName,enabled}`, `mcp_reconnect {serverName}`, `mcp_set_servers {servers}`,
`mcp_authenticate {serverName,redirectUri?}`, `mcp_oauth_callback_url {serverName,callbackUrl}`,
`mcp_clear_auth {serverName}`, `mcp_read_resource {serverName,uri}`, `set_mcp_permission_mode_override {serverName,mode}`.

### 6.8 Misc 📖
`read_file {path,max_bytes?}`, `seed_read_state {path,mtime}`, `get_plan`, `set_prompt_suggestions_paused {paused}`,
`message_rated {messageUuid,sentiment,surface}`, `submit_feedback {description,…}`, `remote_control`, `claim_session`,
Chrome controls (`get_chrome_dialog`, `get_chrome_browsers`, `select_chrome_browser`, `set_chrome_browser_hints`),
`ultrareview_launch`.

---

## 7. CLI → Host control requests

Reply with the same `request_id`:
`{"type":"control_response","response":{"subtype":"success","request_id":ID,"response":{...}}}`

### 7.1 `can_use_tool` — approval prompt ✅

Real request (Bash, default mode):
```json
{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash",
 "input":{"command":"touch deny_me.txt","description":"Create empty file deny_me.txt"},
 "description":"Create empty file deny_me.txt",
 "permission_suggestions":[
   {"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"touch deny_me.txt"}],"behavior":"allow","destination":"localSettings"},
   {"type":"addDirectories","directories":["/…/work"],"destination":"session"},
   {"type":"setMode","mode":"acceptEdits","destination":"session"}],
 "blocked_path":"/…/work/deny_me.txt",
 "tool_use_id":"toolu_01Wr…"}
```
Write requests look similar (`input:{file_path,content}`, suggestion `setMode acceptEdits`). Other possible fields:
`decision_reason`, `title`, `agent_id` (subagent), `requires_user_interaction`, `mcp_server`.

| Answer | `response` |
|---|---|
| Allow once | `{"behavior":"allow","updatedInput":<input, or an edited copy>}` |
| Allow always | same, plus `"updatedPermissions":[<the ONE suggestion the user chose>]` |
| Deny | `{"behavior":"deny","message":"User rejected this command"}` → appears in `result.permission_denials` |
| Deny and stop | `{"behavior":"deny","message":"…","interrupt":true}` |

⚠ Send back only the suggestion the user picked. In the test I returned all three; that also applied
`setMode acceptEdits`, which silently switched the session to Accept-Edits.

When no prompt comes: read-only commands (`ls`, `cat`…), anything matching an allow rule, and the modes in §8.3.

### 7.2 `AskUserQuestion` — Claude asks the user ✅
```json
{"subtype":"can_use_tool","tool_name":"AskUserQuestion","requires_user_interaction":true,
 "input":{"questions":[{"question":"Which color do you prefer?","header":"Color",
   "options":[{"label":"Red","description":"Choose red"},{"label":"Blue","description":"Choose blue"}],
   "multiSelect":false}]}}
```
Answer = **allow** with `answers` added to the input:
```json
{"behavior":"allow","updatedInput":{"questions":[…same…],"answers":{"Which color do you prefer?":"Blue"}}}
```
For multi-select, join labels with `", "`. For "Other", put the typed text in place of the label. Claude then sees
`Your questions have been answered: "Which color do you prefer?"="Blue"`.

### 7.3 `ExitPlanMode` — plan approval ✅
```json
{"subtype":"can_use_tool","tool_name":"ExitPlanMode","requires_user_interaction":true,
 "input":{"plan":"1. Use the Write tool to create goodbye.txt…\n2. Verify…","planFilePath":"/home/cat/.claude/plans/plan-how-to-add-piped-simon.md"}}
```
- **Allow** = approve. The CLI emits `system:status {"permissionMode":"default"}` and starts working.
  To approve straight into Accept-Edits, call `set_permission_mode` afterwards.
- **Deny** with a message = "keep planning", and the message is your feedback.
- The plan is also saved as a Markdown file (`planFilePath`).

### 7.4 Other requests 📖

| subtype | reply |
|---|---|
| `hook_callback` (`callback_id`, `input`) | hook output, e.g. `{}` |
| `mcp_message` (`server_name`, `message`) — your in-process MCP server | `{"mcp_response":<JSON-RPC>}` |
| `elicitation` (MCP asks for input) | `{"action":"accept","content":{…}}` / `{"action":"decline"}` |
| `request_user_dialog` (`dialog_kind`, `payload`) | dialog result |

Unknown subtype → reply `{"subtype":"error","request_id":ID,"error":"Unsupported"}`. If the CLI sends
`control_cancel_request` for a pending prompt (e.g. after an interrupt), close that dialog.

---

## 8. Feature guides

### 8.1 Models ✅

- **List:** `initialize.models[]`. Each entry is `{value, resolvedModel, displayName, description, supportsEffort,
  supportedEffortLevels[], supportsAdaptiveThinking, supportsFastMode, supportsAutoMode}`.
  `unavailable_models[]` entries carry `disabled:true`.
  Real values: `default` → `claude-opus-5-5` "Default (recommended)", `opus`, `sonnet`, `haiku`, …
- **Switch live:** `set_model {"model":"haiku"}`. The next turn's `system:init.model` and `assistant.message.model`
  show the new model. ✅ Tested Sonnet → Haiku → Sonnet in one process.
- **Current:** `get_settings().applied.model`, or `system:init.model`.
- **Make it the default for new chats:** the extension also writes `"model"` to `~/.claude/settings.json` (null for
  "default") and calls `apply_flag_settings`.
- **Per-model limits:** `result.modelUsage[model].contextWindow` / `maxOutputTokens`
  (Sonnet 5.5: 1,000,000 / 128,000; Haiku 4.5: 200,000 / 32,000).

### 8.2 Thinking and effort ✅

The extension has two separate controls:

**Thinking on/off** (`set_thinking_level` in the UI):
- off → `set_max_thinking_tokens {max_thinking_tokens:0}`
- on → `set_max_thinking_tokens {max_thinking_tokens:31999, thinking_display:"summarized" | null}`
- It spawns with `--max-thinking-tokens 31999`.

**Effort level** (low · medium · high · xhigh · max; read the allowed list from `models[].supportedEffortLevels`):
- live: `apply_flag_settings {settings:{effortLevel:"low"}}` → `get_settings().applied.effort == "low"` ✅
- persist: `update_settings {source:"userSettings", settings:{effortLevel}}`
- at spawn: `--effort <level>`
- With nothing set, Sonnet reported `effort:"medium"`.

**Thinking display — important:**

| Mode | What you receive |
|---|---|
| `updates` (default) | `thinking_delta` with **empty** `thinking:""` plus `system:thinking_tokens` estimates → the UI can only show "Thinking… 213 tokens" |
| `summarized` (`--thinking-display summarized` at spawn, or `thinking_display:"summarized"` in `set_max_thinking_tokens`) | real text: `thinking_delta {"thinking":" working through Euclid's classic argument…"}`, and the final `assistant` thinking block holds the full summary ✅ |

Models with adaptive thinking decide for themselves when to think; easy prompts get no thinking block.

### 8.3 Permission modes ✅

| Mode (UI label) | Behaviour seen in tests |
|---|---|
| `default` ("Manual") | prompts (`can_use_tool`) for edits and non-read-only Bash; read-only commands run without a prompt |
| `acceptEdits` | file edits and file-creating commands in the workspace run without a prompt (`touch` ran) |
| `plan` | Claude only writes its plan file, then calls `ExitPlanMode` (§7.3) |
| `auto` | a classifier decides; `touch auto_mode.txt` ran without a prompt. Only available when `models[].supportsAutoMode` |
| `dontAsk` | never prompts; whatever isn't pre-allowed is denied → `system:permission_denied` + `result.permission_denials` |
| `bypassPermissions` | everything allowed; needs `--allow-dangerously-skip-permissions` at spawn, otherwise `error_code:"bypass_not_launched"` |

Switch with `set_permission_mode`; confirmation arrives as `system:status {"permissionMode":…}`.
Initial mode: `--permission-mode`. The extension remembers the last user-chosen
`default`/`auto`/`acceptEdits` as the default for new chats (setting `claudeCode.initialPermissionMode`).

### 8.4 Prompt-cache timer ("cache warm, ~N min left") ✅

This is computed entirely **client-side** from the `usage` the CLI already sends. Algorithm from `webview/index.js`:

1. On each main-agent `stream_event` `message_start` (where `parent_tool_use_id` is null), read `message.usage`.
2. Cache TTL:
   - `usage.cache_creation.ephemeral_1h_input_tokens > 0` → **1h**
   - else `ephemeral_5m_input_tokens > 0` → **5m**
   - only if `cache_read_input_tokens + cache_creation_input_tokens > 0`
3. **anchor** = when the user message was sent (the earlier of the send time and the frame receive time).
4. `msLeft = min(ttl, anchor + ttl − now)`
   - `> 0` → **warm**: "Prompt cache warm, about ⌈msLeft/60000⌉ min left."
   - otherwise → **cold**: "Prompt cache likely expired (idle 12m)." On resume it adds "…your next message will re-cache
     about 35k tokens", where tokens = `input + cache_read + cache_creation`.
5. After `/compact` (`compact_boundary`) the state is cold: "Prompt cache does not cover the compacted conversation."

The tests showed `ephemeral_1h_input_tokens` on a Pro subscription, so the TTL was **1 hour**.
Cost is lower on cache hits; the tests show `cache_read_input_tokens` growing turn after turn.

### 8.5 Usage, limits, cost, context ✅

| What | Source |
|---|---|
| 5-hour / weekly plan usage (live, every turn) | `rate_limit_event.rate_limit_info.unifiedWindows.{five_hour,seven_day}.utilization` (0–1) + `resetsAt` (unix s) |
| Full usage panel | `get_usage` → `rate_limits.limits[]` (`kind`, `percent`, `severity`, `resets_at`), `extra_usage`, `spend`, `seven_day_breakdown` |
| Session cost | `result.total_cost_usd` (running total), `get_usage().session` |
| Per-model tokens | `result.modelUsage` |
| Context bar | `get_context_usage` → `percentage`, `totalTokens / maxTokens`, `categories[]`; quick estimate: last `usage.input_tokens + cache_read + cache_creation` vs `modelUsage[m].contextWindow` |
| Speed | `result.ttft_ms`, `duration_ms`, `duration_api_ms` |

Cost on a subscription is list-price accounting only; real limits are the percentages.

### 8.6 Images, files, @-mentions, editor context ✅

The webview builds `message.content[]` like this (from `PC1()` in `webview/index.js`):

| Attachment | Content block | |
|---|---|---|
| Image (jpeg/png/gif/webp only) | `{"type":"image","source":{"type":"base64","media_type":"image/png","data":"<b64>"}}` | ✅ "Red" |
| Text file (text/*, known code extensions, README/LICENSE…) | `{"type":"document","source":{"type":"text","media_type":"text/plain","data":"<utf8 text>"},"title":"secret.txt"}` | ✅ "4417" |
| PDF | `{"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"<b64>"},"title":"x.pdf"}` | 📖 |
| `@path/to/file` in the text | leave it as plain text — **the CLI expands it** and inlines the file without a Read tool call | ✅ "BLUE-FALCON" |
| Editor selection | `{"type":"text","text":"<ide_selection>The user selected the lines 10 to 20 from /abs/file.kt:\n<code>\n\nThis may or may not be related to the current task.</ide_selection>"}` | 📖 |
| Open file (no selection) | `{"type":"text","text":"<ide_opened_file>The user opened the file /abs/file.kt in the IDE. This may or may not be related to the current task.</ide_opened_file>"}` | 📖 |
| `@terminal:name` | `{"type":"text","text":"<terminal name=\"name\">\n<output>\n</terminal>"}` | 📖 |

Order: context blocks → attachments → terminal blocks → the typed text last.
Pasted long text: optional `"inline_pastes":[…]` on the user message (VS Code UI hint; not needed).
For an @-file picker, list project files yourself (the extension uses `list_files_request` → VS Code file search,
respecting `.gitignore`).

### 8.7 Voice dictation (speech-to-text) 📖

**Not part of the CLI.** The extension records the microphone and streams it to Anthropic itself; the final
transcript is simply typed into the input box.

- **Endpoint:**
  `wss://api.anthropic.com/api/ws/speech_to_text/voice_stream?encoding=linear16&sample_rate=16000&channels=1&endpointing_ms=300&utterance_end_ms=1000&language=en&use_conversation_engine=true`
- **Headers:**
  - `Authorization: Bearer <claudeAiOauth.accessToken>` — from `~/.claude/.credentials.json`; refresh when `expiresAt`
    is near. Requires a claude.ai login, not an API key.
  - `x-app: vscode`
  - `anthropic-client-platform: <platform>`
  - optional `x-config-keyterms: VS Code,MCP,…,<project name>,<git branch words>` (comma list, ≤1024 chars — biases
    recognition)
- **Audio:** raw PCM, **16 kHz, mono, signed 16-bit little-endian**, sent as **binary** WebSocket frames.
  - native capture addon (`resources/audio-capture/<arch>/audio-capture.node`), or a fallback to
    `rec -q --buffer 1024 -t raw -r 16000 -e signed -b 16 -c 1 -` / `arecord -q -f S16_LE -r 16000 -c 1 -t raw`
  - On Android: `AudioRecord(MIC, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)`.
- **Client text frames:**
  - `{"type":"KeepAlive"}` right after open, then every 8 s
  - `{"type":"CloseStream"}` to finish (wait up to 3 s for close)
- **Server frames (JSON):**

  | Frame | Meaning |
  |---|---|
  | `TranscriptInterim` / `TranscriptText` | `data` = current partial text of the utterance |
  | `TranscriptEndpoint` | the utterance ended → commit the last partial text |
  | `TranscriptError` | `description` |
  | `error` | `message` |

- **Behaviour:**
  - committed utterances are joined with spaces
  - auto-stop after 15 s with no transcript, or after 120 s in total
  - one retry if an error arrives before the first transcript
  - a 4xx on connect is fatal

### 8.8 Stop, queue, interrupt ✅

- **Stop:** `interrupt`.
  - Response: `{"still_queued":[]}`.
  - The stream stops and the CLI writes a `user` message `"[Request interrupted by user]"`.
  - The turn ends with `result {subtype:"error_during_execution", is_error:true, terminal_reason:"aborted_streaming"}`.
  - The partial `assistant` text is kept.
- **Queue:** send another user message while a turn runs. It gets `command_lifecycle queued`, then runs as its own
  turn with its own `result`. ✅ Two back-to-back messages gave two results ("first", "second").
- **Drop queued messages:** `interrupt {cancel_queued:true}` (returns `cancelled[]`) or
  `cancel_async_message {message_uuid}`.

### 8.9 Subagents and background tasks ✅

When Claude uses the `Agent`/`Task` tool:
1. `assistant[tool_use name:"Agent"]`
2. `system:task_started` — `task_id`, `tool_use_id`, `description`, `subagent_type`, `is_backgrounded`, `prompt`
3. subagent frames with **`parent_tool_use_id = <Agent tool_use_id>`** and `agent_id` — render them nested, or hide
4. `system:task_updated` — `patch:{status:"completed",end_time}`
5. `system:task_notification` — `status`, `summary:"42"`, `usage:{total_tokens,tool_uses,duration_ms}`, `output_file`
6. `user[tool_result]` for the Agent call

Control: `stop_task {task_id}`, `get_task_output {task_id}`, `background_tasks {tool_use_id}`.
`result.subagent_stats` gives counts.

### 8.10 Slash commands and compaction ✅

- **Send as text:** `{"type":"text","text":"/compact"}`. Get the list from `initialize.commands[]`
  (`name`, `description`, `argumentHint`) or `system:init.slash_commands`.
- **Local commands** (`/context`, `/cost`, `/status`…) make no model call. You receive:
  - the echo `user` message `<command-name>/context</command-name>…`
  - an `assistant` with `message.model:"<synthetic>"` whose text is Markdown (e.g. "## Context Usage …")
  - `result` with `num_turns:0`, `duration_api_ms:0`
- **`/compact`:** `system:status compacting` → `system:status {compact_result:"success"}` → `system:init` →
  `system:compact_boundary {compact_metadata:{trigger:"manual",pre_tokens:35277,post_tokens:2910}}` → `result`.
  Auto-compaction sends the same with `trigger:"auto"`.
- Prompt-type commands and skills run a normal model turn.

### 8.11 Sessions and history ✅

| Action | How |
|---|---|
| Session id | `system:init.session_id` / `result.session_id` |
| Transcript file | `~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl`; `<sanitized-cwd>` = realpath with every non `[A-Za-z0-9]` char → `-` (long paths: first 200 chars + hash) |
| List chats | scan the folder, sort by mtime, read head/tail lines for title / last prompt |
| Open chat | parse the file for display, and spawn `--resume=<id>` ✅ (Claude remembered the earlier edit) |
| Fork | `--resume=<id> --fork-session` → new `session_id`, new file ✅ |
| Rewind chat to a message | `--resume=<id> --resume-session-at=<msgUuid>` (+ `rewind_files` for the file changes) |
| Rename | `rename_session` ✅ (writes a `custom-title` line), or append `{"type":"custom-title","sessionId","customTitle"}` |
| Auto title | the CLI writes `ai-title` lines by itself; `generate_session_title` on demand ✅ |
| Archive | extension-only list of hidden ids (nothing changes on disk) |
| Delete | delete the `.jsonl` (or `claude purge <path>` for a whole project) |

Transcript line `type`s seen:

| Type | Use |
|---|---|
| `user`, `assistant` | messages (`uuid`, `parentUuid`, `isSidechain`, `timestamp`, `cwd`, `gitBranch`) |
| `system` (`local_command`, `compact_boundary`) | shown inline |
| `custom-title` (`customTitle`) | title — wins over `ai-title` |
| `ai-title` (`aiTitle`) | title |
| `last-prompt` (`lastPrompt`) | list preview |
| `agent-name`, `mode` | session metadata |
| `cost-state` (`totalCostUSD`, `modelUsage`) | session cost |
| `summary` | compaction summary |
| `attachment`, `file-history-snapshot`, `file-history-delta`, `queue-operation`, `atis-latch` | internal — skip |

To render: follow `parentUuid` back from the newest leaf and drop `isSidechain:true` (subagent) lines.

### 8.12 Login / logout ✅

- **Status (no chat needed):** `claude auth status --json` →
  `{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"…","orgId":"…","orgName":"…","subscriptionType":"pro","configDirectory":"/home/cat/.claude"}`
- **Login (inside any stream-json process; the extension starts a throwaway one if no chat is open):**
  1. `claude_authenticate {loginWithClaudeAi:true}` → `{manualUrl, automaticUrl}` ✅
  2. Send `claude_oauth_wait_for_completion` right away; it resolves when login completes.
  3. Open **`automaticUrl`**. Its redirect is `http://localhost:<port>/callback` served by the CLI, so on Android/Termux
     a browser on the same phone completes it automatically.
  4. Fallback: open **`manualUrl`**. Its redirect is `platform.claude.com/oauth/code/callback`, which shows a code
     `code#state`. Send `claude_oauth_callback {authorizationCode, state}`.
  5. Run `claude auth status --json` again.
- **Logout:** `claude auth logout` (exit 0 = ok).
- **Tokens:** stored in `~/.claude/.credentials.json` → `claudeAiOauth {accessToken, refreshToken, expiresAt, scopes,
  subscriptionType, rateLimitTier}`. Only voice (§8.7) needs to read them.
- Alternative: `claude setup-token` (long-lived token) or the `CLAUDE_CODE_OAUTH_TOKEN` / `ANTHROPIC_API_KEY` env vars.

### 8.13 Settings, permission rules, MCP, plugins 📖

| Feature | How the extension does it |
|---|---|
| Read settings | `get_settings` (effective + per-source + applied) ✅ |
| Write user settings | edits `~/.claude/settings.json`, then `apply_flag_settings` so the live session picks it up |
| Local project settings | `update_settings {source:"localSettings"}` → `.claude/settings.local.json` |
| Output style | `apply_flag_settings {outputStyle:"Explanatory"}`; list in `initialize.available_output_styles` |
| Permission rules add/remove | `claude edit-permission-rules --json` with stdin `{"op":"add"\|"remove","rules":["Bash(npm test)"],"behavior":"allow"\|"deny"\|"ask","destination":"userSettings"\|"projectSettings"\|"localSettings"}`; then poll `list_permission_rules` |
| MCP add | `claude mcp add --scope <user\|project\|local> --transport stdio [--env K=V…] -- <name> <cmd> <args…>`, or `--transport http\|sse [--header "K: V"…] -- <name> <url>` |
| MCP remove | `claude mcp remove --scope <s> -- <name>`; afterwards `mcp_reconnect` / `mcp_toggle` on the live process |
| Plugins | `claude plugin list --json`, `claude plugin uninstall …`, `claude plugin configure …`, `claude plugin marketplace add\|remove\|update\|list --json`; then `reload_plugins` |
| Hooks / memory / sandbox / skills editors | hidden subcommands `claude edit-hook\|edit-memory-settings\|edit-sandbox-settings\|edit-skill-overrides --json` (JSON on stdin) |
| Memory files (CLAUDE.md) | edited as plain files; paths from `get_memory_dialog` / `system:init.memory_paths` |

### 8.14 File diffs (edit preview) ✅

Every tool result `user` frame carries a structured **`tool_use_result`**, so you can render diffs without parsing
text.

- **Edit** →
  ```json
  {"filePath":"…/hello.txt","oldString":"hi there","newString":"hello world","originalFile":"hi there",
   "structuredPatch":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-hi there","+hello world"]}],"replaceAll":false}
  ```
- **Write** → `{"type":"create","filePath","content","structuredPatch":[],"originalFile":null}`
- **Bash** → `{"stdout","stderr","interrupted","isImage"}`
- **Read** → `{"type":"text","file":{"filePath","content","numLines","startLine","totalLines"}}`
- **AskUserQuestion** → `{"questions","answers"}`

Before approval, the `can_use_tool` input (`old_string`/`new_string`, or `content`) is enough to show a preview.
"Undo" = `rewind_files`.

The VS Code-only extras (in-editor diff tabs, the `claude-vscode` MCP server with `getDiagnostics`/`executeCode`,
`openDiff`, `getOpenEditors`…) are IDE integration and not needed for a phone app.

---

## 9. VS Code extension settings → what they do

| Setting (default) | Effect / protocol |
|---|---|
| `environmentVariables` ([]) | extra env for the spawned CLI |
| `useTerminal` (false) | use the terminal TUI instead of the stream-json UI |
| `allowDangerouslySkipPermissions` | adds `--allow-dangerously-skip-permissions`, unlocks Bypass mode |
| `claudeProcessWrapper` | runs the CLI through a wrapper executable |
| `initialPermissionMode` | `--permission-mode` for new chats (`manual` = `default`) |
| `disableLoginPrompt` (false) | never show login UI |
| `autosave` (true) | PreToolUse hook saves dirty editor files before Read/Edit/Write |
| `focusView` (false) | UI only: hide tool calls |
| `useCtrlEnterToSend`, `scrollToBottomOnSend`, `showMessageTimestamps` | UI only |
| `preferredLocation`, `lockEditorGroups`, `enableNewConversationShortcut`, `enableReopenClosedSessionShortcut`, `hideOnboarding` | UI only |
| `attachOpenFile` (true) | adds the `<ide_opened_file>` / `<ide_selection>` block (§8.6) |
| `continueAfterReload` (true) | on reload: `--resume` and continue an interrupted turn |
| `respectGitIgnore` (true) | the @-file picker skips ignored files |
| `archiveInactiveSessions` (14 days) | auto-hide old chats in the list |
| `usePythonEnvironment` (true) | activates the workspace venv in the CLI env |

Everything else (model, effort, permissions, hooks, MCP, output style…) lives in Claude's own `settings.json` files
(§8.13).

---

## 10. UI action → protocol cheat sheet

| UI action | Protocol |
|---|---|
| New chat | spawn → `initialize` |
| Send text / image / file | `{"type":"user",…content[]}` (§8.6) |
| Stop | `interrupt` |
| Model picker | list `initialize.models`; switch `set_model` |
| Effort slider | `apply_flag_settings {effortLevel}` |
| Thinking toggle | `set_max_thinking_tokens` (0 / 31999) |
| Show thinking text | spawn `--thinking-display summarized` |
| Mode chip (Manual / Accept edits / Plan / Auto / Don't ask / Bypass) | `set_permission_mode`; listen to `system:status.permissionMode` |
| Approve / deny tool | reply to `can_use_tool` |
| Answer Claude's question | `can_use_tool` AskUserQuestion → allow + `updatedInput.answers` |
| Approve plan | `can_use_tool` ExitPlanMode → allow |
| Cache timer | §8.4 from `message_start.usage` |
| 5h / weekly bars | `rate_limit_event`, `get_usage` |
| Context % | `get_context_usage` |
| Cost | `result.total_cost_usd` |
| Slash menu | `initialize.commands`; send `/cmd` as text |
| Compact | send `/compact` |
| Voice | own WebSocket (§8.7) → put the text in the input box |
| History list | scan `~/.claude/projects/<cwd>/*.jsonl` |
| Open old chat | spawn `--resume=<id>` |
| Fork / rewind | `--fork-session`, `--resume-session-at`, `rewind_files` |
| Rename | `rename_session` |
| Login / logout / account | §8.12 |
| MCP / plugins / rules | §8.13 |
| Close tab | close stdin / SIGTERM |

---

## 11. Notes for the Android / Kotlin implementation

- **One `ClaudeSession` per chat:**
  - `ProcessBuilder(claude, flags…).directory(cwd)`, run inside the proot/Termux environment.
  - stdin through a `BufferedWriter` behind a Mutex.
  - stdout: a reader coroutine splitting on `\n`, emitting into a `SharedFlow<ClaudeEvent>`.
  - stderr: drained on its own thread, or the pipe fills up and the CLI blocks.
- **Pending control requests:** `ConcurrentHashMap<String, CompletableDeferred<JsonObject>>`. Complete on
  `control_response`; fail all of them when the process exits.
- **Long lines:** a single line can be several MB (base64 images, big tool output). Use a reader with no line limit.
- **Typed models:** this protocol is JSON (there is no proto schema), so define `@Serializable` sealed classes for the
  events in §5 instead of `JSONObject` everywhere. Decode with `ignoreUnknownKeys = true`, and keep a `Raw` fallback
  for unknown `type`s — new event types appear often (`command_lifecycle`, `rate_limit_event`, `thinking_tokens`… are
  all recent).
- **Memory:** each CLI process holds a lot of RAM; keep only the active chat(s) alive and `--resume` the others when
  needed.
- **Keep the session alive across app restarts:** store `session_id`, then reopen with `--resume`.
- **Stable vs experimental:** `get_usage` is marked experimental in the SDK, and some fields (the `rate_limits` keys)
  change between versions.
- **Reproduce the tests:** the Python harness used for this doc is a ~120-line script; spawn with the §2.1 flags,
  send `initialize`, then user messages, answering `can_use_tool` as in §7.
