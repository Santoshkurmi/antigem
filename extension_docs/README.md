# AntiGem WebView MCP extension development

This SDK documents the APIs provided by AntiGem, not all Chrome APIs. In the app, the Custom AntiGem Plugin Developer MCP serves these documents and examples directly; the model does not need an app source checkout. Use these contracts when generating an extension. An absent API is unsupported; a declared compatibility stub is not a working desktop feature.

- [Create, install and test with the developer MCP](developer-mcp.md)
- [Developer MCP tool schemas](developer-tools.json)
- [Package, installation and lifecycle](lifecycle.md)
- [Skills: Markdown guides served as get_skills / get_skill tools](skills.md)
- [Host a plugin repository on GitHub (antigem-plugins.json)](plugin-repository.md)
- [Build plugins on a PC with this SDK](pc-development.md)
- [Every extension-facing Chrome API](chrome-api.md)
- [AntiGem native automation, file uploads and files](antigem-api.md)
- [MCP protocol and management endpoints](mcp.md)
- [Flow tools, compatibility and batch recipes](google-flow.md)
- [Complete Flow tool JSON schemas](flow-tools.json)
- [Working minimal extension](examples/hello-mcp/manifest.json), [worker](examples/hello-mcp/worker.js)

## Create with a model in the app

Add **Custom AntiGem Plugin Developer** from MCP presets and tell the model what extension you want. The model should start with `webview_extension_docs`, then follow [the development loop](developer-mcp.md) to create files, install/update, test and inspect errors. All example dependencies and tool schemas are served by that MCP. Ask it to include [skills](skills.md) too: Markdown guides in the same extension that teach the model how to use its tools. You can also work in a cloned repository on a PC.

## Create and install manually

An extension (shown in the app as a custom AntiGem plugin) is a ZIP containing `manifest.json` and a JavaScript background worker, a [`skills/`](skills.md) folder, or both. Include your own dependencies as relative JS files. Do not include AntiGem's runtime files. A ZIP may contain those files directly or one enclosing extension folder.

```json
{
  "manifest_version": 3,
  "id": "hello-mcp",
  "name": "Hello MCP",
  "version": "1.0.0",
  "permissions": ["storage"],
  "background": {"service_worker": "worker.js"},
  "mcp_tools": [{
    "name": "hello",
    "description": "Greet a name and remember how many greetings were made.",
    "inputSchema": {
      "type": "object",
      "properties": {"name": {"type": "string"}},
      "required": ["name"],
      "additionalProperties": false
    }
  }]
}
```

```js
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type !== 'mcp_tool_call') return;
  (async () => {
    if (message.tool !== 'hello') throw new Error('Unknown tool');
    if (typeof message.args?.name !== 'string') throw new Error('name is required');
    const {count = 0} = await chrome.storage.local.get('count');
    await chrome.storage.local.set({count: count + 1});
    return {greeting: `Hello ${message.args.name}`, count: count + 1};
  })().then(sendResponse, error => sendResponse({
    isError: true, content: [{type: 'text', text: error.message}]
  }));
  return true;
});
```

ZIP the **contents** of `extension_docs/examples/hello-mcp`, then open **Settings → Custom AntiGem Plugins → Install ZIP**. Choose a folder name. Add its MCP connection through **MCP settings → Custom AntiGem Plugin MCP → choose Hello MCP**. The app fills the URL, stable extension ID and device header.

Optionally add an `icon` (`"icon": "icon.png"` plus a square PNG in the package) so users can recognize the plugin in lists. Give the manifest a `version` (optional, recommended) and raise it with each release: the app shows it on the plugin card and shows installed vs. ZIP version before an update. Updates, reloads and enable/disable actions take effect on the current host. No app restart is required. Reload/update interrupts that extension's active jobs. Persistent `storage.local` survives; worker variables and `storage.session` do not. Avoid editing an extension while a paid generation is being submitted.

Use `chrome.scripting.executeScript` to interact with permitted web pages, `chrome.storage.local` for durable state, and `chrome.runtime` messaging for content contexts. Do not build popups, options pages, side panels or toolbar controls: this platform exposes extensions through MCP only.

To port a Chrome extension, audit its APIs against [the reference](chrome-api.md), package its worker dependencies, declare `mcp_tools`, and handle `mcp_tool_call`. An ordinary Chrome manifest alone does not turn its commands into MCP tools.
