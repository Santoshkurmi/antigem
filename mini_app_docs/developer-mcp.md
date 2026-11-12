# Mini App Developer MCP

The Mini App Developer MCP lets an AI agent build, run, debug, sign and install mini apps on the phone, the way the Plugin Developer MCP does for AntiGem plugins. AntiGem serves it itself, so the agent can be AntiGem's Antigravity, Claude Code, Codex or OpenCode on the phone, or an agent on a PC that reaches the phone through Remote / Server mode. The guide you are reading is bundled with AntiGem and served by the MCP's `miniapp_guide` tool, so it always matches the AntiGem version on the phone.

## Adding it

Open the agent's MCP settings and tap the **Mini App Developer** tile under Quick Presets:

| Agent | Where |
|---|---|
| Antigravity | Settings → Antigravity → MCP Servers |
| Claude Code | Settings → Claude Code → MCP |
| Codex | Codex settings → MCP |
| OpenCode | OpenCode settings → MCP |

The preset adds an HTTP server named `mini_app_developer_mcp` at `http://127.0.0.1:<port>/mini_app_developer` with the phone's `X-Device-Id` header (the same port and header as the other built-in servers). Then start a new chat, or reload the agent's tools.

The built-in slash command **`/mini_app <what it should do>`** tells the agent the whole procedure: check for the tools, read the guide, create, test, and sign.

## What the agent can and can't do

- **Development apps only.** File, console, eval, screenshot, permission and data tools only work on development apps (folders in `~/mini-apps`). An installed (signed) app is never changed, scripted or inspected: that would defeat its signature.
- **Installing needs the user.** `miniapp_install` opens AntiGem's install screen; the user taps Install / Update. This is true for every package, including ones the agent just signed.
- **Permissions are granted by the user.** The agent can list, reset or deny a development app's permissions, never grant them. When an app asks for a permission, its prompt is on the phone's screen and the user answers it.
- **The key password stays on the phone.** The agent can create the developer key and open the backup screen, but the password is typed on the phone and the agent never sees the key or the password. Never ask the user for it.
- **Developer mode.** Development apps only run while developer mode is on (Mini Apps → the `</>` button). `miniapp_list` reports it.
- Mini apps can't use this server through `AntiGem.mcp`: an app never gets the tools to script other apps.

## The development loop

```
miniapp_guide (README, getting-started, manifest, the API pages you need)
miniapp_create            → ~/mini-apps/<folder> from a working template
miniapp_write_files       → your HTML / CSS / JS / icon.png
miniapp_validate          → fix every error
miniapp_open              → on screen
miniapp_screenshot / miniapp_console / miniapp_eval   → check, then fix and repeat
```

Open pages reload by themselves when files change (a change to CSS only swaps the stylesheets). `miniapp_reload` forces a reload and re-reads `manifest.json`.

To ship:

```
miniapp_key_status        → is there a key, is it backed up?
miniapp_key_create        → once per phone (the phone then asks the user to back it up)
miniapp_key_backup        → if not backed up: the user saves it on the phone
miniapp_sign              → ~/mini-apps-dist/<id>-<version>.agapp
miniapp_install           → the install screen on the phone (the user confirms)
```

A package can't take the id of a development app that is still in `~/mini-apps` (the installed app would get the development app's data). To test the installed version on the same phone, sign a copy with another id, or delete the development folder first. Bump `version` (or `version_code`) for each release: an update must be a higher version, and must be signed with the same key.

## Tools

### miniapp_guide

Reads this guide. Start here.

| Argument | Type | Meaning |
|---|---|---|
| `topic` | string | Page name without `.md`, e.g. `README`, `getting-started`, `manifest`, `api-reference`, `files`. Default `README`, which also lists all topics. |
| `query` | string | Search every page (case-insensitive). Returns `topic:line: text` lines. |
| `offset`, `limit` | integer | Paging for long pages (default 30000 characters). A cut page ends with the `offset` to continue from. |

### miniapp_list

Every app, development and installed: id, name, version, `kind`, location, whether it `runs`, its permissions and their state (`granted`, `denied`, `prompt`), `required_permissions`, `shared_id` and which apps it shares data with, its background script, and its open pages (`path`, `on_screen`). Also `developer_mode`, folders whose manifest is broken (`broken`, with the error), id conflicts with installed apps, and the developer key status.

### miniapp_create

Creates a development app from a small working template: `manifest.json`, `index.html` (UI kit), `app.js` (a counter kept in `AntiGem.storage`, a native confirm dialog and a toast) and `style.css`.

| Argument | Type | Meaning |
|---|---|---|
| `id` | string, required | Reverse-domain id, e.g. `com.alex.budget`. Data is stored under it: don't change it later. |
| `name` | string, required | Display name. |
| `folder` | string | Folder in `~/mini-apps` (default: from the name). |
| `description` | string | One line shown in Mini Apps. |
| `theme_color` | string | Splash and status bar color, e.g. `#2E7D32`. |

Fails if the id is already used or the folder exists. Add a square `icon.png` (192 px or more) yourself; without one a letter icon is shown.

### miniapp_read_file

Without `path`: the app's files with sizes (hidden folders skipped). With `path`: one text file, paged with `offset` / `limit` (default 60000 characters). Binary files report their size only.

### miniapp_write_files

Writes 1–100 files in one call: `files: [{path, content, encoding}]`, where `encoding` is `utf8` (default) or `base64` (for images). Paths are relative to the app folder, without `..`. Every path is checked before anything is written; each file is written atomically. Max 10 MB per file.

### miniapp_delete_file

Deletes a file, or a folder with `recursive: true`.

### miniapp_validate

Checks the app and returns `ok`, `errors` (must fix) and `warnings`:

- `manifest.json` is valid JSON and parses (id, `shared_id`, permissions…), `manifest_version`, `name`, `version`;
- manifest entries that were ignored or look like a mistake, as warnings: `"storage"` in the list form, unknown permission names, `"mcp": true`, an empty `network.hosts` or `localhost.ports` list (the same warnings the app's console shows, see [manifest.md](manifest.md#manifest-warnings));
- the `entry` file, the `icon` (exists, readable, square, at least 192 px) and the `background` script exist;
- the id isn't owned by an installed app or used by another folder;
- pages that load scripts or styles from the internet (they need the network permission and break offline: bundle them);
- the app's size, and its shared data group.

`miniapp_sign` runs the same checks and refuses to sign while there are errors.

### miniapp_open

Opens the app on screen (or brings it forward as it was), waits until its page is ready, and returns the page. `path` shows another page, e.g. `settings.html` or `index.html#/stats`. If the app doesn't appear within 12 seconds it is usually waiting on a permission prompt or blocked by a required permission: take a screenshot or ask the user.

### miniapp_reload

Reloads the open pages and re-reads `manifest.json`; opens the app if it isn't running.

### miniapp_console

The app's console: `console.*` calls, uncaught errors and runtime messages (manifest warnings too, `manifest.json: …`), newest last, like `12:03:41.120 ERROR TypeError: … (app.js:12)`.

| Argument | Meaning |
|---|---|
| `limit` | Max entries (default 100). |
| `level` | Minimum level: `debug`, `log`, `info`, `warn`, `error`. |
| `since` | Only entries after this time in ms. Each result has `now`: pass it as `since` next time to get only new lines. |
| `clear` | Clear the buffer after reading. |

The buffer lives in memory; after AntiGem restarts, the last lines come from the app's `console.log` file (`from_log_file`).

### miniapp_eval

Runs JavaScript in the app's page (the one on screen, or the last opened) and returns the result. The code is the body of an async function: use `await` and `return`. A bare expression returns its value.

```js
return document.querySelectorAll('.item').length
```

```js
document.querySelector('#add').click();
await new Promise(r => setTimeout(r, 300));
return { count: document.querySelector('#count').textContent, stored: AntiGem.storage.get('count') };
```

The result is JSON when it can be (`result`), a DOM element comes back as its HTML, and anything else as text with its `type`. A thrown error or a syntax error comes back as `error` with its stack. Console lines printed meanwhile are returned in `console`. `timeout_ms` defaults to 10000 (max 60000). Native dialogs opened by the code (`AntiGem.ui.confirm`…) wait for the user: don't await them in eval.

### miniapp_screenshot

A JPEG of the app as it is on screen (opened or brought forward first). `scale` 0.2–1, default 0.5. Native dialogs and sheets drawn over the app are separate windows and aren't in the picture.

### miniapp_permissions

`action`: `list` (default) shows each declared permission with its state, a description, whether it's required, and the folders the user picked; `reset` forgets every choice (the app asks again, like a fresh install, and its pages reload); `deny` with `permission` (a name from the list, e.g. `network`, `storage:/sdcard/Download`) turns one off. There is no grant: the user grants permissions in the app's prompt or in App info.

### miniapp_clear_data

Deletes the app's storage, IndexedDB, `data/` files, cookies, reminders, background tasks, notifications and logs, then reloads its pages. Same as App info → Clear data.

### miniapp_key_status

`exists`, `developer`, `fingerprint`, `backed_up`, and where and when the last backup was made. When the key isn't backed up the result has a `warning`.

### miniapp_key_create

Creates the phone's developer key with a `developer` name (shown to people who install the apps). There is one key per phone; if one exists this fails. The phone then shows the backup screen.

### miniapp_key_backup

Opens the key backup on the phone. The user types a password twice; the key, encrypted with it, is saved to the phone's public **Download/AntiGem** folder (it stays even if AntiGem is uninstalled) and the share sheet opens so they can keep a second copy in Drive or email. Check the result with `miniapp_key_status`. See [signing and distribution](signing-and-distribution.md).

### miniapp_sign

Validates, then packages and signs a development app into `~/mini-apps-dist/<id>-<version>.agapp`, and checks the result like an installer would. Returns the path, id, version, `version_code`, developer and key fingerprint, plus a warning when the key isn't backed up.

### miniapp_install

| Argument | Meaning |
|---|---|
| `package_path` | The `.agapp`: `~/…`, an absolute path, or a file name in `~/mini-apps-dist`. |
| `wait_seconds` | Wait up to this long (max 180) for the user to install it, then report `installed`. Default 0. |

Verifies the package first and returns what installing would do: `action` `new`, `update`, `reinstall` or `downgrade` (with `installed_version`), or `refused` with the reason (another developer's key, a development app's id, a shared-data group signed by someone else…). If it isn't refused, the install screen opens on the phone and the user confirms.

## Troubleshooting

| Problem | Fix |
|---|---|
| No `miniapp_` tools | Add the Mini App Developer preset, then start a new chat or reload tools. In Remote mode the phone must be connected. |
| "Developer mode is off" | The user turns it on: Mini Apps → `</>`. |
| "… is an installed (signed) app" | Work on the development folder; sign and install a new version. |
| `miniapp_open` times out | A permission prompt or a required-permission screen is showing: `miniapp_screenshot`, then ask the user. |
| `miniapp_eval` returns no result | The page navigated or reloaded meanwhile, or the code waits on something (a dialog, a slow request): raise `timeout_ms` or split the code. |
| Install `refused` | Read the reason; see [signing and distribution](signing-and-distribution.md). |

See also: [getting started](getting-started.md), [manifest](manifest.md), [API reference](api-reference.md), [troubleshooting](troubleshooting.md).
