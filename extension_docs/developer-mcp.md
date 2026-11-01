# Create extensions through the developer MCP

Add **Custom AntiGem Plugin Developer** from the app's MCP presets once. The model can then retrieve this SDK, create/edit draft files, package, install/update, enable/disable, reload, test and inspect logs through MCP. It does not need the app source tree or a separate Terminal MCP for a basic extension.

The developer server is separate from each extension's normal MCP server. Its endpoint is `http://127.0.0.1:8765/extension_developer` in the Termux app, with the app's `X-Device-Id` header. The preset supplies these values; other app variants use their own local bridge port. It exposes only the developer tools listed below, not all installed extension tools.

## Tell the model

> Use Custom AntiGem Plugin Developer MCP to create an extension for: [describe the task and website]. Start by calling webview_extension_docs and reading developer-mcp.md, lifecycle.md and the relevant API reference. Create a draft, implement and validate its tool arguments, write skills/*.md guides for how to use those tools (read skills.md), install it, inspect the registered tools, and test the requested behavior. Use the error logs to fix and update it until it works. Tell me the final extension ID, what it does, and the test results.

The initial MCP setup is the only manual app configuration needed for this development loop. A model must actually have the developer MCP attached; mentioning its name alone does not connect a server. An installed extension's own MCP can be added later through **Custom AntiGem Plugin MCP → choose extension** for normal use.

## Complete development loop

1. Call `webview_extension_docs({})` for the SDK README and available paths. Read `developer-mcp.md`, `lifecycle.md`, `skills.md`, `chrome-api.md`, `antigem-api.md` and `mcp.md` as needed. Read example files through the same tool. Large documents return `next_offset`; continue from that character offset until null.
2. Call `webview_extension_create({"project_id":"my-helper","name":"My Helper"})`. This creates a working hello tool in `~/extension-projects/my-helper/src`. Drafts are separate from installed extensions and are not executed automatically.
3. Inspect with `webview_extension_read_file({"project_id":"my-helper"})` to list files; supply `path` to read one. Replace/add complete UTF-8 files with `webview_extension_write_files({"project_id":"my-helper","files":[{"path":"worker.js","content":"..."},{"path":"manifest.json","content":"..."}]})`. Include every imported JS module, CSS file, locale or other resource in the draft. Use narrow website permissions and only implemented APIs. There are no popup, toolbar or side-panel interfaces. Add skills in the same draft as `skills/<name>.md` files; see [Add skills](#add-skills) below. Give the plugin an icon (`"icon": "icon.png"` in manifest.json plus the image file; write_files takes UTF-8 text, so use an SVG icon written as text, or ask the user to add a PNG).
4. Optionally call `webview_extension_package({"project_id":"my-helper"})` for a ZIP to export. Call `webview_extension_install({"project_id":"my-helper"})` to package and install in one step. Installation checks worker startup; a failed enabled update restores the previous installed package/worker. A disabled package is not executed until enabled.
5. Call `webview_extension_test({"extension_id":"my-helper"})`. It returns discovered tools without invoking them; with skills present, the list includes `get_skills`, `get_skill` and `get_skill_file`. Call `get_skills` to check each skill's name and description came out right. Call again with `tool_name` and `arguments` to actually exercise one tool through the same scoped dispatcher used by the extension's normal MCP.
6. On failure, call `webview_extension_logs({"extension_id":"my-helper"})`, read/edit the draft, raise `version` in manifest.json (e.g. `1.0.0` → `1.0.1`; optional but shown to users so they know which package they have), then `webview_extension_install({"project_id":"my-helper","extension_id":"my-helper"})`. The second ID selects the existing installation to update and preserves its stable ID, folder, stored data and enabled state. No app restart or manual ZIP picker is required.
7. Repeat tests. Call `webview_extension_set_enabled` to enable/disable. Use `webview_extension_reload` after changing files directly in an installed directory; **reload does not copy draft edits into the installation**. Draft edits require install/update first.
8. Report the final stable ID, registered tools, skills, test evidence and any remaining limitations. To publish it for others, put the source folder in a GitHub repository as described in [plugin-repository.md](plugin-repository.md). Add the finished extension's own MCP through the chooser when it should be available to the model independently of developer tools.

## Add skills

Tools say *what* a plugin can do; skills tell the model *how* to use them for a task: which tool to call first, the order of steps, required inputs, website quirks and how to check the result. Put them in the same extension; users install one ZIP and get both, with nothing to install separately.

```
src/
  manifest.json
  worker.js
  skills/
    youtube_upload.md        ← skill "youtube_upload"
    youtube_upload/          ← optional detail files for that skill
      thumbnails.md
```

- Each `.md` file directly in `skills/` is one skill, named after the file. Subfolders are not skills.
- Start each skill with frontmatter so the model can choose the right one from the list:

  ```markdown
  ---
  name: Upload a video
  description: Upload a video to YouTube Studio with title, description and thumbnail.
  ---
  ```
  Without it, the first `# heading` and first paragraph are used.
- Keep the main file short and task-focused. Move long reference material into `skills/<name>/` and point to it by relative path, e.g. "For thumbnail rules read `thumbnails.md`". The model opens detail files only when needed.
- Refer to tools by their exact `mcp_tools` names, and say which steps need them.
- Write skills with `webview_extension_write_files` like any other draft file, then install/update. Skill files are read fresh on each call, so later edits to an installed copy need no reload.
- A plugin can also be skills only: omit `mcp_tools` and the background worker. Use this for guides that work with tools the model already has (terminal, browser automation, files).

The app serves skills to the model in two ways: through the `get_skills` / `get_skill` / `get_skill_file` tools on the plugin's MCP server, and through `/plu:<plugin>:<skill>` in chat, which gives the model the skill file's path. Full details: [skills.md](skills.md).

A tool test is a real call and can perform the operation that tool implements. `dry_run` works only if the extension itself implements it; the developer MCP does not synthesize a universal dry-run mode. Discovery, successful startup and structural ZIP validation do not prove website behavior.

## Tool reference

[developer-tools.json](developer-tools.json) contains the exact complete MCP schemas. Tools return text content containing JSON, except a test call with `tool_name`, which forwards the tested extension's MCP content/error result directly.

| Tool | Inputs and result |
| --- | --- |
| `webview_extension_docs` | `path?` defaults README.md; `list_only?`, `offset?`, `limit?` (1–40000, default16000). Returns available `files`, selected `path`, `content`, `total_characters`, `next_offset`. Only bundled SDK files are readable. No Node host is required to read docs. |
| `webview_extension_list` | `refresh?` defaults false. Returns installed `extensions` with enabled/loaded state and MCP URL, draft `projects`, and `host_error` if the host cannot be reached. refresh=true reconciles disk and live workers. |
| `webview_extension_create` | Required `project_id`,`name`; optional `description`,`tool_name` (default hello). Creates a complete template and returns `source_dir`, `suggested_zip_path`, next steps. Existing source folders are never overwritten by create. |
| `webview_extension_read_file` | Required `project_id`; optional relative `path`,`offset`,`limit`. Omit path to list all source files with byte sizes. Reads UTF-8; offsets count string characters as in docs pagination. |
| `webview_extension_write_files` | Required `project_id`, `files:[{path,content}]`. Writes 1–100 files per call under this draft's src only; creates parent directories. Per-file writes are atomic, but the entire multi-file call is not a transaction. It never edits an installed worker directly. |
| `webview_extension_package` | Required `project_id`. Validates package structure and writes `dist/<project_id>.zip`; returns path, size and manifest. It includes all files in src and does not run JS. |
| `webview_extension_install` | Exactly one of `project_id` or `zip_path`. Optional `extension_id` selects an existing installation to update. `folder_name` applies to new installations; default is the draft ID or ZIP basename. zip_path accepts `~/...` or a local path inside terminal home/shared storage. Returns installed metadata, scoped `mcp_url`, `updated` and next steps. |
| `webview_extension_set_enabled` | Required `extension_id`,`enabled:boolean`. Returns updated installed metadata. Applies live; disabling releases this worker's automation ownership. |
| `webview_extension_reload` | Required `extension_id`. Restarts an enabled worker; disabled packages remain disabled. Persistent local/sync storage survives, memory/session/jobs do not. |
| `webview_extension_rename` | Required `extension_id`,`folder_name`. Renames the installed folder and preserves ID, storage and MCP connection. It does not rename the draft project. |
| `webview_extension_test` | Required `extension_id`; optional `tool_name`,`arguments`. Omit tool_name for discovery only. With a name, forwards the real tool result, including isError/image content. Disabled/unloaded extensions cannot be called. |
| `webview_extension_logs` | Optional `extension_id`, `limit` (1–200, default50). Returns matching console/error entries with extension_id, epoch-ms time, level and message, plus retained count/capacity. Host retains the last500 entries globally in memory, each message truncated to8000 characters. Failed worker-start errors are included; logs reset on host restart. |

Project IDs use 1–64 letters, numbers, underscores or hyphens. Installed folder names must start with a letter or number; pass folder_name when a project ID does not satisfy that requirement. Draft file paths must be relative, with no empty/. /.. components, drive prefixes or backslashes. Symlink paths are rejected. Package limits: 5000 files, 32 MiB per file, 128 MiB total. Write calls enforce the same byte limits; packaging enforces the whole source tree's limits. Outputs live outside src, so an earlier ZIP is not accidentally included in its replacement.

## Updating and debugging

Worker console.log/info/warn/error/debug output is available through logs, including errors during module initialization. A syntax/import/startup failure during update leaves the previous enabled worker active. A later runtime exception in an already-loaded worker is not an automatic rollback; inspect logs, fix the draft, install/update and retest.

Calls to reload, update or disable interrupt in-memory work. Generations/network actions already accepted by a website cannot be undone by stopping local collection. Keep explicit job/result state in storage.local if the extension requires recovery across worker reloads.

## PC development

Without the app source, download the SDK on the phone (**Custom AntiGem Plugins → Repositories → Plugin SDK for PC**) and follow [pc-development.md](pc-development.md). When working in a cloned repository, read `extension_docs/README.md` and follow its links. The normal app runtime sources remain in their existing repository location; no copies are needed in this SDK or in an extension ZIP. The SDK's example files and JSON schemas are already inside this folder.

To test PC-authored code on the phone, use an existing ADB/device connection, transfer the ZIP into the phone's terminal home/shared storage, then call install with zip_path. Alternatively use create/write_files through this MCP, which writes directly on the phone. MCP file paths always refer to the phone, not the PC filesystem. The app must be running; an external client needs access to the forwarded bridge port and the correct device header.
