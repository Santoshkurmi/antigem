---
name: Generate images and videos
description: Generate, edit and combine images and videos on Google Flow with the flow_* tools.
---

# Generate images and videos with Google Flow

Use the `flow_*` tools (the `deepx_*` tools are older aliases of the same features).

## Before generating

1. Call `flow_status`. If `logged_in` is false, tell the user to sign in to Flow in the AntiGem browser and stop.
2. Generation goes into the project open in the Flow tab. Use `flow_list_projects` / `flow_open_project`, or `new_project: true` on a generate call, if the user wants a specific or fresh project.
3. `flow_get_options` lists the valid models, aspect ratios, durations and resolutions.

## Images: `flow_generate_image`

- Models: `Nano Banana 2.1` (default), `Nano Banana Pro`, `Nano Banana 2 Lite`. Aspect ratios: 16:9, 4:3, 1:1, 3:4, 9:16.
- `count` (1–4) is how many images for **one** prompt. For several different prompts, put them all in `items` in one call; never call the tool once per prompt.
- `references`: media ids, local image paths or `@handles` for variants or the same character.
- To change an existing image (“make the jacket red”) use `flow_edit_image` on its media id; to make a separate new image based on it, use `flow_generate_image` with `references`.
- `flow_upscale_image` gives 2K or 4K.

## Videos: `flow_generate_video`

- Models: `Omni 1.1 Flash` (default; 4/6/8/10 s, 360p or 720p), `Veo 3.1 - Lite`, `Veo 3.1 - Fast`, `Veo 3.1 - Quality` (8 s, 720p). Aspect ratios: 16:9 or 9:16.
- Two separate modes, never combined:
  - **Ingredients:** `references` (up to 3 images). Not supported by `Veo 3.1 - Quality`.
  - **Frames:** `start_frame`, and optionally `end_frame` (needs `start_frame`).
- Videos cost credits; Veo tiers cost much more than Omni. Set `max_credits` to cap the whole call, and tell the user what a large batch will cost before running it.
- Continue a clip: `flow_extend_video` for Veo clips; for Omni clips use `flow_extract_frame` (`upload: true`) and then `flow_generate_video` with that `start_frame`.
- Join clips into one MP4 with `flow_combine_videos` (no credits); `flow_video_to_gif`, `flow_upscale_video` and `flow_edit_video` are also available.

## Waiting and results

- Generate calls wait up to `wait_seconds` (max 50). If not finished they return a `job_id`: keep calling `flow_get_job` until it is done, failed or cancelled. Videos take about 1–5 minutes.
- Results are saved in `~/flow-media`. Show one in chat by writing `![caption](<path>)` on its own line. `flow_get_media` with `view: true` lets you look at an image.
- `dry_run: true` prepares everything but stops before the request is sent, so nothing is generated or charged.
- Ask the user before `flow_trash_media`.
