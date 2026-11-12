# MCP tools: AntiGem.mcp

AntiGem has built-in MCP tool servers (notes, themes, the browser, the terminal, Android automation, …) that its AI
agents use. A mini app can call the same tools with `AntiGem.mcp`, for example to read and create notes in AntiGem's
Notes, or to switch the theme. Each server is its own permission, declared in the manifest and granted by the user.
Calls run inside AntiGem (no network, no local port).

This page is about calling tools from a mini app. To let an agent build and test mini apps, see
[developer MCP](developer-mcp.md).

## Declaring servers

```json
"permissions": { "mcp": ["notes_mcp", "theme_mcp"] }
```

Each entry becomes the permission `mcp:<server>` (here `mcp:notes_mcp` and `mcp:theme_mcp`), granted or denied
separately. `"mcp": "notes_mcp"` (one string) and keys like `"mcp:notes_mcp": true` work too. `"mcp": true`
names no server: it declares nothing and the console shows a manifest warning. `"mcp:notes_mcp": false` declares
nothing either. In `required_permissions`, `"mcp"` means every declared server.

Servers a mini app can name:

| Server | Tools for |
|---|---|
| `notes_mcp` | AntiGem's Notes and to-do lists |
| `theme_mcp` | AntiGem's color themes |
| `browser_mcp` | Controlling Chrome through the AntiGem browser extension |
| `terminal_mcp` | The terminal |
| `android_automation_mcp` | Controlling the phone's screen and apps |
| `google_flow_mcp` | Google Flow |
| `custom_plugin_developer_mcp` | Building AntiGem plugins |
| `extension_<id>` | The MCP tools of the extension with that id installed in AntiGem's browser |

The permission prompt marks servers whose name contains `terminal`, `android` or `browser` as dangerous ("Can control
parts of your phone"). Ask only for what the app needs.

## API

| Method | Returns | Meaning |
|---|---|---|
| `AntiGem.mcp.list(server)` | `Promise<tool[]>` | The server's tools: `[{name, description, inputSchema, …}]` as in MCP `tools/list` |
| `AntiGem.mcp.call(server, tool, args?)` | `Promise<result>` | Calls one tool with `args` (an object, default `{}`). Resolves with the MCP `tools/call` result, usually `{content: [{type: 'text', text}], isError?}` |

```js
const tools = await AntiGem.mcp.list('notes_mcp');
console.log(tools.map((t) => t.name));   // ['notes_list', 'notes_get', 'notes_create', …]

const r = await AntiGem.mcp.call('notes_mcp', 'notes_create', { title: 'Groceries', items: ['Milk', 'Eggs'] });
const text = (r.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');
if (r.isError) throw new Error(text);
```

- Read a tool's `inputSchema` (from `list`) for its arguments.
- Results follow MCP: the useful output is usually text in `content`, sometimes JSON inside that text. A tool that
  fails normally resolves with `isError: true` rather than rejecting.
- `extension_<id>` servers answer like the others (`tools/list` tools, `tools/call` result). A JSON-RPC error from
  the extension rejects with a generic `Error`.
- Works in pages, the background page and workers.

## Limits and errors

| Error | When |
|---|---|
| `NotAllowedError` | `mcp:<server>` is not declared or not granted |
| `NotFoundError` | No built-in server with that name |
| `OperationError` | A built-in server answered with a JSON-RPC error; the message is the server's |

Request the permission first, and handle a denial:

```js
if (await AntiGem.permissions.request('mcp:notes_mcp') !== 'granted') {
  AntiGem.ui.toast('Allow "AntiGem tools: notes_mcp" to sync with Notes');
}
```
