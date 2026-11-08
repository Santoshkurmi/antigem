# AntiGem User Documentation — Master Index

This is the table of contents for the whole AntiGem user guide. Every chapter is listed below with all of its sections, so you can jump straight to the part you need.

If you are new, start with the [front page](README.md), then [Getting Started](01-getting-started-and-onboarding.md). If something is broken right now, go to [Troubleshooting & Diagnostics](14-troubleshooting-and-diagnostics.md).

---

## 🚀 Quick Paths

| I want to… | Open this |
|---|---|
| Fix a problem right now | [14 — Troubleshooting & Diagnostics](14-troubleshooting-and-diagnostics.md) |
| Set up the app for the first time | [01 — Getting Started & First Launch](01-getting-started-and-onboarding.md) |
| Understand the AI agents | [03 — AI Agents & Models](03-agents-and-models.md) |
| Use my voice (Jarvis) | [11 — Jarvis Voice Assistant](11-jarvis-voice-assistant.md) |
| Find one specific setting | [13 — Complete Settings Reference](13-settings-reference.md) |
| Know what a word means | [15 — FAQ & Glossary](15-faq-and-glossary.md) |
| Check that everything is documented | [00 — Feature Coverage & Verification Proof](00-feature-coverage.md) |

---

## 📖 Contents of Every Chapter

### [Front Page (README)](README.md)
Quick fixes for the three most common problems, the chapter directory, and how the app is organized.

- 🆘 Quick Help: Start Here
- 🗺️ Documentation Directory
- 📱 Quick Concept: How AntiGem is Organized
- 🛠️ General Troubleshooting

### [00 — Feature Coverage & Verification Proof](00-feature-coverage.md)
Proof that every screen, setting and Jarvis page in the app is covered by a guide, with the real source file for each one.

- 1. Documentation File to Source File Audit Matrix
- 2. UI Screen Packages (`app/src/main/java/com/example/gemini/ui/*`)
- 3. Active Settings Sections & Conditional Visibility (`SettingsDialog.kt`)
- 4. All 10 Jarvis Settings Pages (`JarvisSettingsPage.kt`)

### [01 — Getting Started & First Launch](01-getting-started-and-onboarding.md)
First launch, permissions, choosing your agents, installing the Linux tools, and the floating controls.

- 🚀 1. Welcome to AntiGem & Permissions (`AppPermissionsDialog`)
- 🤖 2. Step 1: Choose Your Agents (`AgentsSetupUi`)
- 📦 3. Setting Up the Local Linux Environment (`FullScreenLocalToolsInstaller`)
- 🔘 4. Terminal App Setup (`TerminalLauncherOnboardingDialog`)
- 🧭 5. Navigating with the Floating App Switcher
- 🫧 6. Android System Floating Bubble (Floating Chat)
- 🔄 7. App Updates (`AppUpdateDialog`)
- 🛠️ Troubleshooting & First-Launch Issues

### [02 — Chat & AI Conversations](02-chat-and-ai-conversations.md)
The chat screen, the history drawer, the input bar, attachments and voice, message actions, tool cards and approvals, and slash commands.

- 💬 1. Understanding the Chat Screen Layout & Controls
- 🗂️ 2. The Chat History Drawer
- ✍️ 3. Input Bar: Messages, Attachments & Voice
- 💬 4. Message Bubble Actions & Controls
- 🧠 5. Special Feed Elements: Tool Calls, Choices & Approvals
- ⚡ 6. Slash Commands (`/`)
- 🛠️ Troubleshooting Chat Issues

### [03 — AI Agents & Models](03-agents-and-models.md)
The three agents (Antigravity, Claude Code and OpenCode), switching between them, models and reasoning effort, custom providers, accounts, and permission approvals.

- 🤖 1. The Three AI Agents Explained
- 🔄 2. How to Switch Agents
- 🎛️ 3. How Models, Thinking, and Reasoning Effort Work
- 🌐 4. Custom API Providers
- 👤 5. Accounts & Usage Dialog
- 🛡️ 6. Permission Modes & Approvals
- 🛠️ Troubleshooting Agent & Model Issues

### [04 — Projects & Code Editor (IDE)](04-projects-and-ide.md)
The code editor, the project sidebar, editor tabs and safety dialogs, Git, running your code, daemon logs, and the diff viewer.

- 💻 1. Accessing the IDE & Toolbar Controls
- 📁 2. The Project Sidebar (`ProjectSidebar.kt`)
- 📝 3. Editor Tabs, Find in File & Safety Dialogs
- 🌿 4. Git & Source Control (`GitSourceControlView.kt`)
- ▶️ 5. Running Your Code (`IdeExecutionRunner.kt`)
- 🔍 6. Go Daemon Logs (`DaemonLogsDialog.kt`)
- 🔬 7. Unified Diff Viewer (`UnifiedDiffViewer.kt`)
- 🛠️ Troubleshooting IDE Issues

### [05 — Terminal & Linux Shell](05-terminal-and-shell.md)
The built-in terminal, the virtual key rows, sessions and tabs, pinch zoom, and every terminal setting.

- ⌨️ 1. Accessing the Terminal
- 🖥️ 2. Virtual Key Rows & Modifier Keys
- 📑 3. Managing Sessions & Tabs (`tmux`)
- 🔍 4. Pinch Zoom & Text Sizing
- ⚙️ 5. Terminal Settings Reference (`Settings → Terminal & Shell`)
- 🛠️ Troubleshooting Terminal Issues

### [06 — Built-in Browser & Web Preview](06-browser-and-web-preview.md)
The in-app browser, the address bar and controls, tabs and shortcuts, the menu and DevTools, AI control of pages, and error screens.

- 🌐 1. Accessing the Browser
- 🧭 2. Browser Navigation, Address Bar & Interactive Controls
- 📑 3. Tabs Panel & Speed Dial Shortcuts
- ⚙️ 4. Browser Menu, DevTools & Desktop Mode
- 🤖 5. AI Browser Automation (`Controlled by AI`)
- ⚠️ 6. Browser Error Cards & Diagnostics
- 🛠️ Troubleshooting Browser Issues

### [07 — Files & Notes](07-files-and-notes.md)
Browsing and organizing files on your phone, and keeping notes and to-do lists.

- 📁 Part 1: The File Explorer
- 📝 Part 2: Notes & To-Do Lists
- 🛠️ Troubleshooting Files & Notes Issues

### [08 — GitHub Integration](08-github-integration.md)
Signing in to GitHub, your account, your commit identity, cloning and managing repositories, and publishing new projects.

- 🧭 Overview & Environment Detection
- 🔑 1. Signing In to GitHub
- 👤 2. Account Details & Sign Out
- ⚙️ 3. Configuring Git & Commit Identity
- 📦 4. Browsing, Cloning & Managing Repositories
- 🚀 5. Creating New Repositories
- 🛠️ Troubleshooting GitHub Issues

### [09 — Tools & MCP Servers](09-mcp-servers-and-tools.md)
What MCP servers are, the one-tap presets, the marketplace, adding your own servers, and inspecting or deleting them.

- 💡 1. What is an MCP Server?
- ⚡ 2. Quick Presets & Built-In Integrations
- 🛒 3. Cascade MCP Marketplace (1-Click Installs)
- ➕ 4. Adding & Editing Custom Servers
- 📋 5. Server List, Tool Inspection & Deletion
- 🛠️ Troubleshooting MCP Issues

### [10 — Skills & Plugins](10-plugins-and-skills.md)
Teaching your agents new skills, where skills and plugins live on disk, custom AntiGem plugins, and custom slash commands.

- 🧠 1. Agent Skills & Official Google Plugins (`Settings → Skills & Plugins`)
- 📁 2. Where Skills & Plugins Live on Disk
- 🔌 3. Custom AntiGem Plugins (`Settings → Custom AntiGem Plugins`)
- ⚡ 4. Custom Slash Commands (`Settings → Slash Commands`)
- 🛠️ Troubleshooting Skills & Plugins

### [11 — Jarvis Voice Assistant](11-jarvis-voice-assistant.md)
Turning Jarvis on, the floating orb and panel, and a step-by-step guide to all ten Jarvis settings pages.

- 🎙️ 1. Turning On Jarvis & The Setup Checklist
- 🔮 2. The Voice Orb & The Jarvis Panel
- ⚙️ 3. Section-by-Section Settings Guide
  - A. Agent (`JARVIS_AGENT`)
  - B. Setup (`JARVIS_SETUP`)
  - C. Personality & you (`JARVIS_PERSONALITY`)
  - D. Waking Jarvis (`JARVIS_WAKE`)
  - E. Listening & sending (`JARVIS_LISTEN`)
  - F. Voice output (`JARVIS_VOICE`)
  - G. Manager & tasks (`JARVIS_TASKS`)
  - H. Approvals (`JARVIS_APPROVALS`)
  - I. Screen & look (`JARVIS_SCREEN`)
  - J. Jarvis chat (`JARVIS_CHAT`)
- 🛠️ Troubleshooting Voice & Audio Issues

### [12 — Server Mode & Remote Access](12-server-mode-and-remote-access.md)
Running the heavy work on your own server: the three modes, saved profiles, addresses and ports, SSH, port forwards, security tokens, and live control.

- 🎛️ 1. Where the Server Runs: The Three Modes
- 💾 2. Saved Server Profiles
- 🌐 3. Server Addresses & Ports
- 🔑 4. SSH Access & Authentication
- 🔀 5. Port Forwards (Server → This Phone)
- 🛡️ 6. Security: Tokens & Proxy Access Headers
- 🕹️ 7. Live Server Control & Logs
- 💾 8. Saving Changes & Reopening AntiGem
- 🛠️ Troubleshooting Server & Remote Connection Issues

### [13 — Complete Settings Reference](13-settings-reference.md)
Every settings page in the app, in order, with the exact labels and options you will see.

- 🧭 The Settings Map (38 Sections)
- 1. Settings & Preferences (`MAIN`)
- 2. Appearance & Theme (`APPEARANCE`)
- 3. Servers & Connectivity (`SERVERS`)
- 4. Custom AntiGem Plugins (`WEBVIEW_EXTENSIONS`)
- 5. Slash Commands (`SLASH_COMMANDS`)
- 6. MCP Servers (`MCP`)
- 7. Skills & Plugins (`SKILLS_PLUGINS`)
- 8. Terminal & Shell (`TERMINAL`)
- 9. Backups & Restore (`BACKUPS`)
- 10. Data Loss Protection (`DATA_PROTECTION`)
- 🗄️ AntiGem Space Manager (Standalone System Screen)
- 11. Commands & Permissions (`COMMANDS`)
- 12. Antigravity (`ANTIGRAVITY`)
- 13. Antigravity CLI (`AGY_CLI`)
- 14. Claude Code (`CLAUDE` & `CLAUDE_PAGE`)
- 15. OpenCode (`OPENCODE` & `OPENCODE_PAGE`)
- 16. Agents (`AGENTS`)
- 17. Jarvis Voice Assistant (All 10 Subsections)
- 18. Diagnostics & Performance (`DIAGNOSTICS`)
- 19. GitHub (`GITHUB`)
- 20. ADB Connect (`ADB_CONNECT`)
- 21. Android Automation (`ANDROID_AUTOMATION`)
- 22. Termux Support (`TERMUX_SUPPORT`)
- 23. Termux:API (`TERMUX_API`)
- 24. X11 Desktop (`X11_DESKTOP`)
- 25. What's New (`CHANGELOG`)
- 26. About & Info (`ABOUT`)
- 🛠️ Troubleshooting Settings Issues

### [14 — Troubleshooting & Diagnostics](14-troubleshooting-and-diagnostics.md)
How to check your setup, fix problems one by one, and read the app's own diagnostic tools.

- 🚦 Quick Diagnostic Health Check: What Healthy Looks Like
- 🔌 1. Connection & Server Issues
- 🤖 2. AI & Generation Issues
- ⌨️ 3. Terminal & Linux Issues
- 🎙️ 4. Jarvis & Voice Assistant Issues
- 🔄 5. App Updates & Update Failures
- ⚙️ 6. Settings & Visibility Issues
- 📊 7. Using AntiGem's Built-in Diagnostics Tools

### [15 — FAQ & Glossary](15-faq-and-glossary.md)
Short answers to common questions, and plain-English definitions of the technical terms used across the app.

- ❓ Frequently Asked Questions (FAQ)
- 📖 Glossary of Terms
- 🛠️ Troubleshooting FAQ
