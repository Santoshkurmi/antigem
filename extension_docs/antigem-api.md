# chrome.antigem — AntiGem native API

These are app additions, not Chrome APIs. Every call requires the manifest permission **`antigemFiles`**, including automation. Every method takes one object and returns a Promise; callback overloads are not supported. Errors reject. Page automation additionally checks the selected tab's host permission/activeTab access.

## automation({tabId,busy?,locked?,tap?})

Returns `{success:true}` after the native operation.

- `tabId`: numeric ID from chrome.tabs, required.
- `busy:true`: acquires or renews this extension's automation lease. The app shows text-only activity feedback, suppresses keyboard/automatic focus zoom and locks zoom while the lease exists. Keep renewing at intervals below60 seconds (Flow uses15 seconds).
- `busy:false`: releases this extension's lease. Original keyboard/zoom settings return when all owners finish.
- `locked:true|false`: controls the tab's separate user-interaction lock for this extension. Locks do not expire with the busy heartbeat; release explicitly.
- `tap:{fx,fy}`: trusted Android touch at fractions of the visible WebView viewport, each in `[0,1]`. The app converts to native pixels. It must have an attached, visible WebView. Do not pass page/document CSS coordinates directly.

A stable element rectangle can be converted using the page's current visual viewport:

```js
const [frame] = await chrome.scripting.executeScript({
  target: {tabId}, world: 'MAIN', func: () => {
    const button = document.querySelector('button[type=submit]');
    if (!button || button.disabled) throw new Error('Submit unavailable');
    const r = button.getBoundingClientRect(), v = visualViewport;
    return {fx:(r.left+r.width/2-(v?.offsetLeft||0))/(v?.width||innerWidth),
            fy:(r.top+r.height/2-(v?.offsetTop||0))/(v?.height||innerHeight)};
  }
});
await chrome.antigem.automation({tabId, tap:frame.result});
```

Use try/finally around leases. Disabling/reloading through the app manager releases ownership. A crashed worker's busy lease expires after60 seconds; explicit interaction locks need manager release. Do not hold the input lock after a finished job. Website acceptance/network results still need checking: a successful native tap only means the touch was dispatched.

## uploadFiles({tabId,files,selector?,point?,method?,timeoutMs?})

Uploads local files to a website the way a person picking them in a file dialog would, without any picker appearing. The app taps the site's upload element with a real touch and answers the file chooser the site opens with your files. Use this instead of building `File`/`DataTransfer` objects or DevTools file commands yourself.

- `tabId`: numeric ID from chrome.tabs, required. The tab needs host permission (or activeTab), like other page automation.
- `files`: 1–20 local paths (same rules as the file methods below: terminal home, `~/...` or `/sdcard/...`).
- `selector`: CSS selector of the upload button, its `<label>`, or the `<input type="file">` itself. A hidden file input works: it is laid over the page only for the tap and then restored.
- `point`: `{fx,fy}` instead of a selector, in viewport fractions like `automation({tap})`.
- Neither selector nor point: the page's only file input is used; several inputs reject with their selectors.
- `method`: `"auto"` (default) uses the real file chooser, and if the page doesn't open one within `timeoutMs`, builds the files inside the page instead (assigns them to the file input, or dispatches a drop on a drag-and-drop area; at most 25 MiB in total). `"chooser"` only uses the file chooser; `"drop"` only builds the files in the page.
- `timeoutMs`: how long to wait for the file chooser, 1000–30000, default 10000.

Resolves to `{files:[names], method:"chooser"|"assign"|"drop", given, target, file_input, multiple?, accept?, note?}`. If the input accepts one file, only the first is given and `note` says so. Rejects if the element isn't found, isn't visible, or (method `"chooser"`) no chooser opened.

A successful upload only means the site received the files. Wait for its preview or upload progress before continuing:

```js
// e.g. a "post with a photo" tool
await chrome.antigem.automation({tabId, busy:true});
try {
  await chrome.scripting.executeScript({target:{tabId}, world:'MAIN', func:text => {
    const box = document.querySelector('[contenteditable=true][role=textbox]');
    box.focus(); document.execCommand('insertText', false, text);
  }, args:[message.args.text]});
  const upload = await chrome.antigem.uploadFiles({tabId, files:[message.args.image_path], selector:'input[type=file][accept*=image]'});
  // then wait until the page shows the preview, and tap the site's Post button with automation({tap})
} finally {
  await chrome.antigem.automation({tabId, busy:false});
}
```

Sites change their markup often: prefer stable selectors (accept types, aria labels, roles) and report clearly when an element is missing. Only upload files the user asked for.

## Local paths and bytes

Paths may be absolute inside the local terminal home, `~/...`, or `/sdcard/...` when Android grants access. Relative paths resolve against the Node host working directory (normally terminal home). File APIs are not per-extension private sandboxes: `antigemFiles` permits access within these roots. For private extension data use storage.local. File methods do not open Android's system document picker; ZIP installation is an app settings feature.

| Method | Arguments → result |
| --- | --- |
| `antigem.stat` | `{path}` → `{path:absolute,size:bytes,modified:epochMs,isFile:boolean}`. Missing files reject. |
| `antigem.readFile` | `{path,encoding?:"utf8"|"base64"}` → string. Entire file is read; use sensible file sizes. Default UTF8. |
| `antigem.writeFile` | `{path,data:string,encoding?:"utf8"|"base64",append?:boolean}` → `{path:absolute}`. Creates parents; overwrite by default. |
| `antigem.listFiles` | `{path,recursive?:boolean,limit?:number}` → `{name,path,isFile,isDirectory,size,modified}[]`. Default limit500, capped2000; includes directories; skips symlink entries. No ordering guarantee or pagination. |
| `antigem.copyFile` | `{from,to}` → `{path:absoluteDestination}`. Creates parents, overwrites destination; same source/destination is a no-op. |
| `antigem.download` | `{url,path,reuse?:boolean}` → `{path:absolute}` after completion. With reuse, an existing nonempty file is reused. Otherwise streams to a temporary file then atomically replaces destination. Deadline180 seconds. Fetch is Node-side and does not inherit WebView auth cookies. |
| `antigem.mediaInfo` | `{path}` → `{duration:numberSeconds}` using Android MediaMetadataRetriever. Local supported media file required. |
| `antigem.mediaFrame` | `{path,at_seconds?:number}` → `{path:absolutePng}`. Omit time for a frame near the video's end (duration minus50ms). Explicit time is clamped to clip bounds. PNG is written beside source as `frame_<stem>_<milliseconds>.png`; nearest decoded frame is used, so timestamp accuracy depends on codec. |

To reuse generated image media as a Flow reference, pass its `media_id` to a Flow tool. Calling readFile/upload again is unnecessary. Local file uploads are cached by canonical stat path, size and modification time for the life of the Flow worker.
