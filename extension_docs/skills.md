# Skills

A plugin can ship skills: Markdown guides that tell the model how to use its tools for a task. Skills need no code; a plugin may contain only skills.

## Layout

```
my-plugin/
  manifest.json
  worker.js                ← optional when the plugin has no mcp_tools
  skills/
    youtube_upload.md      ← skill "youtube_upload"
    youtube_search.md      ← skill "youtube_search"
    youtube_upload/        ← detail files for youtube_upload (any depth)
      thumbnails.md
      metadata.md
```

- Only `.md` files directly inside `skills/` are skills. The file name (without `.md`) is the skill name: letters, digits, `.`, `_` and `-`.
- A folder with the same name holds that skill's detail files. Split a long skill into a short main file that refers to them, for example "for thumbnails, read `thumbnails.md`".
- Title and description come from optional frontmatter, otherwise from the first `# heading` and the first paragraph:

```markdown
---
name: Upload a video
description: Upload a video to YouTube Studio with title, description and thumbnail.
---
```

## What the model gets

Every plugin with skills gets three extra MCP tools on its server, with no worker code:

| Tool | Returns |
| --- | --- |
| `get_skills` | Each skill's `name`, `title`, `description`, `path` and number of detail files. Its tool description also lists the skill names. |
| `get_skill {name}` | The main file's `content`, its `path`, `detail_dir` and `detail_files` (`file` relative to the skill folder, absolute `path`). |
| `get_skill_file {name, file}` | One detail file's `content`. `file` must be relative to the skill folder. |

The server's MCP `initialize` result also carries `instructions` listing the skills, for clients that show server instructions. A tool the plugin declares in `mcp_tools` with one of these names replaces the built-in one. Files are read on each call, so edits apply without a reload. Text over 512 KiB is truncated.

In chat, the user can type `/plu:` to pick a plugin and one of its skills. The model is then told the skill file's path and the plugin's MCP server name.

## Install

A ZIP needs `manifest.json` (with `manifest_version` and `name`; `version` is optional but recommended, and should go up when skills change) and at least one of: nonempty `mcp_tools` with a background worker, or `skills/*.md`.
