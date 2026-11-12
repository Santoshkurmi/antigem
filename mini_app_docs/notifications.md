# Notifications

`AntiGem.notify` posts real Android notifications, with what an installed app gets: the app's own channels in
Android settings, accent colors, big text / picture / inbox / chat styles, progress bars, countdowns and stopwatches
the system ticks by itself, ongoing notifications, up to three buttons and an inline reply box. Taps, buttons, replies
and swipe-aways come back to the app as `notification` events. Reminders (`AntiGem.notify.schedule`) are kept
natively: they fire with the app closed and survive restarts and reboots. Showing, scheduling and creating channels
need the `notifications` permission.

```json
"permissions": { "notifications": true }
```

```js
if (await AntiGem.permissions.request('notifications') === 'granted') {
  await AntiGem.notify.show({ id: 'hello', title: 'Hello', body: 'From a mini app' });
}
```

## API

| Method | Returns | Permission | Meaning |
|---|---|---|---|
| `show(options)` | `Promise<{id}>` | `notifications` | Shows a notification, or updates the one with the same `id` |
| `channel(options)` | `Promise<{id, importance}>` | `notifications` | Creates (or renames) one of the app's channels |
| `deleteChannel(id)` | `Promise<void>` | none | Deletes one of the app's channels |
| `active()` | `Promise<[{id, title, body, ongoing, posted}]>` | none | The app's notifications on screen now (`posted` is epoch ms) |
| `dismiss(id?)` | `Promise<void>` | none | Removes a shown notification; without `id`, all of the app's. Reminders stay scheduled |
| `schedule(options)` | `Promise<{id, at, repeat}>` | `notifications` | Schedules a reminder (see [Reminders](#reminders)) |
| `list()` | `Promise<[{id, title, body, repeat, at}]>` | none | The app's reminders |
| `cancel(id)` | `Promise<void>` | none | Cancels a reminder and removes its shown notification |
| `cancelAll()` | `Promise<void>` | none | Cancels every reminder and removes every shown notification of the app |

## show() options

Every option is optional. The same `id` updates a notification in place (no new sound unless you want one).

| Option | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | a new random id | Identifies the notification (max 100 characters). `tag` is accepted as another name for it |
| `title` | string | `''` | Up to 200 characters |
| `body` | string | `''` | Up to 4000 characters. Longer than 40 characters: shown expandable (big text) |
| `subtext` | string | the app name | Small text in the header |
| `channel` | string | see below | The id of a channel made with `channel()` |
| `ongoing` | boolean | `false` | Stays until the app removes it (on Android 14+ the user can still swipe it away). Without `channel`, it goes to AntiGem's "Mini apps (running)" channel: no sound, no vibration |
| `silent` | boolean | `false` | No sound or vibration. Without `channel`, it goes to the "Mini apps (silent)" channel |
| `onlyAlertOnce` | boolean | same as `ongoing` | Sound and vibration only the first time, not on updates |
| `autoCancel` | boolean | `!ongoing` | Removed when tapped |
| `priority` | `'min'` \| `'low'` \| `'default'` \| `'high'` \| `'max'` | `'low'` when `ongoing` or `silent`, else `'high'` | For Android versions without channels; on newer ones the channel decides |
| `color` | string | the manifest's `theme_color` | Accent of the icon, app name and buttons: `#rgb`, `#rrggbb`, `#rrggbbaa` or a basic color name like `red` (not `rgb()` or `var()`) |
| `colorized` | boolean | `false` | Asks Android to fill the whole notification with `color` (only some kinds of notification are allowed to) |
| `icon` | string | the app icon | Large icon: a file of the app (`img/x.png`; for an installed app, only if it matches the signature), a `data/…`, `app:<id>/…`, `~/…` or `/…` file the app may read, or an `http(s)` URL it may reach (fetched as the app: its hosts list, cookies and `localhost` rules) |
| `smallIcon` | string | AntiGem's icon | Status bar icon: a white-on-transparent PNG (same sources as `icon`) |
| `image` | string | none | Big picture (same sources as `icon`). Takes priority over `lines` and `messages` |
| `lines` | string[] | none | Inbox style, up to 7 lines; `body` becomes the summary |
| `messages` | `[{from?, text, time?}]` | none | Chat style. `from` empty = the user; `time` is a `Date` or epoch ms (default now); `title` is the conversation name |
| `me` | string | `'You'` | The user's name in chat style |
| `style` | `'text'` \| `'inbox'` \| `'messages'` \| `'picture'` | from the content | Forces a style (`'picture'` still needs `image`) |
| `progress` | `{value, max?}` or `{indeterminate: true}` | none | Progress bar; `max` defaults to 100 |
| `countdown` | `Date` or epoch ms | none | A large timer counting down to that moment, ticked by the system. The notification goes away 1.5 s after it ends (unless `timeout` is set) |
| `chronometer` | `Date` or epoch ms | none | A large stopwatch counting up from that moment |
| `display` | string | none | A large static value in the same layout, e.g. a paused timer's `'12:34'` |
| `category` | string | none | Android category: `alarm`, `reminder`, `progress`, `status`, `msg`, `event`, `call`, `email`, … |
| `visibility` | `'public'` \| `'private'` \| `'secret'` | `'private'` | Lock screen: show everything, hide the text, or hide the notification |
| `group` | string | the notification's own | Bundles the app's notifications with the same group. Without it, each stands alone |
| `groupSummary` | boolean | `false` | This notification is the group's summary |
| `number` | number | none | Badge count |
| `timeout` | ms | none | Removes the notification after this long |
| `actions` | array | none | Up to 3 buttons, see below |
| `tapAction` | string | `'tap'` | The `action` reported when the notification itself is tapped |

`countdown`, `chronometer` and `display` use a large-time layout (like Clock's stopwatch) and replace the other
styles; title, body and buttons are still shown.

### Buttons

```js
actions: [
  { id: 'pause', title: 'Pause', open: false },
  { id: 'reply', title: 'Reply', reply: { placeholder: 'Message' } },
  { id: 'open', title: 'Open' },
]
```

| Field | Default | Meaning |
|---|---|---|
| `id` | `'action0'`, `'action1'`, … | The `action` value in the event |
| `title` | the `id` | Button label |
| `open` | `true` | `true`: tapping opens the app. `false`: the app is not opened; the event goes to the app if it is on screen, else to its background script, else it waits (see [Events](#events)) |
| `reply` | none | `true` or `{placeholder}`: the button opens an inline text box. The app is not opened; the text arrives as `event.reply` |

After an inline reply, the notification is posted again silently (Android shows a spinner until then); in chat style
the reply is added to the conversation.

## Channels

Without `channel`, notifications use one of AntiGem's three shared channels: "Mini apps" (pops up, with sound),
"Mini apps (running)" for `ongoing` (no sound or vibration, main part of the shade) and "Mini apps (silent)" for
`silent` (the Silent section). For control per kind of notification, make your own channels. They appear in Android's
notification settings under "Mini app: <name>", where the user can tune each one.

```js
await AntiGem.notify.channel({ id: 'timer', name: 'Running timer', importance: 'default', sound: false, vibrate: false });
await AntiGem.notify.channel({ id: 'messages', name: 'Messages', importance: 'high', vibrate: [0, 120, 80, 120], lights: '#6750A4' });
await AntiGem.notify.show({ id: 'timer', channel: 'timer', ongoing: true, title: 'Focus', countdown: Date.now() + 25 * 60_000 });
```

| Option | Type | Default | Meaning |
|---|---|---|---|
| `id` | string, required | | Letters, digits, `.`, `_`, `-`; 1–40 characters |
| `name` | string | the id | Shown in Android settings (max 60) |
| `description` | string | none | Shown in Android settings (max 200) |
| `importance` | `'min'` \| `'low'` \| `'default'` \| `'high'` \| `'max'` | `'default'` | `max` is the same as `high`. `low` and `min` go to the Silent section: for a running thing in the main part of the shade use `'default'` with `sound: false` |
| `sound` | boolean | `true` | `false` = no sound |
| `vibrate` | boolean or ms pattern | not set | `false`, `true`, or `[wait, buzz, wait, buzz…]` |
| `lights` | color | none | LED color, where the phone has one |
| `badge` | boolean | `true` | Show a launcher badge dot |

Android keeps the user's choices: once a channel exists, the app can still rename it, but its importance, sound and
vibration can't be changed. To change them, use a new channel id (and `deleteChannel` the old one).

## Events

```js
AntiGem.on('notification', (e) => {
  // e = {id, action, reply?, queued?}
  if (e.id === 'timer' && e.action === 'pause') pauseTimer();
  if (e.action === 'reply') sendMessage(e.reply);
});
```

| Field | Meaning |
|---|---|
| `id` | The notification's id |
| `action` | `'tap'` (or your `tapAction`), `'dismiss'` (the user swiped it away or cleared it), or a button `id` |
| `reply` | The text typed into an inline reply |
| `queued` | `true` when it happened while the app was not on screen and was delivered later |

Where an event goes:

1. **The app is on screen:** every open page of the app gets it right away.
2. **Otherwise, for a button with `open: false`, an inline reply, or a swipe-away:** if the app has a `background`
   script and the `background` permission, a button or reply runs the background task `notification` with the event as
   `task.data` (see [background](background.md#notification-buttons)). A swipe-away never starts the background script.
   If that run is skipped (the daily limit is reached, Developer mode is off, a required permission is missing, the
   `background` permission was turned off, …), the event goes on to step 3 instead of being lost.
3. **Otherwise** (and always for a tap or an `open: true` button, which open the app), the event waits and is delivered
   with `queued: true` to every page once the app is on screen. At most 20 events (notifications and intents
   together) wait per app; older ones are dropped.

Register the listener when the page starts, so queued events find it.

## Reminders

`AntiGem.notify.schedule()` keeps a notification to show later, natively. No app code runs when it fires: the
notification is shown exactly as described by its options. Reminders survive app restarts, AntiGem restarts and
reboots, and are re-planned after time or time-zone changes. Scheduling the same `id` again replaces the reminder.

```js
// in 10 minutes
await AntiGem.notify.schedule({ id: 'tea', title: 'Tea is ready', delay: '10m' });

// every day at 07:00
await AntiGem.notify.schedule({ id: 'morning', title: 'Good morning', body: 'Plan your day', at: '07:00', repeat: 'daily' });

// Monday and Thursday at 08:00, until the end of the year
await AntiGem.notify.schedule({
  id: 'gym', title: 'Gym', at: '08:00',
  repeat: { days: ['mon', 'thu'], until: new Date('2026-12-31T23:59') },
});

// a one-off at a local date and time, with a button and a color
await AntiGem.notify.schedule({
  id: 'party', title: 'Party', body: 'Leave in 30 minutes', at: '2026-12-24T18:00',
  color: '#C62828', actions: [{ id: 'snooze', title: 'Snooze', open: false }],
});
```

`schedule` takes every `show()` option (title, body, color, actions, style, channel…) plus:

| Option | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | a new random id | Same id = the reminder is replaced (`tag` also works) |
| `at` | `Date`, epoch ms, `'HH:MM'`, or `'YYYY-MM-DD'` / `'YYYY-MM-DDTHH:MM[:SS]'` | | When it first fires. `'HH:MM'` = the next time that clock time comes. Date strings are local time unless they end in `Z` or an offset like `+05:30` |
| `delay` | ms or a duration string | | Fires this long from now (used when there is no `at`) |
| `repeat` | see below | none | Repeat rule |
| `until` | `Date`, epoch ms or a date string | none | No repeats after this moment (may also go inside a `repeat` object) |
| `count` | number | none | Fire this many times in total, then stop. May also go inside a `repeat` object: a `count` there wins over the top-level one |
| `exact` | boolean | `true` | `true`: on time even in Doze. `false`: Android may batch it with other alarms |

Either `at` or `delay` is needed, except with a repeat: `repeat` alone starts one interval from now (`'2h'`, `{every}`)
or at the next matching time (`'daily'` and day rules use the current clock time unless `repeat.time` is given).

`repeat` values:

| Value | Meaning |
|---|---|
| `'daily'` | Every day at the time of `at` |
| `'weekly'` | Every week, same weekday and time as `at` |
| `'weekdays'` | Monday to Friday at the time of `at` |
| `'hourly'` | Every hour |
| a duration (`'30m'`, `'2h'`, `'1d'`) or a number of ms | Every interval |
| `{every, unit?}` | Every interval. `every` is a duration string, or a number with `unit` `'minute'`, `'hour'`, `'day'` or `'week'` (ms without `unit`) |
| `{days, time?}` | On these weekdays: names (`'mon'`, `'tuesday'`: the first three letters count) or numbers `0` (Sunday) to `6`. `time: 'HH:MM'` overrides the time of `at` |

Duration strings are a number and a unit: `ms`, `s`/`sec`, `m`/`min`, `h`/`hr`/`hour`, `d`/`day`, `w`/`week`, with an
optional plural `s` (`'90s'`, `'15m'`, `'1.5h'`, `'2 hours'`, `'1d'`).

`schedule` resolves `{id, at, repeat}`: `at` is the first time it fires (epoch ms), `repeat` a description such as
`'once'`, `'every 2h'`, `'daily at 07:00'` or `'mon,thu at 08:00'`. `list()` returns the same fields for every
reminder, with `at` as the next time.

What happens around a reminder:

- When it fires, the notification is shown only if the app still has the `notifications` permission; the reminder
  stays scheduled either way.
- A reminder missed while the phone was off is shown at boot if it is at most 6 hours late; otherwise it moves on to
  its next time.
- On Android 12 and later, exact timing needs AntiGem's "Alarms & reminders" access. Without it, reminders fire within
  a window Android chooses. App info shows a warning with a "Fix" button.
- `cancel(id)` and `cancelAll()` also remove the notification if it is on screen.
- Buttons and replies on a reminder's notification produce `notification` events like any other.

## Limits and errors

| Limit | Value | Error |
|---|---|---|
| Notifications on screen per app | 12 (updating an existing id is always allowed) | `QuotaExceededError` |
| Channels per app | 10 | `QuotaExceededError` |
| Reminders per app | 64 | `QuotaExceededError` |
| Buttons per notification | 3 (extra ones are ignored) | |
| Inbox lines | 7 (extra ones are ignored) | |
| Repeat interval | at least 15 minutes | `NotAllowedError` |
| Waiting events per app (with intents) | 20 | older ones are dropped |

| Error | When |
|---|---|
| `NotAllowedError` | No `notifications` permission; notifications for AntiGem are turned off in Android settings; repeating more often than every 15 minutes |
| `NotFoundError` | `channel` names a channel that wasn't created |
| `TypeError` | Bad channel id; a reminder without title and body; a bad `at`, duration, day or repeat; a one-off time in the past |
| `QuotaExceededError` | See the limits above |

- Images (`icon`, `smallIcon`, `image`) that can't be loaded or aren't allowed are left out silently.
- Clear data and uninstall cancel all of the app's reminders, notifications and channels.
- In workers, `notify.show` and `notify.schedule` convert `Date` values (`at`, `until`, `repeat.until`, `countdown`,
  `chronometer`, `messages[].time`) to epoch ms, as in pages.
