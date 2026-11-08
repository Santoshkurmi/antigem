# 02 — Chat & AI Conversations

The **Chat Screen** is your primary workspace for talking to AI agents, asking questions, giving coding instructions, reviewing work, and seeing what actions the AI is performing.

---

## 💬 1. Understanding the Chat Screen Layout & Controls

When you are on the Chat screen, you will see:
1. **Top Bar**:
   - **Drawer Icon (☰)**: Opens your chat history drawer, search, previous conversations, and session manager.
   - **New Chat**: Tap the `+` icon or tap **New Chat** to start a clean session with fresh context.
   - **Choose project**: Tap the folder icon (**Choose project**) to open the workspace folder browser (**Choose the project folder**). This tells the AI which folder on your phone or server contains your code.
   - **Agent Selector**: A pill at the top allowing you to switch between **Antigravity**, **Claude Code**, or **OpenCode**.
   - **Model Selector**: Displays the currently selected model. Tapping it opens the Model Selector bottom sheet with live quotas and thinking effort levels.
   - **Minimize to Bubble**: Tap the bubble icon in the top bar to shrink AntiGem into a floating Android overlay window (`FloatingChatActivity`), letting you chat while using other apps.
   - **Chat Telemetry & Tokens**: Tap the info / telemetry icon to view token usage, latency, and context window limits.
2. **System Status Banners**:
   - **Antigravity Disconnected**: Displays at the top if the connection to the AGY Hub is lost, alongside an automatic reconnection indicator (*"Connecting to Antigravity..."*).
   - **Account Verification Required**: Displays if your account session needs re-authentication.
3. **Conversation Stream**: The feed displaying your messages and the AI's replies.
   - **Floating Scroll Button**: A floating pill appears when scrolling through long chats, labeled **Jump to Bottom** (down arrow) or **Jump to Top** (up arrow) to navigate instantly.
4. **Chat Input Bar**: The bottom area where you type messages, attach files, record voice notes, or speak with live dictation.

---

## 🗂️ 2. The Chat History Drawer

Tapping the **Menu Icon (☰)** in the top-left corner slides out the Chat History Drawer:
- **New Chat**: Starts a clean conversation with a fresh context.
- **Search Conversations**: The search input field displays the placeholder **"Search by title, topic, or model..."**. Filter chips include **"All"**, agent chips, and workspace tags.
- **Active CLI Process Dialog**:
  - When a chat has a live Antigravity CLI process active in the background, a green status pill appears. Tapping it opens the **Active CLI Process** dialog:
    - *"A live Antigravity CLI process is running in the background for this chat:"*
    - Details displayed: **PID: <pid>**, **Model: <model>**, **Uptime: <n>s**, and state **"RUNNING"**.
    - Buttons: **Terminate & Free RAM** (shows confirmation *"Terminate this instance to immediately free RAM?"*) and **Keep Running**.
- **Conversation List**: Past chats are grouped into chronological categories: **"Today"**, **"Past 7d"**, and **"Past 30d"**.
  - Empty states: *"No chats in this category."* (when a date section is empty) or *"No chats found matching \"...\""* (when search yields no matches).
  - You can also toggle **Group by Workspace** to organize chats by project directory.
- **Conversation Overflow Menu (⋮)**:
  - **Fork Conversation**: Branches the conversation from that exact point, allowing you to try an alternative approach without losing earlier history.
  - **Rename chat**: Opens a dialog to edit the conversation title.
  - **Copy Conversation ID**: Copies the internal session identifier to your clipboard.
  - **Export Markdown (.md)**: Saves the chat messages and code blocks as a standard markdown file.
  - **Export Standalone HTML**: Generates a self-contained, beautifully styled HTML document you can open in any browser.
  - **Share**: Bundles the session into an interactive replay package (shows toast: *"Preparing .antigem share..."*).
  - **Delete Conversation**: Opens the *"Delete Conversation?"* confirmation dialog to permanently remove the chat.
- **Bottom Drawer Bar**:
  - 👤 **Accounts**: Shows who is signed in for each agent, current subscription tier, and sign-in status.
  - ⚙️ **Settings**: Opens the main app settings.

---

## ✍️ 3. Input Bar: Messages, Attachments & Voice

The bottom input bar is packed with productivity controls:

### A. Typing and Prompt Placeholders
- **Reply Quote**: The input box displays **Reply to [Model]...** (e.g. *"Reply to Gemini..."* or *"Reply to Claude..."*).
- Type your prompt and press the **Send** button (arrow icon on the right).
- When the agent is responding, the send button transforms into a **Red Square (Stop button)**. Tap it anytime to immediately abort generation.

### B. Attachments (`Add attachments`)
Tap the paperclip icon (**Add attachments**) to open the bottom sheet titled **Add to Chat**:
- **Photo / Image**: *"Attach an image for multimodal vision & analysis"* — opens device image picker for screenshots, photos, and UI diagrams.
- **Document / File**: *"Attach a PDF, text, or binary file from your device"* — opens device file picker for documents, code files, logs, and PDFs.
- **Attachment Preview Strip**: Selected attachments appear as cards above the input bar:
  - Tap any card (**Open [name]**) to preview its contents.
  - Tap the cross button (**Remove**) on a card to detach it before sending.

### C. Voice Input: Voice Notes vs. Transcription
The microphone button automatically adapts its behavior based on which agent you are currently chatting with (the mode follows the active agent):

1. **Claude Chats (`Record and transcribe`)**:
   - In Claude Code chats, the microphone button has `contentDescription="Record and transcribe"`.
   - Tapping it records your speech and automatically transcribes it into text directly in the input box so you can review or edit before sending.
2. **Antigravity & OpenCode Chats (`Record voice note`)**:
   - In Antigravity and OpenCode chats, the microphone button has `contentDescription="Record voice note"`.
   - Tapping it opens the audio recording bar with a duration timer and controls from `ChatInputBar.kt`:
     - **Pause** / **Resume**: Temporarily pause or resume audio capture.
     - **Cancel recording**: Discards the recorded audio.
     - **Send voice note**: Sends the recorded audio note directly into the chat.
     - **Stop & Attach**: Finishes recording and attaches the audio file above the input bar, allowing you to add accompanying typed text before sending.
3. **Live Dictation (Speech-to-Text)**:
   - When dictation is running, a banner above the input displays *"Listening... Speak now"*.
   - Tap **Pause** to hold recording (*"Dictation paused (tap resume to speak)"*), **Resume** to continue, or **Stop** (**Stop Dictation**) when finished.
   - If no speech was detected, the banner shows *"No speech recognized"*.

---

## 💬 4. Message Bubble Actions & Controls

Every message bubble in the conversation stream provides interactive tools:

### User Message Actions:
- **Edit prompt**: Tap the edit pencil icon on your prompt. The **Edit Message?** dialog opens, allowing you to edit the original text and optionally add: *"Explain why or give new instructions to AI (optional):"*.
- **Retry prompt**: Tap the retry icon to re-send your message to the agent immediately.
- **Copy message**: Copies your prompt text to the clipboard (labeled `"Copied Prompt"`, displays toast *"Copied prompt"*).

### Assistant Message Actions:
- **Copy message**: Tap the copy icon to copy the AI's reply to the clipboard (labeled `"Copied Response"`, displays toast *"Copied response"*).
- **Select text**: Tap the select text icon to open the **Select text** sheet. This lets you highlight and copy specific sentences or paragraphs rather than the whole response.
- **Regenerate response**: Tap the refresh icon. The dialog prompts: **Regenerate response?** (*"The conversation from this point on will be replaced with a new answer."*). Confirming discards later turns and re-runs the turn with the active model.
- **Show Details / Show less**: For lengthy responses, tool calls, and thinking processes:
  - Tap **Show Details ▼** to expand full logs, stdout, stderr, and reasoning traces.
  - Tap **Hide ▲** (or **Show less ▲**) to collapse them back to a tidy view.

---

## 🧠 5. Special Feed Elements: Tool Calls, Choices & Approvals

When an AI agent is working on your request, several special visual elements may appear in the conversation stream:

### 1. The "Thinking" Accordion
Models with extended reasoning do internal brainstorming before answering:
- **What it looks like**: A collapsible row labeled **"Thinking..."** while streaming, and **"Thought for [time]"** when finished.
- **How to use it**: Tap anywhere on the row to expand it and read the exact chain of thought. Tap again to collapse it.

### 2. Tool Call Cards (`AgentToolCallCard`)
When the AI agent performs actions on your phone (terminal commands, file edits, web searches):
- Displays the tool name (e.g. `pnpm install`, `write_to_file`).
- Expand the card to inspect parameters and execution output.

### 3. Approval Cards (`PENDING_APPROVAL`)
When the app is set to ask before running commands, or when the agent asks for approval:
- When the agent attempts an action that requires confirmation, execution pauses and an approval card appears.
- Review the proposed command or file diff, then tap **Approve** to proceed or **Deny** to cancel.

### 4. Interactive Questionnaires (`ChoiceQuestionnaireCard`)
When an agent invokes `ask_question`:
- Renders an interactive questionnaire card with selectable choice pills or checkboxes, plus a write-in field.
- Tap your answer and tap **Submit**. The card answers the agent automatically.

### 5. Plan Mode (Claude Code)
When Claude Code enters planning mode:
- The UI displays the plan status and tools like `ExitPlanMode`.
- Once the plan is reviewed, tap **Finish** to exit plan mode and begin implementation.

### 6. Queued Messages Card
If you type and send a message while the agent is busy working:
- Your message is placed in the **Queued Messages Card** at the bottom of the feed.
- The agent will automatically consume and address your message as soon as the current step finishes.

---

## ⚡ 6. Slash Commands (`/`)

Whenever you type a forward slash (`/`) into the chat input bar, an auto-complete popup list appears:
- `/plan` — Instructs the agent to break down your task into a detailed step-by-step implementation plan before making changes.
- `/goal` — Tells the agent to work autonomously without stopping until the task is completely finished and verified.
- `/grill-me` — Asks the agent to interview you with clarifying questions to make sure all project details are crystal clear.
- `/boost` — Activates maximum reasoning effort for tricky algorithmic or debugging problems.
- `/learn` — Teaches the agent a specific rule or convention you want it to remember for future chats.

---

## 🛠️ Troubleshooting Chat Issues

### ❌ Symptom: Live dictation hears nothing or shows "No speech recognized".
- **What to check**: Check microphone permission and ensure dictation is not paused.
- **How to fix**:
  1. Grant microphone permission in Android **Settings → Apps → AntiGem → Permissions → Microphone**.
  2. If the banner shows *"Dictation paused (tap resume to speak)"*, tap the **Resume** button.
  3. Speak close to the phone microphone and avoid noisy background environments.

### ❌ Symptom: Voice note recording fails or cannot send.
- **What to check**: Check if audio recording was cancelled or storage is full.
- **How to fix**:
  1. Ensure you tap **Send voice note** or **Stop & Attach** rather than **Cancel recording**.
  2. Ensure the app has storage access to save the temporary `.m4a` audio file.

### ❌ Symptom: Regenerate response behaves oddly or duplicates answers.
- **What to check**: Check if you regenerated from an earlier message in the conversation.
- **How to fix**: Regenerating removes all messages *after* that turn and generates a fresh answer. If you want to keep the old branch, use **Fork Conversation** from the drawer menu first!

### ❌ Symptom: The AI is stuck waiting and won't proceed.
- **What to check**: Look through the chat feed for a **PENDING_APPROVAL** card or an interactive questionnaire.
- **How to fix**: The AI cannot proceed until you tap **Approve** on the tool card or select an option on the choice questionnaire. If no card is visible, tap the red **Stop button** to interrupt the agent.
