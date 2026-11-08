# 03 — AI Agents & Models

AntiGem is unique because it is not tied to a single AI engine. It provides integrated access to three distinct coding agent systems: **Antigravity**, **Claude Code**, and **OpenCode**, along with live model selection, reasoning budget controls, and security approval levels.

---

## 🤖 1. The Three AI Agents Explained

When starting a conversation or looking at the top bar, you will see the **Agent Picker** with three options:

### 1. Antigravity (`AGY`) — Color: Blue 🔵
- **Engine**: Built around Google DeepMind's Advanced Agentic Coding engine and the Antigravity CLI (`agy`).
- **Best for**: Deep algorithmic thinking, complex codebase navigation, multi-agent teamwork, and heavy reasoning tasks.
- **Models**: Fetched dynamically from your Antigravity connection. Models are grouped into categories (such as Gemini models, Argon models, or Claude/GPT models depending on your account).
- **Special features**: Native subagent spawning, interactive artifacts, and live background task monitoring.

### 2. Claude Code (`CLAUDE`) — Color: Terra Cotta / Orange 🟠
- **Engine**: Developed by Anthropic, running the official Claude Code CLI.
- **Best for**: Rapid code editing, precise refactoring, git commit workflows, and understanding large codebases.
- **Models**: Fetched live from your Anthropic account or your custom Anthropic-compatible providers.
- **Special features**: Deep terminal command synthesis, compact diff generation, and official Anthropic CLI compatibility.

### 3. OpenCode (`OPENCODE`) — Color: Emerald Green 🟢
- **Engine**: An open-source, flexible agent provider.
- **Best for**: Self-hosted AI models (e.g. Ollama running on your computer or local network), private cloud endpoints, DeepSeek, OpenAI, or any custom OpenAI-compatible API.
- **Models**: Configured by you or retrieved directly from your custom server endpoint.
- **Special features**: Completely custom base URLs, custom API keys, and privacy-first local model hosting.

---

## 🔄 2. How to Switch Agents

1. Look at the top bar of the **Chat** screen.
2. Tap the name of the agent you want to talk to: **Antigravity**, **Claude Code**, or **OpenCode**.
3. The accent color of the app highlights your choice (Blue for Antigravity, Orange for Claude Code, Green for OpenCode).
4. Each agent maintains its own separate chat history, context, and active sessions.

> 💡 **Tip**: If you only use one agent and want to hide the picker to save screen space, go to **Settings → Agents** and uncheck the agents you do not use. When only one agent is active, the picker automatically hides.

---

## 🎛️ 3. How Models, Thinking, and Reasoning Effort Work

Model lists are **never hardcoded** in AntiGem. They are fetched dynamically in real time from the active agent backend. Each agent handles thinking and reasoning effort in its own way:

### A. Antigravity Models & Thinking Effort
1. Tap the model selector pill in the top bar of the Chat screen.
2. The **Select model** bottom sheet opens, showing models grouped into family sections with live quota tracking (**5h** and **7d** windows showing remaining percentages and countdown timers).
3. **Thinking Effort Selection**: Models that support variable internal thinking are grouped together. On tiered model cards, you will see direct **Thinking Effort** pills:
   - **High**
   - **Medium**
   - **Low**
   Tapping one of these pills selects that specific thinking tier variant for your model immediately.

### B. Claude Chats: Controls Strip & Model Sheet
Above the keyboard in any Claude chat, a horizontal controls strip provides immediate access to thinking, effort, and live usage:
- **Thinking Chip**: Tap the **Thinking** / **No thinking** chip to toggle Claude's extended reasoning on or off.
- **Effort Chip**: Displays **Effort · [Level]** (e.g. `Default`, `Low`, `Medium`, `High`, `Extra high`). Tap to open the effort menu and choose how hard Claude ponders each problem.
- **Prompt Cache Chip**: Shows live status (e.g. `Cache 4m` when warm or `Cache cold`). Tap to inspect time remaining on the cache.
- **Context Window Chip**: Shows current context consumption (e.g. `Context 24%`).
- **Claude Model Sheet**: Tapping the model in the top bar opens Claude's model picker:
  - Choose between official Claude models and custom providers.
  - Set **Effort** with the segmented selector (Default, Low, Medium, High, Extra high).
  - **Fast mode**: Displays the current status as **Fast mode · On** or **Fast mode · Off** (*"Send /fast in the chat to toggle"*).

### C. OpenCode: Model & Providers Sheet
1. Tap the model pill in OpenCode chat to open the **Select model** sheet.
2. Models are grouped by provider. Type in the **Search models or providers** box to quickly filter.
3. Tap **Connect providers or add an OpenAI-compatible endpoint** to open provider setup.

---

## 🌐 4. Custom API Providers

Both Claude Code and OpenCode allow you to connect external, self-hosted, or third-party AI endpoints.

### 1. Claude Custom Providers (`Settings → Claude Code → Custom providers`)
Connect any external endpoint speaking the Anthropic Messages API (e.g. OpenRouter, Moonshot Kimi, DeepSeek, or a local proxy):
1. Tap **Add a provider**.
2. Select a preset (such as **OpenRouter**, **Kimi**, **DeepSeek**) or choose **Custom** (*"Any endpoint that speaks the Anthropic Messages API."*).
3. Enter the connection settings:
   - **Base URL**: The API root (e.g. `https://openrouter.ai/api`).
   - **API key**: Your secret key.
   - **Auth Mode**: Choose between **Bearer token** (`Authorization: Bearer <key>`) or **x-api-key header**.
   - Tap **Apply & fetch** to test connectivity. AntiGem will ping the endpoint and display **Connected · N ms** (or **Connection failed** with the HTTP error code).
4. Tap **Choose from the provider's models** or tap **Add model** to specify model IDs.
5. In **Advanced**, optionally configure **Context window (tokens)** and a **Background model (optional)** for generating chat titles and summaries.
6. **Switching Providers in Active Chats (`ClaudeProviderSwitchDialog`)**: If you switch an active conversation between Claude's official account and a custom provider, AntiGem warns you with *"⚠️ Switch provider in this chat?"*. Because switching restarts Claude Code and resends the entire conversation without cache (incurring extra costs), AntiGem gives you the choice to **Start a new chat with it**, **Cancel**, or wait 3 seconds and choose **Switch anyway**.

### 2. OpenCode Custom Providers (`Settings → OpenCode → Custom providers`)
Connect OpenAI-compatible endpoints or local servers (such as Ollama):
1. In OpenCode settings, tap **Add a custom provider** (or tap **Connect providers or add an OpenAI-compatible endpoint** in the model picker).
2. Enter the details:
   - **Base URL**: The full endpoint URL (e.g. `http://192.168.1.50:11434/v1` for a local Ollama server, or `https://api.openai.com/v1`).
   - **API key (optional for local servers)**: Enter your API key, or leave blank for local Ollama servers that do not require authentication. When editing an existing provider, the field displays *"API key (blank keeps the saved one)"*.
3. Tap **Fetch from endpoint** to query the server's available models list automatically, or tap **Add model** to manually enter a model name.
4. Tap **Connect** (or **Add account** / **Add another account**). Connected providers appear under **Custom providers** with indicators like **Free models** or number of models connected.

---

## 👤 5. Accounts & Usage Dialog

Each agent authenticates separately:
1. Open the Chat History Drawer (tap **☰** in the top-left corner of Chat).
2. Tap your user profile or accounts button at the bottom of the drawer.
3. The dialog displays account status, active subscription tiers, email addresses, and CLI versions for Antigravity, Claude Code, and OpenCode, with fast shortcuts to sign out or manage settings.

---

## 🛡️ 6. Permission Modes & Approvals

AI agents need to run terminal commands, create files, and inspect code. To protect your phone, AntiGem provides comprehensive security policies and interactive approval cards:

### Global Security & Policies (Settings → Commands & Permissions)
*Subtitle: "Controls terminal command execution and file access outside working directory."*

The **Commands & Permissions** row badge in Settings displays your active policy: **"Ask first"**, **"Smart safety"**, **"Auto-run"**, or **"All off"**.

Under **Security Presets**, choose between four distinct preset cards:
- **"🛡️ Default Mode"** (badge **"Recommended"**):
  - *"Terminal commands require confirmation. Files outside workspace require confirmation."*
- **"💻 Full Machine Mode"** (badge **"Open Files"**):
  - *"Terminal commands require confirmation. Full read/write access to all files on device."*
- **"⚡ Turbo Mode"** (badge **"Full Auto"**):
  - *"Full auto-run: terminal commands execute immediately and all files can be accessed."*
- **"⚙️ Custom Granular Mode"** (badge **"Custom"**):
  - *"Configure terminal command execution and external filesystem permissions separately."*
  - Shows granular configuration chips:
    - **Terminal Command Execution:** **"✋ Ask Every Time"** or **"⚡ Auto-Run (Eager)"**
    - **Files Outside Workspace:** **"✋ Ask"**, **"✅ Allow"**, or **"🚫 Deny"**

### Granular Permission Grants:
- Configure explicit rules for commands, MCP tools, and file paths:
  - *"Explicit Allow, Deny, and Ask rules for commands, MCP tools, and file paths"*
  - **"Add Rule"** button: Creates custom granular permission overrides.

### Safety Toggles:
- **Review File Edits & Writes**: *"Prompt for confirmation before assistant applies file edits or writes code to disk."*
- **Terminal Sandbox Isolation**: *"Enabled: Isolates commands in sandbox without host network access."*

### Per-Project Overrides:
- In addition to global policies, each individual project workspace can override permissions:
  - Tap **"Set to Inherit Global Settings"** to follow app defaults, or pick project-specific preset chips: **"Default"**, **"Machine"**, or **"Turbo"**.

### Claude Code Interactive Approval Cards:
Claude Code uses dynamic in-chat approval cards rather than abstract mode names:
- **Plan / Permission Cards**: Choose between **"Approve · review each edit"** or **"Approve · auto-accept edits"**.
- **Action Approval Prompts**: Renders specific cards based on the tool requested:
  - **"Run a command?"**
  - **"Edit a file?"**
  - **"Create a file?"**
  - **"Read outside the project?"**
  - **"Fetch a web page?"**
  - **"Search the web?"**
- **Action Buttons**: Tap **"Allow"**, **"Always allow…"** (whitelists that tool for the session), or **"Deny"**.
- **Batch Actions**: When multiple actions queue up, cards offer **"Allow all N"** or **"Deny all N"**.
- **Alternative Guidance**: An optional feedback field: *"Tell the agent what to do instead (optional)"* lets you redirect the AI when denying or adjusting an action.

---

## 🛠️ Troubleshooting Agent & Model Issues

### ❌ Symptom: The Model Selector sheet is empty or says "No models available".
- **What to check**: Check whether you are signed into the agent, or tap the refresh icon.
- **How to fix**:
  1. Open the Chat Drawer and check your account connection.
  2. In the Model Selector sheet, tap the refresh icon at the top to re-query the agent backend.
  3. Verify your internet connection.

### ❌ Symptom: Claude custom provider test fails with "Connection failed".
- **What to check**: Check the Base URL format and API key.
- **How to fix**:
  1. Ensure the Base URL does not have trailing slashes or duplicate paths (e.g. use `https://openrouter.ai/api`).
  2. Verify if the provider expects a **Bearer token** or an **x-api-key** header.
  3. Ensure the API key contains no leading or trailing whitespace.

### ❌ Symptom: OpenCode fails to connect to local Ollama server.
- **What to check**: Check whether you used `localhost` instead of the phone-accessible network IP.
- **How to fix**:
  1. `localhost` inside Termux/Android refers to the phone itself, not your PC. Use your computer's local Wi-Fi IP address (e.g. `http://192.168.1.50:11434/v1`).
  2. Make sure `/v1` is included at the end of the URL.
  3. Ensure Ollama is configured to listen on all interfaces (`OLLAMA_HOST=0.0.0.0`) on your computer.
