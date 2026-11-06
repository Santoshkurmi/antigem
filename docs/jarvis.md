# Jarvis: the voice assistant of AntiGem

Jarvis lets you use AntiGem by voice, hands-free. Talk to it while you cook, walk or drive, and it opens your projects and
files, answers questions, starts and watches long jobs for you, reads approvals out loud, changes settings and themes, and
tells you how the app works.

Jarvis is not a separate AI. It runs on the agent you already use, **Claude Code** or **Antigravity**, in a hidden chat
of its own, and controls the app through a built-in MCP server called `jarvis_mcp`. It controls **AntiGem only**, not the
rest of your phone (that is what the separate Android MCP is for).

Jarvis is **off by default**. Nothing listens until you turn it on.

---

## Contents

1. [Quick start](#1-quick-start)
2. [Waking Jarvis](#2-waking-jarvis)
3. [Sending what you said](#3-sending-what-you-said)
4. [When Jarvis listens again](#4-when-jarvis-listens-again)
5. [Talking near other people](#5-talking-near-other-people)
6. [Things Jarvis does instantly](#6-things-jarvis-does-instantly)
7. [What you can ask](#7-what-you-can-ask)
8. [Tasks: Jarvis as your manager](#8-tasks-jarvis-as-your-manager)
9. [Approvals by voice](#9-approvals-by-voice)
10. [Voice and quiet mode](#10-voice-and-quiet-mode)
11. [Personality and notes about you](#11-personality-and-notes-about-you)
12. [The orb, the card and the panel](#12-the-orb-the-card-and-the-panel)
13. [The Jarvis chat](#13-the-jarvis-chat)
14. [All settings](#14-all-settings)
15. [How it works](#15-how-it-works)
16. [Troubleshooting](#16-troubleshooting)
17. [Privacy](#17-privacy)

---

## 1. Quick start

1. Open **Settings → Jarvis** and switch on **Turn on Jarvis**.
2. Under **Which agent runs Jarvis**, pick **Claude Code** or **Antigravity**, and optionally a model. A fast model feels
   best for voice: Jarvis hands big jobs to separate task chats anyway.
3. Work through the **Setup** card:
   - **Add the Jarvis MCP server** (required). These are the tools Jarvis controls the app with.
   - **Let Jarvis' tools run without asking** (Claude Code only). Otherwise every spoken answer would need an approval.
   - **Allow the microphone.**
   - **Install the Jarvis skill** (optional). The full guide is already sent with the first message of every Jarvis chat.
4. A small orb appears on the edge of the screen. **Tap it and talk.** End with **"send it"** or tap **Send**.

To use Jarvis with the screen off, turn on **Hands-free mode** (section 2).

---

## 2. Waking Jarvis

| Trigger | How | Notes |
| :--- | :--- | :--- |
| **The orb** | Tap the orb on the edge of the screen. | Long-press it to open the Jarvis panel. |
| **Quick Settings tile** | Pull down the notification shade, edit the tiles, add **Jarvis**. Tap it to talk. | Works from any app. With hands-free on, it starts listening right away; otherwise it opens AntiGem and listens. |
| **Notification** | With hands-free on, the Jarvis notification has **Talk / Send / Stop**. | |
| **Headset or Bluetooth button** | One press talks (or sends while listening); two presses stop. | Needs hands-free mode. Works while no music app has taken the media button. |
| **Wake phrase** | Say "Hey Jarvis" (or your own phrases). | Needs hands-free mode and **Trigger: Wake phrase too**. |

**Hands-free mode** (Settings → Jarvis → Waking Jarvis) keeps a small microphone service running, so the wake phrase and
the headset button work with the screen off or another app open. A notification shows what Jarvis is doing and how many
tasks are running.

**Wake phrase options**
- Several phrases, separated by commas: `hey jarvis, ok jarvis`.
- **Whole phrase** (default) or **The name alone** ("Jarvis, open my project").
- Common mishearings of "Jarvis" count too (jervis, jarvas, javis…).
- Say it together with the request: "Hey Jarvis, what's running?" starts right away.
- The wake phrase uses Android's speech recognizer continuously, which costs battery.

---

## 3. Sending what you said

By default **nothing is sent while you pause to think**. You decide when Jarvis gets your words:

- **Say a send phrase at the end:** "open the antigem project, **send it**".
- **Tap Send** on the card, press the headset button, or use **Send** in the notification.
- Or switch on **Send automatically when I pause**.

### Send phrases

End with one of these and your message is sent at once (the phrase itself is not sent):

> send it · send it now · send now · send this · send that · send the message · go ahead and send · send it jarvis ·
> jarvis send · that's it send it · send · over · do it · that's it · send kar do · send kardo · bhej do · bhejo · bhej de

### Cancel phrases

End with one of these and nothing is sent:

> cancel that · cancel this · cancel the message · scratch that · never mind · nevermind · forget it · forget that ·
> don't send · don't send it · do not send · do not send it · rehne do · chhod do · chod do · mat bhejo · cancel kar do

Both lists are yours. In **Settings → Jarvis → Listening and sending** you can remove any phrase (tap ×), add a word or a
whole sentence, or reset to the defaults. You can also ask Jarvis to change them: "add 'chalo bhejo' as a send phrase".

### How Jarvis avoids mistakes

A phrase only counts when it really is a command:

- **Only at the very end** of what you said.
- **Not when it belongs to your sentence:** "write an email to Bob **and send it**", "**can you** do it", "**to** send"
  stay part of the message.
- **Never after a negation:** "don't send it" cancels; it never sends.
- **Short or everyday phrases** ("send", "over", "do it", "that's it") only count when you say them **on their own after a
  short pause**. "Fix the bug do it" said in one breath stays one message; "Fix the bug. … Do it." sends.
- When a send and a cancel phrase both fit, the longer one wins; a tie **cancels**, so nothing is sent by mistake.
- Said alone, "never mind" stops Jarvis, and "do it" right after Jarvis asked you something is sent as your answer.
- When a send phrase is cut off, the model is told which one. If it was really part of your request, it can still act on it.

If a phrase keeps firing by mistake, remove it or replace it with a longer one ("jarvis send it now").

### Auto-send on pause

Switch on **Send automatically when I pause** to send after a pause:

- **Smart pause** (default): Jarvis waits longer when you sound unfinished ("…and", "…the", "…because") and less for short
  answers.
- **Fixed pause**: always the same pause (0.8–3.5 s, default 1.5 s).

A thin bar fills on the card during the pause. Tap **Wait** to keep talking without it being sent.

**Answers to Jarvis' own questions** ("yes", "the second one") send by themselves after a short pause even with auto-send
off. You can switch this off.

The longest single message is 90 seconds by default (20–240 s); whatever was heard is sent then.

---

## 4. When Jarvis listens again

After Jarvis speaks, the microphone can open again for your reply. **Settings → Jarvis → After Jarvis speaks, listen for
your reply:**

- **When it expects an answer** (default): the model decides. After "Done. Want me to run it?" the microphone opens; after
  "Saved the file." it does not. A question always opens it.
- **Always:** after every answer while you are talking to Jarvis.
- **Never:** only when you tap or wake Jarvis.

**Wait for a reply** (3–20 s, default 8 s): if you say nothing at all in that time, the microphone closes with a soft tone.

---

## 5. Talking near other people

**Settings → Jarvis → Talking to other people nearby:**

- **Smart** (default): when the microphone reopened by itself, Jarvis ignores filler ("um", "hmm") and anything the model
  can tell was said to someone else, the TV or background noise.
- **Strict:** during those follow-up moments Jarvis only reacts when you say its name, or answer its question.
- **Off:** everything heard is for Jarvis.

---

## 6. Things Jarvis does instantly

These are handled on the phone, without asking the AI, so they are immediate:

| Say | What happens |
| :--- | :--- |
| "stop", "cancel", "quiet", "be quiet", "never mind" | Stops speaking, listening and Jarvis' current turn (tasks keep running). |
| "thanks", "thank you", "that's all", "bye", "go to sleep" | Ends the conversation ("Anytime."). |
| "ok", "great", "got it", "perfect", "sounds good" | Ends the conversation silently. |
| "repeat that", "say that again", "what did you say", "pardon" | Repeats the last answer. |
| "speak slower", "slow down", "speak faster", "speed up" | Changes the speaking speed and asks if it is better. |
| "quiet mode", "text only", "mute yourself" / "quiet mode off", "unmute" | Shows answers instead of speaking them, and back. |
| "start again", "new session", "fresh start", "reset" | Starts a new Jarvis chat (with a short recap). "forget everything" starts without the recap. |

---

## 7. What you can ask

Anything you would ask the agent itself, plus direct control of AntiGem. Some examples:

**The app**
- "Open the antigem project and MainActivity."
- "Show the terminal." · "Go to the browser and open github.com."
- "Search the code for `onCreate`."
- "Switch to dark mode." · "Use the Obsidian theme." · "Make the chat text bigger."
- "Open the Jarvis settings." · "Show me the MCP settings."

**Chats and models**
- "What's running?" · "What did the build chat say last?"
- "Find my chat about the login bug."
- "Switch this chat to Opus." · "Set effort to high." · "Turn thinking off." · "Plan mode."
- "Rename this chat to Release prep." · "Delete that chat." (Jarvis asks first.)

**Help and setup**
- "How do I add an MCP server?" · "Where is the backup setting?" · "How does server mode work?"
- "Which MCP servers do I have?" · "Is Claude signed in?" · "Is everything online?"
- "How much usage do I have left?" (both the 5-hour and the 7-day window, for Claude Code and Antigravity)

**Everyday**
- "Set a timer for 12 minutes for the pasta." · "What timers are running?"
- "What's the weather in Kathmandu?" (with the agent's web tools)
- "Remember that I prefer short answers." (Jarvis' memory note)

**Jarvis itself**
- "Turn on auto send." · "Make the orb tiny." · "Keep the screen on only while you're active."
- "Talk as a woman." (persona) · "Speak a bit faster."

App settings (theme, font size, floating bubble and switcher, new-chat agent) can only be changed by voice after you
allow it: **Settings → Jarvis → Approvals by voice → Let Jarvis change app settings**. Jarvis' own options can always be
changed by voice. Approval and security policies never can.

---

## 8. Tasks: Jarvis as your manager

Big jobs don't block the conversation. Say "fix the failing tests and tell me when it's done" and Jarvis starts a **task**:
a separate chat that the phone watches for you. You can keep talking about other things.

Jarvis tells you when a task:

- **finishes** (with the gist of the result),
- **fails** (what went wrong, and a suggestion),
- **shows no progress** for a while (10 minutes by default),
- **needs an approval**, or
- **asks a question** (multiple choice), which you can answer by voice.

You can ask about it and steer it at any time:

- "How's the build going?"
- "Tell it to also run the lint." · "No, use the other API instead." (changes course, interrupting if needed)
- "Stop the build." · "Show me that chat."

Tasks are listed in the **Jarvis panel** (long-press the orb) with their state (starting, running, waiting for approval,
asking a question, no progress, done, failed, stopped) and **Open / Stop / Dismiss** buttons. They stay listed after the
app restarts; tasks that finished while it was closed are reported.

**Settings → Jarvis → Manager and tasks:**
- **When a task finishes, fails or gets stuck:** **Jarvis decides** (default; the model reads the result, tells you and
  follows up) or **Just tell me** (a short announcement, no model turn).
- **Approvals inside tasks:** **Use my approval rules** (default) or **Jarvis decides** (it approves what clearly serves
  the task's goal and asks you about anything risky or unclear).
- **Report a task with no progress after:** Never / 5 / 10 / 20 / 45 minutes.

Tasks can run on either agent. Each Claude Code task keeps its own live connection, so its approvals and questions reach
you even while another chat is on screen.

---

## 9. Approvals by voice

When an agent wants to run a command or edit a file, Jarvis can read it out and wait for **yes** or **no**
("yes to all" approves the other safe requests too). **Settings → Jarvis → Approvals by voice:**

| Policy | What happens |
| :--- | :--- |
| **Ask me each time** (default) | Every request is read out and needs a yes. |
| **Auto: read-only** | Reading files and searching is approved; the rest is read out. |
| **Auto: except risky** | Everything is approved except risky actions (deleting, force pushing, installing from the web, `sudo`…), which are read out with a warning. |
| **Auto: everything** | Nothing is asked (asks you to confirm before turning this on). |

- Jarvis' own tools never need approval.
- Approvals of the Jarvis chat and of tasks are always handled. Approvals of the **chat on screen** are only handled while
  hands-free mode is on.
- If your answer is unclear, nothing is approved.

---

## 10. Voice and quiet mode

**Settings → Jarvis → Voice output:**

- **Engine:** this device's text-to-speech (default, with a voice picker), **Google Cloud** (API key, voice name), or any
  **OpenAI-compatible** speech API (base URL, model, voice, API key). If an online voice fails, Jarvis falls back to the device.
- **Speed** and **pitch**, and a **Test voice** button.
- **Quiet mode:** answers are shown on the card instead of spoken (meetings, night).
- **Speak progress updates:** a short spoken line while a long step runs.

**Speech recognition** (Listening and sending): **Same as agent** (default), **Claude**, **Antigravity** or **This device**
(Android's recognizer, no sign-in needed), plus the language. If the agent's dictation fails, Jarvis switches to the device
recognizer for the rest of the session.

---

## 11. Personality and notes about you

**Settings → Jarvis → Personality and you:**

- **Jarvis speaks as:** Not set / **Female** / **Male** / **Neutral**. This is the grammatical gender Jarvis uses for itself,
  which matters in languages like Hindi ("main kar **rahi** hoon" / "main kar **raha** hoon"). Pick a matching voice under
  Voice output.
- **About you and extra instructions:** anything Jarvis should always know, for example
  *"Call me Santosh. Reply in Hinglish. I mostly work on the antigem project."*

Both are sent with the first message of every Jarvis chat. When you change them, the open chat is told with your next
message.

This is different from **Jarvis' memory note** (in the Jarvis chat card), which Jarvis writes itself ("remember that…").

---

## 12. The orb, the card and the panel

- **Idle:** a small arc-reactor orb rests on the edge of the screen. **Drag it anywhere**; it snaps to the nearest edge and
  remembers the position. A violet ring shows running tasks; an amber dot means something needs you.
- **Working:** a slim status line shows what Jarvis is doing ("Checking the build…"), with a stop button.
- **Listening or speaking:** the full card opens, with flowing waves, what Jarvis heard or said, the send hint and the
  buttons (Cancel / Wait / Send while listening; Stop / Reply while speaking).
- **Full-screen style** (optional): listening and speaking take the whole screen.

**The Jarvis panel** (long-press the orb):
- Quick switches: hands-free, voice or quiet, auto-send, wake phrase, keep screen on.
- Your **tasks** with open / stop / dismiss.
- The **conversation**.
- A box to **type to Jarvis** when you can't talk.
- Shortcuts: Jarvis settings, new session, stop all.

**Settings → Jarvis → Screen and look:**
- **Keep the screen on:** While Jarvis is on (default) / While it's active / Never. Running chats of both agents keep the
  screen on anyway.
- **Orb while idle:** Tiny / Small (default) / Normal / Hidden (only shown while Jarvis is active).
- Floating card or full screen, vibration when listening starts, show what Jarvis is doing, show what it heard and said.
- Tones when the microphone opens and closes (Listening and sending).

---

## 13. The Jarvis chat

Jarvis talks to a normal chat of its agent. It is hidden from the chat list, but you can open it under the **Jarvis**
filter in the chat list.

- **The first message** of every Jarvis chat carries the whole guide: the rules, every Jarvis tool with its parameters,
  your persona and notes, the memory note and what is on screen. The model acts right away instead of searching for tools
  or reading config files.
- **Every later message** carries a short note with fresh context: the screen, the open project and file, the chat on
  screen, tasks, waiting approvals and timers. You can turn this off (**Send live context with every message**).

**When a new Jarvis chat starts**
- The first time you talk to Jarvis, or after you switch its agent.
- When you ask for it ("start again", "new session"), or press **Start a fresh chat now** (settings) or **New session**
  (panel).
- When the Jarvis chat was deleted: the next message simply starts a new one.
- By your rules (both **Never** by default, so Jarvis never forgets mid-task):
  - **Start a fresh chat after idle time:** 5 / 15 / 30 min, 1 h, 3 h.
  - **Start a fresh chat after this many messages:** 10 / 20 / 40 / 80.
  A chat is only replaced when you send the next message, never while it is working.

With **Carry the conversation over** (default on), a new chat starts with the memory note and the last lines of the old
conversation.

**Speaking while Jarvis is still working** (Listening and sending): **Corrections interrupt** (default: "no, wait…",
"actually…", "instead…" stop the current turn and switch to your new words), **Wait for it**, or **Always interrupt**.

---

## 14. All settings

Everything lives in **Settings → Jarvis**. Defaults in bold.

| Section | Setting | Options |
| :--- | :--- | :--- |
| Jarvis | Turn on Jarvis | on / **off** |
| Which agent runs Jarvis | Agent, model | **Claude Code** / Antigravity; **default model** or any |
| Personality and you | Jarvis speaks as | **Not set** / Female / Male / Neutral |
| | About you and extra instructions | free text |
| Setup | Jarvis MCP server, tool permission, microphone, skill | one-tap buttons |
| Waking Jarvis | Trigger | **Button / tile / headset** / Wake phrase too |
| | Wake phrase, match | **hey jarvis**; **Whole phrase** / The name alone |
| | Hands-free mode | on / **off** (always off after a restart) |
| | Headset button | **on** / off |
| Listening and sending | Send automatically when I pause | on / **off** |
| | Pause style, pause length | **Smart** / Fixed; **1.5 s** (0.8–3.5) |
| | Answers to Jarvis' questions send by themselves | **on** / off |
| | Send with a phrase, Cancel with a phrase | **on** / off, editable lists |
| | After Jarvis speaks, listen for your reply | **When it expects an answer** / Always / Never |
| | Wait for a reply | **8 s** (3–20) |
| | Longest single message | **90 s** (20–240) |
| | Talking to other people nearby | Off / **Smart** / Strict |
| | Speaking while Jarvis is still working | **Corrections interrupt** / Wait for it / Always interrupt |
| | Tones when the microphone opens and closes | **on** / off |
| | Speech recognition, language | **Same as agent** / Claude / Antigravity / This device; **en** |
| Voice output | Quiet mode | on / **off** |
| | Engine, voice, language | **This device** / Google Cloud / OpenAI-compatible |
| | Speed, pitch | **1.0x**, **1.0** |
| | Speak progress updates | on / **off** |
| Manager and tasks | When a task finishes, fails or gets stuck | **Jarvis decides** / Just tell me |
| | Approvals inside tasks | **Use my approval rules** / Jarvis decides |
| | Report a task with no progress after | Never / 5 / **10** / 20 / 45 min |
| | Say when any other chat finishes (+ not for the chat on screen) | on / **off** |
| | Also read the start of the reply | on / **off** |
| | Warn when the 5-hour usage is almost gone | on / **off** |
| Approvals by voice | Policy | **Ask me each time** / Auto: read-only / Auto: except risky / Auto: everything |
| | Let Jarvis change app settings | on / **off** |
| Screen and look | Keep the screen on | **While Jarvis is on** / While it's active / Never |
| | Orb while idle | Tiny / **Small** / Normal / Hidden |
| | Style | **Floating card** / Full screen |
| | Vibrate, show what Jarvis is doing, show what it heard and said | **on** |
| Jarvis chat | Send live context with every message | **on** / off |
| | Fresh chat after idle time / after messages | **Never** / … |
| | Carry the conversation over | **on** / off |
| | Jarvis memory | free text (Jarvis edits it too) |

---

## 15. How it works

- **`jarvis_mcp`** is an MCP server built into the app, served on the phone at `http://127.0.0.1:<bridge port>/jarvis`.
  Its tools cover:
  - **voice:** speak, ask, show a status line, ignore side talk, repeat the guide;
  - **tasks:** start, list, get, reply, answer, stop, open, close;
  - **chats:** list, search, read, send, start, stop, open, rename, fork, delete;
  - **models and chat controls:** model, effort, thinking, permission mode;
  - **settings:** read and change settings, read the configuration, open a settings page, app help;
  - **approvals:** list and resolve;
  - **IDE:** projects, open and close files, open tabs, code search;
  - **screens and the browser**, **usage**, **health**, **timers**, **memory**.
- Every message carries a short hidden note; you only see your own words in the Jarvis chat.
- If the model finds no `jarvis_mcp` tools, it does not look for workarounds. It answers a fixed marker, and the app tells
  you how to add the server (a notice with a **Set up** button and a warning in Settings → Jarvis).
- Antigravity approvals of chats that are not on screen (the Jarvis chat and tasks) are answered through the hub.

---

## 16. Troubleshooting

| Problem | Fix |
| :--- | :--- |
| "My app tools aren't connected" | Settings → Jarvis → Setup → **Add** the Jarvis MCP server for the agent that runs Jarvis, then say "start again". |
| Jarvis doesn't hear me | Allow the microphone (Setup), or set Speech recognition to **This device**. |
| What I say is never sent | Auto-send is off by default: end with "send it", tap Send, or switch on **Send automatically when I pause**. |
| A phrase sends or cancels by mistake | Remove it in Listening and sending, or use a longer sentence. |
| Jarvis is silent | Check quiet mode is off and the voice works (**Test voice**). |
| Wake phrase doesn't work | Turn on hands-free mode and set Trigger to **Wake phrase too**. |
| Headset button does nothing | Needs hands-free mode; a music app may be holding the button (pause or close it). |
| Every answer asks for approval (Claude Code) | Setup → **Let Jarvis' tools run without asking**. |
| Jarvis forgot what we discussed | A new chat started (rotation rules, "start again", deleted chat). The last lines carry over if **Carry the conversation over** is on; use the memory note for lasting facts. |
| Chats don't answer | Ask "is everything online?", or check Settings → Servers. |

---

## 17. Privacy

- Jarvis is off until you turn it on, and the microphone only opens when you trigger it, or for a few seconds after Jarvis
  asked you something.
- Hands-free mode (wake phrase, headset button) keeps a microphone service running and always shows a notification. It is
  off again after every restart of the app.
- What you say goes to the speech recognizer you chose (your agent's dictation, or Android's recognizer, which may use
  Google's servers) and then to your agent, like a normal chat message.
- Online voices (Google Cloud, OpenAI-compatible) receive the text Jarvis speaks.
