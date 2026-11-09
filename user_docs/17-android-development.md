# 17 — Building Android Apps on the Phone

AntiGem can build real Android apps **on the phone itself**: Kotlin or Java apps with **Jetpack Compose**, **Flutter** apps and **React Native** apps. Gradle, the Android SDK, Flutter and Node.js run in an **Ubuntu container** (proot-distro) inside the terminal, the same container REA uses, so parts both need (Java 21, Node.js) are installed only once.

It is not tied to one agent: Antigravity, Claude Code and OpenCode all build apps the same way, with the `adev` command in the terminal. Nothing needs to be added to an agent's MCP settings.

> Needs a **com.termux build** of AntiGem (it uses Termux packages). On other builds the page explains this and does nothing else.

---

## 🚀 Quick start

1. **Settings → Android Development**: tap **Get setup files**, keep **Kotlin / Java · Jetpack Compose** ticked (tick **Flutter** or **React Native** too if you want them) and tap **Install selected**.
2. **Build a test app**: tap **Build** next to the kind you installed. It creates a hello world in `~/AndroidProjects/hello-…` and builds its APK; then tap **Install** to try it.
3. **Install the skill**: tap **Install skill** on the same page, so agents know the commands and the usual fixes.
4. **Ask in chat**, for example: *"Create a Jetpack Compose notes app in ~/AndroidProjects/notes, build it and install it on this phone."*

---

## 🧭 1. Where it lives

**Settings → Android Development** (next to REA on the main page). The row shows nothing (not set up), *Partly set up*, *Working…*, or what is ready (*Kotlin · Flutter · RN*).

| Step | What it does |
| :--- | :--- |
| **1. Setup files** | Downloads the setup script and the `adev` commands. They are **not part of the app**; they come from the AntiGem plugins repository (`Santoshkurmi/antigem_plugins`, folder `android-dev`). **Change** picks another repository or branch (for testing). |
| **2. Install** | *What will you build?*: Kotlin / Java, Flutter, React Native. Everything the choice needs is installed one part after another, with a live log and **Stop**. *Extras*: adb and CMake / Ninja. *All parts* installs, repairs or updates a single part. |
| **3. Build a test app** | Creates and builds a hello world per framework; **Install** opens Android's installer for its APK. |
| **4. Use it** | The terminal commands and example prompts for agents. |

Further down: the **Android Development skill**, **Install on this phone** (ADB Connect), **Check the tools**, **Set up in the terminal** and **Free space / remove**.

The screen stays on while something installs or builds. Stopping half way is safe: install the part again to finish it.

---

## 🧩 2. What gets installed

| Part | What | Size |
| :--- | :--- | :--- |
| Ubuntu container | proot-distro and Ubuntu 24.04 (shared with REA) | ≈150 MB |
| Base tools | curl, git, zip, unzip, xz, Python | ≈60 MB |
| Java 21 | OpenJDK 21 headless (shared with REA) | ≈290 MB |
| Android SDK | command-line tools, Android 16 platform, build-tools, Termux's arm64 `aapt2` | ≈500 MB |
| adev commands | `adev` and `adev-new` | <1 MB |
| Flutter | Flutter stable and its Android files | ≈1.5 GB |
| Flutter release builds | runs Flutter's release compiler (made for PCs) through an emulator | ≈110 MB |
| Node.js 24 | for React Native (shared with REA) | ≈240 MB |
| C/C++ (NDK) | the Android NDK and CMake, made to run on the phone; React Native needs it | ≈1.5 GB |
| adb | to install apps and read logs from the terminal | ≈10 MB |

The first build of each project downloads Gradle and libraries on top (several hundred MB, kept in a cache for later builds).

**Only arm64.** Apps built on the phone are built for the phone: every build (Compose, Flutter, React Native) packages only `arm64-v8a` native code. That makes builds much faster and avoids tools that only exist for PCs. To make an APK for other devices (x86 emulators, old 32-bit phones), build the same project on a PC.

**Why the special parts?** Google builds its Linux SDK tools for x86_64 PCs, and phones are arm64. AntiGem uses Termux's arm64 builds of `aapt2` and `adb`, pins the command-line tools to the last version whose `sdkmanager` is plain Java, swaps the NDK's compilers for Ubuntu's arm64 LLVM of the same version, and runs the two remaining PC-only compilers (Flutter's release compiler and React Native's Hermes compiler) through the qemu emulator.

**How long builds take** (measured on a phone, after the first downloads): a Compose hello world ≈2–3 min, Flutter debug ≈1–2 min, Flutter release ≈3 min, React Native debug ≈3 min. The first build of each kind downloads Gradle and libraries and takes 5–15 min.

---

## ⌨️ 3. The commands

In AntiGem's terminal (and for every agent):

```sh
adev-new compose ~/AndroidProjects/my-app        # also: flutter, rn
cd ~/AndroidProjects/my-app
adev ./gradlew assembleDebug                     # Flutter: adev flutter build apk --debug
termux-open app/build/outputs/apk/debug/app-debug.apk   # opens the installer
```

- **`adev <command>`** runs the command inside the Ubuntu container, in the current folder, with Java, the SDK, Gradle, Flutter and Node.js ready. `adev` alone opens a shell there.
- **`adev-new compose|flutter|rn <folder>`** creates a working hello-world project (`--package com.example.app`, `--name`).
- Keep projects in the **Termux home** (`~/…`), not on shared storage: Gradle and npm need a real Linux file system, and the container sees only the home.

Builds are slower than on a PC: the first build of a project takes several minutes, later ones about 1–3 minutes.

**React Native debug apps** load their JavaScript from Metro: start it in the project folder first (`adev npx react-native start`), or build a release APK, which contains the JavaScript.

---

## 🎓 4. The Android Development skill (recommended)

A skills-only plugin that teaches agents to check what is installed, install what is missing (asking you first), create projects, build, install the APK and fix the usual errors. Install it with **Install skill** on the page, or from **Settings → Custom AntiGem Plugins → Repositories → `Santoshkurmi/antigem_plugins`** → *Android Development*. Then use `/plu:` in chat to give it to an agent.

---

## 📱 5. Installing on this phone

- **Install** buttons (test apps) and `termux-open <apk>` open Android's installer; the first time, allow AntiGem to install apps.
- With **adb** (Extras) connected once in **Settings → ADB Connect**, agents can install and start apps themselves (`adb install -r …`) and read crash logs.

---

## 🗑️ 6. Freeing space and removing

**Free space / remove** on the page:
- **Clean Gradle cache**: deletes Gradle's downloads (often GBs); the next build downloads them again.
- **Remove Flutter**.
- **Remove all**: the SDK, Flutter, Gradle's files and `adev`. The container, Java and Node.js stay (REA uses them); delete the container on the REA page. **Your projects are never deleted.**

---

## 🛠️ Troubleshooting

| Problem | Fix |
| :--- | :--- |
| `Server returned HTTP response code: 504` while downloading Gradle | GitHub, which hosts Gradle's downloads, is having trouble. Build again later; agents can also run `android-setup.sh gradle-dist` (retries; optional mirror, checked against Gradle's official checksum). |
| A library *requires … compileSdk NN* | Raise `compileSdk` in `app/build.gradle.kts`; Gradle downloads the platform. |
| Build *Killed* / *daemon disappeared* | Android ran out of memory: close other apps and keep AntiGem open. |
| Flutter **release** build cannot find `gen_snapshot` | Install *Flutter release builds* (part of Flutter on the page). |
| Apps with **C/C++ code** (React Native, CMake) fail at CMake / NDK | Install *C/C++ (NDK)*. |
| React Native app says *Unable to load script* | It is a debug build: start Metro (`adev npx react-native start` in the project folder) or build a release APK. |
| No space left | **Clean Gradle cache**, delete old projects' `build` folders. |
