# Host your own AntiGem plugin repository

A plugin repository is a public GitHub repository with one folder per plugin. Anyone can add it in **Settings → Custom AntiGem Plugins → Repositories** to browse its plugins, install them and get updates. The app downloads only the folder of the plugin being installed, never the whole repository.

## Repository layout

```
my-plugins/                    ← repository root
  antigem-plugins.json         ← index (recommended)
  youtube/                     ← one plugin per folder
    manifest.json
    worker.js
    icon.png
    skills/
      youtube_upload.md
  notes/
    manifest.json
    skills/
      daily_notes.md
```

Each plugin folder is exactly what you would otherwise ZIP: `manifest.json` at its top, plus a worker, `skills/`, or both. See [lifecycle.md](lifecycle.md) and [skills.md](skills.md).

## The index: `antigem-plugins.json`

```json
{
  "name": "My plugins",
  "plugins": [
    {
      "path": "youtube",
      "id": "youtube",
      "name": "YouTube",
      "version": "1.2.0",
      "description": "Upload and search videos",
      "icon": "icon.png"
    },
    { "path": "notes", "id": "notes", "name": "Notes", "version": "0.3.0" }
  ]
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `name` (top level) | no | Title shown for the repository. Defaults to `owner/repo`. |
| `path` | **yes** | Plugin folder, relative to the repository root. Nested paths such as `plugins/youtube` work. |
| `id` | recommended | Same as the manifest's `id`. Lets the app recognize the plugin even if it was installed from a ZIP. |
| `version` | recommended | Same as the manifest's `version`. Shown in the list and compared with the installed version to offer updates. |
| `name`, `description` | no | Shown in the list. |
| `icon` | no | Icon file, relative to the plugin folder (PNG, JPG, WebP or SVG). |

With `id` and `version` in the index, the app shows the whole list and update status from this one small file. If an entry leaves out `id` or `version`, the app reads that plugin's `manifest.json` to fill them in.

**Keep `version` the same in the index and in the plugin's `manifest.json`, and raise both on every release.** The installed plugin reports its manifest version, so an index that says `1.2.0` over a manifest that still says `1.1.0` shows an update that never goes away.

### Without an index

If the repository has no `antigem-plugins.json`, every top-level folder that contains a `manifest.json` is listed as a plugin, and each manifest is read for its name, version and icon. This needs a GitHub API call that is limited to 60 per hour per network, so an index is more reliable.

## Icons

Give each plugin an icon so people can recognize it. In `manifest.json` use either one file:

```json
"icon": "icon.png"
```

or Chrome-style sizes, where the app picks the largest:

```json
"icons": { "48": "icons/48.png", "128": "icons/128.png" }
```

A square PNG of 128×128 px or larger works best; SVG, JPG and WebP also work. The index's `icon` field is used for the repository list before the plugin is installed.

## Adding it in the app

Enter any of:

- `owner/repo` (default branch)
- `owner/repo@branch` or `owner/repo@v2.0` (a branch or tag)
- `https://github.com/owner/repo` or `https://github.com/owner/repo/tree/branch`

Installs remember which repository and folder they came from, so the plugin card shows when that repository has a newer version. Updating keeps the plugin's ID, stored data and MCP connection.

## Limits

- Public GitHub repositories only.
- Per plugin: at most 5,000 files, 32 MiB per file and 128 MiB in total.
- Plugins from a repository run code on the device just like ZIP installs. Only add repositories you trust.

## Release checklist

1. Change the plugin's files.
2. Raise `version` in its `manifest.json` (for example `1.2.0` → `1.3.0`).
3. Raise the same `version` in `antigem-plugins.json`.
4. Commit and push to the branch people added. Their app shows the update the next time it checks.
