# 13 — Complete Settings Reference

This guide is an exhaustive reference for every section, screen, and setting in AntiGem.

To open Settings: Tap the **Gear icon (⚙️)** in the Chat Drawer or the IDE sidebar.

---

## 🧭 The Settings Map (38 Sections)

The settings hierarchy is organized into 38 authoritative `SettingsSection` destinations:

```
Settings & Preferences (MAIN)
├── Appearance & Theme (APPEARANCE)
├── Servers & Connectivity (SERVERS)
├── Custom AntiGem Plugins (WEBVIEW_EXTENSIONS)
├── Slash Commands (SLASH_COMMANDS)
├── MCP Servers (MCP)
├── Skills & Plugins (SKILLS_PLUGINS)
├── Terminal & Shell (TERMINAL)
├── Backups & Restore (BACKUPS)
├── Data Loss Protection (DATA_PROTECTION)
├── Commands & Permissions (COMMANDS)
├── Antigravity (ANTIGRAVITY)
├── Antigravity CLI (AGY_CLI)
├── Claude Code (CLAUDE)
├── Claude Code (CLAUDE_PAGE)
├── OpenCode (OPENCODE)
├── OpenCode (OPENCODE_PAGE)
├── Agents (AGENTS)
├── Jarvis (JARVIS)
│   ├── Jarvis · Agent (JARVIS_AGENT)
│   ├── Jarvis · Personality & you (JARVIS_PERSONALITY)
│   ├── Jarvis · Setup (JARVIS_SETUP)
│   ├── Jarvis · Waking Jarvis (JARVIS_WAKE)
│   ├── Jarvis · Listening & sending (JARVIS_LISTEN)
│   ├── Jarvis · Voice output (JARVIS_VOICE)
│   ├── Jarvis · Manager & tasks (JARVIS_TASKS)
│   ├── Jarvis · Approvals (JARVIS_APPROVALS)
│   ├── Jarvis · Screen & look (JARVIS_SCREEN)
│   └── Jarvis · Jarvis chat (JARVIS_CHAT)
├── Diagnostics & Performance (DIAGNOSTICS)
├── Utilities (UTILITIES)
├── GitHub (GITHUB)
├── ADB Connect (ADB_CONNECT)
├── Android Automation (ANDROID_AUTOMATION)
├── Termux Support (TERMUX_SUPPORT)
│   ├── Termux:API (TERMUX_API)
│   └── X11 Desktop (X11_DESKTOP)
├── What's new (CHANGELOG)
└── About & Info (ABOUT)
```

---

## 1. Settings & Preferences (`MAIN`)
*Subtitle: "Configure your AntiGem experience"*
The root settings menu. Displays clean, rounded groups of rows for quick navigation to all sub-screens, including current status indicators (e.g. server online status, active agent count, theme mode, and GitHub sign-in state).

---

## 2. Appearance & Theme (`APPEARANCE`)
*Subtitle: "Theme, dark mode, and chat font scaling"*
- **Color Theme**:
  - `Auto (System)`: Automatically tracks your Android system-wide light/dark mode.
  - `Light`: Crisp light theme for daytime use.
  - `Dark`: Dark theme optimized for night use and OLED battery savings.
- **Built-in Color Themes** (`BuiltInThemes.all` — 25 palettes):
  - *Light Themes (8)*: **Porcelain** (Default light with amber accent), **Lavender** (Soft lilac with violet accent), **Sky** (Crisp airy light blue), **Sakura** (Soft cherry blossom pink), **Mint** (Fresh light green), **Linen** (Warm minimal off-white), **Paper** (Warm sepia paper), and **Solarized Light** (Classic low-glare cream).
  - *Dark Themes (17)*: **Midnight Aurora** (Default dark with ink-blue, violet and aurora teal), **Obsidian** (Deep black with periwinkle glow), **Graphite** (Warm charcoal with glowing amber), **Midnight** (Pure OLED black with electric cyan), **High Contrast** (Maximum readability: white on black with bold yellow), **Amber Night** (Original AntiGem dark look), **Mocha** (Pastel cocoa-blue with lavender and pink), **Neon City** (Nighttime blues with electric violet), **Aurora** (Teal and violet over night sky), **Nord** (Calm arctic blue-grey), **Dracula** (Dark purple with vivid pink, green and cyan), **Velvet** (Dusky rose and soft violet on deep plum), **Deep Ocean** (Deep navy with sky-blue accent), **Ember** (Retro warm dark with toasted browns and orange), **Forest** (Dark pine green with leaf accent), **Sunset** (Warm plum dusk with orange glow), and **Solarized Dark** (Classic teal-black).
- **Chat Text Scaling (…%)**:
  - Subtitle: *"Scales messages, markdown, and code blocks"* (scales messages, markdown, and code blocks together).
  - Slider: Adjusts font size continuously from 75% to 160% (range `0.75f..1.60f`, 16 steps).
  - Presets: Quick one-tap buttons for **Small** (85%), **Normal** (100%), **Medium** (115%), **Large** (130%), and **Huge** (150%).
  - **Reset (1.0x)**: Appears when scaling is modified to quickly restore default text size.
- **Live Preview**: An interactive preview card simulating a chat bubble so you can verify readability before returning to your conversations.
- **Vibrate when a chat finishes**: Toggle switch with explanation: *"One light buzz when a chat stops running (its dot in the chat list goes away)."*

---

## 3. Servers & Connectivity (`SERVERS`)
*Subtitle: "Configure AGY Hub and IDE Bridge endpoints"*
A comprehensive configuration screen for managing local and remote agent daemons:
- **Where the server runs**:
  - **This phone**: *"The bridge and the agents run on this phone."*
  - **Server**: *"The bridge and the agents run on your server, reached by a domain or open ports. The app manages them over SSH."*
  - **SSH tunnel**: *"On your server, reached through the SSH connection: no open ports, no domain, no reverse proxy."*
- **Saved profiles**:
  - **Saved profiles (n)**: Opens profile selector with mode tags (`LOCAL`, `SERVER`, `SSH TUNNEL`).
  - **Save as profile**: Save current settings with a **Profile name** and optional **Include SSH password and key** toggle.
  - **Update** and **Delete** actions for existing profiles.
  - **Reset All to Defaults**: Restores local binary paths and addresses to initial installation values.
- **Server addresses**:
  - **IDE Bridge address**: e.g., `http://127.0.0.1:8080` or `https://bridge.example.com`.
  - **AGY Hub address**: e.g., `http://127.0.0.1:50051` or `https://hub.example.com`.
  - **Bridge listens on** & **Hub listens on**: The real server listening ports (when behind a proxy).
  - **Bridge bind address**: `127.0.0.1` behind reverse proxy, `0.0.0.0` for direct address.
  - **Test Hub** and **Test Bridge** buttons: Live probes reporting connectivity and latency.
- **Proxy access header (second layer)**:
  - Header name, Header value (secret), **Generate** button, and **Copy nginx rule** button.
- **SSH access**:
  - **SSH host**, **Port**, **User** (*"not root if you can avoid it"*), **Password**, **Private key (recommended)** with **Choose key file** and **Paste key**, and **Key passphrase**.
  - **Test SSH** button, **Forget host key** button.
  - **Advanced (server paths and ports)**: Bridge binary on the server, agy binary on the server, claude binary on the server, opencode binary on the server, and Bridge folder in the server home.
- **Port forwards (server → this phone)**:
  - Forwards server ports (e.g., dev servers on 5173) to phone's `http://127.0.0.1:port` over SSH.
  - Enforces the 1024-65535 phone-port rule.
- **Server control** (when remote):
  - Live status indicator (*Bridge running on the server*, *Starting…*, *Stopped*).
  - **Start**, **Restart**, **Stop**, and **Server log** buttons.
- **Binary Executable Paths** (when in local mode):
  - Custom paths for IDE Bridge, AGY Hub, Claude Code, and OpenCode binaries.
- **Security Token (Header Obfuscation)**:
  - 12-Character Secret Token, HTTP Framed Header display, **Randomize** button, **Copy Header** button.
- **Save Server & Security Settings**:
  - Validates all fields and prompts with **Reopen AntiGem?** (**Reopen Now** / **Later**).

---

## 4. Custom AntiGem Plugins (`WEBVIEW_EXTENSIONS`)
*Subtitle: "Install and manage plugins: MCP tools and skills"*
- **Tabs**: **Installed** and **Repositories**.
- **Installed Tab**: Lists installed plugins with folder name, version, tool count, and skill count. Includes:
  - **Install ZIP**: Prompts for a ZIP file containing `manifest.json`.
  - **Refresh**: Re-indexes installed plugins.
  - Per-plugin actions: **Download as ZIP**, **Remove** (with *"Also delete its stored data"* checkbox), **Reload**, **Update ZIP** / **Update plugin**, and **Update** when a new repository version is available.
- **Repositories Tab**: Browse and install plugins directly from configured plugin repositories.
- **MCP & Chat Integration**: Use the **Custom AntiGem Plugin MCP** preset in MCP settings to provide plugin tools to agents, or type `/plu:` in chat.
- *(For complete guide, see [Skills & Plugins Guide](10-plugins-and-skills.md)).*

---

## 5. Slash Commands (`SLASH_COMMANDS`)
*Subtitle: "Your own / commands and what they expand to"*
- **Command List**: View all active built-in commands (`/plan`, `/goal`, `/grill-me`, `/boost`, `/learn`) and user-created custom shortcuts.
- **Add Slash Command**: Specify a trigger keyword (e.g. `/audit`) and the prompt expansion text that will be sent to the AI.
- **Edit & Delete**: Manage your saved shortcuts.

---

## 6. MCP Servers (`MCP`)
*Subtitle: "Model Context Protocol tools & integrations"*
- **Presets**: One-tap toggles for Browser Automation, Terminal Tools, Android Bridge, and Cascade Catalog.
- **Add Custom Server**: Set up custom stdio, SSE, or WebSocket MCP servers.
- **Server Health**: Displays ping latency, connection state, and discovered tool counts.

---

## 7. Skills & Plugins (`SKILLS_PLUGINS`)
*Subtitle: "Agent capabilities, Google plugins, and extensions"*
The settings row badge displays `<N> skills · <M> plugins`. Tapping it opens three tabs:
- **Skills (`Skills (N)`)**:
  - Scope filter chips: **Global (Default)**, **Project / Workspace**, and **All**.
  - **Refresh** icon: Re-scans discovered skills.
  - Skill cards: Display command `/{name}`, optional `({displayName})`, `from {plugin}` origin, scope badge (**Built-in**, **Global**, **Workspace**, or **Installed Plugin**), description, directory path, and **View SKILL.md** (which opens markdown instructions or alerts *"No markdown content found in SKILL.md"*).
  - Empty states: *"No skills discovered."* and *"No workspace-scoped skills (.agents/skills/) found for active project."*.
- **Plugins (`Plugins (N)`)**:
  - Header: **Installed Bundles & Extensions**.
  - Lists installed bundles from `~/.gemini/config/plugins/` showing display name, version, **Global** badge, description, and chips for **Bundled Skills (N):**.
  - **Delete Plugin?** dialog: *"Are you sure you want to delete '{display}' ({name})? This will remove its bundled skills from ~/.gemini/config/plugins/."* with **Delete** and **Cancel**.
  - Empty state: *"No plugin bundles installed in ~/.gemini/config/plugins/."*.
- **Store**:
  - Title: **Official 'Build with Google' Plugins**.
  - Official Google plugin catalog with **Install** and **Installed** action badges.
  - Empty state: *"No official Google plugins catalog available."*.

---

## 8. Terminal & Shell (`TERMINAL`)
*Subtitle: "SSH configuration, local tools, and styling"*
- **Menu Row Status**: The row in the main settings menu displays **"SSH"** when SSH mode or remote server mode is active, and **"Local"** otherwise.
- **Use Terminal via SSH**: Switch toggle to route all terminal sessions over direct SSH.
- **Mode Badge**: **Local Mode** (*"Built-in chroot rootfs environment"*) or **SSH Mode** (*"Direct SSH to Termux sshd without local rootfs"*). (When remote server mode is active, the description displays *"Runs on your remote server over SSH"*).
- **SSH Connection Fields** (when SSH is active): **SSH Host**, **Port**, **User**, and **Password**.
- **SSH Buttons**:
  - **Test SSH Connection**: Live connection test reporting *"✓ SSH connection successful!"* or failure details.
  - **Save SSH Settings**: Saves credentials and displays *"✓ SSH settings saved successfully!"*.
- **Embedded Linux Rootfs Card**:
  - Title: **Embedded Linux Rootfs**.
  - Status: Shows **"Installed and ready"** (with green badge **Installed ✓**) or **"Not installed (>30MB download)"** (with amber badge **Not Installed**).
  - Actions:
    - **Download & Install Local Tools**: Starts download and setup when not installed.
    - **Delete All & Factory Reset**: Opens warning confirmation dialog to wipe rootfs.
- **Terminal Customization**: Font size, cursor style, buffer size, and terminal theme palettes.

---

## 9. Backups & Restore (`BACKUPS`)
*Subtitle: "Backup rootfs environment and AGY chat histories"*
- **Visibility Rule**: This row appears in Settings only when local Linux tools are installed (`isLocalToolsInstalled`) AND you are not in remote/server mode (`!currentRemoteMode`). In server mode, Backups is hidden because backups manage the phone's local Linux environment.
- **Server Running Warning**: If the AGY daemon or IDE bridge is active, a top warning banner appears:
  - Header: **"Server is running"**
  - Message: *"Stop it before backing up or restoring, so no database is written to while it's being archived."*
  - Button: **Stop server**
  - Alert dialog: **"Stop the server first"** (*"The AGY IDE Bridge and Hub server must be offline before running '<action>'. Would you like to stop the server now?"*) with buttons **Stop server** and **Cancel**.
- **Backup Linux Environment Card**:
  - Title: **Linux environment** (*"Installed packages, binaries and your home folder"*)
  - Description: *"A full, portable copy of the Linux system (/usr) and $HOME dotfiles. Sockets, caches and auth tokens are left out."*
  - Saves to: `Download/Antigem/backups/bootrapz_<time>.zip`
  - Button: **Back up environment**
- **Backup Chats & Credentials Card**:
  - Title: **Chats & credentials** (*"Conversations, transcripts, skills and login"*)
  - Description: *"Archives your ~/.gemini folder: chat history databases, transcripts, summaries, custom skills and the auth token. Can be password-protected."*
  - Saves to: `Download/Antigem/backups/agy_chats_backup_<time>.zip`
  - Note: *"Includes your login token. Don't share this file: anyone who has it can sign in as you."*
  - Button: **Back up chats**
  - Password Dialog: Titled **"Back up chats"** with input **"Encryption Password (Optional)"** (*"Optionally enter a password to encrypt your backup zip archive. Leave empty for an unencrypted standard zip."*) and warning note *"Don't share this file: anyone who has it can sign in as you."*.
- **Restore Chats Card**:
  - Title: **Chats & credentials** (*"From a chats backup zip"*)
  - Description: *"Restores chats, the conversation index and your login. Your current ~/.gemini is kept as ~/.gemini.bak first, so nothing is lost."*
  - Button: **Restore chats**
  - Restore Dialog: Titled **"Restore chats"**, with file picker for **"Backup Archive Path"**, **"Archive Password (if encrypted)"**, and button **"Start restore"**.
- **Projects Backup & Restore Card**:
  - Title: **IDE & AGY projects** (*"Back up or restore selected projects"*)
  - Description: *"Pick projects from the IDE and chat workspaces. Choose a clean git-aware archive (skips node_modules and build output) or a full copy. Restores map back to the right folders, with a safety backup."*
  - Button: **Manage projects**
  - Dialog options: Tabs for **Backup** and **Restore**, selection of **Git-Aware (Clean)** (*Excludes node_modules & build caches*) or **Full Raw Archive** (*All files, symlinks & permissions as-is*), select all/deselect, and project item tree.
*(Note: "Clear Temporary Cache" is not in Backups & Restore; it is located in AntiGem Space Manager).*

---

## 10. Data Loss Protection (`DATA_PROTECTION`)
*Subtitle: "Uninstall shield & App Info clear data protection"*
Banner: **Data Loss Prevention** (*"Protect your local Linux environment, CLI tools, databases, and AI coding workspaces from accidental wipe or uninstall."*).
- **Card 1: Accidental Uninstall Protection**:
  - Status: Displays **"Status: Protected (Device Admin Active)"** (green) or **"Status: Inactive (Optional)"** (gray).
  - Protection: Activating Android Device Administrator greys out the "Uninstall" button at the OS level across phone launchers, preventing accidental deletion of project repos and Linux packages.
  - Buttons: **Activate Uninstall Protection** (or **Deactivate Uninstall Protection**), and **Open System Device Admin Apps List**.
  - Confirmation Dialog: Tapping deactivate prompts **"Disable Uninstall Protection?"** (*"This will remove AntiGem's Device Admin status. The app will become vulnerable to accidental home-screen uninstallation."*) with buttons **Deactivate** and **Cancel**.
  - Sideloaded App Tip (Android 13+): *"If your phone shows 'Restricted setting', open App Info > tap the 3 dots in the top right > select 'Allow restricted settings'."* with button **Open App Info**.
- **Card 2: App Info Clear Data Shield**:
  - Status: Displays **"Enabled (Replaces Clear Data with Space Manager)"** (blue) or **"Disabled (Standard OS Clear Data)"** (gray).
  - Switch Toggle: When enabled (default), tapping "Clear Data" inside Android system Settings > App Info routes directly to AntiGem's Safe Space Manager instead of wiping everything instantly with one tap.
  - Buttons: **Open App Info** and **Open Space Manager Preview** (which opens AntiGem Space Manager directly).

---

## 🗄️ AntiGem Space Manager (Standalone System Screen)
*Subtitle: "Safe storage and data loss protection"*

Opened from **Android Settings → Apps → AntiGem → Storage → "Manage space"** (when App Info Clear Data Shield is active) or via **Open Space Manager Preview** in Data Loss Protection settings.
- **Header**: **"AntiGem Space Manager"** with subtitle *"Safe storage and data loss protection"*.
- **Card 1: Total App Footprint**:
  - Storage calculation: Displays formatted disk footprint (e.g. `1.8 GB`) or *"Calculating storage..."*.
  - Note: *"This includes your Termux Linux packages, AI configuration, and local SQLite data."*.
- **Card 2: Temporary Cache**:
  - Cache size: Shows current removable temporary cache size (`Size: ...`).
  - Note: *"Safe to clear. Removes temporary logs and cached assets without touching your code or database."*.
  - Button: **Clear Temporary Cache** (cleans temporary cache files without touching code or databases).
- **Card 3: Danger Zone: Factory Reset**:
  - Note: *"Performs a complete wipe of all app data, removing Linux packages, accounts, and chats."*.
  - Button: **Wipe All App Data**.
  - Multi-Step Confirmation Sequence:
    1. **"Confirm Full Reset"** dialog: *"Are you sure you want to factory reset AntiGem? This will permanently delete all installed Linux packages, workspaces, accounts, and chats."* Tap **Proceed to Confirmation** (or **Cancel**).
    2. **"Confirm Destruction"** dialog: *"To confirm and permanently wipe all app data and local Linux storage, type the confirmation phrase exactly as shown below: `iknowwhatiamdoing`"*.
    3. Type `iknowwhatiamdoing` into the input field.
    4. Button **Delete Everything** becomes active. Tapping it permanently wipes all user data and resets the app.

---

## 11. Commands & Permissions (`COMMANDS`)
*Subtitle: "Auto-run policy, sandbox mode, and tool approvals"*
- **Menu Row Status**: The row in the main settings menu displays **"Ask first"**, **"Smart safety"**, **"Auto-run"**, or **"All off"** based on active policy.
- **Header**: **"Global Security & Policies"** with subtitle *"Live daemon settings applied across all workspaces via Jetbox"* and a **Refresh** button.
- **Error State**: If the daemon is unreachable, displays *"Unable to Load Permissions from AGY Hub"* (*"RPC connection failed"*) with button **Retry Connection**.
- **Artifact Review Policy**:
  - **⚡ Auto (Turbo)**: *"Edits and creates documents immediately without asking."*
  - **✋ Always Ask**: *"Suspends and requests your approval before saving document edits."*
- **Security Presets**:
  - **DEFAULT** (Badge: **Recommended**): *"🛡️ Default Mode"* (*"Terminal commands require confirmation. Files outside workspace require confirmation."*).
  - **FULL_MACHINE** (Badge: **Open Files**): *"💻 Full Machine Mode"* (*"Terminal commands require confirmation. Full read/write access to all files on device."*).
  - **TURBO** (Badge: **Full Auto**): *"⚡ Turbo Mode"* (*"Full auto-run: terminal commands execute immediately and all files can be accessed."*).
  - **CUSTOM** (Badge: **Custom**): *"⚙️ Custom Granular Mode"* (*"Configure terminal command execution and external filesystem permissions separately."*).
- **Granular Controls (inside Custom mode)**:
  - **Terminal Command Execution:** Filter chips: **✋ Ask Every Time** (`CASCADE_COMMANDS_AUTO_EXECUTION_OFF`) and **⚡ Auto-Run (Eager)** (`CASCADE_COMMANDS_AUTO_EXECUTION_EAGER`).
  - **Files Outside Workspace:** Filter chips: **✋ Ask** (`AGENT_SETTING_POLICY_ASK`), **✅ Allow** (`AGENT_SETTING_POLICY_ALLOW`), and **🚫 Deny** (`AGENT_SETTING_POLICY_DENY`).
- **Security & Sandbox Toggles**:
  - **Terminal Sandbox Isolation**: Switch. Description: *"Enabled: Isolates commands in sandbox without host network access."* / *"Disabled (Recommended for Hub): Commands execute directly in your Termux / Linux environment."*.
  - **Review File Edits & Writes**: Switch. Description: *"Prompt for confirmation before assistant applies file edits or writes code to disk."*.
- **Granular Permission Grants**:
  - Inspect explicit Allow, Deny, and Ask rules from `globalPermissionGrants`.
  - Button: **Add Rule** opens the **Add Permission Grant Rule** dialog:
    - **1. Operation Type**: Choices for **Command** (`command`), **MCP Tool** (`mcp`), **Read File** (`read_file`), and **Write File** (`write_file`).
    - **2. Target Pattern**: Pattern input field with quick template chips (`*`, `npm*`, `git*`, etc.).
    - **3. Policy Decision**: Choices for **✅ Allow**, **🚫 Deny**, or **❓ Ask**.
    - Buttons: **Save Rule** and **Cancel**.
- **Project Overrides & Inheritance**:
  - Subtitle: *"Projects can inherit the global policy above or maintain specific overrides."* Displays count (e.g. `X/Y Inheriting`).
  - Project Cards: Show project name, path, and badge (**🌐 Inherits Global** or **⚠️ Custom Override**).
  - Actions: **Set to Inherit Global Settings**, or set project override preset chips: **Default**, **Machine**, or **Turbo**.
  - Empty State: *"No workspace projects registered yet on AGY Hub."*.
- **Default Approval Scope**: Options for **Run Once** (*Single call*), **This Chat** (*Current session*), or **Workspace** (*Always*).

---

## 12. Antigravity (`ANTIGRAVITY`)
*Subtitle: "CLI, MCP servers, skills & plugins, commands & permissions"*
Central hub for configuring the Google DeepMind agent backend, managing subagent spawning, and accessing AGY-specific tool permissions.

---

## 13. Antigravity CLI (`AGY_CLI`)
*Subtitle: "agy version and install, on this device or the server"*
- **Installed Version**: Shows the version string reported by `agy --version`.
- **Install / Update**: Download or update the Antigravity CLI binary on this device or on the connected remote server.

---

## 14. Claude Code (`CLAUDE` & `CLAUDE_PAGE`)
*Subtitle: "Account, models, permissions, MCP, plugins & memory"*
Configure the official Anthropic Claude Code CLI engine:
- **Chat defaults**: Subtitle *"Model, effort, thinking, permission mode, output style"*.
- **Default model**: Select the default model dynamically fetched from your Claude subscription tier.
- **Default effort**: Configure the reasoning effort level.
- **Default permission mode**: Choose the default security policy for tool execution.
- **Account & CLI**: Check sign-in status, view account email, plan tier, or tap **Sign in** / **Sign out**.
- **Permission rules**: Subtitle *"What Claude may do without asking"*.
- **Plugins**: Subtitle *"Installed plugins, catalog and marketplaces"*.
- **Memory**: Subtitle *"Instructions (CLAUDE.md) and the notes Claude saved"*.
- **Custom providers**: Subtitle *"OpenRouter, Kimi and other Anthropic-compatible APIs"*.
- **Advanced**: Subtitle *"Edit ~/.claude/settings.json directly"*.

---

## 15. OpenCode (`OPENCODE` & `OPENCODE_PAGE`)
*Subtitle: "Server, providers, custom OpenAI-compatible endpoints & approvals"*
- **Server & CLI**: Status and controls for the OpenCode background server process.
- **Custom providers**: Subtitle *"Sign in or add an API key, or add any OpenAI- or Anthropic-compatible API manually"*.
  - **Add a custom provider**: Add any OpenAI- or Anthropic-compatible endpoint. Set **Base URL** (e.g. `http://192.168.1.50:11434/v1` for Ollama), enter **API key (optional for local servers)**, and tap **Fetch from endpoint** or **Add model**.
  - **Connected accounts**: View saved logins, **Free models** indicator, or tap **Add another account**.
  - *(See [AI Agents & Models Guide](03-agents-and-models.md) for full step-by-step setup).*

---

## 16. Agents (`AGENTS`)
*Subtitle: "Run Antigravity, Claude Code, OpenCode or several"*
Enable or disable individual agent engines. If only one agent is selected, the top picker bar automatically hides to save screen space.

---

## 17. Jarvis Voice Assistant (All 10 Subsections)
*Subtitle: "Hands-free voice control of the whole app"*
- **Jarvis Master Switch**: Turns the voice orb and background listener on or off.
- **Agent (`JARVIS_AGENT`)**: Route voice commands to Antigravity or Claude Code.
- **Personality & You (`JARVIS_PERSONALITY`)**: Set user name, custom Jarvis persona instructions, humor, and tone.
- **Setup (`JARVIS_SETUP`)**: Microphone permission, background service health, and Jarvis MCP server connection.
- **Waking Jarvis (`JARVIS_WAKE`)**: Configure wake phrase ("Hey Jarvis"), push-to-talk button, headset button, and Android Quick Settings tile.
- **Listening & Sending (`JARVIS_LISTEN`)**: Auto-send on silence, custom send phrases ("send it"), cancel phrases ("never mind"), and speech engine.
- **Voice Output (`JARVIS_VOICE`)**: TTS engine (Android device TTS, Google Cloud TTS, OpenAI TTS), speech rate, pitch, and Quiet Mode.
- **Manager & Tasks (`JARVIS_TASKS`)**: Spoken background task progress announcements and completion alerts.
- **Approvals (`JARVIS_APPROVALS`)**: Voice-based command approvals and permission policies.
- **Screen & Look (`JARVIS_SCREEN`)**: Keep screen on while talking, voice orb styling and animation.
- **Jarvis Chat (`JARVIS_CHAT`)**: Screen context awareness and conversation memory.

---

## 18. Diagnostics & Performance (`DIAGNOSTICS`)
*Subtitle: "Live network connections, active streams & thread HUD"*
- **Menu Row Status**: The row in the main settings menu displays **"HUD on"** while the floating diagnostics HUD is enabled.
- **Network Inspector Card**:
  - Title: **Network Inspector (Live Logs)** (*"Inspect IDE Bridge HTTP & AGY Daemon gRPC calls in real-time"*).
  - Status Badge: Displays **ACTIVE** or **OFF**.
  - Zero-Overhead Toggle: Prompts a **"Restart Required"** confirmation dialog (*"Enabling Network Inspector requires restarting the app so OkHttp can attach live routing interceptors. Restart now?"* or *"Disabling Network Inspector requires restarting the app to restore 100% native network performance with zero debug interceptors. Restart now?"*) with buttons **Restart App** and **Cancel**.
  - **Floating Network Bubble**: Toggle switch (*"Show on-screen draggable pill with live call count & streams"*) for an on-screen draggable pill with live call counts.
  - Button: **Open Network Inspector (N calls recorded)**.
  - Inspector Dialog Features:
    - Filter chips: **All**, **gRPC Daemon**, **IDE Bridge**, **Streams**, and **Errors**.
    - Search hint: *"Filter by endpoint, route, or status..."*.
    - Button: **Clear logs**.
    - Empty states: *"No network calls captured yet"* and *"No matching calls found"*.
- **Floating Diagnostics HUD Card**:
  - Title: **Floating Diagnostics HUD** (*"Live on-screen thread, queue & connection monitor"*).
  - Status Badge: Displays **VISIBLE** or **DISABLED**.
  - Switch: Toggles the on-screen draggable floating telemetry HUD.
  - HUD Overlay Panel (tap floating pill to expand):
    - **Thread Groups: ...**: Live summary of thread states across the app.
    - **AgyGrpc Hub:**: Live streams, active RPCs, queue depth, and connection pool state (`Run=... | Q=... | Pool=... (... idle)`).
    - **IDE Bridge:**: Live HTTP bridge calls, active queue, and connection pool state (`Run=... | Q=... | Pool=... (... idle)`).
    - **Blocked Socket Reads:**: Stack traces of threads currently waiting on blocking network socket reads.
    - Button: **Hide HUD** (dismisses the overlay).

*(Note on Utilities: The `UTILITIES` section does not appear in the settings menu because its former tools—GitHub, ADB Connect, and Android Automation—now each have their own prominent top-level rows).*

---

## 19. GitHub (`GITHUB`)
*Subtitle: "Sign in with the GitHub CLI, your repositories and git credentials"*
- **Visibility Rule**: The GitHub settings page only appears in the `com.termux` package build (`LocalEnvironmentManager.isTermuxPackage`). In a standalone build, the GitHub settings page is not shown at all.
- **Status & Installation**: Checks if `gh` and `git` are installed; includes **Install gh** and **Set up git** buttons.
- **Sign In with Browser**: Generates a one-time code and provides **Copy code & open GitHub** (`github.com/login/device`).
- **Use a personal access token instead**: Token input field supporting `ghp_…` or `github_pat_…` tokens.
- **Account View**: Displays user avatar, login name, bio, **Open profile** button, and **Sign out** button.
- **Repositories**: List of user repositories with **Search repositories**, **Reload repositories**, and **Show more**. Tapping the options icon on any repository opens: **Clone and open**, **Copy clone URL**, and **Open on GitHub**. *(Note: There is no "New repository" button in AntiGem; create new repos via Jarvis or by running `gh repo create` in the terminal).*

---

## 20. ADB Connect (`ADB_CONNECT`)
*Subtitle: "Connect the terminal's adb to this phone over Wireless debugging"*
### What it is:
Connects the terminal's built-in `adb` (Android Debug Bridge) command to this phone over Android 11+ Wireless Debugging without needing a computer or USB cable.
### Setup Steps:
1. Tap **Install android-tools** (or **Repair ADB**) if adb is not ready.
2. Tap **Open Wireless debugging** (or **Open About phone** to enable Developer Options by tapping Build number 7 times).
3. Under Wireless Debugging, tap **Pair device with pairing code**.
4. Tap **Pair manually instead** in AntiGem, enter the 6-digit **Code** and 5-digit **Port**, then tap **Pair**.
5. Once paired, enter the connection **Port** (or leave Auto) and tap **Connect**.
6. Toggle **Reconnect automatically** to stay connected across Wi-Fi sessions.
### What to Expect:
Under **Devices**, your phone appears (e.g. `127.0.0.1:port device`). You or the AI can now run `adb shell` or `adb install` directly in the terminal.

---

## 21. Android Automation (`ANDROID_AUTOMATION`)
*Subtitle: "Let AI agents see and control this phone"*
### What it is:
Allows AI agents to interact with your phone's Android operating system (inspecting the accessibility UI hierarchy, tapping buttons, scrolling, and entering text) via the `android_automation_mcp` server.
- **Service Name**: **AntiGem Android Automation**
- **Status Indicator**:
  - **Not allowed**: Subtitle *"Grant the permissions below to use it"*.
  - **Allowed**: Subtitle *"Agents with the Android Automation MCP can control this phone"*.
  - **Not running**: Subtitle *"The service is on but Android hasn't started it. Turn it off and on again."*.
### Setup Steps:
1. **1. Accessibility service** (Required):
   - Tap **Open Accessibility**, find **AntiGem Android Automation**, and turn it on.
   - Alternatively, if ADB is connected, tap **Turn on with ADB** to enable in one tap.
   - If already running, tap **Turn off in Accessibility** to disable.
   - *Switch greyed out, or "Restricted setting"?*: Android 13+ blocks accessibility for sideloaded APKs. Tap **Open App info**, tap the three dots (⋮) in the top-right corner, select **Allow restricted settings**, and then enable the service.
2. **2. Screenshots**:
   - On Android 11+, screen capture is *"Included with the accessibility service"*.
3. **3. ADB (optional)**:
   - Adds advanced capabilities accessibility cannot provide (hardware key events, app installs, shell commands). Tap **Open ADB Connect** to set up.
4. **Then add the MCP**:
   - Open **Settings → MCP Servers**, and tap the **Android Automation** preset tile. Until the accessibility service is allowed, the server refuses to connect.

---

## 22. Termux Support (`TERMUX_SUPPORT`)
*Subtitle: "Termux:API and the X11 desktop"*
- **Visibility Rule**: Appears only in the `com.termux` package build. If the installed build is not the Shared UID build, the row displays **"Standalone"** with a warning badge.
- Hub page grouping Termux:API and X11 Desktop features.

---

## 23. Termux:API (`TERMUX_API`)
*Subtitle: "How termux-api commands are handled"*
### What it is:
Bridges Android hardware capabilities (battery level, camera, clipboard, notifications, vibrate, location) to command-line shell scripts.
### Provider Options:
1. **Built-in (Zero-setup)** (Default):
   - Lightweight native handlers for clipboard, toast, torch, vibration, TTS, battery, volume, and notifications inside AntiGem without requiring an external companion app.
2. **The external Termux:API app**:
   - Forwards commands to the standalone external Termux:API companion app.
   - *Requirement*: Requires the Shared UID build (`termuxBuild`). On a standalone build, this option is disabled with a requirement card.
### Interactive Command Testers:
Tap the built-in test buttons to test `termux-toast`, `termux-clipboard-get`, and `termux-battery-status` live.

---

## 24. X11 Desktop (`X11_DESKTOP`)
*Subtitle: "Linux desktop apps with Termux:X11"*
### What it is:
Enables running graphical Linux desktop applications directly on your phone screen via **Linux desktop** powered by Termux:X11. *(Note: Needs the Shared UID build `termuxBuild` to run Termux:X11 at full speed).*
### Step-by-Step Setup:
1. **Step 1: Termux:X11 app**: Tap **Download & install** to install AntiGem's own build of the Termux:X11 companion app. (You can also tap **Open the download page**).
2. **Step 2: termux-x11 command**: Tap **Install termux-x11-nightly** (or companion package) to install the command that starts the X server.
3. **Step 3: Desktop**: Select your desktop environment (e.g. **XFCE** or **LXQt**) and tap **Install XFCE** (or **Install LXQt**).
4. **Step 4: Start the desktop**: If setup is complete, tap **Start XFCE**. (If incomplete, the app prompts *"Finish the steps above first."*). Once running, tap **Open desktop** to view or **Stop** to shut it down.
5. **Web Browser (Optional)**: Choose **Chromium** or **Firefox** and tap **Install Chromium** / **Install Firefox** to browse from tur-repo.
6. **If the desktop closes by itself (Optional)**: Tap **Turn off the limit** via ADB so Android 12+ does not kill desktop subprocesses.

---

## 25. What's New (`CHANGELOG`)
*Subtitle: "Every AntiGem version and what changed"*
An interactive timeline of every release of AntiGem. Changes are organized by version cards with color-coded tags:
- 🟩 **NEW**: New features and capabilities.
- 🟨 **FIXED**: Bug fixes and stability improvements.
- 🟦 **IMPROVED**: Performance enhancements and UI polish.
- 🟥 **REMOVED**: Deprecated or replaced items.

---

## 26. About & Info (`ABOUT`)
*Subtitle: "App version, package details, and bridge export"*
- **App Branding & Version**: Displays the current version number, build flavor (e.g. `termuxBuild`), and Git commit hash.
- **Check for Updates**: Queries GitHub releases for new APK versions.
- **Export Bridge Binary**: Exports the `agy_ide_bridge` binary directly into your phone's public `Downloads` directory so you can easily copy it to an external server or PC.
- **System Information**: Displays device architecture (e.g. `aarch64`), Android API level, and package details.

## 🛠️ Troubleshooting Settings Issues

### ❌ Symptom: A settings page I read about is missing.
- **What to check**: Check your installed app build type and active operating mode:
  - **GitHub**: Only appears in the `com.termux` package build (`LocalEnvironmentManager.isTermuxPackage`). In a standalone build, the GitHub settings page is not shown at all.
  - **Termux Support (Termux:API & X11 Desktop)**: Only appears in the `com.termux` package build. If the build is not the Shared UID build, the row shows *"Standalone"* with a warning, and external Termux:API is disabled.
  - **Backups & Restore**: Only appears when local Linux tools are installed AND you are in local mode. In remote/server mode, Backups is hidden because backups apply to the phone's local environment.
  - **Utilities**: Does not exist as a page in the UI because its former tools (GitHub, ADB Connect, and Android Automation) now each have their own prominent top-level rows.
  - **Terminal Row**: Shows *"SSH"* when SSH mode or remote server mode is on, and *"Local"* otherwise.
  - **Diagnostics Row**: Shows *"HUD on"* while the floating diagnostics HUD is enabled.

### ❌ Symptom: Tapping a setting switch does nothing or immediately flips back.
- **What to check**: Check if the required prerequisite is met (e.g. microphone permission for Jarvis, or Accessibility service for Android Automation).
- **How to fix**: Open the specific setting's setup page and ensure the required Android system permission has been granted.

### ❌ Symptom: Wireless debugging disconnects after Wi-Fi reconnects.
- **What to check**: Android randomizes the Wireless Debugging port on every Wi-Fi reconnection.
- **How to fix**: Open **Settings → ADB Connect**, check the new 5-digit port in Android Developer Options, and tap **Connect**.

### ❌ Symptom: Download & Install Local Tools fails with "Storage full".
- **What to check**: Check available phone storage in Android Settings.
- **How to fix**: Open **Android Settings → Apps → AntiGem → Storage → "Manage space" → AntiGem Space Manager**, tap **Clear Temporary Cache**, and ensure you have at least 2 GB of free space before tapping Download & Install Local Tools.
