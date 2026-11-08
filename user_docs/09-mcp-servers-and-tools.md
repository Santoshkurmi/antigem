# 09 — Tools & MCP Servers

AntiGem features deep integration with the open-source **Model Context Protocol (MCP)**. MCP standardizes how AI agents communicate with local processes and remote services, giving agents the ability to run shell commands, automate mobile apps, browse the web, read files, and trigger assistant actions.

---

## 💡 1. What is an MCP Server?

Large Language Models on their own can only generate text based on training data. An **MCP Server** acts as a bridge between the AI model and external tools:
- The server declares what functions or tools it can perform (for example, taking a browser screenshot or running a bash command).
- The agent calls these functions with arguments when reasoning through your prompt.
- AntiGem routes the requests to the local bridge or process, enforces user permissions, and sends the structured response back to the agent.

AntiGem manages MCP configurations through the global `mcp_config.json` file and provides a clean graphical interface in **Settings → MCP Servers**. At the top of the screen, you will see the overview: *"Configure local process (Stdio) or remote (SSE) MCP servers"*.

---

## ⚡ 2. Quick Presets & Built-In Integrations

In the **Quick Presets & Templates** section, AntiGem provides nine pre-configured, one-tap server templates:

| Preset Name | Subtitle / Function | Transport & Connection |
| :--- | :--- | :--- |
| **Custom Plugin MCP** | *Pick an installed plugin* | Opens a picker allowing you to select and expose the MCP tools bundled with an installed plugin |
| **Plugin Developer** | *Build & test AntiGem plugins* | Local bridge `/extension_developer` for creating and testing custom AntiGem plugins |
| **Browser** | *Control the in-app browser* | Local bridge `/browser` (`browser_mcp`) for navigating web pages, clicking elements, filling inputs, and evaluating JavaScript |
| **Terminal** | *Run commands in the terminal* | Local bridge `/terminal` (`terminal_mcp`) for running shell commands and inspecting build processes |
| **Android** | *Tap, type & read this phone* | Local bridge `/android` (`android_automation_mcp`) for device automation, screen reading, and UI interaction |
| **Notes & To-dos** | *Read & edit your notes* | Local bridge `/notes` (`notes_mcp`) for searching, reading, creating, and modifying local user notes |
| **Jarvis** | *Voice control of this app* | Local bridge `/jarvis` (`jarvis_mcp`) for voice-driven tool invocation, project creation, and task management |
| **Theme** | *Create & switch app themes* | Local bridge `/theme` (`theme_mcp`) for generating, previewing, and applying custom app color schemes |
| **Stitch** | *Google Stitch UI design · API key* | Remote SSE endpoint (`https://stitch.googleapis.com/mcp`) for generating UI prototypes using Google Stitch (requires entering your `X-Goog-Api-Key` in HTTP headers) |

Tapping any preset tile opens the editor with all required arguments pre-filled, making it easy to configure or activate in seconds. If a preset is already active, its tile displays an added badge.

---

## 🛒 3. Cascade MCP Marketplace (1-Click Installs)

Below the header card, tap **Browse MCP Catalog / Marketplace (1-Click)** to open the **Cascade MCP Catalog** dialog:
- **Search Bar**: Type into the search input (*"Search MCP packages (e.g. Firebase, BigQuery, Firestore)..."*) to find community and enterprise integrations.
- **Package Details**: Each entry in the marketplace displays:
  - Package title and unique package identifier (`ID: ...`).
  - Trust level pill (e.g. verified or community badges).
  - Clear plain-language description of what capabilities the package adds.
  - Connection mode indicator: **⚡ Stdio** (showing CLI command and template arguments) or **🌐 Remote** (showing the SSE endpoint URL).
- **README Documentation**: Tap **README** on any package card to open a full markdown preview of the documentation, setup requirements, and available tools without leaving the app.
- **1-Click Install**: Tap the terracotta **Install** button. AntiGem downloads the package configuration and writes it to your configuration file. When completed, the button updates to a green checkmark with an **Installed** badge.

---

## ➕ 4. Adding & Editing Custom Servers

To add any custom local or remote MCP server:
1. Tap the **Add Server** button under the overview card.
2. The **Add MCP Server** dialog opens (or **Edit MCP Server** when tapping an existing item in the list).
3. Select your configuration mode at the top:
   - **Stdio**: For local processes, Node.js scripts, Python utilities, and command-line binaries.
   - **SSE**: For remote HTTP Server-Sent Events endpoints (e.g. cloud services or web hooks).
   - **Raw**: For directly pasting or inspecting the raw JSON configuration block with a **Format** button.

### Stdio Mode Fields
- **Server Name \***: Unique identifier for the server (e.g. `filesystem`, `github`, or `postgres`).
- **Command \***: Executable command (e.g. `npx`, `python3`, `node`, or `uvx`).
- **Arguments (space separated)**: Command flags and target directory (e.g. `-y @modelcontextprotocol/server-filesystem .`).
- **Working Directory (optional)**: Directory where the process will execute (e.g. `~/projects/my-app`).
- **Environment Variables (KEY=VALUE per line)**: API keys or configuration settings needed by the process (e.g. `API_KEY=xyz` or `DEBUG=true`).

### SSE Mode Fields
- **Server Name \***: Name identifying the remote tool server.
- **SSE Endpoint URL \***: Full HTTP/HTTPS address (e.g. `https://stitch.googleapis.com/mcp` or `http://127.0.0.1:8000/sse`).
- **HTTP Headers (Header: Value per line)**: Authentication and authorization headers (e.g. `Authorization: Bearer token` or `X-Goog-Api-Key: AQ...`).

*(Note: MCP in AntiGem connects via local process Stdio or remote SSE HTTP streams; raw WebSockets are not used by the standard dialog).*

4. Tap **Save**.

---

## 📋 5. Server List, Tool Inspection & Deletion

The **Configured Servers** section lists all active connections:
- **Header**: Shows total active servers and cumulative tool count (e.g. *"Configured Servers (4)"* and *"28 tools available"*).
- **Server Card**: Each item displays the server name, connection status badge, transport type, and a collapsible list of all exposed tools with their input schema descriptions.
- **Removing a Server**: Tap the trash / delete icon next to any configured server to display the confirmation dialog:
  - Title: **Remove MCP Server?**
  - Message: *"Are you sure you want to remove \"[name]\" from global mcp_config.json? The server and its tools will be disconnected."*
  - Tap **Remove** to delete the server, or **Cancel** to abort.

---

## 🛠️ Troubleshooting MCP Issues

### ❌ Symptom: The server shows an error or fails to start in Stdio mode.
- **What to check**: Check if the command binary exists in Termux (e.g. `node`, `python`, or `uvx`).
- **How to fix**:
  1. Open the **Terminal** screen.
  2. Run `which <command>` (for example, `which npx` or `which python3`).
  3. If missing, install it via Termux (e.g. `pkg install nodejs` or `pkg install python`).
  4. Ensure arguments do not contain syntax errors or invalid paths.

### ❌ Symptom: Remote SSE server displays connection timeout or HTTP error.
- **What to check**: Verify the server URL and HTTP headers.
- **How to fix**:
  1. Open **Settings → MCP Servers** and tap the server to edit.
  2. Verify that the **SSE Endpoint URL** is reachable from your phone network.
  3. In **HTTP Headers**, make sure each header is formatted as `HeaderName: HeaderValue` on its own line and check that your API key or token has not expired.
  4. Tap **Save** to reconnect.

### ❌ Symptom: A tool call from the agent is rejected or blocked.
- **What to check**: Check your agent command approval policies in **Settings → Commands & Permissions**.
- **How to fix**:
  1. If approval mode is set to require confirmation for all commands, watch for approval bottom sheets or toast alerts when the AI attempts a tool call.
  2. Tap **Approve** on the prompt to allow the action to proceed.
