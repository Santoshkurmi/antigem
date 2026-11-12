# Background tasks

Like a native app, a mini app can do work while it is closed: check something online, sync, then post a
notification. You write a background script, declare it in the manifest, and register named tasks with a schedule.
When a task is due, AntiGem loads the script in an invisible page with the full SDK (no screen), calls your handler,
and closes the page when the handler's promise settles. Runs are limited to keep the phone's battery safe: at most 48
per app per day, 60 seconds each, one at a time across all apps. Background tasks need the manifest's `background`
script and the `background` permission.

For a notification at a fixed time with no code to run, use a reminder instead
([`AntiGem.notify.schedule`](notifications.md#reminders)): it needs no background permission and has no daily limit.

## Setup

1. Declare the script and the permission in `manifest.json`:

   ```json
   {
     "background": "background.js",
     "permissions": { "background": true, "network": { "hosts": ["api.example.com"] }, "notifications": true }
   }
   ```

   `"background"` is a path relative to the app folder, or `{"script": "bg.js", "type": "module"}` to load it as an ES
   module. Paths with `..` or hidden segments are ignored.

2. In `background.js`, register a handler for each task, at the top level of the script:

   ```js
   AntiGem.background.on('sync', async (task) => {
     const items = await (await fetch('https://api.example.com/new')).json();
     if (items.length) await AntiGem.notify.show({ id: 'sync', title: `${items.length} new items` });
   });
   ```

3. From the app, ask for the permission and register the schedule. Registering on every start is fine: an existing
   task keeps its timing.

   ```js
   if (await AntiGem.permissions.request('background') === 'granted') {
     await AntiGem.background.register('sync', { every: '1h', network: 'any' });
   }
   ```

## API

| Member | Returns | Permission | Meaning |
|---|---|---|---|
| `register(name, options)` | `Promise<{name, schedule, next}>` | `background` | Creates or updates a task. `schedule` is a description (`'every 1h'`, `'once in 10m'`, `'daily at 07:30'`), `next` the expected next run (epoch ms) |
| `list()` | `Promise<task[]>` | none | `[{name, schedule, next, lastRun?}]`; `lastRun` is `{at, ms, status: 'ok' \| 'error' \| 'skipped', detail, reason}` |
| `cancel(name?)` | `Promise<void>` | none | Removes a task; without a name, all of the app's tasks |
| `run(name)` | `Promise<void>` | `background` | Runs the handler for `name` now (reason `'manual'`). Resolves once the run is queued, not when it finishes |
| `on(name, fn)` | `undefined` | | In the background script: the handler for task `name`. `'*'` handles any task without its own handler. One handler per name (a second `on` replaces the first) |
| `isBackground` | boolean | | `true` in the background page, `false` in normal pages |

Task names are 1–64 letters, digits, `.`, `_` or `-`.

## Schedules

Pass exactly one of `every`, `at` or `delay`:

| Option | Type | Meaning |
|---|---|---|
| `every` | duration (`'15m'`, `'1h'`, `'1d'`) or ms | Periodic, at least every 15 minutes. The first run is one full period after registering. Android runs it roughly on time and may batch it with other work |
| `at` | `Date`, epoch ms, `'HH:MM'` or `'YYYY-MM-DDTHH:MM'` | An exact time, with an optional `repeat` (`'daily'`, `'weekly'`, `'weekdays'`, `'hourly'`, an interval of 15 minutes or more, `{days, time}`, plus `until` and `count`). Same rules as [reminders](notifications.md#reminders) |
| `delay` | duration or ms | Once, this long from now. Once that run has finished (or was skipped), the task leaves `list()` and no longer counts toward the task limit |

Conditions, for `every` and `delay` only (an `at` task runs at its time regardless):

| Option | Values | Default | Meaning |
|---|---|---|---|
| `network` | `'any'` (or `'connected'`), `'unmetered'` (or `'wifi'`) | none | Wait until there is a connection / an unmetered one |
| `charging` | boolean | `false` | Wait until the phone is charging |
| `batteryNotLow` | boolean | `false` | Wait while the battery is low |

```js
await AntiGem.background.register('rain-check', { every: '1h', network: 'any' });
await AntiGem.background.register('digest', { at: '07:30', repeat: 'daily' });
await AntiGem.background.register('cleanup', { delay: '10m' });
await AntiGem.background.register('backup', { every: '1d', network: 'unmetered', charging: true });
```

Registering a task name again replaces its options. Re-registering an `every` task with the same kind of schedule
keeps its timing; switching between `every`, `at` and `delay` starts a new schedule. A `delay` task registered again
starts its delay over.

## The handler

```js
AntiGem.background.on('hn-digest', async (task) => {
  // task = {name, reason, runId, data}
  const [topId] = await (await fetch('https://hacker-news.firebaseio.com/v0/topstories.json')).json();
  if (localStorage.getItem('hn.top') === String(topId)) return;
  localStorage.setItem('hn.top', String(topId));
  await AntiGem.notify.show({ id: 'hn', title: 'New #1 on Hacker News' });
  console.log(`hn-digest (${task.reason}): ${topId}`);
});
```

| Field | Meaning |
|---|---|
| `name` | The task's name |
| `reason` | `'schedule'` (an `every` or `delay` task), `'alarm'` (an `at` task), `'manual'` (`run()` or "Run now" in App info), `'notification'` (a notification button, see below) |
| `runId` | A unique id for this run |
| `data` | For `'notification'` runs, the notification event; otherwise `null` |

- **The handler's promise is the run.** Do all the work, and `await` it, before the handler returns. When the promise
  resolves the run is recorded as `ok`; a rejection or a thrown error records `error` with its message. Then the page
  is closed.
- Register handlers at the top level of the background script, synchronously. AntiGem looks for the handler when the
  page has loaded; a task with no handler fails with "No handler".
- The script may load more code: `importScripts` is not available (it's a page), but `<script>` tags added to
  `document`, `import()` and, with `"type": "module"`, static imports work.
- Timers (`setTimeout`) may be slowed in the invisible page. Await promises instead of waiting on timers.

## Notification buttons

A notification button with `open: false`, or an inline reply, pressed while the app is not on screen, runs the
background script with the task name `notification` (when the app has a background script and the `background`
permission). The event is in `task.data`:

```js
AntiGem.background.on('notification', async (task) => {
  const e = task.data;                     // {id, action, reply?}
  if (e.id === 'timer' && e.action === 'pause') await pauseTimerFromStoredState();
  if (e.reply) localStorage.setItem('lastReply', e.reply);
});
```

These runs use the same limits as other runs, except that they also run in battery saver. Without a background script
or permission, the event waits for the app to open instead. If the run is skipped (the daily limit is reached,
Developer mode is off, a required permission is missing, the `background` permission was turned off, …), the event is
not lost either: the app gets it as a `notification` event with `queued: true` when it is next opened. See
[notifications](notifications.md#events).

## What works in the background page

The background page is a normal page of the app that is never shown. It has the same origin, the same storage and the
same permissions as the app's pages.

| Works | Doesn't |
|---|---|
| `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource` | Dialogs: `AntiGem.ui.alert/confirm/prompt/sheet/pickDate/pickTime/viewMedia` reject with `NotSupportedError` |
| `localStorage`, `sessionStorage`, `AntiGem.storage`, IndexedDB | `AntiGem.fs.pick`, `AntiGem.fs.pickFolder` (`NotSupportedError`) |
| `AntiGem.fs` (read and write), `AntiGem.cookies` | `AntiGem.nav.push`, `AntiGem.device.share`, `AntiGem.device.openUrl`, `AntiGem.intent.send` (`NotSupportedError`) |
| `AntiGem.notify` (show, schedule, …) | `AntiGem.permissions.request` shows no prompt; it returns the current state |
| `AntiGem.mcp`, `AntiGem.thread`, Web Workers | `AntiGem.ui.toast` and `AntiGem.ui.statusBar` do nothing |
| `AntiGem.background.register/list/cancel/run` | `alert()` returns at once, `confirm()` returns `false` and `prompt()` returns `null`, with a warning in the console |
| `AntiGem.device.copy/info/status`, `console.*` | `resume`, `pause`, `network`, `battery` events are not sent |

`localStorage` and `AntiGem.storage` changes made here reach open pages of the app as `storage` events, as usual.
`AntiGem.page.background` and `AntiGem.background.isBackground` are `true`.

## Limits

| Limit | Value |
|---|---|
| Tasks per app | 10 (`QuotaExceededError`). A `delay` task stops counting once its run is done |
| Shortest `every` (and repeat interval) | 15 minutes (`NotAllowedError`) |
| Runs per app | 48 in any 24 hours. Successful and failed runs count; skipped ones don't. Further runs are skipped |
| Time per run | 60 seconds, then the run is stopped and recorded as an error ("Stopped after 60 s") |
| Concurrency | One background run at a time across all mini apps; others wait their turn |
| Battery saver | Scheduled (`'schedule'`) and exact (`'alarm'`) runs are skipped. `'manual'` and `'notification'` runs still happen |
| First run of an `every` task | One full period after it is registered |
| Network failures | A run that fails with a network error (cannot resolve or connect, timeout) on its first attempt is retried once, about 30 seconds later |
| Missed `at` runs | After a reboot, an `at` run that is at most 1 hour late still runs; older ones move on to their next time |

A run is also skipped (recorded with `status: 'skipped'` and a reason) when the `background` permission is not
granted, a required permission is missing, the app is a development app and Developer mode is off, or an installed
app's files no longer match its signature. A skipped `notification` run keeps its event: it is delivered to the app,
with `queued: true`, when the app is next opened.

Android may delay or skip background work while AntiGem's battery use is optimized (App info shows a warning with an
"Allow" button), and exact times need AntiGem's "Alarms & reminders" access on Android 12+. Force-stopping AntiGem
stops all scheduled work until AntiGem is opened again.

## Seeing what happened

- `AntiGem.background.list()` returns each task with its `lastRun`.
- App info → Background lists the tasks, their last run (time, status, duration, error), how many of the 48 daily
  runs were used, and a "Run now" button.
- Every run writes a line to the app's console log, like `Background task "sync" (schedule) finished in 812 ms`
  (or `failed in … ms: <error>`), and everything the script logs goes there too. See
  [troubleshooting](troubleshooting.md).

## Errors

| Error | When |
|---|---|
| `NotAllowedError` | `register` or `run` without the `background` permission; an interval under 15 minutes |
| `InvalidStateError` | `register` or `run` when the manifest has no `background` script |
| `TypeError` | Bad task name; none of `every`, `at`, `delay`; a bad duration or time; an `at` in the past |
| `QuotaExceededError` | More than 10 tasks |
