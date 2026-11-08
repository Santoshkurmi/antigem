# 11 — Jarvis Voice Assistant

**Jarvis** is AntiGem's hands-free voice assistant. With Jarvis, you can control the entire app, talk to open code files, watch active background tasks, answer approval prompts, and adjust settings using your voice.

---

## 🎙️ 1. Turning On Jarvis & The Setup Checklist

Jarvis is completely private and turned **OFF** by default. Nothing listens or records audio until you turn it on.

### Turning on Jarvis:
1. Open **Settings → Jarvis**.
2. Flip the switch labeled **"Turn on Jarvis"** (*"A hands-free assistant for the whole app: talk to open projects and files, run and watch tasks, answer approvals, change settings, ask for help or your usage. Off by default; nothing listens until you turn it on."*).
3. Once enabled, navigate to **Settings → Jarvis → Setup** to complete the setup steps for your chosen agent:
   - **"1. Add the Jarvis MCP server"**: Required. These are the tools Jarvis controls the app with. Tap **Add** (or **Refresh**).
   - **"2. Let Jarvis' tools run without asking"** (for Claude Code): Required so every spoken answer does not need an interactive approval dialog. Tap **Allow**.
   - **"3. Allow the microphone"**: Prompts for Android audio recording permission (*"Needed to hear you."*). Tap **Allow**.
   - **"Optional: install the Jarvis skill"**: Installs the detailed Jarvis tool and behavior instructions into your agent's skills directory. Tap **Install**.

---

## 🔮 2. The Voice Orb & The Jarvis Panel

Once Jarvis is turned on, the **Voice Orb** appears floating on your screen:

### Visual Orb States:
- 🔵 **Cyan Pulse (Idle / Ready)**: Jarvis is listening for wake actions or waiting for you to tap.
- 🔴 **Red Glow (Listening)**: Microphone is open; your speech is being transcribed.
- 🟣 **Purple Swirl (Thinking / Working)**: Jarvis is executing a model turn or running tools.
- 🟢 **Green Wave (Speaking)**: Jarvis is speaking back to you aloud.

### Interacting with the Orb:
- **Tap once**: Starts listening immediately.
- **Drag**: Drag the orb anywhere; it gently docks to the nearest screen edge.
- **Long-Press (Hold for 1 second)**: Opens the **Jarvis Panel**!

### Inside the Jarvis Panel:
- **Header**: Shows **"Jarvis"** with live status subtitle and connection mode.
- **Task Monitor**: Shows active agent tasks, with **"Clear finished"** to dismiss completed jobs.
- **Silent Text Input**: An input box with placeholder **"Type to Jarvis…"** and a send button so you can silently type commands to Jarvis when you cannot speak aloud.
- **Quick Switches**: 1-tap toggles for Mute, Quiet Mode, and Hands-Free mode.

---

## ⚙️ 3. Section-by-Section Settings Guide

Every sub-page under **Settings → Jarvis** controls a specific capability:

### A. Agent (`JARVIS_AGENT`)
- **"Pick the agent Jarvis talks to first"**: Select between **Claude Code**, **Antigravity**, or **OpenCode**.
- **"Model" Dropdown**: Pick a specific model or keep **"Default model"**.
- *Note*: Jarvis communicates with a dedicated, hidden chat of this agent (visible under the "Jarvis" filter in the chat drawer). A fast model is recommended for responsive voice conversation.

### B. Setup (`JARVIS_SETUP`)
- **"The Jarvis MCP server, permissions and the microphone"**:
  - **"1. Add the Jarvis MCP server"**: Required tools for Jarvis to control app actions, projects, and editor files. Tap **Add** (or **Refresh**).
  - **"2. Let Jarvis' tools run without asking"** (for Claude Code): Adds an auto-allow permission rule so spoken answers execute without requiring interactive approvals. Tap **Allow**.
  - **"3. Allow the microphone"**: Requests Android audio recording permissions (*"Needed to hear you."*). Tap **Allow**.
  - **"Optional: install the Jarvis skill"**: Installs the Jarvis guide and behavioral instructions directly into the agent's skills directory. Tap **Install** (or **Reinstall**).

### C. Personality & you (`JARVIS_PERSONALITY`)
- **"Jarvis speaks as"**: Set grammatical self-address to **"Female"**, **"Male"**, **"Neutral"**, or **"Not set"** (essential for gendered languages like Hindi).
- **"About you and extra instructions"**: Multi-line instructions sent on the first message of every new Jarvis chat (e.g., *"Call me Santosh. Reply in Hinglish. I mostly work on the antigem project."*).
- **"Jarvis memory (Jarvis can edit this too)"**: A persistent memory scratchpad that Jarvis can read and edit on its own across sessions.

### D. Waking Jarvis (`JARVIS_WAKE`)
- **"Trigger"**:
  - **"Button / tile / headset"**: Only opens mic when explicitly triggered.
  - **"Wake phrase too"**: Enables continuous background listening for your wake phrase.
- **"Wake phrase(s), comma separated"**: Enter custom wake phrases (default: *"hey jarvis"*).
- **Wake Match**: Choose **"Whole phrase"** or **"The name alone"**.
- **"Hands-free mode"**: Keeps Jarvis' microphone service running in the background with a persistent notification offering **Talk / Send / Stop** buttons.
- **"Headset button"**: Press once to talk/send, twice to stop.
- **Quick Settings Tile**: Add the **Jarvis** tile to your Android Quick Settings shade to talk from any app with one tap.

### E. Listening & sending (`JARVIS_LISTEN`)
- **"Send automatically when I pause"** vs. **"Manual send"**:
  - When auto-send is ON: choose **"Smart pause"** (waits longer when you sound unfinished like *"...and"*) or **"Fixed pause"**, and adjust the **"Pause that ends your sentence"** slider (0.8s to 3.5s).
  - When auto-send is OFF: toggle **"Answers to Jarvis' questions send by themselves"** for short replies like *"yes"*.
- **"Send with a phrase"**: Toggle ON to send immediately upon hearing a trigger phrase. Tap **"Send phrases"** (19 default phrases including *"send it"*, *"over"*, *"go ahead"*).
- **"Cancel with a phrase"**: Tap **"Cancel phrases"** to discard current speech (e.g. *"never mind"*, *"cancel"*).
- **"After Jarvis speaks, listen for your reply"**: Choose **"When it expects an answer"**, **"Always"**, or **"Never"**, and adjust **"Wait for a reply"** slider (3s to 20s).
- **"Longest single message"**: Limit speech recording from 20s to 240s.
- **"Talking to other people nearby"**: Set side-talk filtering to **"Off"** (*"Everything heard is for Jarvis"*), **"Smart"**, or **"Strict"**.
- **"Speaking while Jarvis is still working"**: Choose **"Corrections interrupt"**, **"Wait for it"**, or **"Always interrupt"**.
- **"Tones when the microphone opens and closes"**: Audio earcons confirming mic state.
- **"Speech recognition"**: Choose **"Same as agent"**, **"Claude"**, **"Antigravity"**, or **"This device"** (Android offline recognizer), with optional **"Language"** code (e.g. `en`, `hi`, `es`).

### F. Voice output (`JARVIS_VOICE`)
- **"Quiet mode"**: Shows answers on the card instead of speaking aloud (*"Say 'quiet mode off' to undo"*).
- **TTS Engine**:
  - **"This device"**: Android built-in speech synthesis. Choose from installed system voices and language.
  - **"Google Cloud"**: High-fidelity neural voices. Requires **API key**, **Voice name** (e.g. `en-US-Neural2-F`), and **Language code**.
  - **"OpenAI-compatible"**: Requires **API key**, **Base URL (default https://api.openai.com/v1)**, **Model (default tts-1)**, and **Voice (default alloy)**.
- **"If the online voice fails, Jarvis falls back to this device's voice"**: Automatic fallback safeguard.
- **"Speed"** slider (0.5x to 2.0x) and **"Pitch"** slider (0.5 to 2.0).
- **"Test voice"** button.
- **"Speak progress updates"**: *"Jarvis says a short line while a long step runs."*

### G. Manager & tasks (`JARVIS_TASKS`)
- **"When a task finishes, fails or gets stuck"**: Choose **"Jarvis decides"** (reads result and tells you the gist) or **"Just tell me"** (simple spoken announcement).
- **"Approvals inside tasks"**: **"Use my approval rules"** or **"Jarvis decides"** (approves routine tasks automatically and only asks about risky actions).
- **"Report a task with no progress after"**: Alert if a task stalls for **Never**, **5 min**, **10 min**, **20 min**, or **45 min**.
- **"Say when any other chat finishes"**: Spoken alert when any background chat finishes, with **"Not for the chat I'm looking at"** and **"Also read the start of the reply"**.
- **"Warn when the 5-hour usage is almost gone"**: Alert when account quota hits 15%.

### H. Approvals (`JARVIS_APPROVALS`)
- **Policy Chips**:
  - **"Ask me each time"**: Jarvis reads each request out loud and waits for your spoken yes/no.
  - **"Auto: read-only"**: Reading files and searching is approved automatically; commands or edits require voice confirmation.
  - **"Auto: except risky"**: Approves routine work; only dangerous actions (deleting, git push, package installation) require voice approval.
  - **"Auto: everything"**: Hands-off approval with warning dialog: *"Nothing is asked. Use only if you fully trust what the agents do."*
- **"Let Jarvis change app settings"**: Allows voice control of theme, font scaling, floating switcher, and default agent.

### I. Screen & look (`JARVIS_SCREEN`)
- **"Keep the screen on"**: Choose **"While Jarvis is on"**, **"While it's active"**, or **"Never"**.
- **"Orb while idle"**: Set orb size to **"Tiny"**, **"Small"**, **"Normal"**, or **"Hidden"**.
- **"Show what Jarvis is doing"**: Choose between **"Floating card"** or **"Full screen"**.
- **"Vibrate when listening starts"**: Haptic feedback on mic activation.
- **"Show what Jarvis heard and said"**: Displays speech transcription on screen.

### J. Jarvis chat (`JARVIS_CHAT`)
- **"Send live context with every message"**: Automatically includes current screen, project path, active chat, tasks, and approvals with your spoken prompt.
- **"Start a fresh chat after idle time"**: Auto-rotates chat after **Never**, **5 min**, **15 min**, **30 min**, **1 h**, or **3 h**.
- **"Start a fresh chat after this many messages"**: Auto-rotates after **Never**, **10**, **20**, **40**, or **80** messages.
- **"Carry the conversation over"**: Carries over memory note and recent lines when a chat rotates.
- **"Start a fresh chat now" Button**: Force-resets the voice session immediately.

---

## 🛠️ Troubleshooting Voice & Audio Issues

### ❌ Symptom: Jarvis says "Microphone permission required" and stops.
- **What to check**: Check Android system permissions.
- **How to fix**: Open Android **Settings → Apps → AntiGem → Permissions → Microphone** and select **Allow only while using the app** (or **Allow all the time** for hands-free background wake phrase).

### ❌ Symptom: Wake phrase ("Hey Jarvis") does not trigger.
- **What to check**: Check trigger mode and Hands-free setting.
- **How to fix**:
  1. Open **Settings → Jarvis → Waking Jarvis**.
  2. Set **Trigger** to **"Wake phrase too"**.
  3. Ensure **"Hands-free mode"** is switched **ON**.

### ❌ Symptom: Jarvis says its app tools are missing in the agent.
- **What to check**: Check Jarvis MCP server status.
- **How to fix**: Open **Settings → Jarvis → Setup** and tap **Add** next to **"1. Add the Jarvis MCP server"**, then tap **"Start a fresh chat now"** in **Settings → Jarvis → Jarvis chat**.

### ❌ Symptom: Online voice fails or produces no sound.
- **What to check**: Check API key and network connection for OpenAI or Google Cloud TTS.
- **How to fix**: Open **Settings → Jarvis → Voice output**. AntiGem automatically falls back to **"This device"** voice if online TTS fails. Tap **Test voice** to verify local speech synthesis.
