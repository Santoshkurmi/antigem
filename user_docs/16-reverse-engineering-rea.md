# 16 — Reverse Engineering with REA

**REA** (Reverse Engineer Anything, [github.com/morluto/rea](https://github.com/morluto/rea)) is an open-source MCP server that lets your AI agents take apart apps and binaries: decompile an Android app's Java/Kotlin classes, read its manifest, permissions and resources, identify Flutter builds, decompile React Native (Hermes) code, and inspect native `.so` libraries. Everything is **static**: REA reads files and never runs the app.

AntiGem runs REA in an **Ubuntu container** (proot-distro) inside the terminal. REA is not tied to one agent: Antigravity, Claude Code and OpenCode all reach it the same way, as an MCP server you add in that agent's MCP settings.

> REA needs a **com.termux build** of AntiGem (it uses Termux packages). On other builds the REA page explains this and does nothing else.

Only analyze apps and files you own or are allowed to analyze.

---

## 🚀 Quick start

1. **Settings → REA · Reverse Engineering**: get the setup files, then install the parts under *Needed for APK analysis*.
2. **Connect REA to your agent**: in that agent's MCP settings, tap the **REA** preset and keep *On demand* ([section 5](#-5-connecting-rea-to-an-agent)). Not sure what to pick in its dialog? See [What should I choose?](#-what-should-i-choose)
3. **Install the REA skill plugin**: tap **Install skill** on the REA page, or install **REA** from **Settings → Custom AntiGem Plugins → Repositories → `Santoshkurmi/antigem_plugins`** ([section 7](#-7-the-rea-skill-plugin-recommended)).
4. **Ask with `/plu:rea:rea`** at the start of your message, for example:
   `/plu:rea:rea find the APK of com.example.app and summarize its permissions`

---

## 🧭 1. Where it lives

**Settings → REA · Reverse Engineering.** The row shows the state: nothing (not set up), *Partly set up*, *Installing…*, or REA's version when it is ready.

The page has numbered steps:

| Step | What it does |
| :--- | :--- |
| **1. Setup files** | Downloads REA's setup scripts. They are **not part of the app**; they come from the AntiGem plugins repository when you need them, so the app stays small. |
| **2. Install** | Installs the parts you pick. |
| **3. Tool groups** | Chooses which REA tools agents see. |
| **4. Connect REA to your agent** | Says where to add REA in each agent's MCP settings, what the two ways of offering its tools cost, and the plain command for other agents. |
| **5. Check it works** | Runs REA's health check. |

Further down: the optional **REA skill**, **Phone access** (adb), **Set up in the terminal** (the same steps as commands), and **Remove**.

---

## 📥 2. Getting the setup files

Tap **Get setup files**. The source is shown under the text (`Santoshkurmi/antigem_plugins@main` by default); **Change** lets you point at a fork for testing. When a newer version of the setup files exists, the step shows **Update** and an **Update to vX** button. Updating also refreshes the `rea-mcp` launcher if it is installed.

---

## 🧩 3. Installing the parts

Install only what you need. Each part has a checkbox, its size and, when you tap it, a description:

| Part | Size | What for |
| :--- | :--- | :--- |
| Ubuntu container | ≈150 MB | proot-distro + Ubuntu 24.04; REA runs in it. You can use it for other Linux tools too (`proot-distro login ubuntu`). |
| Base tools | ≈60 MB | curl, Python, git, unzip in Ubuntu |
| Java 21 | ≈290 MB | OpenJDK 21 headless (no desktop libraries); JADX and apktool need it |
| Node.js 24 | ≈240 MB | installed with nvm, not apt; REA runs on it |
| REA | ≈60 MB | REA itself (npm `rea-agents`). Its button says **Update**. |
| JADX decompiler | ≈45 MB | Java/Kotlin source of APK classes and methods |
| apktool | ≈16 MB | manifest, resources, strings (latest official jar) |
| MCP launcher | <1 MB | `rea-mcp` (what agents start), `rea-shell`, the tool group filter |
| Native code tools *(optional)* | ≈40 MB | objdump and rizin for `.so` libraries |
| adb *(optional)* | ≈10 MB | REA's phone tools |
| React Native (hermes-dec) *(optional)* | ≈40 MB | decompiles Hermes bytecode |
| Debugger (gdb) *(optional)* | ≈70 MB | debugging in the container |
| Firmware (binwalk) *(optional)* | ≈30 MB | firmware images |

- **Install selected (N)** installs every ticked part one after another. Parts a ticked part needs are added automatically (the text under the list says which). **Select all** / **Clear** change the ticks.
- Each part also has its own **Install** / **Reinstall** button. Reinstall repairs a broken part; on REA it updates REA.
- Everything for APK analysis is about **1 GB** and takes 5–15 minutes depending on your network.
- **The screen stays on** while something installs, and AntiGem keeps the phone awake. Keep the app open.
- **Stop** ends the running part. That is safe: install the part again and it finishes where it stopped.

---

## 🗂️ 4. Tool groups

REA has more than 200 tools, and every tool an agent sees costs context on **every** request. **Tool groups** decide which tools a REA server shows.

**Settings → REA → Edit tool groups** lists the groups:

- Built-in groups: *Basics, Android apps, Flutter, Phone (adb), Native code, JavaScript & React Native, Unity games, Debuggers, Frida, Firmware, Network captures, Apple / iOS, Windows & .NET, Decompiler session*.
- Each card shows its tool count and roughly how many tokens it costs.
- **⋮** on a card: *Edit tools*, *Rename / describe*, *Reset to default* (built-in groups), *Delete*.
- **New group** creates your own group.
- **In no group** lists tools that no group contains: new tools from REA updates show up here. **Add to…** puts one in a group.
- **Reset all groups to the defaults** restores the built-in groups and deletes your own.

Opening a group shows every REA tool with its description, required inputs and size. Tick a tool to add it. Search and the filters *All / In group / Not in group / In no group* narrow the list; **Add N shown** and **Remove N shown** change every listed tool at once.

> Group changes reach an agent when its REA server restarts (restart it in that agent's MCP settings, or start a new chat).

**Refresh tools** re-reads REA's tool list, for example after updating REA.

---

## 🔌 5. Connecting REA to an agent

REA is connected where you manage each agent's MCP servers, with the same **REA** preset (a tile in *Quick presets*):

| Agent | Where |
| :--- | :--- |
| Antigravity | Settings → Antigravity → MCP Servers → **REA** |
| Claude Code | Settings → Claude Code → MCP → **REA** |
| OpenCode | Settings → OpenCode → MCP servers → **REA** |

Use it in every agent you want REA in; each can have its own choices, and an agent can have several REA servers (for example an on-demand `rea` for everything and a direct `rea-android` with just *Android apps*). The preset's dialog explains every choice and shows, live, how much context it costs the agent on **every** request. Typing the name of an existing REA server loads its settings to change them (Claude Code cannot change a server in place: remove it first).

**How the agent sees REA's tools**

| Choice | What it does | Context per request |
| :--- | :--- | :--- |
| **On demand** *(Recommended, the default)* | The agent gets 4 small tools: `rea_tools` (lists REA's tools by name), `rea_tool_info` (details of the ones it needs), `rea_call` (runs a tool) and `rea_call_change` (runs a tool that changes things: installing apps, pushing files, debugger and Frida commands). The first use of a tool takes 1–2 extra steps. Because changing tools have their own gateway, you can approve them separately. | ≈0.7k tokens, however many tools you allow |
| **Directly** | Every allowed tool is a normal MCP tool: no lookup step, but every tool's description is sent with every request. | ≈24k tokens for the default five groups; ≈75k for all of REA |

**Tools the agent may use**: *Chosen tool groups* (chips; in Directly mode each chip shows its cost) or *All REA tools*. On demand, allowing more costs nothing up front, so a new server allows every group except *Decompiler session* (it needs Ghidra, IDA, Hopper or Kuna, which are not installed).

**Also offer directly** *(On demand only, optional)*: groups whose tools are offered as normal tools as well, to skip the lookup step. Each chip shows what it adds (for example *Basics +9k*). Usually not needed.

The box at the bottom sums it up: the context per request, how many tools are usable (and how many are offered directly), and what the same tools would cost Directly.

**Full output schemas**: off by default. They describe every REA result in the tool list and make tools offered directly about 12× bigger; turn them on only if your agent needs them.

### 🤔 What should I choose?

The dialog has several choices because of one thing: **context**.

**What "context" means here.** A model can only read a limited amount of text at once (its *context window*). Every time you send a message, the app sends the model your conversation **plus a description of every tool the agent has**: each tool's name, what it does and its arguments. Tool descriptions are not sent once; they are sent **with every single message**. The more tools the agent has, the more of the context window they fill before your conversation even starts. That makes replies **slower**, uses more of your **quota or cost**, leaves **less room** for your files and chat, and many tools at once can **confuse** the model into picking the wrong one.

REA has **229 tools**. Sent as normal tools, they would take about **75k tokens on every message** (about 900k with full output schemas, more than most models can read at all). The choices in the dialog exist to avoid that.

**The four choices, and what each is for**

| Choice | The question it answers | Why it saves context |
| :--- | :--- | :--- |
| **How the agent sees REA's tools** (*On demand* / *Directly*) | Does the agent get REA's tools as a short catalogue, or every tool's full description? | *On demand* sends 4 small tools (≈0.7k tokens) and the agent looks up details only for the tools it actually uses. |
| **Tools the agent may use** (tool groups) | Which of REA's tools is the agent allowed to use at all? | With *Directly*, only the chosen groups are sent. With *On demand* it costs nothing extra, but it still limits what the agent can do. |
| **Also offer directly** *(On demand only)* | Should some groups skip the lookup step? | It doesn't save context: each group you pick adds its size. Use it only for tools the agent uses in nearly every message. |
| **Full output schemas** | Does the agent need the exact shape of every result? | Leaving it off keeps tool descriptions ~12× smaller. Almost no agent needs it. |

**Why groups?** A group is a set of related REA tools (for example *Android apps* = JADX decompiling + apktool + APK signature). Groups let you say "this agent may use Android and Flutter tools, nothing else" in one tap instead of ticking 229 tools. They do two jobs:

1. **Save context** in *Directly* mode: only the chosen groups' tools are sent.
2. **Limit what the agent can do**, in both modes: a tool outside the chosen groups can't be called at all. Leave out *Phone (adb)*, *Debuggers* and *Frida* if the agent shouldn't install, uninstall or start apps, push files or attach debuggers.

You can change any group, or make your own, in **Settings → REA → Edit tool groups** (section 4 above).

**What each group costs in *Directly* mode** (REA 6.8.0, output schemas off, per message; REA's own notes add ≈0.7k):

| Group | Tools | ≈ Tokens | Tools that change things ✎ |
| :--- | ---: | ---: | ---: |
| Basics | 13 | 8.9k | 1 |
| Android apps | 9 | 2.3k | 0 |
| Flutter | 2 | 0.6k | 0 |
| Phone (adb) | 24 | 4.7k | 6 |
| Native code | 13 | 7.0k | 1 |
| JavaScript & React Native | 11 | 4.3k | 0 |
| Unity games | 1 | 0.7k | 0 |
| Debuggers | 9 | 2.9k | 5 |
| Frida | 9 | 2.3k | 4 |
| Firmware | 5 | 2.1k | 0 |
| Network captures | 4 | 1.4k | 0 |
| Apple / iOS | 14 | 3.8k | 0 |
| Windows & .NET | 11 | 3.9k | 0 |
| Decompiler session | 60 | 13.3k | 9 |

*Decompiler session* only works with Ghidra, IDA, Hopper or Kuna, which AntiGem does not install, so leave it off.

**Recommended setups**

| Your situation | Mode | Tools the agent may use | Also offer directly | ≈ Context per message |
| :--- | :--- | :--- | :--- | ---: |
| **Not sure / just want it to work** (the default) | On demand | every group except *Decompiler session* | nothing | **0.7k** |
| A small or fast model (Flash, local models), or long chats | On demand | what you need | nothing | 0.7k |
| Only Android apps, and you want the fastest calls | Directly | *Android apps* + *Flutter* | — | 3.7k |
| Android apps + their `.so` libraries | Directly | *Android apps* + *Flutter* + *Native code* | — | 10.7k |
| Android apps + this phone (installed apps, logcat) | Directly | *Android apps* + *Flutter* + *Phone (adb)* | — | 8.4k |
| The agent must not change anything on the phone | On demand | leave out *Phone (adb)*, *Debuggers*, *Frida* | nothing | 0.7k |
| Everything, as normal tools | Directly | all | — | 46k–75k *(not recommended)* |

Rules of thumb:

- **Start with On demand.** It costs the same however many tools you allow, so you rarely need to think about groups for context.
- **Choose Directly only for a small set** you use all the time (under about 10k tokens). Above 40k the dialog warns you.
- **Keep approvals on** for `rea_call_change` (on-demand servers) and for ✎ tools: those are the ones that change things (installing or uninstalling apps, pushing files, debugger and Frida commands).
- **Two servers are fine.** For example, a direct `rea-android` (*Android apps* + *Flutter*, 3.7k) for everyday work, and an on-demand `rea` with everything else for the rare cases.
- **Each agent is separate.** You might use On demand in one agent and Directly in another.

**Step by step in the dialog**

1. **Server name**: keep `rea` (or a name like `rea-android`). Typing the name of an existing REA server loads it so you can change it.
2. **How the agent sees REA's tools**: keep **On demand** unless one of the setups above says Directly.
3. **Tools the agent may use**: keep the preselected groups, or tap chips to add and remove. *All REA tools* includes tools in no group too.
4. **Also offer directly**: usually leave empty.
5. Look at the **summary box**: context per message, how many tools are usable, and what Directly would cost.
6. **Full output schemas**: leave off.
7. **Save**, then restart the server in the agent's MCP settings (or start a new chat).

**Another agent, or by hand:** add a stdio (local command) MCP server with the command `/data/data/com.termux/files/usr/bin/rea-mcp` and the environment variables `REA_MODE=lazy` (on demand; `direct` for every tool) and `REA_GROUPS` (`all`, or group ids such as `core,android`). Step 4 on the REA page shows them with copy buttons.

---

## ✅ 6. Checking that it works

**Run health check** runs REA's doctor and shows which engines REA can use (JADX, apktool, objdump, rizin, adb…). Engines you did not install (Ghidra, IDA, Hopper…) show as missing; that is normal.

Then try in chat (the page has these ready to copy). Start the message with `/plu:rea:rea` so the agent also gets the REA skill ([section 7](#-7-the-rea-skill-plugin-recommended)):

- `/plu:rea:rea` *Use REA to find the APK of the installed app com.example.app and summarize its manifest and permissions.*
- `/plu:rea:rea` *With REA, is com.example.app a Flutter, React Native or native app? Show the evidence.*
- `/plu:rea:rea` *Decompile the MainActivity of com.example.app with REA and explain what it does.*

Installed apps don't need adb: their APK files are readable directly.

---

## 🎓 7. The REA skill plugin (recommended)

REA gives the agent its **tools**; the **REA skill** tells the agent **how to use them well**. It is a custom AntiGem plugin (skills only, no code). It teaches the agent to:

- check that REA's MCP server works before using it, and tell you exactly what to fix when it doesn't;
- find installed apps and their split APKs, detect Flutter / React Native / Unity / native apps, and use the right tools for each;
- use REA's on-demand tools (`rea_tools`, `rea_tool_info`, `rea_call`) correctly;
- decompile React Native Hermes bytecode with hermes-dec;
- install REA from the terminal if you ask it to, instead of using the REA page.

### Installing it

Either way works:

- **Settings → REA → REA skill for agents → Install skill** (also **Update skill** later), or
- **Settings → Custom AntiGem Plugins → Repositories**: `Santoshkurmi/antigem_plugins` is already in the list (AntiGem adds it once; add it again if you removed it). Open it and install **REA · Reverse Engineering**. Updates show up there too.

### Using it in chat: `/plu:`

Start your message with the plugin's command. Type `/plu:` and a picker lists your plugins and their skills, or type it yourself:

| You type | What the agent gets |
| :--- | :--- |
| `/plu:rea:rea …` | The plugin **and** its skill `rea`: the path of that skill file and the instruction to read it fully before acting. The most direct choice. |
| `/plu:rea …` | The plugin **without** naming a skill: where the plugin's skills are, and the instruction to read the one that matches the task. |

When you send the message, **AntiGem expands the command automatically** into these instructions for the model; you don't paste anything. The chat still shows just `/plu:rea:rea` and your text. It works the same in Antigravity, Claude Code and OpenCode.

Examples:

```text
/plu:rea:rea find the APK of the installed app com.example.app and list its permissions
/plu:rea:rea is com.example.app a Flutter or React Native app? Show the evidence
/plu:rea decompile the login code of com.example.app and explain it
```

> The skill does not replace REA's MCP server: the agent still needs REA connected ([section 5](#-5-connecting-rea-to-an-agent)) for REA's tools. The skill plugin itself has no tools, so it needs no MCP server of its own.

---

## 📱 8. Phone access (optional)

REA's phone tools (installed packages, pulling an app's APKs, logcat, screenshots, starting apps) use adb. Install the **adb** part, then connect the terminal's adb to this phone once in **Settings → ADB Connect**.

---

## ⌨️ 9. Setting up in the terminal

**Set up in the terminal → Show** lists the same steps as commands, each with a copy button:

```sh
bash $PREFIX/share/antigem-rea/rea-setup.sh help       # every step
bash $PREFIX/share/antigem-rea/rea-setup.sh android    # everything for APK analysis
rea-mcp --doctor                                       # health check
rea-shell                                              # a shell in the container with REA's tools
```

---

## 🗑️ 10. Removing REA

**Remove → Show**:

- **Remove REA** removes REA, its JADX and apktool jars, hermes-dec and the launcher. The Ubuntu container, Java, Node.js and your tool groups stay.
- **Delete container** deletes the whole Ubuntu container, including anything else you installed in it. This cannot be undone.

---

## 🛠️ Troubleshooting

| Symptom | Fix |
| :--- | :--- |
| *Get setup files* fails with "Not found" | The source repository or branch is wrong or not public. Tap **Change → Default**. |
| A part fails | Read the log under the step. Tap the part's **Install** again; a stopped or failed install continues. If apt says another install is running, wait for it. |
| No REA tools in chat | Add REA with the REA preset in the agent's MCP settings, restart the server, start a new chat. |
| The server shows an error in the agent's MCP settings | Run the health check on the REA page; install the parts it reports missing. |
| "not in this server's tool groups" / "not available on this server" | The tool is hidden: choose more groups with the REA preset (type the server's name to change it) or add the tool to a group, then restart the server. |
| Agents are slow or forget things | Too many tools offered directly: switch the server to **On demand**, or choose fewer groups. |
| JADX is slow on the first call | Normal in the container: the first call on an APK takes up to a minute. |
