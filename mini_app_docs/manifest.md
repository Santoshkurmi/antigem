# manifest.json

Every mini app has a `manifest.json` at the root of its folder. It gives the app its id, name, version, icon and color, and declares what the app may use: permissions, a background script, the intents it accepts and the data group it shares with. This page lists every field exactly as AntiGem reads it: types, defaults, validation and what happens with wrong values. Unknown fields are ignored.

## A complete example

```json
{
  "manifest_version": 1,
  "id": "com.example.notes",
  "name": "Notes",
  "version": "1.2.0",
  "version_code": 1002000,
  "description": "Quick notes with reminders",
  "entry": "index.html",
  "icon": "icon.png",
  "theme_color": "#F9A825",
  "permissions": {
    "network": { "hosts": ["api.example.com", "*.example.com"] },
    "localhost": { "ports": [8080] },
    "storage": {
      "folders": [
        { "path": "/sdcard/Documents/Notes", "access": "readwrite" },
        { "path": "/sdcard/Download", "access": "read" }
      ],
      "pick": "read"
    },
    "notifications": true,
    "background": true,
    "clipboard": true,
    "mcp": ["notes_mcp"]
  },
  "required_permissions": ["notifications"],
  "background": "background.js",
  "intents": [{ "action": "share", "types": ["text/plain", "image/*"] }],
  "shared_id": "com.example.suite"
}
```

Most apps need far less. A typical offline app:

```json
{
  "manifest_version": 1,
  "id": "com.example.tally",
  "name": "Tally",
  "version": "1.0.0",
  "icon": "icon.png",
  "theme_color": "#2E7D32"
}
```

## All fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `manifest_version` | number | no | – | Not read by AntiGem today. Write `1`. |
| `id` | string | **yes** | – | 1–100 characters: letters, digits, `.`, `_`, `-`; the first one a letter or digit. See [id](#id). |
| `name` | string | no | the folder name | Shown in the Mini Apps grid, Recents, the splash screen and home-screen shortcuts. |
| `version` | string | no | `"0.0.0"` | Shown to users. Also gives `version_code` when that is missing. |
| `version_code` | integer (or a string of digits) | no | from `version` | Orders updates. See [version and version_code](#version-and-version_code). |
| `description` | string | no | `""` | One line, shown in App info. |
| `entry` | string | no | `"index.html"` | The first page, relative to the app folder. A leading `/` is removed. |
| `icon` | string | no | a letter tile | Path of an image in the app folder. See [icon](#icon). |
| `theme_color` | string | no | follow the AntiGem theme | Splash, Recents card, status bar, letter icon. See [theme_color](#theme_color). |
| `permissions` | object or array | no | none | See [permissions](#permissions) and [permissions.md](permissions.md). |
| `required_permissions` | array of strings | no | none | Permissions the app can't open without. See [required_permissions](#required_permissions). |
| `background` | string or object | no | none | The background-task script. See [background](#background). |
| `intents` | array | no | none | What other apps may send it. See [intents](#intents). |
| `shared_id` | string | no | none | Data group of one developer's apps. See [shared_id](#shared_id). |

A manifest that is not valid JSON, or whose `id` or `shared_id` breaks the rules, means the folder isn't an app: instead of an icon, Mini Apps lists the folder in red with the reason (`~/mini-apps/<folder> isn't an app: manifest.json: …`). See [troubleshooting.md](troubleshooting.md#the-app-doesnt-appear-in-mini-apps).

## id

```json
"id": "com.example.notes"
```

- Must match `^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$`. Leading and trailing spaces are removed first.
- Error when it doesn't: `"id" must be letters, digits, '.', '_' or '-' (got "…")`.
- Use reverse-domain style (`com.yourname.app`). The id is the app's identity: its data folder (`~/.antigem/mini-apps/<id>/`), its permissions, reminders, background tasks and its Recents card all hang on it. **Don't change it later**: a new id is a new app with empty data.
- Each app is served at its own origin, made from the id: lower-cased, every run of characters other than `a-z` and `0-9` turned into `-`, then `.antigem.invalid`. `com.example.notes` → `https://com-example-notes.antigem.invalid`. Ids longer than 50 characters after this are shortened to 40 characters plus a hash.
- Two development folders with the same id: the first folder in alphabetical order wins. The other doesn't run, and Mini Apps lists it in red: `~/mini-apps/<folder> isn't an app: its id <id> is already used by ~/mini-apps/<first folder>`.
- An installed (signed) app owns its id: a development folder with the same id doesn't run, and Mini Apps shows `~/mini-apps/<folder> doesn't run: its id <id> belongs to an installed (signed) app`. A package can't take the id of an existing development app either ([signing-and-distribution.md](signing-and-distribution.md#install-and-update-rules)).

## name

Any string. When missing or blank, the folder name is used (for installed apps that is the id). Keep it short: it is shown under a 58 dp icon and cut with "…".

## version and version_code

```json
"version": "1.2.0",
"version_code": 1002000
```

`version` is free text shown to users (App info, the install screen, the grid label `DEV · 1.2.0`). `version_code` is a whole number that decides whether a package is a new version, the same version or an older one when it is installed over an installed app.

`version_code` is a whole number (`1002000`) or a string of digits (`"1002000"`). Anything else (a decimal, a negative number, other text, `null`) is an error when the app is signed or installed: `"version_code" must be a whole number (got …)`.

When `version_code` is missing it is computed from `version`: the part before any `-` or `+`, split at `.`, as `a × 1000000 + b × 1000 + c`. A part that isn't a number counts as 0. The phone and the PC tool `agapp.mjs` use the same rules.

| `version` | computed `version_code` |
|---|---|
| `1.0.0` | 1000000 |
| `1.2.3` | 1002003 |
| `2.0` | 2000000 |
| `1.2.3-beta.1` | 1002003 (the suffix is ignored) |
| `v1.2` | 2000 (`v1` is not a number: avoid the `v`) |

- Bump `version` (or `version_code`) for every release. A package with the same code as the installed one is offered as **Reinstall**, a lower one as **Downgrade**.
- Pre-release suffixes don't change the code: `1.2.3-beta` and `1.2.3` are the same version for the installer. Set `version_code` yourself if you ship pre-releases.
- Keep each part below 1000, or codes overlap.

The version and code a package was signed with are part of its signature ([signing-and-distribution.md](signing-and-distribution.md#the-agapp-format)).

## description

Any string, default empty. Shown in App info under the name.

## entry

```json
"entry": "index.html"
```

The page that opens first, relative to the app folder (`"app/start.html"` works). Default `index.html`. It is also what the app serves for unknown paths without an extension, so single-page-app routing works (`/notes/42` → the entry page).

## icon

```json
"icon": "icon.png"
```

- A path inside the app folder; a leading `/` is removed.
- Use a square PNG, 192 × 192 or larger. It is scaled to a square, so a non-square image is stretched.
- Android must be able to decode it (PNG, JPEG, WebP). SVG doesn't work.
- When the field is missing, the file doesn't exist or can't be decoded, AntiGem draws a rounded tile in `theme_color` (or a default violet) with the first letter or digit of `name`.

## theme_color

```json
"theme_color": "#2E7D32"
```

Used for the splash screen, the Recents card, the default status-bar color and the letter icon.

| Value | Meaning |
|---|---|
| missing, `""` or `"auto"` | Follow the user's AntiGem theme (its background color) |
| `#rgb` | Short hex, e.g. `#2a7` |
| `#rrggbb` | Hex |
| `#rrggbbaa` | Hex with alpha, CSS order |
| a color name | Android's names: `red`, `blue`, `green`, `black`, `white`, `gray`, `teal`, `navy`, … |

An invalid value is ignored (as if missing). The page sees it as the CSS variable `--ag-app-color`.

## permissions

Two forms. A list of names:

```json
"permissions": ["notifications", "clipboard"]
```

or an object, which also takes options:

```json
"permissions": {
  "network": { "hosts": ["api.example.com"] },
  "notifications": true
}
```

| Name | Declaration | Becomes the permission |
|---|---|---|
| `network` | `true` (any host), `{ "hosts": [...] }` | `network` |
| `localhost` | `true` (any port), `{ "ports": [...] }` | `localhost` |
| `storage` | see below | `storage:<path>` per folder, `storage:all` |
| `notifications` | `true` | `notifications` |
| `background` | `true` | `background` |
| `clipboard` | `true` | `clipboard` |
| `camera` | `true` | `camera` |
| `microphone` | `true` | `microphone` |
| `mcp` | `["server", ...]` or `"server"` | `mcp:<server>` per server |
| `mcp:<server>` | any value except `false` / `null` | `mcp:<server>` |

Parsing rules:

- Unknown names are ignored, with a warning (`Unknown permission "notification" ignored …`). Check the spelling.
- `false` or `null` means not declared.
- For the simple names, an object is kept as the options; any other value (`true`, a string, a number) declares the permission without options.
- In the list form every entry is declared as if its value were `true`, except `"storage"`: **`"storage"` in the list form is ignored**, with a warning. Folders must be named: use the object form with `folders` (or `"storage": true` in the object form for all files).
- `mcp` must be a list or a string. `"mcp": true` (or `"mcp"` in the list form) declares nothing and gives a warning: name the servers. In the list form write `"mcp:notes_mcp"`.
- An `mcp:<server>` key is declared with any value except `false` or `null`.

### Manifest warnings

Entries that are ignored or look like a mistake don't stop the app: `"storage"` in the list form, unknown permission names, `"mcp": true`, an empty `network.hosts` or `localhost.ports` list. AntiGem prints each one in the app's console as a warning (`manifest.json: …`) when the app's first page loads and whenever the manifest changes. The Mini App Developer MCP's `miniapp_validate` returns them in its `warnings` ([developer-mcp.md](developer-mcp.md#miniapp_validate)).

### network options

```json
"network": { "hosts": ["api.example.com", "*.example.com"] }
```

- `hosts`: exact host names, or `*.domain` for every subdomain (not the bare domain: list `example.com` too if you need it). Matching ignores case. The port is not checked. Redirects must stay within the list too.
- `true`, `{}`, no `hosts` list, or `"*"` in it: any host. The permission prompt then shows a warning.
- An empty `hosts` list (`"hosts": []`) allows no host, and gives a [manifest warning](#manifest-warnings). Leave `hosts` out for any host.

### localhost options

```json
"localhost": { "ports": [8080, 3000] }
```

- `ports`: the ports allowed on servers of this phone (`127.0.0.1`, `localhost`, the phone's own addresses). Missing: any port.
- An empty `ports` list allows no port, and gives a [manifest warning](#manifest-warnings). Leave `ports` out for any port.
- AntiGem's own ports are refused whatever is declared ([permissions.md](permissions.md#localhost)).

### storage options

```json
"storage": {
  "folders": [
    { "path": "/sdcard/Download", "access": "read" },
    { "path": "/sdcard/Documents/MyApp", "access": "readwrite" },
    { "path": "~/exports", "access": "write" }
  ],
  "pick": "read"
}
```

| Form | Result |
|---|---|
| `true`, `"full"` or `"all"` | `storage:all`, access `readwrite`: all files |
| `{ "full": true }` | `storage:all`, access from `"access"` (default `readwrite`) |
| `{ "full": "read" }` | `storage:all` with that access |
| `{ "folders": [...] }` | one `storage:<path>` permission per folder |
| `{ "pick": ... }` | allows `AntiGem.fs.pickFolder()` |

Folder entries:

- An object `{ "path", "access" }`, or just a path string (read only).
- `path`: `~/...` (AntiGem's home folder), `/sdcard/...` or another absolute path. Spaces around it and a trailing `/` are removed. Empty paths are skipped.
- `access`: `read`, `write` or `readwrite`. Also accepted: `w` (write), `rw`, `both`, `read-write`, `read_write` (readwrite). Anything else, or nothing, is `read`.
- `write` means create, change and delete, but not read.

`pick`:

| Value | Meaning |
|---|---|
| missing, `false`, `null` | the app can't pick folders |
| `true` | picked folders get `read` |
| `"read"`, `"write"`, `"readwrite"` (and the aliases above) | picked folders get that access |

`pick` only works in the object form of `permissions` with `storage` as an object.

Folders that are (or contain) the home folder or the root of shared storage, and `storage:all`, need the user to type a confirmation phrase. Ask for the narrowest folder. Full rules: [permissions.md](permissions.md#storage).

### mcp options

```json
"mcp": ["notes_mcp", "theme_mcp"]
```

Each server name becomes its own permission `mcp:<server>`, granted or denied on its own. The names are the MCP server names AntiGem knows (for example `notes_mcp`, `theme_mcp`, `browser_mcp`). Details in [mcp.md](mcp.md) and [permissions.md](permissions.md#mcpserver).

## required_permissions

```json
"required_permissions": ["notifications", "storage"]
```

Permissions the app can't work without. If one isn't granted, the app doesn't load: a native screen lists what is missing, with **Review permissions** and **Close** ([permissions.md](permissions.md#required-permissions)).

| Entry | Means |
|---|---|
| a declared name, e.g. `"notifications"`, `"mcp:notes_mcp"`, `"storage:/sdcard/DCIM"`, `"storage:all"` | that permission |
| `"storage"` | every declared storage permission (all folders, and all files if declared) |
| `"mcp"` | every declared `mcp:<server>` |

Names that are not declared in `permissions` are ignored.

## background

```json
"background": "background.js"
```

or

```json
"background": { "script": "background.js", "type": "module" }
```

- The script that runs background tasks while the app is closed. It runs in an invisible page with the SDK ([background.md](background.md)).
- The path is relative to the app folder; a leading `/` is removed. A path with a `..` part or a part starting with `.` is refused (as if missing).
- `"type": "module"` loads it as an ES module.
- Background tasks also need the `background` permission. Without a script, `AntiGem.background.run()` fails with `InvalidStateError` and scheduled runs are skipped with "The manifest has no background script".

## intents

```json
"intents": [
  { "action": "share", "types": ["text/plain", "image/*"] }
]
```

What other mini apps (and Android's share sheet, as "Mini app") may send the app.

- `action`: default `"share"`.
- `types`: MIME patterns; `type/*`, `*/*` and `*` are wildcards. Default `["*/*"]`.
- Entries that are not objects are skipped.

How sending and receiving works: [intents.md](intents.md).

## shared_id

```json
"shared_id": "com.example.suite"
```

Like Android's `sharedUserId`: apps with the same `shared_id` read and write each other's `data/` folder. Same rules as `id` (`"shared_id" must be letters, digits, '.', '_' or '-' (got "…")` otherwise). Installed apps only share with installed apps signed with the same key; development apps only with development apps. Everything else is in [shared-data.md](shared-data.md).

## Editing the manifest

- **Development apps:** saving `manifest.json` reloads the open app and applies the new name, color and permissions at once. Its [warnings](#manifest-warnings) are printed in the console again. A changed `id` is not applied to an open app: close it and open the new one from Mini Apps.
- **Installed apps:** the manifest can only change through a signed update. Editing an installed app's files marks it as modified and it stops running ([signing-and-distribution.md](signing-and-distribution.md#integrity)).
- Widening a permission's declaration (more hosts or ports, more access, any host instead of a list) puts that permission back to "not decided": the user is asked again. Narrowing it or reordering it keeps the user's choice ([permissions.md](permissions.md#when-a-declaration-changes)).
