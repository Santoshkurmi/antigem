<div align="center">

<img src="docs/readme/icon.svg" alt="AntiGem app icon" width="112">

# AntiGem

*A gem that defies gravity. A full coding environment, light enough to carry in your pocket.*

**An AI coding workspace for Android: Antigravity, Claude Code, OpenCode and Codex agents, a real Linux terminal, a code editor and a browser the agent can control, all in one app.**

[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Go](https://img.shields.io/badge/Bridge-Go-00ADD8?style=flat-square&logo=go&logoColor=white)](https://go.dev)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Releases](https://img.shields.io/github/v/release/santoshkurmi/antigem?style=flat-square&label=Download)](https://github.com/santoshkurmi/antigem/releases)

[Download](https://github.com/santoshkurmi/antigem/releases) · [Website](https://santoshkurmi.github.io/antigem/) · [VS Code extension](https://github.com/Santoshkurmi/antigem_vscode_extension) · [Plugin SDK](extension_docs/README.md) · [User Guide](user_docs/README.md) · [WhatsApp community](https://chat.whatsapp.com/IGQX8q9zSVV0RarG70sclV?s=cl&p=a&mlu=4&ilr=4)

<br>

<img src="docs/readme/hero.webp" alt="AntiGem on a phone: code editor, live browser preview, Claude Code chat, Linux terminal and model picker" width="100%">

</div>

---

## See it in action

<div align="center">

A full tour on a real phone: launch, chats, settings and themes, then Claude Code builds an animated solar system from one prompt, and it runs in the app's own terminal and browser. Real recording, sped up.

<video src="https://github.com/user-attachments/assets/821731b9-9713-4988-b358-09dc62aef006" width="360" controls autoplay loop muted playsinline></video>

</div>

---

## Screenshots

<table>
  <tr>
    <td colspan="4" align="center"><b>🤖 Agents that actually code</b></td>
  </tr>
  <tr>
    <td align="center" width="25%"><img src="docs/readme/screens/agent_picker.webp" alt="Pick Antigravity, Claude Code, OpenCode or Codex" width="200"><br><sub>Four agents, one app</sub></td>
    <td align="center" width="25%"><img src="docs/readme/screens/chat_agent_working.webp" alt="Claude Code working: thinking, to-do list and file edits" width="200"><br><sub>Thinking, to-dos and live edits</sub></td>
    <td align="center" width="25%"><img src="docs/readme/screens/chat_agent_summary.webp" alt="Claude Code summary with token stats" width="200"><br><sub>Tool cards and token stats</sub></td>
    <td align="center" width="25%"><img src="docs/readme/screens/model_picker_usage.webp" alt="Model picker with 5-hour and weekly plan usage" width="200"><br><sub>Models and plan usage</sub></td>
  </tr>
  <tr>
    <td colspan="4" align="center"><b>🛠️ Build, run and preview on the phone</b></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/readme/screens/code_editor.webp" alt="Code editor with tabs and syntax highlighting" width="200"><br><sub>Code editor</sub></td>
    <td align="center"><img src="docs/readme/screens/terminal_fastfetch.webp" alt="Linux terminal running fastfetch and a web server" width="200"><br><sub>Real Linux terminal</sub></td>
    <td align="center"><img src="docs/readme/screens/browser_live_preview.webp" alt="Built-in browser previewing the app the agent built" width="200"><br><sub>Live preview in the browser</sub></td>
    <td align="center"><img src="docs/readme/screens/solar_system.webp" alt="Animated solar system the agent built from one prompt" width="200"><br><sub>Built from one prompt</sub></td>
  </tr>
  <tr>
    <td colspan="4" align="center"><b>⚙️ Make it yours</b></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/readme/screens/themes_light.webp" alt="Theme gallery with light and dark themes" width="200"><br><sub>Light and dark themes</sub></td>
    <td align="center"><img src="docs/readme/screens/settings.webp" alt="Settings" width="200"><br><sub>Settings</sub></td>
    <td align="center"><img src="docs/readme/screens/mcp_presets.webp" alt="One-tap MCP server presets" width="200"><br><sub>One-tap MCP servers</sub></td>
    <td align="center"><img src="docs/readme/screens/jarvis_settings.webp" alt="Jarvis voice assistant settings" width="200"><br><sub>Jarvis voice assistant</sub></td>
  </tr>
</table>

---

## What it is

AntiGem runs four coding agents on your phone:

- **Antigravity (`agy`)**: Google's official `agy` CLI, installed with Google's own installer, runs inside the app's Linux environment.
- **Claude Code**: Anthropic's official `claude` CLI runs inside the app's Linux environment, and the app talks to it over its stream-json protocol, with one CLI process per chat.
- **OpenCode**: the open-source coding agent. It works with 200+ providers with your own keys, any OpenAI-compatible API, and free models.
- **Codex**: OpenAI's official `codex` CLI runs inside the app's Linux environment, and the app talks to it through its `app-server` protocol, the same one the Codex VS Code extension uses. Sign in with your ChatGPT account.

You can turn on any of the four agents, on first launch or later in **Settings → Agents**. An agent you turn off does not run.

**Use the models you want:** OpenCode connects to 200+ providers, including local models with Ollama or LM Studio, and Claude Code can run on OpenRouter, Kimi, DeepSeek, Z.ai (GLM) or any Anthropic-compatible API. See [Bring your own models](#bring-your-own-models-third-party-providers).

The app reads each agent's structured output, not its terminal text. That is how it shows streaming replies, tool approvals, the agent's questions, subagents and file changes as normal chat UI.

---

## Features

### Bring your own models: third-party providers
You are not tied to one company's models. Two of the agents can use other providers with your own keys, and their models show in the same model picker as everything else.

**OpenCode: 200+ providers**
- Quick sign-in for the subscriptions people use most: ChatGPT / OpenAI, GitHub Copilot, your Claude subscription, SuperGrok / xAI, OpenCode Zen and Go, OpenRouter and the Anthropic API. Hundreds more are one search away, with an API key or a browser / device-code sign-in.
- **Any OpenAI-compatible or Anthropic-compatible API** by its address: local models with Ollama or LM Studio, vLLM, LiteLLM, or a company gateway.
- OpenCode's own free models work with no account at all.
- Choose which providers and models appear in the picker: switch them on or off one by one, or all at once.

**Claude Code: Anthropic-compatible providers**
- One-tap presets for **OpenRouter, Kimi (Moonshot), DeepSeek and Z.ai (GLM)**, plus any other Anthropic-compatible API with its base URL and key.
- Test the connection, then fetch the provider's model list. For OpenRouter you can fetch only the free models that can call tools.
- Their models sit in the model picker next to Claude's own, so you can switch per chat.

Antigravity uses your Google account and Codex your ChatGPT account.

### Agents and chat
- Live streaming replies, collapsible thinking, tool cards, diffs and subagent progress.
- Tool approvals, plan approval and the agent's multiple-choice questions, answered inside the chat.
- Switch model, effort, thinking and permission mode during a chat.
- Image and file attachments, voice dictation, and audio playback.
- Edit or regenerate a message, fork, rename, share, export and delete chats.
- Slash commands, plus your own custom slash commands that expand into full prompts.
- **Global context**: tell every chat, in every agent, about the app and your own standing instructions.
- Markdown, syntax-highlighted code, Mermaid diagrams and an artifacts drawer.
- A floating chat bubble you can use over other apps.

### Jarvis voice assistant
Use the whole app hands-free by voice, on top of Antigravity, Claude Code, OpenCode or Codex. Full guide: **[docs/jarvis.md](docs/jarvis.md)**.
- Open projects and files, switch screens, models and settings (theme too), search code, read chats, check your usage, set timers, and ask how the app works.
- **Tasks:** hand over big jobs ("fix the failing tests and tell me when it's done"). Each runs in its own chat; Jarvis tells you when it finishes, fails, gets stuck, needs an approval or asks a question, and you can redirect or stop it by voice.
- Approvals read out loud and answered by voice, with policies from "ask me each time" to "auto except risky".
- Wake it with the floating orb, a Quick Settings tile, the notification, a headset button, or a wake phrase (hands-free mode, works with the screen off).
- Send with your own phrases ("send it", "bhej do"), cancel with others ("scratch that"), or auto-send on a pause; it ignores what you say to other people.
- Listens again when it expects an answer, quiet mode, male / female / neutral persona, notes about you, device or online voices.
- Turn it on in **Settings → Jarvis**; it is off by default.

### Claude Code
- Install and update the official `claude` CLI from **Settings → Claude Code**.
- Sign in with a Claude subscription or an Anthropic Console account.
- **Third-party providers**: add any Anthropic-compatible API, such as OpenRouter, Kimi or DeepSeek, with your own key. OpenRouter is a one-tap preset that can fetch its free models. Provider models appear in the model picker next to Claude's own.
- Usage at a glance: prompt-cache timer, context window use, 5-hour and weekly plan limits, and token counts for each reply.
- Settings for chat defaults, permission rules, the command sandbox, MCP servers, plugins and marketplaces, `CLAUDE.md` and Claude's memory.

### Antigravity
- Runs Google's official `agy` CLI.
- Model picker with live quota, Google sign-in and account details.
- Security presets and per-project rules for command auto-run and file access.
- MCP servers, skills and Google plugins.
- **Cloud agents**: run Antigravity chats on a Google cloud machine (Remote Agents) instead of the phone.

### OpenCode
- The open-source coding agent, run through the bridge: its own CLI and its own providers.
- Sign in to a provider with an API key, a device-code / browser sign-in, or credentials already on the machine.
- **Custom providers**: add any OpenAI-compatible endpoint (Ollama, LM Studio, vLLM, LiteLLM…) or Anthropic-compatible one with your own base URL and key; free models work too. Its models show in the model picker like the other agents. See [Bring your own models](#bring-your-own-models-third-party-providers).
- Settings for the server, providers, endpoints, MCP servers and approvals: **Settings → OpenCode** ("Server, providers, custom OpenAI-compatible endpoints & approvals").

### Codex
- Runs OpenAI's official `codex` CLI.
- Install and update it from **Settings → Codex → Server & install**: on the phone that is the Termux package (`pkg install codex`); on a server, the official Linux build from Codex's GitHub releases.
- Sign in with your ChatGPT account, in the browser or with a device code.
- Usage at a glance: your plan's limits and when they reset.
- Permissions for new chats: what Codex asks before it runs commands or edits files. Approvals are answered inside the chat.
- Plan mode, `/review` of uncommitted changes, `/compact`, `/fork`, and messages you send while Codex is working are queued for the next turn.
- MCP servers: the built-in presets and your own local (command) or remote (HTTP) servers.

### Built-in MCP servers
Each one is a one-tap preset in **Settings → MCP Servers**, for every agent.

| MCP | What the agent can do |
| :--- | :--- |
| **Browser** | Open, switch and close tabs, click and type on pages, run JavaScript, inspect the DOM, read console logs, take screenshots, upload files, download URLs and control media. |
| **Terminal** | Create terminal sessions, send commands, read output, kill processes, and change the terminal's theme and font. |
| **Android** | Read the screen's UI tree, tap, swipe, scroll, type, press keys, take screenshots, launch and list apps, open intents, and (with ADB) install, uninstall or force-stop apps and run `adb shell`. |
| **Notes & To-dos** | List, read, create, edit and delete your notes and to-do items. |
| **Custom Plugin** | Use the tools of any installed AntiGem plugin. |
| **Plugin Developer** | Create, install, test and debug AntiGem plugins from the chat. |
| **Jarvis** | Control the app for the Jarvis voice mode: speak and ask, run and watch tasks, chats, models, settings, approvals, the IDE and more ([docs/jarvis.md](docs/jarvis.md)). |
| **Theme** | List, switch, create, edit, import and export the app's color themes. |
| **Stitch** | Google Stitch UI design, with your own API key. |
| **REA** | Reverse engineering: analyze APKs, Flutter, React Native and native code. Set it up first in **Settings → REA** (see [Reverse engineering](#reverse-engineering-rea)). |

### Reverse engineering (REA)
- **Settings → REA** installs [REA](https://github.com/morluto/rea) in an Ubuntu environment inside the app, then any agent can use it through the REA MCP preset.
- The agent can unpack and decompile APKs, and dig into Flutter, React Native and native libraries.
- Two modes: **on demand** (recommended: the agent gets 4 small tools and looks up only the REA tools it needs, which keeps the context small) or **direct** (every tool listed up front). Tool groups let you show only the tools you want.

### Build Android apps on the phone
- **Settings → Android Development** sets up everything to build **Kotlin / Jetpack Compose, Flutter and React Native** apps on the phone itself, and the agent can drive the builds.
- Builds target arm64 phones. For other CPU types, build on a PC.

### Custom AntiGem plugins
- Plugins are small packages with a `manifest.json` and a JavaScript worker that add MCP tools, and can include Markdown **skills** that teach the agent how to use them.
- They run in the app's own extension host, which implements a large part of the Chrome extension API (`tabs`, `scripting`, `storage`, `debugger` and more), so many Chrome extension workers can be ported.
- Install from a ZIP, or from a plugin repository on GitHub. The app checks repositories for updates in the background.
- Enable, disable, reload and update plugins without restarting the app.
- Full SDK, API reference and an example: [extension_docs/](extension_docs/README.md).

### Linux terminal
- A full Termux-based Linux environment with `apt`/`pkg`, bash, Python, Node.js, Git, Go and more. No root and no proot.
- Multiple sessions, themes, font sizes, quick keys and text selection.
- Background service for long-running builds, servers and scripts.
- **ADB Connect**: connects the terminal's `adb` to the same phone over Wireless debugging, with pairing from a notification and automatic reconnects.
- **Linux desktop (X11)**: run an XFCE or LXQt desktop and Linux GUI apps through Termux:X11, set up from **Settings → Termux Support → X11 Desktop** (Shared UID build). See [X11 desktop](#x11-desktop).

### Code editor and Git
- Syntax highlighting for Python, Go, Kotlin, Java, JS/TS, Rust, C/C++, HTML/CSS, JSON, YAML, Markdown and more.
- Tabs, line numbers, find and replace, undo and redo, auto-indent and a file explorer.
- Run commands from the editor in a terminal sheet with Ctrl keys.
- Git: clone, status, diffs, commit, pull and push from a source control view.
- **GitHub**: sign in with the GitHub CLI from **Settings → GitHub**, browse your repositories, and set up git credentials.

### Browser
- In-app browser for previewing local dev servers and testing web apps.
- Desktop and mobile modes, a steady browser identity that keeps Google sign-in working, and built-in Eruda DevTools.

### Connect to PC (with AntiGem for VS Code)
Use the agents running on your PC from your phone. [AntiGem for VS Code](https://github.com/Santoshkurmi/antigem_vscode_extension) runs them on the PC, and the app becomes a second screen for the same chats.
- **Same chats on both:** start a chat in VS Code and continue it on the phone, or the other way round. Replies stream live on both, and every agent (Antigravity, Claude Code, Codex, OpenCode) works, with its subagents, images and files.
- **Pair once, no typing of addresses:** in VS Code open **Settings → Connect phone → Start pairing**. On the phone, turn on **Settings → Connect to PC**, tap **Add a PC**, pick the PC from **Nearby PCs** and type the 6-digit code it shows. If the PC is not listed (guest Wi-Fi), type the address shown on the PC.
- **Switch in one tap:** the PC button in the side bar lists your paired PCs; pick one, or go back to **Use this phone**. Several PCs can be paired.
- **Copy files between phone and PC:** in this mode the Files screen has a **This phone** tab next to the PC's files. Copy or cut on one side, paste on the other, folders too.
- **Terminal on the PC (optional):** with **Allow terminal from phone** on in VS Code, the app's terminal and Run configurations open on the PC. Off (the default), they stay on the phone.
- **Secure by design:** the phone connects over SSH with its own key; no PC password is stored. The code is checked with SPAKE2, so nobody on the Wi-Fi can learn it. The key can only reach AntiGem on the PC (plus a shell if you allow the terminal). Remove a phone in VS Code to lock it out at once.
- **What the PC needs:** AntiGem for VS Code, and its SSH server turned on (Linux: `openssh-server`; macOS: *Remote Login*; Windows: the *OpenSSH Server* feature). VS Code shows the steps for your system.

### Notes
- Notes and to-do lists inside the app and in the floating bubble.
- Agents can read and update them through the Notes MCP.

### Backups and updates
- Back up and restore the Linux rootfs, projects and chat history to phone storage.
- Data loss protection: an optional uninstall shield and a guard against **Clear data** in App info.
- In-app update checks and APK updates.
- Diagnostics: a network inspector, live stream counts and daemon logs.

---

## Download

Get the latest APK from [Releases](https://github.com/santoshkurmi/antigem/releases). There are two build variants, and one extra **mode** that works with either of them:

| Variant | Package name | Needs the Termux app? | Notes |
| :--- | :--- | :---: | :--- |
| **`termux`** (recommended) | `com.termux` | No | Self-contained: the app sets up its own Linux environment. This is the one published in releases. |
| **`standard`** | `com.antigem` | Yes | You run `agy_ide_bridge` inside a separately installed Termux app, and AntiGem connects to it over localhost. Not published; build it yourself from source. |
| **Server mode** (a mode, not a third build) | the same as the build you installed (`com.termux` or `com.antigem`) | No | The bridge, the agents (`agy`, Claude Code, OpenCode, Codex), your projects and the terminal run on **your own server (a VPS)**, and the phone is only the screen. Switch it on in **Settings → Servers**. Works with both variants. See [Server mode](#server-mode-run-it-on-a-vps) below. |

> [!NOTE]
> The `standard` variant is not deprecated, but it gets little attention because almost nobody uses it. Some features, such as the Browser MCP, may not work in it right now, and it may be dropped in the future. Use the `termux` variant.

> [!IMPORTANT]
> The `termux` variant uses the package name `com.termux`, so it cannot be installed on the same phone as the official Termux app. Uninstall Termux first, or use the `standard` variant.

### Why the package name is `com.termux`

Termux packages are compiled for the fixed path `/data/data/com.termux/files/usr`. An app with any other package name cannot run them directly. It would have to use **proot** to fake that path, and proot slows down every program, or rebuild every package for a new path.

With the package name `com.termux`, AntiGem can install the official Termux bootstrap and use the official Termux package repositories, and everything runs at full native speed. This is the only reason for the name.

### This is not a Termux fork

AntiGem is not a copy or modification of the Termux app. The app was written from scratch. It uses only Termux's open-source **terminal-emulator** and **terminal-view** libraries for drawing the terminal, and the Termux bootstrap and package repositories for the Linux environment. The UI, the agent integrations, the Go bridge, the MCP servers, the plugin system and everything else are AntiGem's own code.

### Termux compatibility

Packages, `apt`/`pkg`, the shell and almost everything else work as in Termux.

AntiGem has essential Termux:API commands built in with zero setup:

| Command | Built in |
| :--- | :---: |
| `termux-toast` | ✅ |
| `termux-vibrate` | ✅ |
| `termux-clipboard-get` / `termux-clipboard-set` | ✅ |
| `termux-torch` | ✅ |
| `termux-tts-speak` | ✅ |
| `termux-notification` / `termux-notification-remove` | ✅ |
| `termux-volume` | ✅ |
| `termux-battery-status` | ✅ |
| `termux-open` / `termux-open-url` | ✅ |
| `termux-wake-lock` / `termux-wake-unlock` | ✅ |

For advanced hardware APIs (such as `termux-location`, `termux-camera-photo`, `termux-sensor`, `termux-sms-*`, `termux-wifi-*`), install the matching signed companion app:

👉 **[Download AntiGem Termux:API Companion App](https://github.com/Santoshkurmi/termux-api/releases)**

> [!IMPORTANT]
> **AntiGem Variant Requirement for Termux:API:**
> - To use the external Termux:API companion app, you **must install the Shared UID variant** (`antiGem-termux-shared-v...apk`). Both apps share the `com.termux` Linux UID to communicate via UNIX sockets.
> - **Existing Users:** Android does not allow changing `sharedUserId` via an update (`INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`). If you currently have the Standalone build installed, you must **backup your files, uninstall the app, and install the Shared UID build**.
> - You can check your current variant in AntiGem under **Settings → About & Info** (shows *Shared UID Build* or *Standalone Build*).

**How to enable:**
1. Install the companion APK from the [releases page](https://github.com/Santoshkurmi/termux-api/releases).
2. In AntiGem, go to **Settings → Termux Support → Termux:API** and select **External Termux:API App**.
3. Tap **Reinstall Package Now** (or run `pkg reinstall termux-api` in the terminal) to restore the official dispatcher binary.

#### X11 desktop

AntiGem can run a Linux desktop (XFCE or LXQt) with [Termux:X11](https://github.com/termux/termux-x11). It uses AntiGem's own build of Termux:X11, signed to share the `com.termux` UID, so the desktop runs inside AntiGem's process and Android doesn't slow it down or stop it while you use it. This needs the **Shared UID build**.

Go to **Settings → Termux Support → X11 Desktop** and follow the steps: install the Termux:X11 app, the `termux-x11` command and a desktop, then tap **Start**.

To set it up by hand instead, get `antigem-termux-x11-sharedUid.apk` and `antigem-termux-x11-*-all.deb` from the [AntiGem Termux:X11 releases](https://github.com/Santoshkurmi/termux-x11/releases/latest), install the APK, and run:

```bash
pkg install x11-repo
apt install ./antigem-termux-x11-*-all.deb
pkg install dbus xfce4
termux-x11 :0 -xstartup "dbus-launch --exit-with-session xfce4-session"
```

Then open Termux:X11. Don't install `termux-x11-nightly` from x11-repo: it only works with the official Termux:X11 app.

---

## How it works

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           AntiGem Android app                            │
│         Compose UI · terminal · editor · browser · MCPs · Jarvis         │
└───────────────────────┬─────────────────────────────────┬────────────────┘
                        │ HTTP / WebSocket                │ HTTP / WS
                        │ localhost:1234                  │ localhost:1235
┌───────────────────────┴────────────────────────┐   ┌────┴────────────────┐
│              agy_ide_bridge (Go)               │   │   Antigravity hub   │
│      Git · files · plugins · agent relays      ├──►│      agy --hub      │
│       starts and supervises every agent        │   │  (Google's agy CLI) │
└───────┬──────────────────┬──────────────────┬──┘   └─────────────────────┘
        │ stream-json      │ HTTP + SSE       │ JSON-RPC (stdio)
        │                  │                  │
┌───────┴────────┐ ┌───────┴────────┐ ┌───────┴────────┐
│  Claude Code   │ │    OpenCode    │ │     Codex      │
│  one process   │ │   one shared   │ │   one shared   │
│    per chat    │ │ opencode serve │ │codex app-server│
└────────────────┘ └────────────────┘ └────────────────┘
```

---

## Server mode (run it on a VPS)

By default everything runs on the phone. In **Server mode** the heavy part moves to a Linux server that you own (a VPS, a home server, a spare PC): the **IDE bridge**, the **Antigravity hub (`agy`)**, **Claude Code**, your **projects, files, git and the shell** all live there, and AntiGem on the phone only shows the UI. It is the same app and the same interface, but the chats, the files and the agents' accounts live on the server (they are separate from any chats you have in local mode), so the agents keep working when the phone sleeps, and the phone stays light.

It is **not a separate APK**. Both the `termux` and the `standard` variant can use it: open **Settings → Servers** and pick **SSH tunnel** or **Server** under *Where the server runs*. The Linux environment on the phone is not needed in this mode. (Default ports differ per variant: `termux` uses 1234 / 1235 for the bridge / hub, `standard` uses 1236 / 1237.)

### Two ways to connect: start with the SSH tunnel

| | **SSH tunnel** (recommended, try this first) | **Server** (domain or open ports) |
|---|---|---|
| Addresses in the app | `http://127.0.0.1:1234` and `http://127.0.0.1:1235` | `https://bridge.example.com` / `https://hub.example.com`, or `http://<ip>:1234` / `:1235` |
| What you set up on the server | nothing beyond SSH | DNS, certificates, nginx / Caddy / Apache, firewall rules, optional access header |
| Reachable from the internet | only SSH; the bridge and the hub listen on the server's `127.0.0.1` only | the bridge and the hub, through the proxy or the open ports |
| Encryption | SSH | TLS (only if you use `https://`) |
| When the SSH connection drops | chats, files and the terminal pause until the app reconnects (automatic) | chats keep working; only the terminal, start/stop and the phone tools wait for SSH |
| Speed for big files | slower (everything goes through one SSH connection) | full speed |
| Other devices / a desktop browser | no, only this app | yes |

**The SSH tunnel is the safer and simpler choice.** Nothing but SSH is open to the internet, so a bug in the bridge or in `agy` cannot be reached by strangers, and there is no proxy that could be misconfigured. Its drawbacks are that everything depends on one SSH connection (a network switch or a sleeping phone pauses the app for a few seconds while it reconnects) and that large uploads and downloads are slower. Pick **Server** mode when you need full speed, chats that survive SSH drops, or access from other devices, and are ready to secure a public service. Both are compared in detail at the top of [docs/remote-server.md](docs/remote-server.md#two-ways-to-connect-which-one-to-use).

```
SSH tunnel mode (recommended)
phone (AntiGem app)                                   your server (Linux, x86_64 or arm64)
 └─ SSH  ───────────────────────────────────────────►  sshd
      · phone 127.0.0.1:1234 ──(tunnel)──► agy_ide_bridge (server 127.0.0.1:1234)
      · phone 127.0.0.1:1235 ──(tunnel)──► agy hub        (server 127.0.0.1:1235)
      · your own port forwards, e.g. a dev server: phone 127.0.0.1:5173 ──► server :5173
      · the app starts / stops the bridge for you (in tmux), shows its log; the terminal runs here
      · reverse tunnels: the server's agents can use the phone's browser, Android and notes tools (and adb, if you connect it)

Server mode (domain)
phone (AntiGem app)                              your server
 ├─ https://bridge.example.com  ──────────────►  nginx / Caddy / Apache ──► agy_ide_bridge (127.0.0.1)
 ├─ https://hub.example.com     ──────────────►  nginx / Caddy / Apache ──► agy hub        (127.0.0.1)
 └─ SSH                          ──────────────►  sshd (start / stop, terminal, reverse tunnels, as above)
```

**Port forwards:** in both modes, *Settings → Servers → Port forwards* makes any port of the server reachable on the phone's `127.0.0.1` over SSH. Start `npm run dev` on the server, forward 5173, and open `http://127.0.0.1:5173` in the app's browser.

### How to set it up (short version)

1. **Prepare the server:** a normal (non-root) user, `sshd`, `tmux`, `git`. Use a **dedicated VPS and a dedicated user**.
2. **Get the bridge on the server:** the app does **not** contain it and never uploads anything. Build it once with Go (`CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build` in `agy_ide_bridge/`, or `arm64`), copy it to `~/.antigem/agy_ide_bridge` and `chmod +x` it. Build it from the **same version as your app**: the app compares versions on every launch and tells you when the bridge is out of date.
3. **Install the agents on the server:** `curl -fsSL https://antigravity.google/cli/install.sh | bash` for Antigravity, `curl -fsSL https://claude.ai/install.sh | bash` for Claude Code (both end up in `~/.local/bin`). You can also install `agy` from the app (**Settings → Agents**) once the bridge is running.
4. **Choose how the phone reaches it.** *SSH tunnel* (recommended): nothing to do, skip to step 5. *Server* mode: put a reverse proxy with TLS in front (nginx, Caddy or Apache) on two sub-domains, one for the bridge and one for the hub, add the **proxy access header** (a second secret that the proxy checks), and open only SSH, 80 and 443 in the firewall.
5. **In the app (Settings → Servers):** pick **SSH tunnel** (the addresses are filled in as `http://127.0.0.1:<port>`; keep the default ports unless you changed them) or **Server** (enter your two addresses and, optionally, the proxy access header). Then enter the SSH host, user and key, check the server paths (they are pre-filled with the defaults), **Save**, **Reopen**. The app connects over SSH, opens the tunnels, starts the bridge, and shows its state and log.
6. **Sign in** to Antigravity (a Google redirect page that you paste back into the app) and to Claude Code, and start chatting.

**The full, step-by-step guide, with the two connection modes compared, ready-to-paste nginx, Caddy and Apache configurations, the app settings explained field by field, adb from the server to the phone, updating, and debugging, is here: 👉 [docs/remote-server.md](docs/remote-server.md).**

> [!CAUTION]
> **A server on the internet is a target, and nothing here is guaranteed to be secure.** The bridge gives whoever controls it the power of the server user: every file that user can read or write, the agents (which run commands), git, and the logins stored in its home folder. This software is young and has **not been independently audited**; parts of it have had real bugs, and more exist. **Assume your server can be hacked**: use a dedicated VPS and an unprivileged user, keep nothing on it that you cannot afford to lose, prefer the SSH tunnel (or, in Server mode, use `https://` only and add the proxy access header), open only the ports you need, and keep everything updated.
>
> **Test your protections yourself.** A proxy rule or a firewall setting that you typed in but never checked may do nothing. The guide lists concrete checks to run from another machine (is the access header really enforced, does the bridge refuse a request without the token, which ports are open, does an answer ever contain the secret) in [section 15.5](docs/remote-server.md#155-test-it-yourself-do-not-skip). Repeat them after every change.
>
> **Ask a strong AI model to audit it:** give it this repository's source code and your own proxy configuration, and ask it to look for more bugs and security problems, as described in [section 15.6](docs/remote-server.md#156-ask-an-ai-model-to-look-for-more-bugs). Verify whatever it claims by running it. If you find a real problem, please report it.

The Backups & Restore screen is for the phone's own data and is hidden in Server mode: back up `~/.gemini` and your project folders on the server yourself.

---

## Building from source

**Requirements:** JDK 17+, Android SDK (API 35, Build Tools 35+) and Go 1.23+ for the bridge.

```bash
git clone https://github.com/santoshkurmi/antigem.git
cd antigem

# Build the termux variant
./gradlew assembleTermuxDebug

# Or build every variant
./scripts/build_all_apks.sh
```

APKs are written to `app/build/outputs/apk/<variant>/<buildType>/` and `build/outputs/apk_all/`.

---

## Project structure

```text
antigem/
├── app/src/main/
│   ├── java/com/example/gemini/
│   │   ├── data/          # Agent backends (agy, claude, opencode, codex), terminal, ADB, automation, notes, updater
│   │   ├── domain/        # Chat models and parsing
│   │   └── ui/            # Compose screens: chat, IDE, browser, notes, settings
│   ├── java/com/termux/   # Termux terminal-view widget and com.termux service entry points
│   └── assets/browser-runtime/  # Extension host and Chrome API for plugins
├── agy_ide_bridge/        # Go bridge: agy hub, Claude Code relay, Git, plugins
├── extensions/            # Bundled plugins
├── extension_docs/        # Plugin SDK documentation and examples
├── scripts/               # Build, backup and restore scripts
└── version.json           # Version and changelog used by the in-app updater
```

---

## Related projects

AntiGem comes in three parts that link to each other:

| Project | What it is | Repository |
|---|---|---|
| **AntiGem** (Android app) | The coding agents, a Linux terminal, an editor and a browser on your phone. With **Connect to PC** it also uses the agents of a PC that runs AntiGem for VS Code. | [Santoshkurmi/antigem](https://github.com/Santoshkurmi/antigem) |
| **AntiGem for VS Code** | The same agents and chat in a VS Code sidebar. Its **Connect phone** lets the app on your phone use them. | [Santoshkurmi/antigem_vscode_extension](https://github.com/Santoshkurmi/antigem_vscode_extension) · install: [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=santoshkurmi.antigem-vscode-extension), [Open VSX](https://open-vsx.org/extension/santoshkurmi/antigem-vscode-extension) |
| **AntiGem Browser Bridge** (Chrome extension) | Lets agents in AntiGem for VS Code use your real Chrome. Not needed by the Android app, which has its own browser. | [Santoshkurmi/antigem-browser-extension](https://github.com/Santoshkurmi/antigem-browser-extension) · not in the Chrome Web Store yet: installed with *Load unpacked* from AntiGem for VS Code |

## Contributing

Issues, ideas and pull requests are welcome. For help, join the [WhatsApp community](https://chat.whatsapp.com/IGQX8q9zSVV0RarG70sclV?s=cl&p=a&mlu=4&ilr=4).

## Analytics

AntiGem uses [TelemetryDeck](https://telemetrydeck.com) only to count how many people use the app. It records app opens, an anonymous install ID, session counts, and general device details (phone model, Android version, app version). It does **not** collect chats, prompts, code, files, terminal output, account emails or API keys. Debug builds are marked as test data.

## Disclaimer

AntiGem is an independent project. It is not affiliated with, endorsed by or sponsored by Google, Anthropic, OpenAI, OpenCode or any other AI provider. Antigravity, Claude, Claude Code, Codex and other product names are trademarks of their owners and are used here only to say which tools the app works with.

AntiGem does not include these tools. You install each one from its official source and sign in with that tool's own login, and the app gives you an interface on top of it. Your use of each tool and account is covered by that provider's own terms, the same as when you use the tool from a terminal.

AntiGem is free software, provided as is, without warranty, as described in the [license](LICENSE).

## License

Copyright (C) 2026 Santosh Kurmi. Licensed under the [GNU General Public License v3.0](LICENSE) or any later version.
