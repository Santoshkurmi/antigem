# AntiGem User Guide & Documentation

Welcome to the **AntiGem** documentation! AntiGem is an all-in-one mobile development environment and AI assistant studio for Android. It combines cutting-edge AI coding agents, a complete Linux terminal environment, a full-featured code editor (IDE), an in-app preview browser, a file manager, and a hands-free voice assistant named **Jarvis**—all running directly on your phone or connected to your computer.

This documentation is written for everyday, non-technical users. You do not need to be a software developer or a command-line expert to use AntiGem. Every guide explains concepts in plain language, walks you through each screen step-by-step, describes what you should see, and shows you how to fix things when something doesn’t go as expected.

Looking for a chapter's sections at a glance? The full table of contents for every guide is in **[index.md](index.md)**.

---

## 🆘 Quick Help: Start Here

If you are experiencing an immediate issue, check these fast solutions:

- **Can't connect or red dot in top bar?**
  1. Swipe down your Android notification shade: check if the persistent "AntiGem Service" notification is active.
  2. Open Android **Settings → Apps → AntiGem → Battery** and set to **Unrestricted** so Android does not kill background processes.
  3. If running locally on phone, open **Settings → Servers & Connectivity** and ensure **Remote Mode** is turned **OFF**.
- **Jarvis won't hear your voice or the orb stays cyan?**
  1. Go to Android **Settings → Apps → AntiGem → Permissions** and grant **Microphone** permission.
  2. Check **Settings → Jarvis** and ensure **Turn on Jarvis** is switched **ON**.
  3. Test speech-to-text in the Chat input bar by tapping the small mic icon.
- **The AI agent won't answer or is stuck thinking?**
  1. Look at the bottom-right corner of the chat input bar: while generating, the send button becomes a **Red Square (Stop button)**. Tap it to stop execution.
  2. Tap the **Model Selector** at the top of the chat and try selecting another model or tapping the **Refresh (🔄)** icon to reload available models.
  3. Check **Chat Telemetry & Tokens** in the top bar to ensure your quota window hasn't expired.

---

## 🗺️ Documentation Directory

Explore the guides below to learn how each part of AntiGem works:

| # | Guide | What You Will Learn |
|---|---|---|
| **00** | [Feature Coverage & Verification Proof](00-feature-coverage.md) | Exhaustive mapping and proof that every UI package, all 38 SettingsSection enums, and all 10 Jarvis settings tabs are documented. |
| **01** | [Getting Started & First Launch](01-getting-started-and-onboarding.md) | Initial setup, app permissions, installing local Linux tools, phone vs. remote mode, the floating switcher widget, and system floating bubbles. |
| **02** | [Chat & AI Conversations](02-chat-and-ai-conversations.md) | Chat layout, drawer history & the Active CLI Process dialog, sending messages, photos/files, voice dictation, thinking summaries, and sharing chats (`.antigem`). |
| **03** | [AI Agents & Models](03-agents-and-models.md) | Understanding Antigravity, Claude Code, and OpenCode; live model lists & quotas; thinking effort pills & controls strip; custom API providers; accounts; and safety approvals. |
| **04** | [Projects & Code Editor (IDE)](04-projects-and-ide.md) | Managing coding projects, writing code, viewing images, running apps, using Git, reviewing diffs, and viewing daemon logs. |
| **05** | [Terminal & Linux Shell](05-terminal-and-shell.md) | Using the built-in terminal, managing multiple tabs (tmux), virtual touch keys, running commands, and connecting over SSH. |
| **06** | [Built-in Browser & Web Preview](06-browser-and-web-preview.md) | Testing web projects locally, managing browser tabs, using Eruda developer tools, and AI web automation. |
| **07** | [Files & Notes](07-files-and-notes.md) | Browsing and organizing files on your phone, opening files shared from other apps, and keeping persistent notes and to-do lists. |
| **08** | [GitHub Integration](08-github-integration.md) | Signing in to GitHub (web device code or token), downloading (cloning) repositories, and publishing new projects. |
| **09** | [Tools & MCP Servers](09-mcp-servers-and-tools.md) | Connecting extra capabilities (browser, terminal, device automation) safely using Model Context Protocol (MCP) presets and custom servers. |
| **10** | [Skills & Plugins](10-plugins-and-skills.md) | Expanding what your AI agents can do with custom skills (`SKILL.md`), plugin repositories, and custom slash commands. |
| **11** | [Jarvis Voice Assistant](11-jarvis-voice-assistant.md) | Hands-free voice control: floating orb & panel, wake phrase, speaking, listening, voice output engines, task manager, and voice approvals. |
| **12** | [Server Mode & Remote Access](12-server-mode-and-remote-access.md) | Running AntiGem on your phone while letting a powerful desktop or cloud server handle heavy computing and conserve battery. |
| **13** | [Complete Settings Reference](13-settings-reference.md) | An exhaustive walkthrough of active settings pages: Appearance & real themes, Servers, Backups, Protection, ADB Connect, Automation, Termux:API, X11 Desktop, Changelog, and About. |
| **14** | [Troubleshooting & Diagnostics](14-troubleshooting-and-diagnostics.md) | Plain-language self-help for connection errors, audio/mic issues, terminal freezes, floating diagnostics HUD, and network inspector. |
| **15** | [FAQ & Glossary](15-faq-and-glossary.md) | Answers to common questions and plain-English definitions of technical terms used throughout the app. |

---

## 📱 Quick Concept: How AntiGem is Organized

AntiGem is designed so you can switch between doing different tasks without losing your place:

1. **Persistent Screens**: The app has 6 core screens: **Chat**, **IDE**, **Terminal**, **Browser**, **Notes**, and **Files**. When you switch from writing code to chatting with the AI or browsing a website, your work stays open in the background—nothing is reset or closed.
2. **The Floating App Switcher**: A small floating pill/bubble stays on your screen at all times. Tapping it lets you jump directly between Chat, Code, Terminal, Browser, Notes, and Files with a single tap.
3. **The Voice Orb (Jarvis)**: A glowing circular orb floats on your screen when voice mode is turned on. You can tap it anytime to give instructions by voice, ask what a task is doing, or approve actions.

---

## 🛠️ General Troubleshooting

### ❌ Symptom: The app feels slow, or UI lag occurs after long sessions.
- **What to check**: Check how many terminal tabs and browser tabs are open, and check the floating diagnostics HUD.
- **How to fix**: Close unused terminal sessions by typing `exit`, close unused browser tabs, and navigate to **Android Settings → Apps → AntiGem → Storage → "Manage space" → AntiGem Space Manager → "Clear Temporary Cache"**.

### ❌ Symptom: An AI agent produced code, but it doesn't appear in my project files.
- **What to check**: Check if the agent paused waiting for an approval, or if it ran in Sandbox mode outside the active directory.
- **How to fix**: Look for an approval card in the chat and tap **Approve**, or check the IDE file sidebar and tap the **Refresh (🔄)** icon to reload the file tree.
