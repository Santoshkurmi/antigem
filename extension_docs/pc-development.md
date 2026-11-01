# Build plugins on a PC

This SDK folder is everything needed to write AntiGem plugins anywhere: the plugin contract, every supported Chrome and AntiGem API, skills, icons, repository hosting and a working example. You don't need the app's source code.

In the app, **Settings → Custom AntiGem Plugins → Repositories → Plugin SDK for PC** (ZIP icon) saves this folder as `Downloads/AntiGem/antigem-plugin-sdk.zip`. Copy it to your PC and unzip it.

## Start here

1. Read [README.md](README.md), then [lifecycle.md](lifecycle.md) for the manifest and package rules.
2. Copy `examples/hello-mcp/` into a new folder as your starting point.
3. Use [chrome-api.md](chrome-api.md) and [antigem-api.md](antigem-api.md) for the APIs a worker may call; anything not listed there is unsupported.
4. Add skills (`skills/<name>.md`) as described in [skills.md](skills.md), an `icon`, and a `version`.

## Using a coding model on the PC

Open the unzipped SDK and your plugin folder in your editor or agent (Claude Code, Antigravity, etc.) and tell it, for example:

> Read README.md, lifecycle.md, skills.md and the API references in this SDK folder. Create an AntiGem plugin in ./my-plugin that [describe what it should do]. Use only documented APIs, validate every tool argument, add skills/*.md guides for its tools, an icon and a version. Then zip the contents of ./my-plugin.

The PC can't run the plugin; the phone's app is the runtime. Test on the phone.

## Testing on the phone

- **ZIP:** zip the *contents* of the plugin folder (so `manifest.json` is at the top of the ZIP), copy it to the phone and use **Settings → Custom AntiGem Plugins → Install ZIP**. Reinstalling the same plugin offers **Update**, which keeps its ID and stored data.
- **ADB:** `adb push my-plugin.zip /sdcard/Download/` and install it the same way. If the phone's model has the Custom AntiGem Plugin Developer MCP, it can install it with `webview_extension_install({"zip_path": "/sdcard/Download/my-plugin.zip"})`, test with `webview_extension_test` and read errors with `webview_extension_logs`.
- **Repository:** push the folder to a GitHub repository ([plugin-repository.md](plugin-repository.md)) and add the repository on the phone. Raise `version` on each push and the app offers the update.

After installing, add the plugin to your model under **MCP settings → Custom AntiGem Plugin MCP**, and in chat use `/plu:` to point the model at it or one of its skills.
