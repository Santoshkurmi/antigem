# MCP protocol and extension management

## Model configuration

App MCP settings → **Custom AntiGem Plugin MCP** → choose one enabled extension. This preset is first, followed by Custom AntiGem Plugin Developer, Browser Automation, Terminal Automation and Stitch. The [developer MCP](developer-mcp.md) provides SDK docs and the complete create/edit/install/test loop without exposing a combined normal extension server. There is no server that combines all extension tools. Custom manual server configuration is still possible.

```json
{
  "mcpServers": {
    "my_extension": {
      "serverUrl": "http://127.0.0.1:8865/mcp?extension=hello-mcp",
      "headers": {"X-Device-Id": "YOUR_APP_DEVICE_ID"}
    }
  }
}
```

The chooser fills the stable ID and header. Folder names are display/installation choices and need not match IDs. Google Flow keeps its `google_flow_mcp` server name and `google-flow` ID even after a folder rename. Each extension can reuse tool names from other extensions safely: listing and dispatch are scoped to the selected ID.

The runtime must be started by the app and Node must be available in its terminal environment. Port8865 is loopback on the phone; an external development machine needs ADB forwarding. Port8765 is the separate native browser/terminal bridge. Forwarding does not remove the device-header requirement.

## Transport

Send JSON-RPC2 POST requests with Content-Type application/json and X-Device-Id. Supported methods:

| Method | Parameters → result |
| --- | --- |
| `initialize` | `{protocolVersion?,clientInfo?,capabilities?}` → protocolVersion, `capabilities:{tools:{}}`, serverInfo `{name:extensionID,version:manifestVersion}`. |
| `ping` | → `{}`. |
| `tools/list` | → `{tools:[{name,description,inputSchema}]}` for selected enabled extension only. No pagination. |
| `tools/call` | `{name,arguments?:object}` → MCP content result. |
| Notifications (no id) | Accepted with HTTP202 and no response body. No cancellation subscription is implemented; long workflows expose their own cancel tool. |

This is stateless request/response HTTP. GET returns405; there is no SSE stream, session ID requirement, subscriptions, automatic tools/list_changed notification, resources or prompts API. Refresh tools after an extension update. Settings-triggered management changes request a refresh of the app's connected model MCP servers.

Unauthorized calls return401. Missing/empty extension query returns400; disabled/unloaded/unknown IDs return404. Calls for another extension's tool return a scoped tool error. JSON-RPC unknown methods return-32601. Worker exceptions become `{isError:true,content:[{type:"text",text:...}]}`. A worker may return this same content structure itself; other JSON values are wrapped as text content. The host does not perform JSON-schema validation on tool arguments; validate in the worker.

The worker receives `{type:"mcp_tool_call",tool:"name",args:{...}}` through runtime.onMessage. Sender is the same-extension MCP context. Reply via sendResponse, or a returned Promise. A delayed callback must returntrue. Deadline180 seconds per message; return a job ID and poll for operations that may exceed it.

Example:

```sh
curl -H 'Content-Type: application/json' -H 'X-Device-Id: YOUR_APP_DEVICE_ID' \
  'http://127.0.0.1:8865/mcp?extension=hello-mcp' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hello","arguments":{"name":"Ada"}}}'
```

The legacy native `/google_flow` MCP alias is Flow-only. New clients should use the selected extension endpoint.

## Development/admin HTTP endpoints

These are native app management APIs, not Chrome extension APIs. They require X-Device-Id and are intended for local tools/tests. Paths below are on port8765:

| Path | Method/body → result |
| --- | --- |
| `/api/browser/extensions` | GET → `{success:true,extensions:[{id,name,version,description,toolCount,folderName,permissions,isEnabled,dirPath}]}` from disk. |
| `/api/browser/extensions/refresh` | POST `{}` → reconcile installed folders and live workers. |
| `/api/browser/extensions/toggle` | POST `{id,enabled}` → `{success:true}` after load/unload completes. |
| `/api/browser/extensions/reload` | POST `{id}` → `{success:true}` after reload; disabled workers stay disabled. |
| `/api/browser/extensions/rename` | POST `{id,folder_name}` → updated extension metadata. |
| `/api/browser/extensions/install` | POST `{zip_path,folder_name,update_id?}` → installed extension metadata. ZIP must be in terminal home or Android shared storage. update_id selects an existing extension and keeps its ID and current folder. |

The equivalent app UI uses the Android document picker and supports ZIPs from document providers without requiring a filesystem path. Invalid packages/actions return a native bridge error. Do not treat a worker's in-memory job list as durable across update/reload.

Host diagnostics on8865 (all require device header): POST `/health` → `{status,ready,pid,tools}`, POST `/extensions` → loaded workers `[{id,name,enabled,dir,tools}]`; `/extension/load` `{dir}`, `/extension/unload` `{id}`, `/extension/reload` `{id}` are low-level, nonpersistent operations. Use the app manager for persistent enable/disable/install. `/tab/prepare` `{nativeId}` prepares content scripts. Private context `/rpc`, `/events`, `/context/close` use per-context credentials managed by the runtime and are not extension-author APIs.

Resources returned by runtime.getURL use an unguessable per-load token. JS/CSS/assets can be fetched; HTML/HTM/XHTML return404. Never hardcode those URLs; they change on reload.

## Validation

Run repository tests from the project root:

```sh
node --experimental-vm-modules --test app/src/test/browserextensions/*.test.mjs
./gradlew :app:testTermuxDebugUnitTest --tests com.example.gemini.ui.browser.WebViewExtensionArchiveTest --offline
```

A minimal package load and MCP call test does not prove page automation. Test against the device's actual WebView, verify returned network/media IDs, and use guarded dry runs when the extension implements them.
