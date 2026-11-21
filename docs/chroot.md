# Ubuntu chroot: a real Ubuntu for the agents (rooted phones)

On a rooted phone, AntiGem can run its bridge, the agents (Antigravity, Claude Code, OpenCode, Codex) and the terminal
inside a **real Ubuntu** instead of its built-in Linux environment. Ubuntu is unpacked into a folder on `/data` and entered
with `chroot` through your root manager (Magisk, KernelSU or APatch). Programs run natively at full speed, with Ubuntu's
own `apt`, `glibc` and `sudo`.

It is optional. AntiGem's built-in environment needs no root and is the right choice for most people. This guide explains
what the Ubuntu chroot is, when it is worth it, every setting with the recommended choice, and the risks.

> **In one line:** use the Ubuntu chroot when you need real Ubuntu packages or Linux programs that do not run in the
> built-in environment, you have root, and you understand that **root inside the chroot is root of your phone**.

---

## Contents

1. [What it is, and what it is not](#1-what-it-is-and-what-it-is-not)
2. [Should you use it?](#2-should-you-use-it)
3. [What you need](#3-what-you-need)
4. [Installing it: the setup page, step by step](#4-installing-it-the-setup-page-step-by-step)
5. [Switching between AntiGem, the Ubuntu chroot and a server](#5-switching-between-antigem-the-ubuntu-chroot-and-a-server)
6. [The Ubuntu chroot page](#6-the-ubuntu-chroot-page)
7. [Shared folders, phone storage and USB devices](#7-shared-folders-phone-storage-and-usb-devices)
8. [Agents inside the chroot](#8-agents-inside-the-chroot)
9. [Closing AntiGem: does the chroot keep running?](#9-closing-antigem-does-the-chroot-keep-running)
10. [Backup, restore and the clean image](#10-backup-restore-and-the-clean-image)
11. [Risks and safety](#11-risks-and-safety)
12. [How it works inside](#12-how-it-works-inside)
13. [What does not work in the chroot (yet)](#13-what-does-not-work-in-the-chroot-yet)
14. [Troubleshooting](#14-troubleshooting)

---

## 1. What it is, and what it is not

**It is:**

- An Ubuntu root filesystem (the folders `/bin`, `/etc`, `/usr`, `/home` … of an Ubuntu install) in a folder on the
  phone's `/data` partition, for example `/data/local/antigem/ubuntu-24.04`.
- Entered with `chroot`: programs started inside see that folder as `/`. They use Ubuntu's libraries and tools, but
  Android's Linux kernel. Nothing is emulated, so they run at full speed.
- Where AntiGem's IDE bridge, the agents and the terminal run while it is chosen in **Settings → Where agents run**.
- Managed by AntiGem: installing, starting, stopping, mounting, packages, backups and deleting are all done from the app.

**It is not:**

- **Not proot.** proot fakes root and translates every file access in software (slow, and some programs break). The
  chroot uses real root and no translation.
- **Not a virtual machine or a container sandbox.** It shares the phone's kernel, network and processes. A chroot only
  changes what `/` means. **Root inside it can do anything to the phone.** See [Risks](#11-risks-and-safety).
- **Not a full Ubuntu boot.** There is no `systemd`, no login manager and no services that start by themselves. You
  start what you need (a database, a web server) yourself, like in a terminal.

---

## 2. Should you use it?

| | AntiGem (built in) | Ubuntu chroot | Your server |
|---|---|---|---|
| Root needed | No | **Yes** (Magisk, KernelSU, APatch) | No |
| Packages | `pkg install` (Termux packages, built for Android) | `apt install` (all of Ubuntu) | Whatever the server has |
| Linux programs downloaded from the web | Often do not run (Android's libc) | Run (glibc), arm64 / x86_64 builds | Run |
| Speed | Native | Native | Your server's |
| Battery and storage | Light | About 1–3 GB, same battery | Nothing on the phone |
| Works without internet to a server | Yes | Yes | No |
| Isolation from the phone | The app's own sandbox | **None for root** | Separate machine |
| Setup | One download | One page, a few minutes | A server and SSH |

**Recommended:** stay on **AntiGem** if it does what you need. Choose the **Ubuntu chroot** when you hit its limits:

- you need a package only Ubuntu has, or a prebuilt Linux tool (a database, a compiler, a language runtime release)
  that does not run in the built-in environment;
- an agent keeps failing on Android-specific differences;
- you want `apt`, `sudo` and a standard Linux file layout for the agents.

Choose **a server** when the phone itself is too weak or you want the agents to work while the phone is off.

Both phone environments can be installed at the same time. One of them is in use; switching takes one tap
([section 5](#5-switching-between-antigem-the-ubuntu-chroot-and-a-server)).

---

## 3. What you need

- **A rooted phone** with Magisk, KernelSU or APatch that grants AntiGem root. The first time, your root manager asks
  you to allow AntiGem.
- **Free space on `/data`:** at least 1 GB to install. 2–3 GB is comfortable once packages and agents are in.
- **An arm64 phone** (or the x86_64 build of AntiGem on an x86_64 device). The CPU of the rootfs must match.
- **For the recommended private mount namespace:** the `unshare` and `nsenter` tools. Magisk, KernelSU and APatch ship
  them in their busybox, and recent Android has them in toybox, so this is almost always there. The setup page checks it
  for you.

---

## 4. Installing it: the setup page, step by step

Open it from the **first-launch installer** (the *Ubuntu chroot (root)* card) or from **Settings → Where agents run →
This phone: Ubuntu chroot → Install or find an Ubuntu chroot**. It is a full page with these sections.

### 4.1 This phone

Checks root, the root manager and its version, the CPU and kernel, SELinux, whether the mount tools are there, and the
free space on `/data`. Nothing is installed by this check. **Accept the root manager's prompt** when it appears.

### 4.2 Found on this phone

AntiGem looks for Ubuntu folders that are already there (its registry, the folders in `/data/local/antigem`, and
`/data/local/ubuntu` from the first chroot version).

- **Use this:** an install AntiGem made before (for example after reinstalling the app). It is used again as it is.
- **Adopt…:** a rootfs without AntiGem's manifest (the first chroot version, or one made by another tool). AntiGem
  stops it, writes its manifest and settings, and **moves its user to AntiGem's own uid** (files in that user's home move
  with it). After that it is managed like any other install.

Each one shows its state: **running** (processes inside), **mounted, nothing running** (mounts left behind by an older
launcher) or **stopped**.

### 4.3 Source: where Ubuntu comes from

| Choice | What it is | When to pick it |
|---|---|---|
| **AntiGem Ubuntu (prebuilt)** | Ubuntu 24.04 with sudo, git, curl, Node.js, Python and the Android fixes already set up, from AntiGem's GitHub release | **Recommended** once it is published (the option says *not published yet* until then). Quickest start. |
| **Ubuntu 24.04 LTS (Noble)** | Official *Ubuntu Base* from `cdimage.ubuntu.com`, about 30 MB. AntiGem picks the newest point release (24.04.5 …) and checks it against Ubuntu's `SHA256SUMS` | **Recommended** otherwise: long support, what most tools are tested on. |
| Ubuntu 26.04 LTS | The newest LTS | When you want newer packages. |
| Ubuntu 22.04 LTS (Jammy) | The older LTS | Only for tools that need it. |
| Another URL | Any rootfs `.tar.gz` / `.tar.xz` (Debian, Kali, Arch ARM …), with an optional sha256 | Advanced. Non-Ubuntu systems may need manual fixes. |
| A file on this phone | An AntiGem backup, or any rootfs archive you downloaded | Restoring a backup ([section 10](#10-backup-restore-and-the-clean-image)). |

### 4.4 Location: where the folder goes

The rootfs needs Linux permissions, owners and the right to run programs, so it **must be on `/data`**. `/sdcard` cannot
hold it (no Linux permissions, no `exec`).

| Choice | What happens | Recommendation |
|---|---|---|
| **`/data/local/antigem/<name>`** | Stays when AntiGem is uninstalled or its data is cleared, and is found again after a reinstall (*Found on this phone → Use this*) | **Recommended.** Your Ubuntu survives app reinstalls. |
| AntiGem's app storage | Inside the app's own data: removed together with the app (uninstall or *Clear data*). Only the private namespace is allowed here. | When you want no trace left after uninstalling. |
| Another folder on `/data` | Any folder you type, checked against Android's and the root manager's own folders | Advanced. |

The *Name* (for example `ubuntu-24.04`) is only the folder name and the label in the app.

### 4.5 User

The normal user the bridge, the agents and your terminal run as.

- **User name:** lowercase letters, digits, `_` and `-` (default `ubuntu`).
- **It gets AntiGem's own uid** (for example 10789), not Ubuntu's usual 1000. Why:
  - on Android, uid 1000 is the **system** user, with extra rights. A chroot user should not be that.
  - with AntiGem's uid, files in folders shared with the app (see [section 7](#7-shared-folders-phone-storage-and-usb-devices))
    belong to both sides, so both can read and write them.
  - if AntiGem is reinstalled and gets a new uid, the user follows it automatically at the next start.
- **Password:** optional. Without one, the user can only become root through *sudo without a password*.
- **sudo without a password:** on by default, **recommended for agents**, so they can `apt install` what they need without
  asking. **Risk:** an agent that runs `sudo` gets root of your phone ([section 11](#11-risks-and-safety)). Turn it off
  (and set a password) if you want every `sudo` to need you.

### 4.6 Options

All of these can be changed later on the chroot's own page.

- **Phone storage at `/sdcard`** (on): your phone's files (Download, DCIM …) inside Ubuntu.
  - **Read-only** (off): programs in Ubuntu can read the phone's files but never change or delete them. **Turn it on if
    agents should not touch your files.**
  - **USB drives and SD cards (`/storage`)** (on): every storage volume at `/storage/…`, including drives plugged in
    while the chroot runs. Off while `/sdcard` is read-only (otherwise the phone's storage would be writable through
    `/storage`).
- **Automatic DNS** (on): at every start, Ubuntu's `/etc/resolv.conf` gets the phone's current DNS servers (plus
  1.1.1.1 and 8.8.8.8 as fallbacks). Turn it off to set your own servers.
- **Mount namespace:**
  - **Private (recommended):** the chroot's mounts (`/dev`, `/proc`, `/sdcard`, shared folders …) exist only for the
    chroot's own processes. Outside, the folders inside the rootfs are just empty folders. So **nothing outside can reach
    your files through the rootfs**: not a root file manager, not a manual `rm -rf`, not Android deleting the app's data.
    USB drives plugged in later still show up.
  - **Global (whole phone):** the mounts are visible to the whole phone until the chroot is stopped (like Linux Deploy
    does). For advanced users who want to see the chroot's mounts from other root tools. **Risk:** deleting the rootfs
    folder with another tool while it is mounted would go into `/sdcard`, USB drives and shared folders. Not allowed for
    installs in the app's storage.
- **Shared folders:** see [section 7](#7-shared-folders-phone-storage-and-usb-devices).

### 4.7 Packages

Installed with `apt` right after the rootfs.

- **Essentials (always):** sudo, certificates, curl, wget, git, ripgrep, procps, tzdata, less, nano, unzip, xz-utils,
  gnupg, openssh-client. The bridge and the agents need these.
- **Python** and **Node.js 22 LTS** (on by default): most agents and MCP servers use them. Node.js comes from NodeSource
  because Ubuntu's own is older.
- **Build tools:** build-essential, pkg-config, cmake, make.
- **Command-line extras:** htop, tmux, jq, zip, file, vim, ping, dig.
- **Android tools:** adb, fastboot, lsusb, to flash or debug another phone over USB OTG.
- **More packages:** any Ubuntu package names, separated by spaces.

### 4.8 Installing

The page shows each step with its progress and a live log:

1. **Check root and space**
2. **Download** (skipped for a file on the phone; a download that is already there is reused)
3. **Verify the checksum** (sha256; a damaged download is deleted)
4. **Extract the rootfs** (as root, owners and permissions kept)
5. **Set up Ubuntu and the user** (Android's network groups, the user, sudo, DNS)
6. **Install packages**
7. **Finish** (registers the install, puts this app's bridge into it, makes it the one in use)

The screen stays on while it works. **Continue in background** hides the page and the install goes on. If a step
fails, **Retry** continues from that step (a failed download or extraction starts over and cleans up its own half-done
folder).

---

## 5. Switching between AntiGem, the Ubuntu chroot and a server

**Settings → Where agents run → Agents run on:**

- **This phone:** **AntiGem** or **Ubuntu chroot**
- **Your server:** **Server** or **SSH tunnel** ([docs/remote-server.md](remote-server.md))

Pick one and tap **Save**. AntiGem asks to reopen, and comes back on the chosen runtime. Under the choice you only see
what belongs to it: AntiGem's install and its paths, the chroot's installs and its paths, or the server's settings.

Good to know:

- **Each runtime has its own home**: its own agent logins, chats, settings and projects. Switching does not move them.
  Share a folder ([section 7](#7-shared-folders-phone-storage-and-usb-devices)) if you want the same projects in both.
- **Ports:** the chroot's bridge and hub listen on AntiGem's own ports **+ 100** (for example 1336 / 1337 when
  AntiGem uses 1236 / 1237). A chroot bridge that is still running can then never block AntiGem's own bridge after a
  switch. The addresses change with the choice by themselves; addresses you typed by hand are kept.
- **Leaving the chroot** (to AntiGem or a server) stops the chroot's bridge, so it does not keep running in the
  background. Its mounts stay until you stop it on its page.
- **Installing AntiGem's environment while the chroot is in use** switches to it when the install finishes: the chroot's
  bridge stops, terminals close and the bridge starts in AntiGem.
- **Agent paths:** each runtime has its own paths for the bridge, agy, Claude Code, OpenCode and Codex
  (*Agent paths in AntiGem*, *Agent paths in the Ubuntu chroot*, the server's). **Leave them empty**: the defaults are
  shown in grey and work. Only change them if you put an agent somewhere else on purpose.

---

## 6. The Ubuntu chroot page

**Settings → Where agents run → Ubuntu chroot → Manage ›** (one page per install).

### Overview (top)

- **State:**
  - **Running · N processes** (and for how long)
  - **Mounted, nothing running** (mounts are still there, for example left by an older launcher)
  - **Stopped**
- **Uses X GB · Y GB free on /data:** measured when the page opens.
- **Bridge running (pid …)** for the install in use.
- **Buttons:**
  - **Stop:** stops every process in it (the bridge and agents too) and unmounts everything. Quick.
  - **Start:** for the install in use, starts the chroot and the bridge, and follows it until the bridge is up.
    Otherwise only mounts it.
  - For the install in use: **Restart bridge**, **Terminal** (a shell as your user), **Root shell** (`sudo -i`).
  - **Refresh.**
  - **Delete this Ubuntu…** and **Forget (keep files)** (removes it from AntiGem's list only).

**Delete** first lists everything still mounted inside the folder, in **every** mount namespace of the phone (for
example `/system (Android's namespace)` left by an older launcher). These are unmounted first, and what they show is
never deleted: the delete refuses while anything stays mounted, and never crosses into another filesystem. You type the
name to confirm.

### Mounts

What is mounted inside it right now, with **Unmount** for each (programs using it may stop working until a restart).

### Settings

`/sdcard` (and read-only), `/storage`, DNS, the `/dev/shm` size (shared memory used by browsers and Python; 256 MB by
default), the mount namespace and the shared folders. They are saved into the rootfs (`.antigem/chroot.conf`), so they
travel with it and with its backups. **Mount changes apply at the next start:** Stop, then Start.

### Storage

The total, and how much each part takes (home folders, programs in `/usr`, apt lists and cache, the rest).
**Clean apt cache** frees the downloaded packages and lists.

### Backup & restore

See [section 10](#10-backup-restore-and-the-clean-image).

### Packages

Install the same bundles as at setup, any packages by name, or **Update & upgrade all**. The output shows in a log
window.

### User

Set the password, and choose whether `sudo` asks for it.

### When AntiGem exits

See [section 9](#9-closing-antigem-does-the-chroot-keep-running).

### Processes

Every process running in it, with **Kill**.

### Repair

Sets up Android's network groups, the user (and moves it to AntiGem's uid), DNS and this app's bridge again. Use it
after restoring an old backup, after updating AntiGem, or when something inside stopped working.

### About this phone

The root manager, the mount tools, SELinux, the kernel, where the install came from, and the path of the control script
([section 12](#12-how-it-works-inside)).

---

## 7. Shared folders, phone storage and USB devices

### Shared folders

On the setup page (Options) and on the chroot's page (Settings), **Add a folder**:

1. **The folder on the phone:**
   - **AntiGem home:** the home of AntiGem's built-in environment, shown at `~/antigem_home`, so both runtimes see the
     same projects.
   - **Download, Documents, DCIM, Pictures.**
   - **Browse…** picks any folder on the phone's storage or an SD card.
   - **Android:** `/system`, `/vendor`, `/apex`, `/product` (read-only by default) and `/data/local/tmp`.
   - Or type any path. Root can share any folder, including Android's own and other apps' folders.
2. **Where it shows up inside Ubuntu:** Android's own folders at the same place (`/system` at `/system`), everything else
   at `~/<folder name>`. You can change it (for example `/mnt/photos`).
3. **Read-only**, if Ubuntu should never change or delete anything in it.

A shared folder comes **with the mounts inside it** (each APEX under `/apex`, a drive mounted in a folder), and receives
the ones Android adds later. Read-only covers the folder's own mount; mounts inside it keep their own setting. Running
Android's own programs inside Ubuntu (for example `/system/bin/…`) may need more than these folders; sharing them is for
reading and using their files.

Rules (checked by the app and again by the control script):

- Nothing can be shared onto folders Ubuntu needs (`/`, `/etc`, `/usr`, `/bin`, `/lib`, `/dev`, `/proc`, `/sys`, `/run`,
  `/boot`, `/sdcard`, `/storage`, or the home folder itself). Folders inside `/mnt`, `/media`, `/opt`, `/srv`, `/var`,
  `/tmp` or your home are fine.
- A folder that contains the rootfs itself is skipped, and so is a path that leads outside the rootfs through a symlink.
- One bad folder is skipped with a message; the chroot still starts.

**Inside Ubuntu a shared folder is the real folder.** Deleting something in it (by you or an agent) deletes it on the
phone. Choose read-only for folders that should only be read.

### Phone storage and USB drives

`/sdcard` is your phone's storage. `/storage` holds every volume: the phone's storage, SD cards and USB drives
(at `/storage/XXXX-XXXX`), **including drives plugged in while the chroot runs**. In the private namespace with
*USB drives and SD cards* on, `/sdcard` is a link into `/storage`, so it keeps working when Android remounts its storage
(for example after the media service restarts).

### USB devices: fastboot, adb, serial adapters (OTG)

USB devices are at `/dev/bus/usb`. The chroot's user is in Android's `usb` group, so **fastboot and adb work without
sudo**. Install the *Android tools* package bundle, plug the other phone in over OTG and run `fastboot devices` or
`adb devices`. Serial adapters (`/dev/ttyUSB*`, `/dev/ttyACM*`) may need `sudo`. On an install made before this was
added, run **Repair** once.

---

## 8. Agents inside the chroot

Install the agents from the app as usual (**Settings → each agent → Install**). In the chroot the bridge uses each
agent's official Linux installer:

| Agent | Installed with | Default path |
|---|---|---|
| Antigravity (`agy`) | Google's install script | `~/.local/bin/agy` |
| Claude Code | Anthropic's install script | `~/.local/bin/claude` |
| OpenCode | OpenCode's official Linux build | `~/.opencode/bin/opencode` |
| Codex | OpenAI's installer (`chatgpt.com/codex/install.sh`): the whole package with its own ripgrep, its bwrap sandbox and its exec server | `~/.local/bin/codex` |

The paths can be changed in **Where agents run → Agent paths in the Ubuntu chroot**. Leave them empty unless you moved
an agent on purpose.

Agent logins (`~/.claude`, `~/.codex`, …) live in the chroot user's home, separate from AntiGem's built-in environment.
MCP servers get a normal Linux environment.

**What agents are told** (the built-in part of **Settings → Agents → Global context**, which follows
**Where agents run**): that they run in a rooted Ubuntu chroot on an Android phone (apt, `/tmp` and `/usr` work, no
systemd), which user they are, that `sudo` is root of the **whole phone**, and to **never use `sudo` or `su` on their
own**: only when you ask, and to ask you before `sudo apt install`. AntiGem's built-in environment, a server and a PC
(Connect to PC) each get their own text, with the same rule about root.

> Codex runs its commands in a `bwrap` sandbox. Some Android kernels lack what bwrap needs (user namespaces). If Codex
> installs and starts but its commands fail, turn its sandbox off in Codex's settings.

---

## 9. Closing AntiGem: does the chroot keep running?

The chroot's bridge and agents run as their own processes, outside the AntiGem app. **Android does not kill them** when
you leave AntiGem, swipe it away from recents, or when Android closes the app in the background. So an agent can finish
its work while AntiGem is closed, and AntiGem reconnects when you open it again. A bridge started with the same settings
is reused, so the agents are not interrupted.

They stop only when:

- you stop them (**Stop** on the chroot's page, or Exit with a "stop" choice, below);
- the phone restarts (nothing starts at boot; the next time AntiGem opens, it starts the chroot again);
- the phone runs completely out of memory (rare, and then anything can be closed).

Terminal tabs are the exception: if AntiGem closes, open terminal shells close with it. The agent chats keep running.

**When AntiGem exits** (chroot page) decides what Exit from the side menu or the notification does:

- **Keep running** (default): the bridge and the agents keep working while AntiGem is closed.
- **Stop the bridge and agents:** the chroot's mounts stay, so the next start is quick.
- **Stop everything:** every process of the chroot is stopped and its folders are unmounted.

The side menu's **Exit** dialog also has **Also stop the Ubuntu chroot**, preset from this setting, to choose for that
one exit.

---

## 10. Backup, restore and the clean image

On the chroot's page, **Backup & restore**.

### Back up

1. Choose **.tar.gz (fast)** (default) or **.tar.xz (smaller, slow)**.
2. Tap **Back up…** and pick where to save it, for example `Download/ubuntu-24.04-2026-11-21.tar.gz`.

The backup is one file with everything of this Ubuntu: system, packages, your home, agent logins, chats and the
chroot's settings, with owners and permissions kept. **It never contains** `/proc`, `/sys`, `/dev`, `/sdcard`,
`/storage`, `/tmp`, the contents of shared folders, AntiGem's run files or downloaded `.deb` packages.

It works while the chroot runs, so agents are not interrupted. The log shows the progress, the file size and its sha256.
If the chroot's mounts are in Android's namespace (the global namespace, or leftovers of an older launcher), it asks you
to Stop it first. A failed backup's file is removed.

> **A backup contains your agent logins and tokens.** Keep it private; do not share it.

### Restore

**Restore a backup…** opens the setup page in restore mode: pick the file, a name and a folder.

- It is installed as **a new copy**. Nothing is overwritten, and your current install stays until you delete it.
- An AntiGem backup **keeps its user, home, settings and packages**. Its user moves to this app's uid, so it also works
  on another phone or after reinstalling AntiGem.
- The restored copy appears in your list. Choose it in **Where agents run** to use it.

### Export a clean image (to share)

For publishing a ready-made Ubuntu, for example AntiGem's own prebuilt image.

**Export a clean image…** asks for a folder and writes two files there:

- `ubuntu-24.04-antigem-arm64.tar.xz` (`-amd64` on x86_64), compressed with xz
- the same name with `.sha256` added

The clean image **leaves out** the home folders, root's logins and history (`.claude`, `.codex`, `.gemini`, `.ssh`,
caches …), AntiGem's own files, logs, the apt lists and cache, AntiGem's sudo rule and SSH host keys. Installing it
creates the user again (with Ubuntu's usual home files) and runs `apt update` before installing packages.

**To publish it as AntiGem's prebuilt Ubuntu:** upload both files to the GitHub release **`chroot-ubuntu-v1`** of
`Santoshkurmi/antigem`. The setup page's **AntiGem Ubuntu (prebuilt)** source then turns on by itself and checks the
download against the `.sha256`. Make the image from a fresh install with the packages you want in it, not from your
daily one.

AntiGem's built-in environment keeps its own backups in **Settings → Backups**.

---

## 11. Risks and safety

**Root inside the chroot is root of the phone.** A chroot is not a security sandbox. A process running as root inside
Ubuntu (through `sudo`, or the **Root shell**) can leave the chroot and change anything on the phone: system files,
other apps' data, the root manager.

- **With "sudo without a password" (the default), an agent that runs `sudo` has that power.** Agents are told never to
  use `sudo` or `su` on their own and to ask you first (see [section 8](#8-agents-inside-the-chroot)), but that is an
  instruction, not a lock. To make it a lock, turn it off on the chroot's page (**User → sudo: ask password**, after
  setting a password). Then every `sudo` needs you.
- **The normal user** has AntiGem's own uid and no more rights than the app itself.

**Your files:**

- With `/sdcard` and `/storage` on, programs in Ubuntu (and agents) can change and delete your phone's files. Turn on
  **Read-only** if they should only read them.
- Shared folders are the real folders. A deletion inside Ubuntu deletes them on the phone. Use read-only where it fits.

**Deleting the Ubuntu folder:**

- **Always use Delete on the chroot's page.** It stops everything, checks every namespace for mounts, refuses while
  anything is mounted, and never crosses into another filesystem.
- With the **private** namespace (recommended), deleting the folder some other way is also safe: outside the chroot the
  mount points are empty folders, and `/sdcard` is a link that points into the rootfs itself.
- With the **global** namespace, the mounts are visible to the whole phone. A root file manager or `rm -rf` on the
  rootfs folder **while the chroot is mounted would delete the contents of `/sdcard`, USB drives and shared folders.**
  Stop it first, or use AntiGem's Delete.
- A rootfs in **AntiGem's app storage** is deleted by Android with the app (uninstall, *Clear data*). That is why only
  the private namespace is allowed there, and why AntiGem stops the chroot before it clears its own data.

**Other things to know:**

- **SELinux:** processes in the chroot run in your root manager's SELinux context, which is not restricted. SELinux does
  not protect the phone from them.
- **USB:** with an OTG cable, programs in the chroot can talk to connected USB devices directly (that is what
  fastboot needs). A wrong `fastboot flash` can break the device it targets.
- **Root manager notifications:** Magisk shows a "granted superuser" toast each time AntiGem uses root (every terminal,
  every status refresh). You can turn it off for AntiGem in Magisk's superuser list.
- **Updates:** Ubuntu's packages are updated with **Update & upgrade all** (or `sudo apt upgrade`), not by AntiGem. The
  bridge inside is replaced with this app's version at every start.

---

## 12. How it works inside

For the curious and for troubleshooting.

### The control script

All root work goes through one shell script, `chrootctl.sh`. The app keeps it in its own files
(`/data/user/0/<package>/files/chroot/chrootctl.sh`, shown under **About this phone**) and runs it with
`su -c 'sh …/chrootctl.sh <command> --root <rootfs> …'`. Every argument is a checked path or name, or base64 (folders,
commands), so nothing in them can inject shell code. Each command runs and exits. Only two things it starts keep running:
the **holder** and the **bridge**.

Useful from any root shell:

```sh
CTL=/data/user/0/<package>/files/chroot/chrootctl.sh
su -c "sh $CTL status --root /data/local/antigem/ubuntu-24.04"      # state, mounts, processes
su -c "sh $CTL mounts-all --root /data/local/antigem/ubuntu-24.04"  # mounts in every namespace
su -c "sh $CTL stop --root /data/local/antigem/ubuntu-24.04"        # stop everything, unmount
su -c "sh $CTL scan"                                                # rootfs folders on the phone
```

Other commands: `start`, `exec`, `bridge-start`, `bridge-stop`, `bridge-log`, `du`, `umount`, `kill`, `backup`, `delete`,
`probe`.

### The private mount namespace

On **Start**, a tiny **holder** process (`sleep`) is started in a mount namespace of its own, cloned from Android's main
one. All the chroot's mounts are made in it:

- the rootfs bound onto itself (to allow `sudo`'s setuid on `/data`);
- `/dev`, `/dev/pts`, a `/dev/shm` of its own, `/proc`, `/sys`;
- `/storage` and `/sdcard`, and your shared folders.

The bridge and every terminal join that namespace (`nsenter`), then `chroot` into the rootfs and become your user
(`su -l`). **Stop** kills every process in the namespace and the holder, and the mounts disappear with it.

The namespace is a **slave** of Android's: what Android mounts later (a USB drive, an SD card, its storage remounted)
flows in, and what the chroot mounts never flows out. In **global** mode there is no holder; the mounts are made in
Android's main namespace instead.

### Long-running processes

- **The holder and the bridge** are started detached (`setsid`) and moved out of the app's process group, so Android
  does not stop them with the app. The bridge writes its output to `<rootfs>/.antigem/run/bridge.log`, and AntiGem's
  *Server Runner* terminal shows that log.
- **Terminal tabs** get the tab as their controlling terminal (job control, Ctrl+C and Ctrl+Z work, and so does `sudo su`).
- The bridge is this app's own **Linux** build (the one used by AntiGem's built-in environment needs Android's linker).
  It is copied in at every start when the app has a newer one.

### Files

| Where | What |
|---|---|
| `<rootfs>/.antigem/manifest` | Name, Ubuntu version, CPU, user, uid, source, creation date |
| `<rootfs>/.antigem/chroot.conf` | The chroot's settings (mounts, DNS, namespace, shared folders) |
| `<rootfs>/.antigem/run/` | holder and bridge pids, the bridge's log |
| `/data/local/antigem/registry` | The rootfs folders AntiGem installed (to find them after a reinstall) |

### Android specifics it handles

- **Network:** Android only lets members of group 3003 open sockets ("paranoid networking"). The user and root are in
  `aid_inet` (3003) and `aid_net_raw` (3004), and apt's sandbox user is turned off.
- **DNS:** Android has no `/etc/resolv.conf`; the phone's DNS servers are written into Ubuntu's at every start.
- **`/data` is `nosuid`:** the rootfs is bound onto itself without it, so `sudo` works.
- **No `/dev/shm` on Android:** the chroot gets its own.

---

## 13. What does not work in the chroot (yet)

These pages use the tools of AntiGem's built-in environment, so they show a notice while the Ubuntu chroot is in use:

- **REA** (reverse engineering) and **Android development**
- **Termux:API** and the **X11 desktop**
- **Settings → Backups** (the chroot has its own, [section 10](#10-backup-restore-and-the-clean-image))
- **Settings → GitHub** (the GitHub CLI page). Cloning repositories works: it goes through the bridge.

Also:

- `termux-*` commands do not exist inside Ubuntu.
- Mini-apps and the EGM design reviews keep their folders in AntiGem's built-in home. Share **AntiGem home** into the
  chroot ([section 7](#7-shared-folders-phone-storage-and-usb-devices)) if agents should work on them.

---

## 14. Troubleshooting

| What you see | What to do |
|---|---|
| **No root access** | Allow AntiGem in your root manager (Magisk → Superuser, KernelSU / APatch → app list), then **Check again**. |
| **The private mount namespace needs unshare and nsenter** | Your root manager has no busybox with them. Choose **Global** under Options (and mind its risk). |
| **Could not make the mount namespace a slave** | Same as above: use **Global**. |
| An install shows **Mounted, nothing running** | Mounts left by an older launcher. **Stop** unmounts them. |
| **Backing up asks to Stop first** | Its mounts are in Android's namespace (global mode or leftovers). Stop it, back up, Start. |
| **Delete refuses**: "still mounted" | The dialog lists what and where. Stop, then Delete again. If it stays, restart the phone (that removes every mount), then Delete. |
| The bridge does not come up | Read its log: the *Server Runner* terminal, or `<rootfs>/.antigem/run/bridge.log`. Then **Repair**, and **Restart bridge**. |
| `apt` or downloads fail ("temporary failure resolving") | Switch DNS to custom servers your network allows. Check that the phone itself is online. |
| A USB drive is not in `/storage` | Turn on **USB drives and SD cards** (not possible with read-only `/sdcard`), save, Stop, Start. |
| `fastboot` / `adb` cannot open the device | Run **Repair** once (it adds the user to Android's usb group), unplug and plug in again. |
| Codex commands fail, but Codex runs | Its bwrap sandbox may need kernel features the phone lacks: turn the sandbox off in Codex's settings. |
| Magisk shows a toast all the time | Turn notifications off for AntiGem in Magisk's superuser list. |
| After reinstalling AntiGem the Ubuntu is gone from the list | Setup page → **Found on this phone → Use this** (the folder in `/data/local/antigem` is still there). |
| Something inside stopped working after an update | **Repair** on the chroot's page. |

Still stuck? Open an issue with the log from the setup page or the task window, and the **About this phone** details.
