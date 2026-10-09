# Google Flow MCP — Complete Build Spec (v2)

Reverse-engineered from flow.google.com, frontend build `boq_labs-ai-sandbox-frontend_20261006.11_p0`, captured 2026-10-08/09.
Hand this whole file to a coding model to build the MCP server. ✅ = verified live. ⚠️ = verified partially / inferred. ❓ = not captured.

---

## 0. TL;DR for the builder

1. Attach to the user's logged-in Chrome with Playwright over CDP. Work inside one `flow.google.com` tab.
2. **Everything that doesn't create AI media is a direct RPC** from inside the page (§2): list/create/rename projects, list/search/rename/trash/restore media, credits, models, video status, **combine videos into one MP4**, GIF export, scenes.
3. **Generation, uploads and upscales carry a reCAPTCHA token**, so they must be submitted by the real page. The server sets the settings, attaches references, types the prompt, and clicks Start (§6, §7).
4. **Install the Send Guard (§1.2) before anything else.** It intercepts every generate request, checks model, aspect, count, mode and reference IDs, and **aborts it before it reaches Google** if anything is wrong. Zero credits are spent on a wrong request. This is what makes the MCP reliable.
5. Never trust the UI. Trust the request (guard) and the response (parsed JSON).

---

## 1. Architecture

### 1.1 Connection
```
Chrome started with --remote-debugging-port=9222 --user-data-dir=<dedicated profile>, signed in to Flow.
Windows: "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir="%LOCALAPPDATA%\FlowMCPProfile"
macOS:   /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir="$HOME/.flow-mcp-profile"
```
- `chromium.connectOverCDP('http://localhost:9222')` → reuse a tab whose URL starts with `https://flow.google.com/`, or open one.
- **Reads work from any Flow page** for any project (verified: read project B's contents and signed URLs while on project A).
- **Generation goes into the project currently open**, because the page puts its own project ID into the request. `ensureProject(pid)`: if `page.url()` doesn't contain `/project/<pid>`, `page.goto('https://flow.google.com/project/<pid>')`, wait for `button[aria-label="Start generation"]`, and re-inject helpers.
- Optional: a second tab for reads, so reads never wait on navigation.
- The page may be zoomed (seen: devicePixelRatio 0.67). **Use Playwright locators, never pixel coordinates.**
- Keep a per-tab **generation queue** (one UI generation at a time). Reads can run in parallel.

### 1.1b Login check ✅ (verified by comparing the signed-in page with the same requests sent without cookies)
| Signal | Signed in | Signed out |
|---|---|---|
| `window.WIZ_global_data.SNlM0e` (`at` token) | present | **missing** |
| `button[aria-label^="Google Account:"]` in the header | present (label includes the name and email) | missing; the page shows "Sign in" |
| Any RPC, e.g. `GetCredits` `nzlxg` | 200 + `wrb.fr` | **HTTP 401**, body contains `["er",null,null,null,null,401,…]` |
| URL | `flow.google.com/…` | may redirect to `accounts.google.com/…` |

```js
async function checkLogin(page) {
  if (/accounts\.google\.com/.test(page.url())) return { loggedIn:false, reason:'redirected to Google sign-in' };
  return page.evaluate(async () => {
    const w = window.WIZ_global_data || {};
    if (!w.SNlM0e) return { loggedIn:false, reason:'no session token on page' };
    try { const credits = (await window.__flowRpc('nzlxg', []))[0];
          const acct = document.querySelector('[aria-label^="Google Account:"]')?.getAttribute('aria-label') || '';
          return { loggedIn:true, account: acct.replace('Google Account: ',''), credits }; }
    catch (e) { return { loggedIn:false, reason:'API returned 401 / error: ' + e.message }; }
  });
}
```
- Run `checkLogin` at startup, before every generation, and whenever any RPC returns 401 or a non-`wrb.fr` body.
- When signed out, every tool returns: `"Not signed in to Google Flow. Open the Flow Chrome window, sign in, then retry."`, and does nothing else.
- Expose it as a tool too: `flow_status()` → `{ chromeConnected, flowTabOpen, loggedIn, account, credits, currentProjectId }`.

### 1.2 The Send Guard ✅ (the single most important piece)
Generation RPCs (all go to `/_/AiSandboxAngularFrontend/data/batchexecute?rpcids=<ID>`):

| RPC | What |
|---|---|
| `ogiZ0b` | image generate |
| `YhhmEf` | video text→video |
| `eb1hJf` | video start frame→video |
| `nprQif` | video start+end frame |
| `MZZa6b` | video ingredients (reference images) |
| `jIps6` | video edit |
| `fZytfe` | video extend ❓ |
| `p0UkFb` | video upscale |
| `SPrCad` | image upscale |
| `maseQ` | upload |

```js
let expectation = null;   // set right before clicking Start; cleared after
await page.route('**/data/batchexecute?*', async route => {
  const req = route.request();
  const rpc = (req.url().match(/rpcids=([^&]+)/) || [])[1];
  if (!expectation || !GEN_RPCS.has(rpc)) return route.continue();
  const args = JSON.parse(JSON.parse(new URLSearchParams(req.postData()).get('f.req'))[0][0][1]);
  const problems = validate(rpc, args, expectation);     // see §1.3
  const exp = expectation; expectation = null;
  if (problems.length) { exp.reject(new Error('Blocked before sending: ' + problems.join('; '))); return route.abort(); }
  exp.resolve({ rpc, args }); return route.continue();
});
```
- An aborted request never reaches Google, so **no credits are used** (verified with 30+ blocked requests).
- After an abort the page shows a client-only **"Failed" tile** (it has Retry / Reuse prompt / Delete buttons). It is not saved on the server and disappears on reload. Click its `Delete` button or ignore it.
- **The page clears the reference chips on submit, even when the request is aborted or fails.** On retry, re-attach the references.

### 1.3 What `validate()` checks (field positions verified)
```js
const IMAGE_MODEL = { 'Nano Banana 2.1':'BELUGA', 'Nano Banana Pro':'GEM_PIX_2', 'Nano Banana 2 Lite':'HARBOR_SEAL' };
const IMAGE_ASPECT = { '1:1':1, '9:16':2, '16:9':3, '3:4':4, '4:3':5 };
const VIDEO_ASPECT = { '9:16':1, '16:9':2 };

// image (ogiZ0b):  entries = args[1]
//   entries.length === count
//   e[5] === IMAGE_MODEL[model]          e[4] === IMAGE_ASPECT[aspect]
//   (e[2]||[]).map(r=>r[0])  deepEquals  expectedRefIds   (same list on every entry; each ref = [mediaId,null,null,null,1])
// video text / start / start+end (YhhmEf | eb1hJf | nprQif):  entries = args[0]
//   rpc === expectedRpc (decided by mode: none → YhhmEf, start → eb1hJf, start+end → nprQif)
//   entries.length === count   e[1] === expectedModelKey (§4.2)   e[2] === VIDEO_ASPECT[aspect]
//   start frame id = e[4][1]   end frame id = e[5][1]
// video ingredients (MZZa6b):  entries = args[0]      ⚠ fields shift by one
//   e[1].map(r=>r[1]) deepEquals expectedRefIds   e[2] === modelKey   e[3] === aspect
// Every ref id must also exist on the server: before clicking Start, call GetMedia(id) for each (§3). "not found" → re-attach.
```
Two real silent failures this catches:
- **Veo 3.1 Quality + Ingredients** silently drops the references and sends `YhhmEf` text→video instead. The guard sees the wrong RPC and aborts.
- **A just-uploaded file attached too early** puts a temporary client ID in the request (server says NOT_FOUND). The reference is silently ignored. The guard checks the ref ID against the expected uploaded ID and aborts (§7.4).

---

## 2. RPC transport (`batchexecute`) ✅

```
POST https://flow.google.com/_/AiSandboxAngularFrontend/data/batchexecute
  ?rpcids=<ID>&source-path=<location.pathname>&bl=<WIZ_global_data.cfb2h>&f.sid=<WIZ_global_data.FdrFJe>&hl=en&_reqid=<rand>&rt=c
headers: content-type: application/x-www-form-urlencoded;charset=UTF-8 ; x-same-domain: 1
body:    f.req=JSON.stringify([[[ID, JSON.stringify(ARGS), null, "generic"]]]) & at=<WIZ_global_data.SNlM0e>
credentials: include
```
Response: strip `)]}'`, split lines, `JSON.parse` lines starting with `[`, find `e[0]==='wrb.fr' && e[1]===ID`. Payload = `JSON.parse(e[2])`. If `e[2]` is null, the error is in `e[5]` (`[3]` invalid argument, `[5]` not found).

```js
window.__flowRpc = async (id, args) => {
  const w = WIZ_global_data;
  const q = new URLSearchParams({ rpcids:id, 'source-path':location.pathname, bl:w.cfb2h, 'f.sid':w.FdrFJe, hl:'en', _reqid:String(1e5+Math.floor(Math.random()*9e5)), rt:'c' });
  const body = new URLSearchParams({ 'f.req': JSON.stringify([[[id, JSON.stringify(args), null, 'generic']]]), at: w.SNlM0e });
  const r = await fetch('/_/AiSandboxAngularFrontend/data/batchexecute?'+q, { method:'POST', credentials:'include', body,
    headers:{ 'content-type':'application/x-www-form-urlencoded;charset=UTF-8', 'x-same-domain':'1' } });
  const t = await r.text();
  for (const line of t.split('\n')) if (line.startsWith('[')) try {
    for (const e of JSON.parse(line)) if (e[0]==='wrb.fr' && e[1]===id) return e[2] ? JSON.parse(e[2]) : { error: e[5] };
  } catch {}
  throw new Error(`no wrb.fr for ${id} (HTTP ${r.status})`);
};
```
- Re-inject after every full navigation. Read `bl`, `f.sid` and `at` live, and never cache them.
- **Self-healing IDs:** rebuild the `method → rpcId` map at startup by fetching every loaded script whose URL contains `boq-labs-ai-sandbox` (from `document.scripts` plus `performance.getEntriesByType('resource')`, since lazy chunks load when the editor opens) and matching `/new _\.\w+\("([A-Za-z0-9]{4,8})",[\s\S]{0,400}?"(\/[\w.]+)"\]/g`. Look up by method name and fall back to the IDs below.

---

## 3. RPC catalog

### Direct-callable (no reCAPTCHA): use `__flowRpc`
| Purpose | Method | ID | Args | Result |
|---|---|---|---|---|
| Credits ✅ | VideoFxService.GetCredits | `nzlxg` | `[]` | `d[0]` = remaining credits |
| Models ✅ | FlowService.GetModels | `HTrJv` | `[]` | §4 |
| List projects ✅ | FlowService.GetProjects | `UpteDb` | `["projects/*", pageSize, null,null,null,null,[1]]` | `d[0][i] = [projectId,[title,null,[sec,ns],thumbUrl,...]]`, `d[1]` next page token |
| Create project ✅ | AiSandbox.CreateProject | `jHPbke` | `["projects/*",[null,["<title>"]],[null,22]]` | `["<projectId>",["<title>"]]` |
| Rename project ✅ | AiSandbox.UpdateProjectInfo | `o8DA4` | `["projects/<pid>",["<title>"],[["project_title"]],[null,22]]` | `["<title>"]` |
| Project contents ✅ | FlowService.GetProjectContents | `Zzl0ze` | `["projects/<pid>"]` | §5.1 |
| Media details + signed URL ✅ | FlowService.GetMedia | `as29s` | `["<mediaId>"]` | §5.2 |
| **Rename media** ✅ | FlowService.UpdateWorkflow | `mYWVGd` | `[["<workflowId>",null,null,["<newTitle>"],"<pid>"],[["metadata.display_name"]]]` | updated workflow |
| **Move to trash** ✅ | FlowService.BatchUpdateWorkflows | `pGCYOe` | `[[["<wfId>",null,null,[null,null,true],"<pid>"]],[["metadata.archived"]]]` | |
| **Restore from trash** ✅ | same | `pGCYOe` | same with `false` | verified reversible |
| Video status (poll) ✅ | VideoFxService.BatchCheckAsyncVideoGenerationStatus | `jwpduf` | `[null,null,[["<mediaId>"],…]]` | `d[2][i]` = media record, status `d[2][i][5][8][0]` |
| **Combine videos → one MP4** ✅ | AiSandbox.RunVideoFxConcatenation | `GjQLTd` | `[[ ["<mediaId>",null,[startSec]\|[],[endSec]], … ]]` | `[null,[["projects/…/jobs/<id>"]]]` |
| Combine status / result ✅ | AiSandbox.RunVideoFxCheckConcatenationStatus | `IxdSwd` | `[[["<jobName>"]]]` | `[3,"","",3,"<base64 MP4>"]` when done |
| **Video → GIF** ✅ | VideoFxService.GeneratePinholeGif | `QZasKb` | `[null,"<videoMediaId>"]` | `["<base64 GIF>"]` (270p) |
| Create scene ✅(via UI) | FlowService.CreateScene | `rqZuUc` | `["projects/<pid>",["<wfId1>","<wfId2>",…],null,null,<aspect 2=16:9>]` | `[[sceneId,name,null,created,updated,aspect,null,[]],[clips…]]` |
| Scene clips ✅ | FlowService.GetSceneWorkflows | `uwAyfb` | `["<sceneId>","<pid>"]` | clips (§8.1) |
| Update scene clips ⚠️ | FlowService.UpdateSceneWorkflows | `GoMJte` | `["<pid>","<sceneId>",[clip…],…]` | captured, not fully decoded |
| Delete scenes ❓ | FlowService.BatchDeleteScenes | `BLIwAb` | | |
| Delete project ❓ | AiSandbox.DeleteProject | `QI2zvc` | | |
| Cancel generation ❓ | FlowService.CancelMediaGeneration | `CK0Pk` | | |
| User settings ✅ | FlowService.GetUserSettings / UpdateUserSettings | `Yizz8d` / `DA4VGb` | | |

`workflowId` for a media item = `GetMedia(mediaId)[2]`. Rename and trash work on the **workflow** (the tile), not the media.

### Page-submitted (reCAPTCHA in `ctx[10][0]`): never call directly; always via UI + Send Guard
`ogiZ0b` image · `YhhmEf`/`eb1hJf`/`nprQif`/`MZZa6b` video · `jIps6` edit video · `fZytfe` extend · `p0UkFb` upscale video · `SPrCad` upscale image · `maseQ` upload · `sAVZzc` TransformImage ❓.
The common ctx object is `[null,22,null,null,null,"<projectId>",null,null|"",null,null,["<reCAPTCHA>",1]]`.

---

## 4. Models

### 4.1 Image ✅ (0 credits on this plan)
| UI menu text | key in request | Notes |
|---|---|---|
| Nano Banana 2.1 (default) | `BELUGA` | |
| Nano Banana Pro | `GEM_PIX_2` | |
| Nano Banana 2 Lite | `HARBOR_SEAL` | |
Aspects 16:9, 4:3, 1:1, 3:4, 9:16. Count x1–x4. All references work with every model.

### 4.2 Video: full verified key matrix ✅ (dry-run captured, not guessed)
Omni durations 4/6/8/10 s, resolution 360p/720p. **Veo models have fixed 8 s and 720p** (the UI hides duration and resolution).

| Model (UI text) | Text→video `YhhmEf` | Start frame `eb1hJf` | Start+End `nprQif` | Ingredients `MZZa6b` | Cost per video |
|---|---|---|---|---|---|
| Omni 1.1 Flash | `abra_t2v_{D}s` · 360p: `abra_t2v_{D}s_360p` | `abra_i2v_{D}s[_360p]` | `omni_flash_i2v_{D}s_first_last[_360p]` | `abra_r2v_{D}s[_360p]` | 720p 7/10/12/15 · 360p 4/5/6/7 (4/6/8/10 s) |
| Veo 3.1 - Lite | `veo_3_1_t2v_lite` (both aspects) | `veo_3_1_i2v_lite` | `veo_3_1_interpolation_lite` | `veo_3_1_r2v_lite` | 10 |
| Veo 3.1 - Fast | 16:9 `veo_3_1_t2v_fast` · 9:16 `veo_3_1_t2v_fast_portrait` | 9:16 `veo_3_1_i2v_s_fast_portrait` (16:9 ⚠️ `veo_3_1_i2v_s_fast`) | 9:16 `veo_3_1_i2v_s_fast_portrait_fl` (16:9 ⚠️ `veo_3_1_i2v_s_fast_fl`) | 9:16 `veo_3_1_r2v_fast_portrait` (16:9 ⚠️ `veo_3_1_r2v_fast`) | 20 |
| Veo 3.1 - Quality | 16:9 `veo_3_1_t2v` · 9:16 `veo_3_1_t2v_portrait` | 16:9 `veo_3_1_i2v_s` | 16:9 `veo_3_1_i2v_s_fl` | **❌ not supported: refs silently dropped, sent as `YhhmEf`** | 100 |
| Omni edit (editor) | `abra_edit` / `abra_edit_360p` (`jIps6`) | | | | same as Omni t2v of the clip length |
| Upscale video | `omni_upsampler_360p` (360p→720p), `veo_3_1_upsampler_1080p`, `veo_3_1_upsampler_4k` (`p0UkFb`) | | | | |

- Cost = per video × count. Read the exact cost from the popover footer `Generating will use N credits` **before** submitting, and enforce a `max_credits` parameter.
- Aspect never changes the Omni or Lite key; it's only in the aspect field. For Veo Fast and Quality, 9:16 adds `_portrait`.
- **In the guard, prefer "family regex + aspect field" over exact keys**, since Google renames keys: Omni text `^abra_t2v_` · start `^abra_i2v_` · start+end `first_last` · ingredients `^abra_r2v_` · Veo start `_i2v_` · Veo start+end `(_fl$|interpolation)` · Veo ingredients `_r2v_`. Check duration with `_${D}s` (Omni), resolution with `_360p$` presence (Omni), and the model family with `/^abra|^omni/` vs `/^veo_3_1/` plus `lite|fast|(none)`.
- `GetModels` (`HTrJv`): `d[0][4]` video families, `d[0][5]` image families, `d[0][7]` TTS; each family is `[displayName,[[key,…,costTiers@4,…]]]`.

---

## 5. Data shapes ✅

### 5.1 `GetProjectContents` → `d`
| Index | Content |
|---|---|
| `d[1]` | **workflows (tiles)**: `[wfId,null,null,[title,[createdSec,ns],archived?,null,primaryMediaId,clientId,[updatedSec,ns]],pid]` |
| `d[2]` | **media**: `[mediaId,pid,wfId,"CAE",null,META,DIMS,VIDEO?]`. `META[0]` created, `META[1]` title, `META[6][1][0][0]` modelKey (videos), `META[8][0]` video status, `META[9]` 1, `META[12]` prompt, `META[13]` bytes, `DIMS[2]` = [w,h] |
| `d[4]` | **scenes**: `[sceneId,name,null,created,updated,aspect,null,[]]` |
| `d[6]` | **scene clips** (same shape as `uwAyfb`) |
| `d[3]`, `d[7]` | not needed |

- Image vs video: a video has `META[6][1][0][0]` matching `/abra|veo|omni/` and a `VIDEO` block. In `GetMedia` the MP4 URL is at `[7][0][8]`.
- **"The last image"** = the newest `d[2]` entry by `META[0]` that isn't a video (exclude archived workflows). Even better: the MCP keeps its own session history of every mediaId its tools returned, and resolves "last", "previous" or "the 2nd one" from that first.

### 5.2 `GetMedia(mediaId)` → `GM`
`GM[2]` wfId · `GM[5][1]` title · **`GM[5][10]` signed image URL** (video thumbnail) · `GM[5][12]` prompt · `GM[5][8][0]` video status · **`GM[7][0][8]` signed MP4 URL** · `GM[6][2]` [w,h].
Signed URLs (`https://flow-content.google/{image|video}/<mediaId>?Expires=…&Signature=…`) download **without cookies** for about 6 h. Call GetMedia again for a fresh one.

### 5.3 Video job status (`META[8][0]` / `jwpduf`)
`6` submitted → `2` generating → `3` done. Anything else = failed (exact failed value ❓; return the raw record). Omni 360p 4 s ≈ 45 s.

---

## 6. Settings (model / aspect / count / duration / resolution) ✅

### 6.1 DOM
| Control | Locator |
|---|---|
| Open settings | `button[aria-label="Settings trigger"]` (text shows the current state, e.g. `🍌 Nano Banana Pro crop_portrait x1` or `Video · 360p · 4s crop_9_16 x2`) |
| Popover | `.cdk-overlay-pane` that contains `[role=radio]` |
| Mode | radio `Image` / `Video` |
| Video sub-mode | radio `Frames` (text / start / start+end) / `Ingredients` |
| Aspect | radio `16:9 4:3 1:1 3:4 9:16` (video: `16:9 9:16`) |
| Model | `button[aria-label="Select model family"][aria-haspopup=menu]` → `[role=menuitem]` whose text **includes** the model name |
| Resolution / Duration (Omni only) | radio `360p 720p` / `4s 6s 8s 10s` |
| Count | radio `x1 x2 x3 x4` |
| Cost | `/Generating will use (\d+) credits/` in the popover text |
| Prompt | `div.ProseMirror[contenteditable=true]` |
| Submit | `button[aria-label="Start generation"]` |
| Clear prompt + chips | `button[aria-label="Clear prompt"]` |
| Agent toggle | pill with text `Agent`, **must be off** (on = `Agent instructions` button visible, no `Settings trigger`) |

### 6.2 Behaviours (all observed)
- The **trigger needs a real click**, and the first click after the popover closes often does nothing. Click, wait ≤1.5 s for a `[role=radio]`, and retry up to 3 times.
- Radios and the model menu respond to normal Playwright clicks (and even to script clicks).
- **Image-mode submit needs a real click** (a script `.click()` didn't fire it). Playwright `click()` is fine.
- Radio accessible names include icon text (`crop_16_9 16:9`, `image Image`, `360p info`). Match with `(^|\s)LABEL$`, or exact for `x1` (not `x10`).
- **Aspect and count are shared** between Image and Video. Always set every field.
- Pick order: mode → sub-mode → **model** → aspect → resolution → duration → count. Changing the model hides or resets resolution and duration.
- Re-query elements after every click (layout moves).

```js
async function applySettings(page, s) {           // s = {mode, sub, model, aspect, res, dur, count}
  const pane = page.locator('.cdk-overlay-pane').filter({ has: page.getByRole('radio') }).first();
  for (let i=0; i<3 && !(await pane.isVisible().catch(()=>false)); i++) {
    await page.getByRole('button', { name: 'Settings trigger' }).click();
    await pane.waitFor({ state:'visible', timeout:1500 }).catch(()=>{});
  }
  const radio = l => pane.getByRole('radio', { name: new RegExp(`(^|\\s)${esc(l)}( info)?$`) });
  const pick = async l => { const r = radio(l); if (!(await r.count())) return false;
                            if ((await r.getAttribute('aria-checked')) !== 'true') await r.click(); return true; };
  await pick(s.mode); if (s.mode==='Video') await pick(s.sub || 'Frames');
  const mb = pane.locator('button[aria-label="Select model family"]');
  if (!(await mb.innerText()).includes(s.model)) { await mb.click(); await page.getByRole('menuitem').filter({ hasText: s.model }).first().click(); }
  if (!(await mb.innerText()).includes(s.model)) throw new Error('model not applied');
  await pick(s.aspect); if (s.res) await pick(s.res); if (s.dur) await pick(`${s.dur}s`); await pick(`x${s.count}`);
  const cost = Number(((await pane.innerText()).match(/Generating will use (\d+) credits/)||[])[1] ?? NaN);
  await page.keyboard.press('Escape');
  return { cost };
}
```

---

## 7. References (images as ingredients / frames) ✅

### 7.1 Modes
| Goal | Page state | Attach where | RPC |
|---|---|---|---|
| Image from 1..N reference images (variants, edits, "same character") | Image mode | **+** ingredient picker | `ogiZ0b` (refs in `e[2]`) |
| Video starting from an image | Video → Frames | `Start` slot | `eb1hJf` |
| Video from start + end image | Video → Frames | `Start` + `End` slots (`button[aria-label="Swap first and last frames"]` swaps them) | `nprQif` |
| Video using reference images (subject or style) | Video → Ingredients | **+** ingredient picker | `MZZa6b` |
Set the mode and sub-mode **first**, then attach.

### 7.2 Pickers
- Ingredient picker: `button[aria-label="Add ingredients to the prompt box"]`. Tabs: All, Images, Videos, Voices, Characters, Avatars, Uploads.
- Frame picker: click the slot button with text `Start` / `End`. Title "Select a frame image". Tabs: **Images (default)** and **Uploads**. **Uploaded files only appear under the Uploads tab**, so switch tabs or search finds nothing.
- Common elements: `input[aria-label="Search assets"]` (searches titles), `mat-select[aria-label="Select project"]` (references can come from any project), virtualized list `[role=listbox][aria-label="Asset list"]`, items `button[role=option]`, confirm button text `Add to prompt`, `Upload media` button.
- Clicking an option sometimes attaches it immediately and sometimes only previews it. After clicking, check whether a new chip appeared; if not, click `Add to prompt`.
- **Option thumbnails are not reliable IDs.** They're sometimes `flow-content.google/image/<id>` and sometimes opaque `flow.google.com/asb/…`. **Titles repeat** (you have two "A yellow rubber duck" and two "ref_test.png"). Grid tiles also use opaque `/asb/` thumbnails, with `flow-grid-tile-container[aria-label="<title>"]`.

### 7.3 Chips (the source of truth) ✅
- Ingredient chip `button[aria-label="Ingredient"]`, frame chip `button[aria-label="Image ingredient"]`.
- **Chip `img` src = `https://flow-content.google/image/<mediaId>`.** That's the attached ID. It's empty for a moment while loading, so wait up to 5 s.
- Remove a chip by clicking it (the × icon). `Clear prompt` removes all.
- Chips are cleared on submit, success or failure.

### 7.3b ⭐ PRIMARY METHOD: picker-free attach via the grid tile menu ✅ (verified 2026-10-09)
The asset picker is fragile: sometimes an item click attaches directly, sometimes it needs "Add to prompt", the frame picker hides uploads, and the list is virtualized. Error seen in practice: **"Add button not found"**. **Don't use the picker.** Use the **project grid tile menu** instead:

| Goal | Tile menu item | Result (verified) |
|---|---|---|
| Image references (Image mode) | `Add to prompt` | adds `Ingredient` chip, mode stays Image |
| Video Ingredients (Video → Ingredients) | `Add to prompt` | adds `Ingredient` chip, mode stays |
| Video **Start frame** | `Animate` | switches the bar to Video → Frames and sets **Start** = this image (resets both frames) |
| Video **End frame** | `Add to prompt` **while in Frames mode with Start already set** | fills **End** |
| Start + End | `Animate(A)` **then** `Add to prompt(B)` | Start=A, End=B ✅ |
| Edit / variant of ONE image | open `/project/<pid>/edit/<workflowId>` directly, type into "What do you want to change?", Start | request auto-includes the image as base ref `[mediaId,null,null,null,2]` (flag **2** = edit base). Settings there: aspect + model only, 1 output |

Tile = `flow-grid-tile-container[aria-label="<title>"]` → its `button[aria-label="More options"]` (present in the DOM; dispatch `mouseover` on the tile first) → `.cdk-overlay-pane [role=menuitem]` with text `Animate` / `Add to prompt`. **Script clicks work for this whole menu**, so no real mouse is needed. Never click the tile body: that opens the editor.

**Make the tile unique and visible (titles repeat, grid may not render it):**
```
attachViaTile(mediaIds[], how):                 // how: 'ingredient' | 'start' | 'end'
  for each id: wf = GetMedia(id)[2]; save original title (GetProjectContents d[1] wf[3][0]);
               UpdateWorkflow(wf, title = 'mcpref-' + id.slice(0,8))          // direct RPC (mYWVGd)
  page.reload()   // REQUIRED: the grid caches titles; an API rename isn't visible until reload. Re-inject helpers; page.route guard survives.
  ensure Agent mode off; set mode/sub-mode via applySettings FIRST (Image | Video+Ingredients | Video+Frames)
  click "Clear prompt"
  for each id (in order):
     search box input[aria-label="Search"] = 'mcpref-<id8>' + Enter → exactly 1 tile
     tile ⋮ → 'Animate' (start) | 'Add to prompt' (ingredient / end)
     wait ≤5 s until newest chip img src contains id   (verify!)
  clear search; rename every wf back to its original title (finally{})
  verify chips == expected (order: frames Start then End)
```
- Uploaded files are normal grid tiles too. So: `upload_media` → real mediaId → `attachViaTile`. This also avoids the temporary-ID bug (§7.5).
- Batch: tag all references, then **one** reload, then attach each.
- For **Start + End**: run `Animate` for the start image **first**, then `Add to prompt` for the end image. (Animate always resets both slots.)
- After attaching, set the remaining settings (model/aspect/count/duration). Changing model or aspect doesn't remove chips, but **switching the Image/Video mode or the Frames/Ingredients sub-mode can**, so set the mode before attaching, and re-check chips right before Start.
- The Send Guard still verifies the IDs in the request.

### 7.3a ⭐ Agent mode vs manual mode ✅ (verified 2026-10-09)
- Pill: `button.agent-mode-chip` (text `Agent`, left of the Settings trigger). **State = `aria-pressed`** (`"true"` = Agent mode on).
- Agent ON also shows `button[aria-label="Agent instructions"]` and `button[aria-label="Settings"]`; generation then goes through Flow's chat agent (it asks for confirmation, and requests/settings differ). **The MCP must always run in manual mode.**
- An Agent side panel may also be open (`button[aria-label="Start new session"]`, `button[aria-label="Close"]`). Close it.
```js
async function ensureManualMode(page) {
  const pill = page.locator('button.agent-mode-chip, button:has-text("Agent")').first();
  if ((await pill.getAttribute('aria-pressed')) === 'true') await pill.click();        // script click also works
  await page.waitForFunction(() => !document.querySelector('button[aria-label="Agent instructions"]'));
  const panelClose = page.locator('button[aria-label="Close"]').filter({ has: page.locator('text=close') });
  if (await page.locator('button[aria-label="Start new session"]').count()) await panelClose.first().click().catch(()=>{});
}
```
Call it after every navigation and before every generation. Optional tool: `set_agent_mode(on: boolean)` → returns `{agentMode}`. Even when the user turns it on, generation tools must switch back to manual first.

### 7.3d ⭐ Edit a specific image version (iterations / history) ✅ (real edit verified 2026-10-09)
- Open **`/project/<pid>/edit/<workflowId>`** (workflowId = `GetMedia(mediaId)[2]`). Prompt placeholder "What do you want to change?". Settings there: aspect + model only, 1 output. `+` ingredients also available for extra references.
- **Every edit = a new version in the same workflow (tile)**, not a new tile. Verified: edit "add a small red ladybug" on leaf `28930729` → new mediaId `8a0613f5`, same workflowId `d90bfb1f`. The request carries the workflowId at `ctx[4]` (`A[1][i][7][4]` and `A[3][4]`), and the base image as ref flag **2**: `A[1][i][2] = [[baseMediaId,null,null,null,2], …extra refs flag 1]`.
- **List versions** (direct RPC): `GetProjectContents` → `d[2].filter(m => m[2] === workflowId)` sorted by `META[0]` (includes upscaled copies). The tile shows the latest (`d[1]` wf `[3][4]`).
- **Choose which version to edit:** `button` "Show history" (text `history Show history`) opens a right-side list of versions (`flow-tile-container` items, newest at the bottom). Click a version → the main image (`img` width > 300) `src` contains that version's mediaId. **Verify it**: some history thumbnails are opaque, so click each item until the main image src matches the wanted mediaId. The selected version becomes the base ref (verified both directions with dry-runs).
```
edit_image(media_id, prompt, model?, aspect?, extra_reference_ids?):
  wf = GetMedia(media_id)[2]; goto /project/<pid>/edit/<wf>; ensureManualMode
  if latest version != media_id: open "Show history", click items until main img src contains media_id
  (optional) set model/aspect in the editor's Settings; attach extras with the editor's "+" (fallback picker)
  type prompt; guard expects ogiZ0b, refs[0] = [media_id,…,2], ctx[4] = wf; click Start
  → returns new version mediaId (R[0][0][0]); same workflowId
```
- `Reuse prompt` (history item / tile menu) puts the old prompt back into the box.
- "Variant" (new tile) vs "edit" (new version of the same tile): for a **new tile** use `generate_image(prompt, reference_media_ids=[id])` from the grid (ref flag 1). For an **iteration** use `edit_image` (ref flag 2).

### 7.3c ⭐ Using images from the user's PC (local files) as references
Local files must first become Flow media (each upload creates a normal grid tile with its own mediaId). Then attach them with §7.3b, exactly like any other image.

**Step 1: upload without the asset picker (top-bar "Add media" menu)**
- Menu verified 2026-10-09: `button[aria-label="Add media menu"]` → items `Upload`, `New collection`, `Create character`, `New scene`.
- `Upload` opens the browser file dialog. Playwright intercepts it with the file chooser, so no dialog appears.
- ⚠️ This exact menu item wasn't clicked during the study. The picker's `Upload media` button sends the same `maseQ` request (verified). Test once; if the menu path fails, use the picker's `Upload media` (§7.5) for the upload only, then close the picker.

```js
async function uploadLocalFiles(page, filePaths) {           // absolute paths on the user's PC
  await ensureProject(page, projectId); await checkLogin(page);
  const results = [];
  // one maseQ response per file
  const respPromises = filePaths.map(() => page.waitForResponse(r => r.url().includes('rpcids=maseQ'), { timeout: 120000 }));
  await page.getByRole('button', { name: 'Add media menu' }).click();
  const [chooser] = await Promise.all([
    page.waitForEvent('filechooser', { timeout: 10000 }),
    page.locator('.cdk-overlay-pane [role=menuitem]').filter({ hasText: /^\s*\S*\s*Upload\s*$/ }).first().click(),
  ]);
  await chooser.setFiles(filePaths);                          // multiple allowed (input has multiple=true)
  for (const p of respPromises) {
    const resp = await p;
    const data = parseRpc(await resp.text(), 'maseQ');        // §2 parser
    if (!data || data.error) throw new Error('Upload failed: ' + JSON.stringify(data?.error));
    results.push({ mediaId: data[0][0], workflowId: data[0][2], width: data[0][6]?.[2]?.[0], height: data[0][6]?.[2]?.[1] });
  }
  await page.keyboard.press('Escape');
  return results;                                             // REAL server mediaIds
}
```
- Upload request (reference): `maseQ` args `[ctx(with reCAPTCHA), "<base64 bytes>", "<mime>", 1, null,null,null,null, "<fileName>", null, "<uuid>", "<uuid>"]`. The title of the new tile = the file name.
- Accepted: `.png .jpg .jpeg .webp .gif .heif .heic` (images) and `.mp4 .m4v .mov .3gp .avi` (videos).
- Verify after upload: `GetMedia(mediaId)` must succeed (not `[5]` NOT_FOUND).
- Order of responses: if uploading several files, map each response to its file by `args[8]` (fileName) of the matching request (`page.waitForRequest` alongside), not by arrival order.

**Step 2: attach with the tile method (§7.3b)**
```js
const uploaded = await uploadLocalFiles(page, ['C:\\Users\\me\\Pictures\\cat.png', 'C:\\Users\\me\\Pictures\\bg.jpg']);
await attachViaTile(page, uploaded.map(u => u.mediaId), 'ingredient');   // or 'start' / 'end'
```
`attachViaTile` already does tag rename → one page reload → search → tile ⋮ `Add to prompt` / `Animate` → chip verification → rename back. Uploaded tiles are found the same way as generated ones.

**Rules**
- ❌ Never attach from the picker in the same session right after uploading. The chip gets a temporary client ID, the server says NOT_FOUND, and the reference is silently ignored (§7.5).
- ✅ Always attach by the **real mediaId from the maseQ response**, and let the Send Guard check that this ID is in the request.
- Cache `{localPath + fileSize + mtime → mediaId}` in the MCP so the same file isn't uploaded twice in a session.
- Tool signature: `generate_image(..., reference_files?: string[])`, `generate_video(..., start_frame_file?, end_frame_file?, reference_files?)` → internally `uploadLocalFiles` then `attachViaTile`. Also expose `upload_media(file_paths[])` → `[{mediaId, title}]` so the model can reuse IDs later.

### 7.4 Fallback attach algorithm via the asset picker (only if the tile method fails)
```
attachById(mediaId, slot):            // slot = 'ingredient' | 'Start' | 'End'
  1. GetMedia(mediaId) must succeed (else error "unknown media").
  2. Make it uniquely findable: wf = GM[2]; original = GM[5][1] (or the workflow title);
     UpdateWorkflow(wf, title = `mcpref-${mediaId.slice(0,8)}`)                    // direct RPC
  3. Open the picker (slot button or +). If it's an uploaded file and the slot is Start/End, click tab "Uploads".
  4. Fill "Search assets" with `mcpref-${id8}` → exactly 1 option → click it → if no new chip, click "Add to prompt".
  5. Wait ≤5 s until the newest chip's img src contains mediaId. If a different ID shows up: click that chip to remove it, retry (max 2).
  6. Rename back to the original title (always, in finally{}).
Before Start: chips' IDs must deep-equal the expected list (order matters for Start/End).
After Start: the Send Guard checks the request again.
```
A no-rename fallback: search by title, click options one by one, keep the one whose chip ID matches, and remove wrong chips.

### 7.5 Uploading local files: root cause of the bug and the fix ✅
Observed: upload via `+` → `Upload media` → the file uploads (`maseQ` → real mediaId `da9dd93a…`), and the picker stays open with the new item on top. Clicking `Add to prompt` **in that same picker session** produced a chip with **no image**. The request then referenced **`fd5f7e69…`, a temporary client ID that `GetMedia` reports as NOT_FOUND**. The generation runs with a broken reference.

Fix:
```
uploadFiles(paths):
  open + picker → [chooser] = Promise.all([page.waitForEvent('filechooser'), click "Upload media"])
  respP = waitForResponse(rpcids=maseQ) (one per file) ; chooser.setFiles(paths)
  mediaId = parse(resp)[0][0]                          // the REAL id
  press Escape (close picker)                          // ← essential: don't attach from this session
  return mediaIds
then attachById(mediaId, slot)                         // fresh picker, verified chip (§7.4)
```
Verified: after reopening the picker, the chip shows `da9dd93a…` and the request carries `[["da9dd93a…",null,null,null,1]]`.
Accepted types: `.png .jpg .jpeg .webp .gif .heif .heic .mp4 .m4v .mov .3gp .avi`; several files at once is OK. `maseQ` args: `[ctx, "<base64>", "<mime>", 1, …, "<fileName>", …]`.

### 7.6 Limits
- Image: 2 references verified on all models; the list is repeated in every output entry (`x2` → 2 entries with the same refs).
- Video Ingredients: the UI accepted 8 chips and the request carried 7. Keep ≤3 for quality and let the guard check the count.
- Veo 3.1 Quality: no Ingredients (silently dropped). Use Fast or Lite or Omni, or Frames mode.
- Frame crop box (`e[4][5]` / `e[5][5]` = `[top,left,bottom,right]` normalized) is auto-center-cropped by the page to the output aspect.

### 7.7 Variants and follow-ups ("use that last image and make a variant")
- **Variant from an image**: `generate_image(prompt, reference_media_ids=[id])`. The prompt describes the change ("same scene at night"), with the same aspect as the source.
- **Same prompt again**: `GM[5][12]` is the original prompt. Generate with it and, optionally, the original as reference. (UI equivalent: tile ⋮ → `Reuse prompt`.)
- **Animate an image**: `generate_video(prompt, start_frame=id)`. (UI: tile ⋮ → `Animate`.)
- **Continue a video**: get a frame from the end of the video (download the MP4, `ffmpeg -sseof -0.1 -i in.mp4 -frames:v 1 last.png`), `upload_media`, then `start_frame`. Or use Extend (Veo only, ❓).

---

## 8. Video post-production ✅

### 8.1 Combine / join / trim / reorder / export: no UI needed ✅
```
job = GjQLTd([[ [idA,null,[],[4]], [idB,null,[1],[3]], [idA,null,[],[2]] ]])[1][0][0]
loop every 2 s: r = IxdSwd([[[job]]]); if r[0] === 3 → mp4 = base64decode(r[4])
```
- Clip = `[mediaId, null, [startSec] or [], [endSec]]`. The output plays the clips **in array order** (reorder or swap = reorder the array). Repeats are allowed.
- Verified: trim `[1]..[3]` → 2.04 s; `[]..[2] + [2]..[4]` → 4.01 s; duck, boat, duck → 12.01 s.
- Any video mediaId works (originals or scene copies). **Mixed aspects are OK; the output uses the first clip's size** (16:9 + 9:16 → 640×360).
- Result: a valid MP4 (`ftypisom`, includes a C2PA manifest), returned inline as base64. 8 s at 360p ≈ 7 MB. Save to disk, and optionally `upload_media` it back into Flow.
- The scene editor's **Download scene** button does exactly this call, with each scene clip's `[mediaId,null,[],[len]]`.

### 8.0 ⭐ The video editor: in-Flow storytelling tools (verified 2026-10-09, no ffmpeg needed)
Open any video: **`/project/<pid>/edit/<videoWorkflowId>`** (wf = `GetMedia(mediaId)[2]`). After a clip is added or extended, the page becomes a scene: **`/project/<pid>/scene/<sceneId>`**.

**Layout / locators**
| Thing | Locator / fact |
|---|---|
| Player | canvas-based (`img[aria-label="Scene video preview"]` / canvas), **no `<video>` element**, so you can't set `currentTime` |
| Time display | leaf text `SS:SS:FF` style, e.g. `00:03:22` = 3 s + 22 frames (24 fps); total next to it (`00:04:00`) |
| Transport | `Play`, `Skip to previous clip`, `Skip to next clip`, `Mute`, `Full screen`, `Disable loop` (aria-labels) |
| Timeline ruler | `div.time-unit > span.time-label` with text `00`,`01`,`02`… at each second; zoom `Zoom in` / `Zoom out` |
| Playhead | `div.playhead` (+ `div.playhead-hit-target`) |
| **Save frame** | `button[aria-label="Save frame"]` → saves the frame under the playhead as an image tile |
| Add clip / Extend | `button[aria-label="Add clip"]` (the `+` after the last clip) → menu `Add clip` · `Extend (<model>)` (Veo clips only) |
| Edit prompt | ProseMirror box "Describe how to edit this video…" (Omni edit, `jIps6`); in extend mode it reads "What happens next?" |
| Clip context menu | right-click a clip on the timeline: Copy · Paste · Save to Project · Download · Delete |
| History | `history Show history` → versions of this video (edits) |
| ⚠️ Keyboard | **Arrow keys switch to the previous/next media**, not frames. Don't send arrow keys. `End` does nothing. |

**Seek to any time (verified: clicking the ruler at ≈3.92 s showed `00:03:22`)**
```js
async function seek(page, tSec) {
  const ticks = await page.$$eval('div.time-unit > span.time-label', els => els.map(e => ({ s: +e.textContent, x: e.getBoundingClientRect().left })));
  const [a, b] = [ticks.find(t => t.s === 0), ticks.find(t => t.s === 1)];
  const pxPerSec = b.x - a.x;
  const ruler = await page.$('div.time-unit'); const y = (await ruler.boundingBox()).y + 5;
  await page.mouse.click(a.x + tSec * pxPerSec, y);
  // verify: read the SS:SS:FF display; nudge ±1 px and re-click until within 1 frame
}
// last frame of a clip of length L: seek(L - 1/24)
```
(If the clip is long and the ruler scrolls, zoom out first, or scroll the timeline into view.)

**Save frame → image (the "continue from last frame" building block) ✅ real test**
- `seek(L - 1/24)` → click `Save frame` → toast "Saving frame…" → "Frame saved · View image".
- Under the hood the page captures the frame and uploads it: `maseQ`, PNG, title **`Saved frame from <video title>`**. Response `R[0][0]` = new **mediaId** (verified `53fddb69…`, **1920×1080** from a 360p clip), `R[0][2]` = new workflowId.
- `waitForResponse(rpcids=maseQ)` to get the ID, then use it as `start_frame` (tile ⋮ `Animate`, §7.3b). Being a real server ID, it doesn't have the temporary-ID problem.

**Extend (Veo clips only) ✅ captured (dry-run) + verified UI**
- `Add clip` → `Extend (Veo 3.1 - Lite)` (label names the clip's model; disabled for Omni clips) → timeline shows an extra 8 s slot "Prompt to extend", prompt placeholder **"What happens next?"**, chip `button[aria-label="Exit extend mode"]`.
- Submitting **first calls `CreateScene`** (the video becomes a scene, URL → `/scene/<sceneId>`), then **`fZytfe` BatchAsyncGenerateVideoExtendVideo**:
  `A[0][0][0] = [null, sourceMediaId, 169, 192]` (the last ~1 s of an 8 s clip as context, frames at 24 fps) · prompt `A[0][0][1][2][0][0][0]` · key **`veo_3_1_extension_lite`** (Fast/Quality: `veo_3_1_extend_fast_*` / `veo_3_1_extend_*` from GetModels) · aspect `A[0][0][3]` · sceneId `A[0][0][5][0]` and `A[2][3] = [sceneId, 1]`.
- Result = a new 8 s clip appended in the scene, polled with `jwpduf` like any video. Chain extends repeatedly for long continuous shots. Export the scene with `combine_videos` (§8.1) or the scene's `Download scene`.
- Cost ≈ one Veo video of that tier (Lite 10). Guard expects `fZytfe` + the source mediaId.

**Edit / restyle a clip** (Omni): type in "Describe how to edit this video…" → `jIps6` with `[null, mediaId, startFrame, endFrame]`. To edit only part of a clip, select or trim the range on the timeline first (⚠️ range selection UI not verified).

**Add an existing clip after this one**: `Add clip` → `Add clip` → video picker → `Add media` → `CreateScene([wf1, wf2])` (§8.2).

### 8.1b ⭐ Story pipeline: chaining clips (every building block verified)
**Use the in-Flow route (§8.0) for frames: `Save frame` replaces ffmpeg.** The ffmpeg lines below are an optional offline fallback only.

**Facts that shape the pipeline:**
- A **video** added to the prompt (tile ⋮ → `Add to prompt` on a video) does **not** continue it. It becomes a **video edit / restyle** of the whole clip: RPC `jIps6`, key `abra_edit`, frames 0..end (24 fps), count = number of entries. Use it only for "restyle or change this clip".
- **Extend** (`Extend (Veo 3.1 only)` in the scene timeline `+` menu) continues a clip but needs a **Veo** clip (❓ not captured).
- **Start frame (`Animate`) and start+end (`Animate` + `Add to prompt`)** work on every model and are the reliable way to chain.

**Pattern A: Continue from the last frame (any model)**
```
clip1 = generate_video(prompt1, ...)                               // or any existing video
img  = save_frame(clip1, at='end')                                 // §8.0: /edit/<wf> → seek(L-1/24) → "Save frame" → maseQ → real mediaId
clip2 = generate_video(prompt2, start_frame=img.mediaId, ...)      // attachViaTile 'start' (= tile ⋮ Animate)
   // Veo clips: or extend_video(clip1, prompt2) (§8.0 Extend) for a seamless continuation in one step
... repeat ...
final = combine_videos([clip1, clip2, ...])                        // §8.1 GjQLTd, trim with [start],[end] (e.g. drop clip2's first 0.1 s)
```
Alternative to ffmpeg: in the clip editor `button[aria-label="Save frame"]` saves the *current* frame as an image tile ("Saved frame from …"), but you'd have to seek the player to the end first, so ffmpeg is more reliable.

**Pattern B: Keyframe storyboard (best consistency)**
```
1. Characters/styles: generate or upload reference images (R1, R2…)
2. Keyframes: K1..Kn = generate_image(scene_i_prompt, reference_media_ids=[R1,R2], aspect='16:9')
   (fix a keyframe with edit_image(Ki, "...") → new version in the same tile, §7.3d)
3. Segments:  Si = generate_video(motion_prompt_i, start_frame=Ki, end_frame=K(i+1))   // Animate(Ki) then Add to prompt(K(i+1)) → nprQif
   (or start_frame only for open endings)
4. Order/trim: combine_videos([S1, S2, …]) with trims; reorder = reorder the array
5. Optional: upscale_video per segment before combining; video_to_gif for previews
```
- Keep the same aspect for all keyframes and segments (combined output = first clip's size).
- Durations: Omni 4/6/8/10 s per segment (360p cheap drafts → 720p finals); Veo fixed 8 s.
- Credits: estimate `segments × per-video cost` up front and confirm with the user if it's over `max_credits`.

**Ordering, swapping and re-cutting** never needs the UI: `combine_videos` takes clips in array order, with per-clip `[startSec]`/`[endSec]` trims and repeats allowed. To keep the arrangement inside Flow too, `create_scene(project, [workflowIds in order])` (§8.2).

**Suggested high-level tools for the model:**
| Tool | Does |
|---|---|
| `save_frame(video_media_id, at_sec \| 'end')` | §8.0 seek + Save frame → `{mediaId}` of the new image |
| `extend_video(video_media_id, prompt)` | §8.0 Extend (Veo clips only) → new clip appended in a scene; returns `{sceneId, mediaId}` |
| `continue_video(media_id, prompt, model, duration, resolution)` | Pattern A in one step: Veo clip → `extend_video`; otherwise `save_frame('end')` → `start_frame` → generate |
| `storyboard_to_video(keyframe_ids[], motion_prompts[], model, duration, resolution, combine=true)` | Pattern B steps 3–4 |
| `edit_image(media_id, prompt)` | §7.3d new version of a keyframe |
| `restyle_video(media_id, prompt)` | video → `Add to prompt` → `jIps6` (Omni edit) |
| `combine_videos(clips[], out_path)` | §8.1 |

### 8.2 Scenes (Flow's own timeline, if the user wants it saved in Flow)
- Video tile ⋮ → `Add to scene ▸`, or the editor timeline `button[aria-label="Add clip"]` → menu `Add clip` / `Extend (Veo 3.1 only)` → video picker → `Add media`. This calls `CreateScene` (`rqZuUc`), args `["projects/<pid>",[wfId…],null,null,aspect]`, then `GetSceneWorkflows` and `UpdateSceneWorkflows`.
- Scene URL `/project/<pid>/scene/<sceneId>`. Toolbar: `Toggle aspect ratio`, `Download scene`, `Move to trash`, `Show history`, `Done editing scene`.
- Clip context menu (right-click a clip on the timeline): Copy, Paste, Save to Project, Download, Delete.
- Clip record: `[[sceneWfId,null,null,[title,…,mediaId,…],pid], sceneId, [order(null=0), [lenSec], [], [lenSec], [1]]]`.
- Drag-to-reorder on the timeline didn't work with synthetic drags. **To build a new order, call `CreateScene` with the workflow IDs in the new order**, or just use §8.1 for the final file.

### 8.3 Edit a video with a prompt ✅(captured) — `jIps6`, page-submitted
- Open `/project/<pid>/edit/<workflowId>` (single-clip editor), type into the prompt `Describe how to edit this video…` (model chip fixed `Omni 1.1 Flash`), and click Start.
- Args: `e[0] = [null, mediaId, startFrame, endFrame]` at **24 fps** (a whole 4 s clip = 0..96), prompt at `e[1][2][0][0][0]`, key `abra_edit[_360p]`, aspect `e[3]`, `e[4][1]` = workflowId, cost `e[12][0]`.

### 8.4 Extend ❓
Timeline `+` → `Extend (Veo 3.1 only)`, disabled for Omni clips. RPC `fZytfe`, keys `veo_3_1_extend_*`. Capture once with a Veo clip, using a dry-run (§10).

### 8.5 Upscale video ✅(captured) — `p0UkFb`, page-submitted
- Video tile ⋮ → `Download ▸` → `270p Animated GIF` · `360p Original size` · `720p Upscaled` (for 360p Omni; higher options appear for 720p videos).
- Args: `[[[[null,mediaId],null,aspect,null,[null,wfId,null,null,uuid],null,1,…, "omni_upsampler_360p"@31]], ctx]`. Async: poll `jwpduf` (⚠️), then GetMedia.
- The page also triggers a browser download. Handle `page.on('download')` (save to the requested dir or `download.cancel()`).

### 8.6 GIF ✅ — direct: `QZasKb [null, videoMediaId]` → base64 GIF (1.3 MB for 4 s).

### 8.7 Save a frame as an image ✅(captured)
Editor `button[aria-label="Save frame"]` grabs the current frame in the page and uploads it with `maseQ` (PNG, title `Saved frame from <title>`). Headless alternative: download the MP4, use `ffmpeg -ss <t> -i in.mp4 -frames:v 1 f.png`, then `upload_media`.

---

## 9. Image post-production ✅

### 9.1 Upscale 2K / 4K — `SPrCad`, page-submitted
- Tile ⋮ → `Download ▸` → `1K Original size` · `2K Upscaled` · `4K Upscaled` (4K was **disabled** for Nano Banana 2.x images; ⚠️ likely Pro-only).
- Args `[mediaId, 1(=2K) | ⚠️2(=4K), ctx]`. It takes about 40 s. **Response `R[0]` = new media record (the upscaled copy, saved in the project, with the parent ID at `R[0][5][6][3][0][0][2]`), `R[1]` = base64 JPEG** (2K ≈ 2.8 MB).
- The page then auto-downloads `<Title>_2K_<timestamp>.jpg`. Intercept with `page.on('download')`.
- To find the tile: grid tiles are `flow-grid-tile-container[aria-label="<title>"]`. Use the §7.4 unique-rename trick, hover the tile, then `button[aria-label="More options"]`.

### 9.2 Other tile actions (menu `button[aria-label="More options"]` on hover)
Image: Favorite · Reuse prompt · Animate · Add to prompt · Download ▸ · Copy · Rename · Share · Set project cover · Flag output · Move to trash.
Video: Favorite · Reuse prompt · Add to scene ▸ · Add to prompt · Download ▸ · Copy · Rename · Share · Publish to YouTube · Set project cover · Flag output · Move to trash.

---

## 10. Dry-run mode (for development and capturing ❓ items) ✅
Use the Send Guard with "always abort" to capture any generate request at zero cost:
```js
expectation = { capture: true, resolve: x => console.log(x.rpc, JSON.stringify(x.args)), reject(){} };   // validate() returns ['dry-run'] → abort
```
This is how the §4.2 matrix was built (30+ requests, 0 credits).

---

## 11. Tools to expose

| Tool | How |
|---|---|
| `get_credits()` | `nzlxg` |
| `list_models()` | `HTrJv` + §4 tables (name, modes supported, durations, cost) |
| `list_projects(limit, page_token?)` / `create_project(title?)` / `rename_project(id,title)` / `open_project(id)` | `UpteDb` / `jHPbke` / `o8DA4` / navigation |
| `list_media(project_id, type?, include_trashed?)` | `Zzl0ze` → merge `d[1]` titles with `d[2]` |
| `search_media(project_id, query)` | `list_media` + filter on title/prompt |
| `get_media(media_id)` / `download_media(ids, dir)` | `as29s` → signed URL → HTTP GET |
| `get_last_media(project_id, type='image'\|'video', n=1)` | session history first, then `list_media` sorted by created |
| `rename_media(media_id, title)` | `mYWVGd` |
| `trash_media(ids)` / `restore_media(ids)` | `pGCYOe` archived true/false (confirm with the user before trashing) |
| `upload_media(paths[], project_id?)` | §7.5 → mediaIds |
| `generate_image(prompt, model, aspect, count, reference_media_ids?, reference_files?, project_id?, download_dir?)` | §6 + §7.4 + guard → parse `ogiZ0b` (`R[0][i]`: mediaId `[0]`, url `[6][0][13]`, size `[6][2]`) |
| `generate_video(prompt, model, aspect, duration?, resolution?, count, start_frame?, end_frame?, reference_media_ids?, reference_files?, max_credits?, wait=true, project_id?, download_dir?)` | §6 + §7 + guard → mediaIds from `R[3][i][0]` → poll `jwpduf` → `GM[7][0][8]` |
| `video_status(ids)` / `list_pending(project_id)` | `jwpduf` / `d[2]` with status 6 or 2 |
| `combine_videos(clips:[{media_id,start?,end?}], out_path, upload_back?)` | §8.1 |
| `video_to_gif(media_id, out_path)` | `QZasKb` |
| `edit_video(media_id, prompt, start_s?, end_s?)` | §8.3 (+ guard) |
| `upscale_video(media_id, target)` / `upscale_image(media_id, '2K'\|'4K', out_path?)` | §8.5 / §9.1 (+ guard) |
| `extract_frame(media_id, at_s, upload=true)` | ffmpeg on the downloaded MP4 → `upload_media` |
| `create_scene(project_id, media_ids[], aspect)` | `rqZuUc` with workflowIds |

Validation in the tool layer (before touching the page):
- `start_frame`/`end_frame` vs `reference_*` are exclusive; `end_frame` needs `start_frame`.
- Veo Quality + references → error with a suggestion.
- Veo + duration/resolution → error (fixed 8 s, 720p).
- Omni duration ∈ {4,6,8,10}; image aspect ∈ 5 values; video aspect ∈ {16:9, 9:16}; count 1–4.

---

## 12. Still not captured ❓
Extend (`fZytfe`, needs a Veo clip) · Veo Fast 16:9 start / start+end / ingredients keys (pattern-inferred) · 4K image upscale arg · 1080p/4K video upscale options · failed-video status value · DeleteProject / BatchDeleteScenes / CancelMediaGeneration args · UpdateSceneWorkflows full shape. To capture any of them, do the action once in the UI with the guard in dry-run mode (§10).

## 13. Robustness rules
- The guard is mandatory for every generation. Never return success unless the guard approved the request **and** the response parsed.
- Before submit: settings verified (trigger text plus popover state), chips verified by mediaId, every ref ID exists (GetMedia), and cost ≤ `max_credits`.
- On guard abort: delete the "Failed" tile, re-attach references (chips were cleared), retry once, then return a clear error.
- Never cache `bl`, `at`, `f.sid` or RPC IDs across reloads. Re-inject helpers after `goto`.
- UI selectors: ARIA labels, roles and visible text only. Never classes or pixel coordinates.
- Handle `page.on('download')` for upscales and the scene download.
- ⚠️ These are private, undocumented endpoints. They can break on any Flow deploy, and automated use may violate Google's terms. Use at your own risk on your own account.
