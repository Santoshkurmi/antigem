# Packages, identity and lifecycle

## Manifest contract

| Field | Contract |
| --- | --- |
| `manifest_version` | `3` preferred; `2` accepts background script arrays. |
| `id` | Stable `[A-Za-z0-9_-]+`. Explicit IDs are recommended. ZIP installation supplies one when absent; folder rename preserves it. |
| `name`, `version`, `description` | Display metadata. Install requires a nonempty name. `version` is optional but recommended: use dotted numbers such as `1.2.0` and raise it on every update. The app shows it on the plugin card and compares installed vs. ZIP version (newer, same, older) before updating; without it users see "no version" and cannot tell an old package from a new one. |
| `background.service_worker` | Relative worker filename. |
| `background.type` | `"module"` enables relative ES-module imports. Otherwise classic script execution. |
| `background.scripts` | MV2 classic script array, in execution order; use instead of service_worker. No HTML background pages. |
| `mcp_tools` | Nonempty array, required for ZIP install unless the package has [`skills/*.md`](skills.md); a skills-only package needs no background worker. Each entry has a unique `name`, `description`, `inputSchema` JSON schema. Names are unique within this extension; another extension may reuse them. The worker must validate its actual arguments. |
| `permissions` | Permission names: `storage`, `scripting`, `debugger`, `downloads`, `alarms`, `cookies`, `antigemFiles`; `activeTab` allows the active tab. `tabs` is accepted for compatibility; basic tab enumeration does not currently enforce it. MV2 URL patterns are accepted here. |
| `host_permissions` | URL match patterns for page scripting/debugging/cookies/automation. Prefer the narrow origins needed. |
| `content_scripts` | `matches`, `exclude_matches`, relative `js`/`css` arrays, `run_at`, `all_frames`, `world`. See limitations below. |
| `default_locale` | Loads `_locales/<locale>/messages.json`. Only that locale is selected; no locale fallback negotiation. |
| `icon`, `icons` | Optional plugin icon shown in the app's plugin list, MCP chooser and `/plu` picker. Either `"icon": "icon.png"` or Chrome-style `"icons": {"48": "icons/48.png", "128": "icons/128.png"}` (the largest is used). PNG, JPG, WebP or SVG inside the package; a square 128×128 px or larger PNG works best. |
| `action`, `browser_action`, `side_panel`, `options_page` | Do not create app UI. Nonvisual action metadata may be stored for compatibility. UI opening rejects. |

Do not depend on unlisted manifest keys. CRX signatures, extension-store updates, native messaging hosts, declarativeNetRequest rules and browser-specific background pages are not implemented.

## Storage locations and precedence

The app owns and supplies the extension runtime. Extension authors do not need to distribute, import or edit its implementation files.

The bundled Flow package comes from `extensions/google-flow/`. Installed ZIPs go to local terminal home `~/extensions/<folder>`. Directory discovery order is:

1. `~/extensions`
2. `<projects>/antiGem/extensions` when present
3. private `files/extensions`
4. bundled private `files/browser-extensions`
5. `/sdcard/antiGem/extensions` when present

The first manifest with a given ID wins. A home package can override a bundled package without modifying the APK. The per-root `extensions.json` file stores disabled IDs. The host's private `.antigem-state/<ID>.json` stores `local`, locally persisted `sync`, version and alarms. Folder renaming does not change the ID or state filename.

ZIP extraction rejects traversal, absolute/drive paths, canonical duplicates and ambiguous enclosing packages. Limits: 5,000 entries, 32 MiB per unpacked file, 128 MiB total. Installation validates the manifest and background files before replacing a package. An enabled replacement must load successfully; otherwise the previous files and worker are restored. Updating a disabled package validates its package but does not execute its worker until enabled.

**Rename folder** changes the actual managed directory, not the manifest's display name or stable ID. A bundled/read-only-root extension is copied into the managed home directory as an override. Existing MCP URLs remain valid. **Update ZIP** preserves the selected extension's ID even if the downloaded manifest uses another ID. It preserves local/sync storage and enabled state. **Refresh** discovers folders and reconciles enabled workers; use **Reload** after changing files in an existing folder.

## App and worker lifecycle

1. App initialization copies the bundled runtime and starts one Node host on loopback port 8865. A healthy existing host is reused. The host is tied to the parent app process.
2. Enabled workers load immediately, before an MCP call. Top-level code, timers and lifecycle listeners can run. `onInstalled` fires for a new ID or changed manifest version; `onStartup` fires on each load in this implementation.
3. The host polls native tabs about every 300 ms. Tab events can fire without MCP. Declarative/dynamic content scripts can run on matching pages without MCP. Plain workers with no such scripts do not automatically inject page code.
4. The bundled Flow worker has no declarative content scripts or startup generation. Opening the browser alone does not configure Flow or submit a prompt. Its helper is installed lazily by a Flow tool call.
5. A scoped MCP call is delivered to the selected background worker as `{type:"mcp_tool_call",tool,args}`. It may query tabs, inject code, use storage, make network requests and return results.
6. Disable/unload removes that worker's tool registry, timers, ports and content contexts, cancels future content registrations and releases automation ownership. It cannot undo arbitrary DOM changes, network requests or generation already accepted by a website.
7. Reload/update replaces the worker and its contexts. JavaScript variables and session storage reset; durable storage stays. Jobs held in worker memory must be polled to completion before reloading if their results are needed.

A worker remains alive while the host does; it does not use Chrome's idle service-worker suspension model. Android process/background restrictions still apply. Alarms do not wake a stopped app. Repeating alarms resume from persisted schedules when the worker loads.

## JavaScript environment

Workers have `chrome`, `browser` (same object), `self`, `globalThis`, `console`, timers, `fetch`, URL utilities, TextEncoder/Decoder, Blob, FormData, Request/Response/Headers, AbortController/Signal, Web Crypto, performance, structuredClone, atob/btoa, Event/EventTarget/MessageEvent, navigator and location. WebSocket is present only if the embedded Node version provides it. It is a WebSocket **client**, not a listening server.

No worker DOM, window, document, localStorage, IndexedDB, Node `require`, `process`, arbitrary built-in module imports or Node HTTP/TCP listen APIs are provided. Use `chrome.storage` instead of localStorage. Package dependencies as relative files. Static ES imports must be relative and stay in the package; dynamic relative imports and classic `importScripts(...relativeFiles)` use the same package boundary. Module initialization is limited to ten seconds of synchronous VM execution. Async page/API operations have their own deadlines.

This compatibility VM is for user-installed code and is not a hardened hostile-code sandbox. File privileges and website access should be declared deliberately. `fetch` is the Node host's network fetch and does not automatically inherit WebView cookies or enforce manifest host patterns; perform authenticated website fetches inside a permitted page when that is needed.

## Content scripts

`ISOLATED` is the default and creates an extension-specific world; `MAIN` shares the page's JavaScript global environment and has no injected Chrome facade. Isolated contexts have Chrome APIs and communicate over a CDP binding. The DOM is shared. A page navigation destroys content contexts and ports. Scripts register for future documents and are injected into the currently loaded top frame when enabled.

`document_start` runs when Chromium executes the registration; other `run_at` values currently run at DOMContentLoaded or immediately if already ready. `document_idle` is not Chrome's full idle scheduling. `all_frames` affects future documents; enabling on an existing document initially injects the top frame only. Dynamic registrations are memory-only; `persistAcrossSessions`, match_origin_as_fallback and match_about_blank are not implemented. Register them again on worker startup.

Disabling cannot reverse changes a content script has already made. Guard DOM listeners and avoid uncontrolled global mutation. MAIN-world page code can observe and modify your helpers.
