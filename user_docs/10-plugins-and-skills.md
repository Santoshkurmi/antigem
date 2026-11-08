# 10 — Skills, Plugins & Slash Commands

AntiGem provides an extensible customization system that lets you equip AI agents with specialized knowledge, install community and official Google plugins, build custom browser add-ons, and create productivity slash shortcuts.

---

## 🧠 1. Agent Skills & Official Google Plugins (`Settings → Skills & Plugins`)

Under **Settings → Skills & Plugins** (*"Agent capabilities, Google plugins, and extensions"*), the row badge displays `<N> skills · <M> plugins`. Tapping it opens the hub with three tabs:

### Tab 1: Skills (`Skills (N)`)
The **Skills** tab inspects all discovered agent instruction sets:
- **Scope Filter Chips**:
  - **Global (Default)**: Discovers global agent capabilities.
  - **Project / Workspace**: Filters to skills found within your active project workspace.
  - **All**: Displays all discovered skills across all scopes.
- **Refresh**: Tap the refresh icon to re-scan discovery paths.
- **Skill Cards**:
  - **Command**: Displays `/{name}` in bold monospace (e.g. `/generative_ui`, `/migrate-workflows`).
  - **Display Name**: Shows an optional `({displayName})` if defined in metadata.
  - **Plugin Origin**: Shows `from {pluginName}` when provided by an installed plugin bundle.
  - **Scope Badge**: Badges indicate where the skill originates: **Built-in**, **Global**, **Workspace**, or **Installed Plugin**.
  - **Description**: Summary of agent capability instructions and workflows.
  - **Path**: Displays the absolute or project-relative directory path.
  - **View SKILL.md**: Tap to open the markdown dialog to inspect the full instructions, tool definitions, and system prompts. If empty, it alerts *"No markdown content found in SKILL.md"*.
- **Empty States**:
  - In workspace view when no project skills exist: *"No workspace-scoped skills (.agents/skills/) found for active project."*
  - In global or all view: *"No skills discovered."*

### Tab 2: Installed Plugins (`Plugins (N)`)
The **Plugins** tab lists installed bundles under the title **Installed Bundles & Extensions**:
- Displays each installed bundle with its logo or extension icon, display name, identifier, and version (e.g. `v1.2.0`).
- **Global Badge**: Displays `Global` if the bundle is configured globally.
- **Bundled Skills ({N})**: Shows horizontal assist chips for each bundled skill (e.g. `/{skill_name}`). Tap any chip to inspect its `SKILL.md`.
- **Deleting a Plugin**:
  - Tap the trash icon to open the confirmation dialog:
    - **Title**: **Delete Plugin?**
    - **Message**: *"Are you sure you want to delete '{display}' ({name})? This will remove its bundled skills from ~/.gemini/config/plugins/."*
    - **Buttons**: Tap **Delete** (in red) to confirm, or **Cancel** to abort.
- **Empty State**:
  - If no bundles are installed: *"No plugin bundles installed in ~/.gemini/config/plugins/."*

### Tab 3: Google Marketplace (`Store`)
The **Store** tab displays **Official 'Build with Google' Plugins**:
- Browse the catalog of verified Google agent capabilities and extensions.
- Tap **Install** next to an entry to download and register the plugin. Once active, the button updates to **Installed**.
- **Empty State**: If the catalog is offline or empty: *"No official Google plugins catalog available."*

---

## 📁 2. Where Skills & Plugins Live on Disk

- **Project / Workspace Skills**: Stored inside `.agents/skills/` in your active project directory (Claude also reads `.claude/skills/`). Workspace skills only take effect when working inside that project.
- **Plugin Bundles**: Stored in `~/.gemini/config/plugins/`. When you delete a plugin from the **Plugins** tab, it removes its folder and bundled skills from this directory.
- **Built-in Skills**: Shipped internally with the app runtime and exposed across all sessions.

---

## 🔌 3. Custom AntiGem Plugins (`Settings → Custom AntiGem Plugins`)

Under **Settings → Custom AntiGem Plugins** (`WebViewExtensionsScreen`), you can install modular extensions that execute inside AntiGem's webview engine:

> *"Install custom AntiGem plugins from ZIP files or from a repository. A plugin can have MCP tools, skills (skills/*.md), or both. Changes apply to the running browser immediately."*

### A. Installed Tab
Lists all active webview extensions:
- **Header Actions**:
  - **Install ZIP**: Pick a `.zip` archive containing `manifest.json`.
  - **Refresh**: Re-indexes installed plugins.
- **Plugin Cards**:
  - Displays the folder name, title, version, and metrics: `<name> · v<version> · N MCP tools · N skills`.
  - Source tracking: `From <repo>@<ref>` (if installed from a repository).
  - Description and status toggle switch to enable or disable the plugin instantly.
- **Quick Card Actions**:
  - **Update available**: Displays `Update available: <old> → <new>` with an **Update** button.
  - **Update ZIP**: Replaces the plugin files from a newer ZIP archive while preserving its stored data and unique ID.
  - **Reload**: Restarts the plugin's background worker without restarting AntiGem.
  - **Download as ZIP**: Exports the plugin package to your phone's public `Downloads` directory.
  - **Remove**: Opens the confirmation dialog:
    - **Title**: **Remove <folder name>?**
    - **Message**: Displays storage path details and notes: *"If you added it in MCP settings, remove that server there too."*
    - **Checkbox**: Check *"Also delete its stored data (otherwise kept for a reinstall)"* to wipe state, or leave unchecked to preserve database records.
    - **Buttons**: Tap **Remove** or **Cancel**.
- **Empty State**: *"No plugins installed. Choose a ZIP containing manifest.json plus a background worker, a skills/ folder, or both."*

### B. Repositories Tab
Browse community and third-party plugin repositories to install pre-built add-ons with a single tap.

### C. Installing & Updating from ZIP:
1. Tap **Install ZIP** and pick an archive:
   - If new: Dialog is titled **Install plugin** (or **Install as new plugin** if installing a copy). Fill in **Folder name**, review **Version**, and tap **Install**.
   - If updating an existing version: Dialog is titled **Update plugin** (*"The plugin's ID and stored data are preserved. The previous package is restored if loading fails."*). Tap **Update**.
   - If matching an already-installed plugin: Opens **Plugin already installed** (*"<folder> (<name>) is already installed. Update it with this ZIP, or install the ZIP as a separate new plugin?"*) with options:
     - **Update**: Replaces files in-place.
     - **Install as new**: Generates a distinct folder name and unique ID.
     - **Cancel**: Aborts installation.

### D. Connecting Plugins to AI Agents via MCP:
Installing a plugin makes it available in the app, but agents need MCP access to invoke its tools:
1. Open **Settings → MCP Servers**.
2. Tap the preset tile titled **Custom Plugin MCP** (opens dialog titled **Custom AntiGem Plugin MCP**).
   - If no plugins are enabled: displays *"Enable or install a plugin in Settings → Custom AntiGem Plugins first."*
   - Select your enabled plugin to attach its tools to the agent. Its bundled skills are automatically exposed as `get_skills` and `get_skill` tools.
3. In chat, you can type `/plu:` to point the model directly at a plugin or one of its skills.

### E. Developing Your Own Plugins:
> *"To create a plugin with your model, add Custom AntiGem Plugin Developer in MCP settings. It supplies the SDK, draft editing, installation, updates, tests and worker logs."*

- In **Settings → MCP Servers**, select the preset tile **Plugin Developer**.
- This equips the agent with the developer SDK so you can design, test, update, and inspect background worker logs entirely through chat!

---

## ⚡ 4. Custom Slash Commands (`Settings → Slash Commands`)

Save time on frequent instructions by setting up custom trigger commands under **Settings → Slash Commands** (`CustomSlashCommandsScreen`).

### How to Create a Custom Slash Command:
1. Open **Settings → Slash Commands**.
2. Tap **Add command** (or tap **Restore defaults** to reset standard commands).
3. If no custom shortcuts exist yet, the screen displays *"No custom commands yet."*
4. In the **New slash command** dialog (or **Edit /name** when editing), fill in:
   - **Name**: The command keyword without the leading slash (e.g. `audit`, `review`).
   - **Description (shown in the / list)**: Explanation shown in the auto-complete menu.
   - **Expands to**: Full prompt template. Use `$ARGUMENTS` to insert any text you type after the command (e.g. *"Review $ARGUMENTS for syntax errors and explain fixes"*).
   - **Plugin**: Optionally link the command to an installed plugin (or `None`).
   - **Skill**: If linked to a plugin, pick a specific skill (or `None (whole plugin)`).
5. Tap **Save**.

### Deleting a Command:
- Tap **Delete** next to any custom command and confirm in the dialog: **Delete /name?**.

### Using Commands in Chat:
- In the chat box, type `/` followed by your keyword (e.g. `/audit main.py`) and tap Send. AntiGem automatically replaces the shortcut with your full expanded prompt template!

---

## 🛠️ Troubleshooting Skills & Plugins

### ❌ Symptom: The plugin shows an error or will not load after installing a ZIP.
- **What to check**: Check if the ZIP contains `manifest.json` at its root or inside a single top-level folder.
- **How to fix**:
  1. Open **Settings → Custom AntiGem Plugins**, find the plugin card, and tap **Reload**.
  2. If the problem persists, tap **Update ZIP** and select a corrected package.

### ❌ Symptom: How do I cleanly remove a plugin and all its cached data?
- **How to fix**:
  1. In **Settings → Custom AntiGem Plugins**, tap the trash (**Remove**) icon on the card.
  2. In the **Remove <folder name>?** dialog, check the box **"Also delete its stored data (otherwise kept for a reinstall)"**.
  3. Tap **Remove**.
  4. If you had added it in MCP settings, open **Settings → MCP Servers** and remove the corresponding server entry there too.

### ❌ Symptom: A workspace skill is not showing up in chat or the Skills tab.
- **What to check**: Check the folder location.
- **How to fix**: Ensure the skill folder contains a valid `SKILL.md` and is placed in `.agents/skills/<skill-name>/SKILL.md` in the root of your active project workspace (or `.claude/skills/` for Claude). Open **Settings → Skills & Plugins**, select the **Project / Workspace** scope chip, and tap **Refresh**.
