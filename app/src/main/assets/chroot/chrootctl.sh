#!/system/bin/sh
# AntiGem chroot control. Runs as root (Magisk / KernelSU / APatch su); the app writes it to files/chroot/ and runs
#   su -c 'sh <files>/chroot/chrootctl.sh <command> --root <rootfs> [options]'
# Every argument is a plain path / name / number or base64 (commands, folders), so nothing is re-quoted by a shell.
#
# Mount namespaces:
#   private (default) a holder process owns a mount namespace of its own (cloned from init's) with every mount of the
#                     chroot in it; the bridge and the terminals join it (nsenter). Outside it <rootfs>/sdcard is an empty
#                     folder, so nothing outside (a root file manager, a manual rm -rf, Android's uninstall) can reach
#                     the phone's storage through the rootfs. Stopping = killing the processes in it: the mounts go away.
#                     It is a SLAVE of init's namespace: what Android mounts later (a USB drive, an SD card, its storage
#                     remounted) flows in; what the chroot mounts never flows out.
#   global            the mounts are made in init's namespace (visible to the whole phone) until stop or reboot.
CTL_VERSION=1
SELF="$0"
BASE_DIR=/data/local/antigem
REGISTRY=$BASE_DIR/registry
CPATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

die() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------- tools: the root manager's busybox first, then toybox
BB=""
for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
    if [ -x "$b" ]; then BB="$b"; break; fi
done
BB_LIST=""
[ -n "$BB" ] && BB_LIST=" $("$BB" --list 2>/dev/null | tr '\n' ' ') "
tool() {
    case "$BB_LIST" in *" $1 "*) echo "$BB $1"; return 0;; esac
    p=$(command -v "$1" 2>/dev/null)
    case "$p" in /*) echo "$p";; esac
}
UNSHARE=$(tool unshare)
NSENTER=$(tool nsenter)
MOUNT=$(tool mount)
UMOUNT=$(tool umount)
MOUNTPOINT=$(tool mountpoint)
SETSID=$(tool setsid)
SLEEP=$(tool sleep)
TAIL=$(tool tail)
FIND=$(tool find)
DU=$(tool du)
CMP=$(tool cmp)
BASE64=$(tool base64)
TAR=$(tool tar)
ENV=$(tool env)
CHROOT=$(tool chroot)
[ -n "$MOUNT" ] || MOUNT=mount
[ -n "$UMOUNT" ] || UMOUNT=umount
[ -n "$CHROOT" ] || CHROOT=chroot
[ -n "$ENV" ] || ENV=env
NS1=""
[ -n "$NSENTER" ] && NS1="$NSENTER -t 1 -m"

# ---------------------------------------------------------------- arguments
CMD="$1"
[ $# -gt 0 ] && shift
ROOT=""; USER_NAME=""; AS_ROOT=0; CLEAN=0; CWD_B64=""; RUN_B64=""; DNS_ARG=""; TZ_ARG=""; BRIDGE_SRC=""
TARGET=""; TERM_ARG="xterm-256color"; META_NAME=""; PID_ARG=""; EXTRA_PATH=""; EXEC_FILE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --root) ROOT="$2"; shift 2;;
        --user) USER_NAME="$2"; shift 2;;
        --as-root) AS_ROOT=1; shift;;
        --clean) CLEAN=1; shift;;
        --cwd) CWD_B64="$2"; shift 2;;
        --cmd) RUN_B64="$2"; shift 2;;
        --dns) DNS_ARG="$2"; shift 2;;
        --tz) TZ_ARG="$2"; shift 2;;
        --bridge) BRIDGE_SRC="$2"; shift 2;;
        --path) TARGET="$2"; shift 2;;
        --term) TERM_ARG="$2"; shift 2;;
        --name) META_NAME="$2"; shift 2;;
        --pid) PID_ARG="$2"; shift 2;;
        --extra) EXTRA_PATH="$2"; shift 2;;
        --file) EXEC_FILE="$2"; shift 2;;
        *) die "unknown option: $1";;
    esac
done

plain() { case "$1" in *[!A-Za-z0-9._/-]*) return 1;; esac; return 0; }

# a rootfs path: under /data, normalized, plain characters, never a system or storage folder
check_root_path() {
    R="${1%/}"
    [ -n "$R" ] || die "no --root given"
    plain "$R" || die "unsupported characters in the path: $R"
    case "$R" in
        */../*|*/..|*/./*|*/.|*//*) die "the path must be normalized: $R";;
        /data/*) ;;
        *) die "the rootfs must be on /data: $R";;
    esac
    case "$R" in
        /data/local|/data/local/tmp|/data/local/tmp/*|$BASE_DIR|/data/media|/data/media/*|/data/data|/data/user|/data/user_de|/data/user_de/*|/data/adb|/data/adb/*|/data/app|/data/app/*|/data/system|/data/system/*|/data/system_ce|/data/system_ce/*|/data/system_de|/data/system_de/*|/data/misc|/data/misc/*|/data/vendor|/data/vendor/*|/data/dalvik-cache|/data/dalvik-cache/*)
            die "refusing to use $R as a rootfs";;
        /data/data/*/*|/data/user/*/*/*) ;;
        /data/data/*|/data/user/*|/data/user/*/*) die "refusing to use $R as a rootfs";;
    esac
    ROOT="$R"
    META="$ROOT/.antigem"
    RUN="$META/run"
    CONF="$META/chroot.conf"
}

# ---------------------------------------------------------------- the chroot's settings (.antigem/chroot.conf)
NS_MODE=private; SDCARD=1; SDCARD_RO=0; STORAGE=1; SHM_MB=256; DNS_MODE=auto; DNS_CUSTOM=""; BINDS=""
read_conf() {
    [ -f "$CONF" ] || return 0
    while IFS='=' read -r k v; do
        case "$k" in
            NS_MODE) case "$v" in private|global) NS_MODE="$v";; esac;;
            SDCARD) case "$v" in 0|1) SDCARD="$v";; esac;;
            SDCARD_RO) case "$v" in 0|1) SDCARD_RO="$v";; esac;;
            STORAGE) case "$v" in 0|1) STORAGE="$v";; esac;;
            SHM_MB) case "$v" in ''|*[!0-9]*) ;; *) SHM_MB="$v";; esac;;
            DNS_MODE) case "$v" in auto|custom) DNS_MODE="$v";; esac;;
            DNS_CUSTOM) DNS_CUSTOM="$v";;
            BINDS) BINDS="$v";;
        esac
    done < "$CONF"
}

# ---------------------------------------------------------------- namespace / processes
holder_pid() {
    hp=$(cat "$RUN/holder.pid" 2>/dev/null)
    hn=$(cat "$RUN/holder.ns" 2>/dev/null)
    case "$hp" in ''|*[!0-9]*) return 1;; esac
    [ -d "/proc/$hp" ] || return 1
    [ -n "$hn" ] && [ "$(readlink /proc/$hp/ns/mnt 2>/dev/null)" = "$hn" ] || return 1
    echo "$hp"
}

# the prefix that runs a program in the namespace the chroot's mounts live in
ns_prefix() {
    if [ "$NS_MODE" = private ]; then
        hp=$(holder_pid) || die "the chroot is not started"
        echo "$NSENTER -t $hp -m"
    else
        echo "$NS1"
    fi
}

# every process running in this chroot (in the holder's namespace, or with the rootfs as its root), not the holder.
# test -ef (same device and inode) is a shell builtin: no process per /proc entry.
chroot_pids() {
    cp_h=$(holder_pid 2>/dev/null)
    for d in /proc/[0-9]*; do
        p=${d#/proc/}
        [ "$p" = "$cp_h" ] && continue
        if [ -n "$cp_h" ] && [ "$d/ns/mnt" -ef "/proc/$cp_h/ns/mnt" ] 2>/dev/null; then echo "$p"; continue; fi
        [ "$d/root" -ef "$ROOT" ] 2>/dev/null && echo "$p"
    done
}

kill_pids() {  # TERM, up to 3 s, then KILL
    [ -n "$1" ] || return 0
    kill -TERM $1 2>/dev/null
    i=0
    while [ $i -lt 30 ]; do
        alive=""
        for p in $1; do [ -d "/proc/$p" ] && alive="$alive $p"; done
        [ -z "$alive" ] && return 0
        $SLEEP 0.1
        i=$((i + 1))
    done
    kill -KILL $alive 2>/dev/null
    $SLEEP 0.2
}

# $1 and all its descendants
tree_pids() {
    tp_root=$1
    pairs=""
    for d in /proc/[0-9]*; do
        read -r line 2>/dev/null < "$d/stat" || continue
        rest=${line##*) }
        set -- $rest
        pairs="$pairs ${d#/proc/}:$2"
    done
    want=" $tp_root "
    changed=1
    while [ $changed = 1 ]; do
        changed=0
        for pair in $pairs; do
            p=${pair%:*}; pp=${pair#*:}
            case "$want" in *" $pp "*) case "$want" in *" $p "*) ;; *) want="$want$p "; changed=1;; esac;; esac
        done
    done
    echo $want
}

# leave the app's cgroup: Android kills an app's whole process group when it stops the app, and the chroot may keep running
leave_app_cgroup() {
    for cg in /sys/fs/cgroup /acct; do
        [ -w "$cg/cgroup.procs" ] && echo $$ > "$cg/cgroup.procs" 2>/dev/null
    done
}

# ---------------------------------------------------------------- mounts
is_mp() {
    if [ -n "$MOUNTPOINT" ]; then $MOUNTPOINT -q "$1" 2>/dev/null; return $?; fi
    while read -r _ _ _ _ mp _; do [ "$mp" = "$1" ] && return 0; done < /proc/self/mountinfo
    return 1
}

# mount points under the rootfs in the namespace of process $1, deepest first
mounts_under() {
    while read -r _ _ _ _ mp _; do
        case "$mp" in "$ROOT"|"$ROOT"/*) echo "$mp";; esac
    done 2>/dev/null < "/proc/$1/mountinfo" | sort -r
}

# a mount target must be a real folder of the rootfs: a symlink (the target, or a folder above it, e.g. one a program in
# the chroot made in its home) would point the mount anywhere on the phone
safe_target() {
    [ -L "$1" ] && die "refusing to mount on a symlink: $1"
    # the rootfs' own real path (/data/user/0 is a link to /data/data on many phones)
    st_root=$(readlink -f "$ROOT" 2>/dev/null)
    [ -n "$st_root" ] || die "cannot resolve $ROOT"
    if [ ! -d "$1" ]; then
        # check the nearest existing folder before creating anything below it
        st_up=$1
        while [ ! -e "$st_up" ] && [ ! -L "$st_up" ]; do st_up=${st_up%/*}; done
        st_upreal=$(readlink -f "$st_up" 2>/dev/null)
        case "$st_upreal" in
            "$st_root"|"$st_root"/*) ;;
            *) die "refusing to create $1: it leads outside the rootfs (${st_upreal:-unresolved})";;
        esac
        mkdir -p "$1" || die "cannot create $1"
    fi
    st_real=$(readlink -f "$1" 2>/dev/null)
    case "$st_real" in
        "$st_root"/*) ;;
        *) die "refusing to mount on $1: it leads outside the rootfs (${st_real:-unresolved})";;
    esac
}

SDCARD_LINK=storage/emulated/0

# /sdcard as a RELATIVE link into the /storage bind: it follows Android's storage when it is remounted (a bind of the
# FUSE mount itself would go stale). Seen from outside the chroot it points at <rootfs>/storage, an empty folder there
# in the private namespace, so no tool outside can reach the phone's files through it.
sdcard_link() {
    t="$ROOT/sdcard"
    if [ -L "$t" ]; then
        [ "$(readlink "$t")" = "$SDCARD_LINK" ] && return 0
        echo "$t is a symlink AntiGem did not make: leaving it" >&2
        return 1
    fi
    if [ -d "$t" ]; then
        is_mp "$t" && return 1
        rmdir "$t" 2>/dev/null || { echo "$t is not empty: binding it instead" >&2; return 1; }
    fi
    ln -s "$SDCARD_LINK" "$t"
}

sdcard_bind() {
    t="$ROOT/sdcard"
    if [ -L "$t" ]; then
        [ "$(readlink "$t")" = "$SDCARD_LINK" ] || { echo "$t is a symlink AntiGem did not make: not mounting /sdcard" >&2; return 0; }
        rm -f "$t"
    fi
    src=$(sdcard_source)
    [ -n "$src" ] || return 0
    safe_target "$t"
    if ! is_mp "$t"; then
        $MOUNT -o bind "$src" "$t" && [ "$SDCARD_RO" = 1 ] && $MOUNT -o remount,bind,ro "$t"
    fi
    return 0
}

sdcard_source() {
    for s in /storage/emulated/0 /sdcard /data/media/0; do
        [ -d "$s" ] && { echo "$s"; return 0; }
    done
}

do_mounts() {
    for d in dev proc sys tmp; do safe_target "$ROOT/$d"; done
    if ! is_mp "$ROOT"; then
        $MOUNT -o bind "$ROOT" "$ROOT" || die "cannot bind $ROOT"
    fi
    # /data is nosuid on Android: sudo needs suid on the rootfs
    $MOUNT -o remount,bind,suid,dev,exec "$ROOT" 2>/dev/null || $MOUNT -o remount,suid "$ROOT" 2>/dev/null
    is_mp "$ROOT/dev" || $MOUNT -o bind /dev "$ROOT/dev" || die "cannot mount /dev"
    # Android's own /dev/pts, so the terminal's pty is visible inside (tty, sudo, tmux, ssh)
    is_mp "$ROOT/dev/pts" || $MOUNT -o bind /dev/pts "$ROOT/dev/pts" || $MOUNT -t devpts devpts "$ROOT/dev/pts"
    [ -d "$ROOT/dev/shm" ] || mkdir -p "$ROOT/dev/shm"
    is_mp "$ROOT/dev/shm" || $MOUNT -t tmpfs -o "size=${SHM_MB}m,mode=1777,nosuid,nodev" tmpfs "$ROOT/dev/shm"
    is_mp "$ROOT/proc" || $MOUNT -t proc proc "$ROOT/proc" || die "cannot mount /proc"
    is_mp "$ROOT/sys" || $MOUNT -t sysfs sysfs "$ROOT/sys" || $MOUNT -o bind /sys "$ROOT/sys"
    [ -e "$ROOT/dev/fd" ] || ln -s /proc/self/fd "$ROOT/dev/fd" 2>/dev/null
    [ -e "$ROOT/dev/stdin" ] || ln -s /proc/self/fd/0 "$ROOT/dev/stdin" 2>/dev/null
    [ -e "$ROOT/dev/stdout" ] || ln -s /proc/self/fd/1 "$ROOT/dev/stdout" 2>/dev/null
    [ -e "$ROOT/dev/stderr" ] || ln -s /proc/self/fd/2 "$ROOT/dev/stderr" 2>/dev/null
    # /storage: the phone's storage and every USB drive / SD card, also those plugged in after the start (a recursive
    # slave bind: Android's mounts flow in, the chroot's never flow out). Not with a read-only /sdcard: a remount ro
    # covers only the top mount, so /storage/emulated/0 would stay writable through it.
    want_storage=0
    [ "$SDCARD" = 1 ] && [ "$SDCARD_RO" != 1 ] && [ "$STORAGE" = 1 ] && [ -d /storage ] && want_storage=1
    if [ "$want_storage" = 1 ]; then
        safe_target "$ROOT/storage"
        if ! is_mp "$ROOT/storage"; then
            $MOUNT --rbind /storage "$ROOT/storage" 2>/dev/null || $MOUNT -o rbind /storage "$ROOT/storage" || want_storage=0
            if [ "$want_storage" = 1 ]; then
                $MOUNT --make-rslave "$ROOT/storage" 2>/dev/null || $MOUNT -o rslave "$ROOT/storage" 2>/dev/null
                if grep -F " $ROOT/storage " /proc/self/mountinfo | grep -q " shared:"; then
                    # it would send the chroot's mounts out to the phone: not without the slave mark
                    echo "skipping /storage: cannot mark it as a slave mount" >&2
                    $UMOUNT -l "$ROOT/storage" 2>/dev/null
                    want_storage=0
                fi
            fi
        fi
    fi
    if [ "$SDCARD" = 1 ]; then
        if [ "$want_storage" = 1 ] && [ "$NS_MODE" = private ] && sdcard_link; then
            :
        else
            sdcard_bind
        fi
    fi
    # shared folders: "src|dst|ro;src|dst|rw" (the phone folder may have spaces; the path inside is plain)
    if [ -n "$BINDS" ]; then
        old_ifs=$IFS; IFS=';'
        # no globbing: a folder named like "Photos [2024]" is a name, not a pattern
        set -f
        set -- $BINDS
        set +f
        IFS=$old_ifs
        for e in "$@"; do
            bsrc=${e%%|*}; brest=${e#*|}; bdst=${brest%%|*}; bmode=${brest#*|}
            bsrc=${bsrc%/}; bdst=${bdst%/}
            case "$bsrc" in /?*) ;; *) echo "skipping bind, not a folder path: $bsrc" >&2; continue;; esac
            case "$bsrc" in */..|*/../*|*/.|*/./*) echo "skipping bind with . or ..: $bsrc" >&2; continue;; esac
            plain "$bdst" || { echo "skipping bind onto $bdst: unsupported characters" >&2; continue; }
            case "$bdst" in /?*) ;; *) continue;; esac
            case "$bdst" in */..|*/../*|*/.|*/./*) continue;; esac
            case "$bdst" in
                /bin|/boot|/dev|/etc|/lib|/lib32|/lib64|/libx32|/proc|/run|/sbin|/sys|/usr|/var|/tmp|/home|/root|/mnt|/media|/opt|/srv|/sdcard|/storage|\
                /bin/*|/boot/*|/dev/*|/etc/*|/lib/*|/lib32/*|/lib64/*|/libx32/*|/proc/*|/run/*|/sbin/*|/sys/*|/usr/*|/sdcard/*|/storage/*)
                    echo "skipping bind onto $bdst: a folder Ubuntu needs" >&2; continue;;
            esac
            [ -e "$bsrc" ] || { echo "skipping bind, missing: $bsrc" >&2; continue; }
            # a folder holding the rootfs would show the rootfs inside itself
            case "$ROOT/" in "${bsrc%/}"/*) echo "skipping bind of $bsrc: the rootfs is inside it" >&2; continue;; esac
            # one bad folder (e.g. a symlink made in the home) skips that bind, not the whole start
            if ! ( safe_target "$ROOT$bdst" ); then continue; fi
            if ! is_mp "$ROOT$bdst"; then
                # recursive: a folder with mounts inside it (Android's /apex, a drive folder) comes with them. As a
                # slave it receives the source's later mounts and never sends the chroot's out (global mode).
                # Read-only covers the folder's own mount, not other mounts inside it.
                if $MOUNT --rbind "$bsrc" "$ROOT$bdst" 2>/dev/null || $MOUNT -o rbind "$bsrc" "$ROOT$bdst"; then
                    $MOUNT --make-rslave "$ROOT$bdst" 2>/dev/null || $MOUNT -o rslave "$ROOT$bdst" 2>/dev/null
                    [ "$bmode" = ro ] && $MOUNT -o remount,bind,ro "$ROOT$bdst"
                fi
            fi
        done
    fi
    return 0
}

# receive Android's mounts, never send ours: every mount becomes a slave (a private one would freeze the list as it
# was at the start, so a USB drive plugged in later would never show up)
make_slave() {
    $MOUNT --make-rslave / 2>/dev/null || $MOUNT -o rslave / 2>/dev/null
    while read -r _ _ _ _ mp _ rest; do
        case " $rest " in *" shared:"*) echo "ERROR: could not make the mount namespace a slave (mount $mp is still shared)" >&2; return 1;; esac
    done < /proc/self/mountinfo
    return 0
}

start_mounts() {
    mkdir -p "$RUN"
    if [ "$NS_MODE" = private ]; then
        holder_pid >/dev/null && return 0
        [ -n "$UNSHARE" ] && [ -n "$NSENTER" ] || die "the private mount namespace needs unshare and nsenter (busybox of Magisk / KernelSU / APatch, or toybox). Switch the chroot to the global namespace in its settings."
        rm -f "$RUN/holder.pid" "$RUN/holder.ns" "$RUN/holder.failed"
        # busybox' unshare makes the new namespace private by default, which cuts it off from init's for good: ask it
        # for a slave (toybox' leaves the propagation as it is; _holder makes it a slave right away)
        un_opts=""
        case "$UNSHARE" in "$BB "*) un_opts="--propagation slave";; esac
        # cloned from init's namespace (not from the app's), so it does not depend on the app process
        $SETSID $NSENTER -t 1 -m $UNSHARE -m $un_opts /system/bin/sh "$SELF" _holder --root "$ROOT" </dev/null >"$RUN/holder.log" 2>&1 &
        i=0
        while [ $i -lt 100 ]; do
            holder_pid >/dev/null && return 0
            if [ -f "$RUN/holder.failed" ]; then break; fi
            $SLEEP 0.1
            i=$((i + 1))
        done
        cat "$RUN/holder.log" >&2
        rm -f "$RUN/holder.failed"
        die "the chroot's mount namespace did not start"
    else
        $NS1 /system/bin/sh "$SELF" _mount --root "$ROOT" || die "mounting failed"
    fi
}

umount_list() {  # $1 = namespace prefix, $2 = list of mount points (deepest first)
    for mp in $2; do $1 $UMOUNT -l "$mp" 2>/dev/null || $1 $UMOUNT "$mp" 2>/dev/null; done
}

# one process per mount namespace, init's first (one ls reads every process' namespace link: no process per entry)
ns_pids() {
    echo 1
    np_seen=" $(readlink /proc/1/ns/mnt) "
    ls -l /proc/[0-9]*/ns/mnt 2>/dev/null | while IFS= read -r np_line; do
        np_ns=${np_line##*-> }
        case "$np_seen" in *" $np_ns "*) continue;; esac
        np_seen="$np_seen$np_ns "
        np_path=${np_line%% -> *}; np_path=${np_path##* }
        np_p=${np_path#/proc/}; np_p=${np_p%/ns/mnt}
        case "$np_p" in ''|*[!0-9]*) continue;; esac
        echo "$np_p"
    done
}

# mounts under the rootfs in every mount namespace on the phone (every app has its own): "pid mountpoint" lines, one
# per mount point, from init's namespace when it is there (unmounting it there removes the copies in the apps').
# One mount table per namespace (a phone has hundreds of processes but a few dozen namespaces), read by one grep.
mounts_anywhere() {
    ma_seen=" "
    ma_files=$(ns_pids | while read -r ma_p; do echo "/proc/$ma_p/mountinfo"; done)
    grep -F " $ROOT" $ma_files 2>/dev/null | while IFS= read -r ma_line; do
        ma_f=${ma_line%%:*}
        ma_p=${ma_f#/proc/}; ma_p=${ma_p%/mountinfo}
        set -f
        set -- ${ma_line#*:}
        set +f
        ma_mp=$5
        case "$ma_mp" in "$ROOT"|"$ROOT"/*) ;; *) continue;; esac
        case "$ma_seen" in *" $ma_mp "*) continue;; esac
        ma_seen="$ma_seen$ma_mp "
        echo "$ma_p $ma_mp"
    done
}

# the quick places: init's namespace (global mode, older launchers) and this process' (the app's, with Magisk's
# "inherit" mode); the private namespace goes away with its processes
mounts_quick() {
    mounts_under 1
    mounts_under $$
}

umount_quick() {
    for uq_i in 1 2; do
        for uq_mp in $(mounts_under 1); do $NS1 $UMOUNT -l "$uq_mp" 2>/dev/null; done
        for uq_mp in $(mounts_under $$); do $UMOUNT -l "$uq_mp" 2>/dev/null; done
    done
}

umount_everywhere() {
    n=0
    while [ $n -lt 4 ]; do
        # deepest first
        left=$(mounts_anywhere | sort -r -k2)
        [ -z "$left" ] && return 0
        echo "$left" | while read -r p mp; do
            [ -n "$mp" ] || continue
            if [ -n "$NSENTER" ]; then $NSENTER -t "$p" -m $UMOUNT -l "$mp" 2>/dev/null
            else $UMOUNT -l "$mp" 2>/dev/null
            fi
        done
        n=$((n + 1))
    done
}

# ---------------------------------------------------------------- inside the rootfs
user_field() {  # $1 user, $2 field number of /etc/passwd
    while IFS=: read -r n _ u g _ _ _; do
        if [ "$n" = "$1" ]; then
            [ "$2" = 3 ] && echo "$u"
            [ "$2" = 4 ] && echo "$g"
            return 0
        fi
    done < "$ROOT/etc/passwd"
    return 1
}

sq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

b64d() { printf '%s' "$1" | $BASE64 -d 2>/dev/null; }

# the script a session runs inside the chroot: environment, folder, command (removes itself first). Created with
# O_EXCL (set -C) so a symlink left in /tmp cannot redirect this root write.
write_exec_file() {  # $1 = uid, $2 = gid
    [ -d "$ROOT/tmp" ] || mkdir -p "$ROOT/tmp"
    chmod 1777 "$ROOT/tmp" 2>/dev/null
    name=".antigem-exec.$$.$RANDOM"
    f="$ROOT/tmp/$name"
    rm -f "$f" 2>/dev/null
    ( set -C; : > "$f" ) 2>/dev/null || die "cannot create $f"
    cwd=""
    [ -n "$CWD_B64" ] && cwd=$(b64d "$CWD_B64")
    {
        echo "rm -f /tmp/$name"
        echo "export TERM=$(sq "$TERM_ARG") COLORTERM=truecolor"
        echo '[ -n "$LANG" ] || export LANG=C.UTF-8'
        if [ -n "$cwd" ]; then echo "cd -- $(sq "$cwd") 2>/dev/null || cd"; else echo "cd"; fi
        if [ -n "$RUN_B64" ]; then b64d "$RUN_B64"; echo; else echo 'exec "${SHELL:-/bin/bash}" -l'; fi
    } >> "$f"
    chown "$1:$2" "$f" 2>/dev/null
    chmod 600 "$f"
    echo "/tmp/$name"
}

install_bridge() {
    [ -f "$BRIDGE_SRC" ] || die "bridge not found: $BRIDGE_SRC"
    dst="$ROOT/usr/local/bin/agy_ide_bridge"
    mkdir -p "$ROOT/usr/local/bin"
    if [ -f "$dst" ] && [ -n "$CMP" ] && $CMP -s "$BRIDGE_SRC" "$dst"; then echo "bridge=same"; return 0; fi
    cp "$BRIDGE_SRC" "$dst.new" && chmod 755 "$dst.new" && mv -f "$dst.new" "$dst" || die "cannot install the bridge"
    echo "bridge=updated"
}

write_file() {  # $1 path inside the rootfs (replaced, never followed if a symlink), stdin = content
    rm -f "$1"
    cat > "$1"
    chmod 644 "$1"
}

write_resolv() {  # $1 = comma separated servers
    case "$1" in ''|*[!0-9a-fA-F.:,]*) return 0;; esac
    old_ifs=$IFS; IFS=','
    set -- $1
    IFS=$old_ifs
    mkdir -p "$ROOT/etc"
    for s in "$@"; do [ -n "$s" ] && echo "nameserver $s"; done | write_file "$ROOT/etc/resolv.conf"
}

set_tz() {
    case "$TZ_ARG" in ''|*[!A-Za-z0-9_+/-]*|*..*) return 0;; esac
    [ -f "$ROOT/usr/share/zoneinfo/$TZ_ARG" ] || return 0
    rm -f "$ROOT/etc/localtime"
    ln -s "/usr/share/zoneinfo/$TZ_ARG" "$ROOT/etc/localtime"
    echo "$TZ_ARG" | write_file "$ROOT/etc/timezone"
}

ensure_hosts() {
    grep -q localhost "$ROOT/etc/hosts" 2>/dev/null && return 0
    printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' | write_file "$ROOT/etc/hosts"
}

bridge_pid() {
    bp=$(cat "$RUN/bridge.pid" 2>/dev/null)
    case "$bp" in ''|*[!0-9]*) return 1;; esac
    [ -d "/proc/$bp" ] || return 1
    case " $(chroot_pids | tr '\n' ' ') " in *" $bp "*) echo "$bp"; return 0;; esac
    return 1
}

bridge_stop() {
    bp=$(bridge_pid) || { rm -f "$RUN/bridge.pid"; return 0; }
    kill_pids "$(tree_pids "$bp")"
    rm -f "$RUN/bridge.pid"
}

# commands that start, enter or read a rootfs: one must be there (never create a folder where one used to be)
require_rootfs() {
    [ -d "$ROOT" ] && { [ -e "$ROOT/bin/sh" ] || [ -e "$ROOT/usr/bin/sh" ]; } || die "there is no rootfs at $ROOT"
}

owned_rootfs() {
    [ -f "$META/manifest" ] || [ -f "$ROOT/.antigem_chroot_user" ] || [ -f "$ROOT/start-ubuntu.sh" ]
}

# ---------------------------------------------------------------- commands
cmd_probe() {
    echo "ctl=$CTL_VERSION"
    echo "uid=$(id -u)"
    echo "busybox=$BB"
    echo "unshare=$UNSHARE"
    echo "nsenter=$NSENTER"
    echo "selinux=$(getenforce 2>/dev/null)"
    echo "kernel=$(uname -r)"
    echo "machine=$(uname -m)"
    if command -v magisk >/dev/null 2>&1; then echo "root=Magisk $(magisk -v 2>/dev/null) ($(magisk -V 2>/dev/null))"
    elif [ -x /data/adb/ksud ]; then echo "root=KernelSU $(/data/adb/ksud -V 2>/dev/null | head -1)"
    elif [ -x /data/adb/apd ]; then echo "root=APatch $(/data/adb/apd -V 2>/dev/null | head -1)"
    fi
    echo "init_ns=$(readlink /proc/1/ns/mnt)"
    echo "self_ns=$(readlink /proc/self/ns/mnt)"
}

cmd_scan() {
    list=""
    [ -f "$REGISTRY" ] && list=$(cat "$REGISTRY" 2>/dev/null)
    for d in $BASE_DIR/*/; do [ -d "$d" ] && list="$list ${d%/}"; done
    list="$list /data/local/ubuntu $EXTRA_PATH"
    seen=" "
    for d in $list; do
        d=${d%/}
        case "$seen" in *" $d "*) continue;; esac
        seen="$seen$d "
        plain "$d" || continue
        case "$d" in /data/*) ;; *) continue;; esac
        [ -d "$d" ] || continue
        osr=""
        for f in "$d/usr/lib/os-release" "$d/etc/os-release"; do [ -f "$f" ] && { osr="$f"; break; }; done
        [ -n "$osr" ] || continue
        [ -e "$d/bin/sh" ] || [ -e "$d/usr/bin/sh" ] || continue
        echo "ROOTFS=$d"
        echo "OS=$(grep '^PRETTY_NAME=' "$osr" | head -1 | cut -d= -f2- | tr -d '"')"
        if [ -e "$d/lib/ld-linux-aarch64.so.1" ] || [ -e "$d/usr/lib/ld-linux-aarch64.so.1" ]; then echo "ARCH=aarch64"
        elif [ -e "$d/lib64/ld-linux-x86-64.so.2" ] || [ -e "$d/usr/lib64/ld-linux-x86-64.so.2" ]; then echo "ARCH=x86_64"
        fi
        [ -f "$d/.antigem/manifest" ] && sed 's/^/M_/' "$d/.antigem/manifest"
        [ -f "$d/.antigem/chroot.conf" ] && echo "CONF=1"
        [ -f "$d/.antigem_chroot_user" ] && echo "LEGACY_USER=$(head -1 "$d/.antigem_chroot_user")"
        ROOT="$d"; META="$d/.antigem"; RUN="$META/run"
        # running = processes inside; mounted = mounts left anywhere (e.g. by an older launcher) with nothing running
        sc_procs=$(chroot_pids | wc -l | tr -d ' ')
        echo "PROCS=$sc_procs"
        if holder_pid >/dev/null || [ -n "$(mounts_quick)" ]; then echo "MOUNTED=1"; else echo "MOUNTED=0"; fi
        if [ "$sc_procs" -gt 0 ]; then echo "RUNNING=1"; else echo "RUNNING=0"; fi
        echo "END"
    done
}

cmd_register() {
    mkdir -p "$BASE_DIR"
    touch "$REGISTRY"
    grep -qx "$ROOT" "$REGISTRY" || echo "$ROOT" >> "$REGISTRY"
}

cmd_unregister() {
    [ -f "$REGISTRY" ] || return 0
    grep -vx "$ROOT" "$REGISTRY" > "$REGISTRY.tmp"
    mv -f "$REGISTRY.tmp" "$REGISTRY"
}

cmd_prepare() {  # an empty folder to extract into
    if [ -e "$ROOT" ]; then
        [ -d "$ROOT" ] || die "$ROOT exists and is not a folder"
        [ -z "$(ls -A "$ROOT" 2>/dev/null)" ] || die "$ROOT is not empty: pick another folder, or delete that install first"
    fi
    mkdir -p "$ROOT" && chmod 755 "$ROOT" || die "cannot create $ROOT"
}

cmd_extract() {  # stdin = an uncompressed tar stream
    [ -d "$ROOT" ] || die "$ROOT does not exist"
    # toybox's tar keeps the archive's numeric owners (busybox's maps names through Android's user database)
    systar=$(command -v tar 2>/dev/null)
    if [ -n "$systar" ] && $systar --help 2>&1 | grep -q -- '--numeric-owner'; then
        $systar --numeric-owner -xpf - -C "$ROOT" || die "tar failed"
    elif [ -n "$TAR" ]; then
        $TAR -xpf - -C "$ROOT" || die "tar failed"
    else
        die "no tar on this phone"
    fi
    mkdir -p "$META" "$RUN"
    chmod 755 "$META"
    echo "extracted"
}

cmd_put_meta() {  # stdin = content of .antigem/manifest or .antigem/chroot.conf
    case "$META_NAME" in manifest|chroot.conf) ;; *) die "unknown file: $META_NAME";; esac
    mkdir -p "$META" "$RUN"
    chmod 755 "$META"
    rm -f "$META/$META_NAME.tmp"
    cat > "$META/$META_NAME.tmp" && chmod 644 "$META/$META_NAME.tmp" && mv -f "$META/$META_NAME.tmp" "$META/$META_NAME"
}

cmd_start() {
    read_conf
    mkdir -p "$RUN"
    [ -n "$BRIDGE_SRC" ] && install_bridge
    if [ "$DNS_MODE" = custom ]; then write_resolv "$DNS_CUSTOM"
    elif [ -n "$DNS_ARG" ]; then write_resolv "$DNS_ARG"
    fi
    [ -n "$TZ_ARG" ] && set_tz
    ensure_hosts
    start_mounts
    bp=$(bridge_pid) && echo "bridge_pid=$bp"
    echo "started ns=$NS_MODE"
}

cmd_exec() {
    read_conf
    start_mounts
    pre=$(ns_prefix) || exit 1
    # A terminal (stdin is a tty): the root manager starts us in a session without a controlling terminal, and su -c
    # would start yet another one, so bash says "cannot set terminal process group" and has no job control (nor does
    # anything started in it). The rootfs' setsid -c gives the session this tty as its controlling terminal, and
    # su --session-command keeps that session. Without those tools (another distro): as before.
    ctty=""
    sucmd="-c"
    if [ -t 0 ] && [ -x "$ROOT/usr/bin/setsid" ]; then
        ctty="/usr/bin/setsid -c -w"
        grep -q -- '--session-command' "$ROOT/usr/bin/su" 2>/dev/null && sucmd="--session-command"
    fi
    if [ "$AS_ROOT" = 1 ]; then
        f=$(write_exec_file 0 0) || exit 1
        exec $ENV -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash PATH="$CPATH" TERM="$TERM_ARG" LANG=C.UTF-8 \
            $pre $CHROOT "$ROOT" $ctty /bin/sh -c ". $f"
    fi
    [ -n "$USER_NAME" ] && plain "$USER_NAME" || die "no --user given"
    uid=$(user_field "$USER_NAME" 3) || die "there is no user $USER_NAME in the chroot"
    gid=$(user_field "$USER_NAME" 4)
    f=$(write_exec_file "$uid" "$gid") || exit 1
    # setsid -c only helps when su keeps its session
    [ "$sucmd" = "-c" ] && ctty=""
    exec $ENV -i PATH="$CPATH" TERM="$TERM_ARG" LANG=C.UTF-8 $pre $CHROOT "$ROOT" $ctty /bin/su -l "$USER_NAME" $sucmd ". $f"
}

cmd_bridge_start() {
    read_conf
    start_mounts
    bridge_stop
    [ -n "$USER_NAME" ] && plain "$USER_NAME" || die "no --user given"
    uid=$(user_field "$USER_NAME" 3) || die "there is no user $USER_NAME in the chroot"
    gid=$(user_field "$USER_NAME" 4)
    f=$(write_exec_file "$uid" "$gid") || exit 1
    : > "$RUN/bridge.log"
    $SETSID /system/bin/sh "$SELF" _bridge_run --root "$ROOT" --user "$USER_NAME" --file "$f" \
        </dev/null >>"$RUN/bridge.log" 2>&1 &
    i=0
    while [ $i -lt 30 ]; do
        bp=$(bridge_pid) && { echo "bridge=$bp"; return 0; }
        $SLEEP 0.1
        i=$((i + 1))
    done
    $TAIL -n 30 "$RUN/bridge.log" >&2
    die "the bridge did not start"
}

cmd_status() {
    read_conf
    echo "ctl=$CTL_VERSION"
    echo "ns_mode=$NS_MODE"
    hp=$(holder_pid) && echo "holder=$hp" && echo "since=$(stat -c %Y "$RUN/holder.pid" 2>/dev/null)"
    bp=$(bridge_pid) && echo "bridge=$bp"
    pids=$(chroot_pids)
    echo "procs=$(echo $pids | wc -w)"
    if [ -n "$hp" ]; then src_pid=$hp; else src_pid=1; fi
    while read -r _ _ _ _ mp opts rest; do
        case "$mp" in "$ROOT"|"$ROOT"/*) ;; *) continue;; esac
        after=${rest#*- }
        fstype=${after%% *}
        after2=${after#* }
        msrc=${after2%% *}
        echo "MOUNT|$mp|$fstype|$msrc|$opts"
    done < "/proc/$src_pid/mountinfo"
    n=0
    for p in $pids; do
        [ $n -ge 150 ] && break
        u=$(stat -c %u "/proc/$p" 2>/dev/null)
        c=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | cut -c1-160)
        [ -n "$c" ] || continue
        echo "PROC|$p|$u|$c"
        n=$((n + 1))
    done
    [ -f "$META/manifest" ] && sed 's/^/M_/' "$META/manifest"
    [ -f "$CONF" ] && sed 's/^/C_/' "$CONF"
    return 0
}

cmd_du() {
    [ -n "$DU" ] || die "no du on this phone"
    echo "total=$($DU -skx "$ROOT" 2>/dev/null | cut -f1)"
    for sub in home root usr var/cache/apt var/lib/apt/lists opt tmp; do
        [ -d "$ROOT/$sub" ] && echo "du_$sub=$($DU -skx "$ROOT/$sub" 2>/dev/null | cut -f1)"
    done
}

cmd_stop() {
    read_conf
    bridge_stop
    kill_pids "$(chroot_pids)"
    hp=$(holder_pid) && kill -KILL "$hp" 2>/dev/null && $SLEEP 0.3
    rm -f "$RUN/holder.pid" "$RUN/holder.ns"
    # mounts left in init's namespace (global mode, older launchers) or in this app's: quick (delete checks every
    # namespace on the phone)
    umount_quick
    left=$(mounts_quick)
    if [ -n "$left" ]; then
        echo "still mounted:"
        echo "$left"
        exit 2
    fi
    echo "stopped"
}

cmd_umount() {
    read_conf
    T="${TARGET%/}"
    plain "$T" || die "unsupported characters: $T"
    case "$T" in "$ROOT"/*) ;; *) die "$T is not inside $ROOT";; esac
    case "$T" in */..|*/../*) die "bad path";; esac
    pre=$(ns_prefix) || exit 1
    $pre $UMOUNT -l "$T" || die "umount $T failed"
    echo "unmounted $T"
}

# every mount under the rootfs in every namespace on the phone (what delete would refuse): MOUNTANY|pid|mountpoint
cmd_mounts_all() {
    mounts_anywhere | while read -r p mp; do
        [ -n "$mp" ] && echo "MOUNTANY|$p|$mp"
    done
    return 0
}

# stdout = an uncompressed tar of the rootfs (the app compresses it). --clean: a shareable image without the homes,
# logins, history, logs, apt lists and AntiGem's own files. Messages go to stderr only: stdout is the archive.
cmd_backup() {
    read_conf
    # private mode: the chroot's mounts are not in this namespace, so tar sees plain empty folders even while it runs.
    # Mounts visible here (global mode, an older launcher's leftovers) would take tar into /sdcard, /proc, ...
    [ -z "$(mounts_quick)" ] || die "the chroot is mounted in Android's namespace (global mode, or mounts left by an older launcher): stop it first, then back up"
    if [ "$CLEAN" = 1 ]; then
        [ -n "$UNSHARE" ] || die "a clean image needs unshare (busybox of Magisk / KernelSU / APatch, or toybox)"
        exec $UNSHARE -m /system/bin/sh "$SELF" _clean_backup --root "$ROOT" --clean
    fi
    backup_tar
}

# --clean: the image everyone downloads (the prebuilt). It runs in a mount namespace of its own, where scrubbed copies of
# the account files and machine-id are bind-mounted over the real ones, so tar reads those. The running chroot and the
# rest of the phone see no change, and the mounts end with this process.
cmd__clean_backup() {
    read_conf
    # toybox' unshare keeps the propagation: a bind mount here must never reach init's namespace (or the chroot's)
    $MOUNT --make-rprivate / 2>/dev/null || $MOUNT -o rprivate / 2>/dev/null
    grep -q ' shared:' /proc/self/mountinfo && die "could not make a private mount namespace for the clean image: nothing was exported"
    AWK=$(tool awk)
    [ -n "$AWK" ] || die "a clean image needs awk (busybox of Magisk / KernelSU / APatch)"
    cl="$BASE_DIR/.clean.$$"
    rm -rf "$cl"
    mkdir -p "$cl" && chmod 700 "$cl" || die "cannot create $cl"
    trap 'rm -rf "$cl"' EXIT
    clean_accounts "$cl"
    backup_tar
}

# The people's accounts (uid 1000-65533: the user this phone made, with its password hash and sudo membership) are left
# out, every other password is locked ("!"), machine-id is empty. Setup adds the new owner's account and a machine-id.
clean_accounts() {
    cl=$1
    e="$ROOT/etc"
    [ -f "$e/passwd" ] && [ ! -L "$e/passwd" ] || die "$e/passwd is missing"
    users=$($AWK -F: '$3 + 0 >= 1000 && $3 + 0 < 65534 { print $1 }' "$e/passwd")
    for f in passwd shadow group gshadow subuid subgid; do
        src="$e/$f"
        [ -f "$src" ] && [ ! -L "$src" ] || continue
        # a copy keeps the owner and mode tar records (shadow is root:shadow 0640)
        cp -p "$src" "$cl/$f" || die "cannot copy $src"
        $AWK -F: -v OFS=: -v f="$f" -v users="$users" '
            function strip(list,    m, k, i, o) {
                k = split(list, m, ","); o = ""
                for (i = 1; i <= k; i++) if (m[i] != "" && !(m[i] in u)) o = o (o == "" ? "" : ",") m[i]
                return o
            }
            BEGIN { n = split(users, a, "\n"); for (i = 1; i <= n; i++) if (a[i] != "") u[a[i]] = 1 }
            ($1 in u) { next }
            f == "shadow" && $2 != "*" { $2 = "!" }
            f == "group" { $4 = strip($4) }
            f == "gshadow" { if ($2 != "*") $2 = "!"; $3 = strip($3); $4 = strip($4) }
            { print }' "$src" > "$cl/$f.new" || die "cannot clean $src"
        cat "$cl/$f.new" > "$cl/$f" && rm -f "$cl/$f.new" || die "cannot clean $src"
        $MOUNT -o bind "$cl/$f" "$src" || die "cannot cover $src"
    done
    i=0
    for f in "$e/machine-id" "$ROOT/var/lib/dbus/machine-id"; do
        [ -f "$f" ] && [ ! -L "$f" ] || continue
        i=$((i + 1))
        cp -p "$f" "$cl/machine-id.$i" && : > "$cl/machine-id.$i" || die "cannot copy $f"
        $MOUNT -o bind "$cl/machine-id.$i" "$f" || die "cannot cover $f"
    done
    [ -z "$users" ] || echo "clean image: accounts left out: $(echo $users)" >&2
}

backup_tar() {
    systar=$(command -v tar 2>/dev/null)
    [ -n "$systar" ] && $systar --help 2>&1 | grep -q -- '--numeric-owner' || die "this phone's tar cannot keep numeric owners"
    # the exclude list lives outside the rootfs
    mkdir -p "$BASE_DIR"
    ex="$BASE_DIR/.backup-exclude.$$"
    rm -f "$ex"
    {
        for d in proc sys dev tmp var/tmp sdcard storage; do echo "./$d/*"; done
        echo "./.antigem/run/*"
        echo "./var/cache/apt/archives/*.deb"
        echo "./var/cache/apt/*.bin"
        # shared folders (their mount points stay, empty)
        if [ -n "$BINDS" ]; then
            old_ifs=$IFS; IFS=';'
            set -f
            set -- $BINDS
            set +f
            IFS=$old_ifs
            for e in "$@"; do bt=${e#*|}; bt=${bt%%|*}; bt=${bt%/}; plain "$bt" && [ -n "$bt" ] && echo ".$bt/*"; done
        fi
        if [ "$CLEAN" = 1 ]; then
            echo "./.antigem"
            echo "./home/*"
            for f in .bash_history .zsh_history .zhistory .lesshst .viminfo .python_history .node_repl_history .wget-hsts \
                .mysql_history .psql_history .sqlite_history .sudo_as_admin_successful \
                .cache .local .config .npm .ssh .gnupg .claude .claude.json .codex .gemini .opencode .antigem .agy; do echo "./root/$f"; done
            echo "./var/lib/apt/lists/*"
            echo "./var/cache/apt/*"
            echo "./var/log/*.log"
            # login records (who logged in, from where) and rotated logs
            for f in wtmp btmp lastlog faillog; do echo "./var/log/$f"; done
            echo "./var/log/*.gz"
            echo "./var/log/*.[0-9]"
            echo "./var/log/apt/*"
            echo "./var/log/journal/*"
            echo "./etc/sudoers.d/90-antigem"
            echo "./etc/ssh/ssh_host_*"
            # the "-" copies tools leave hold the old account files (password hashes)
            for f in passwd shadow group gshadow subuid subgid; do echo "./etc/$f-"; done
        fi
    } > "$ex"
    cd "$ROOT" || die "cannot enter $ROOT"
    $systar -cf - --numeric-owner -X "$ex" .
    bk_rc=$?
    rm -f "$ex"
    return $bk_rc
}

cmd_kill() {
    case "$PID_ARG" in ''|*[!0-9]*) die "bad pid";; esac
    case " $(chroot_pids | tr '\n' ' ') " in *" $PID_ARG "*) ;; *) die "process $PID_ARG is not in this chroot";; esac
    kill -KILL "$PID_ARG"
}

cmd_delete() {
    owned_rootfs || die "$ROOT has no AntiGem manifest: refusing to delete it"
    ( cmd_stop ) >/dev/null 2>&1
    umount_everywhere
    left=$(mounts_anywhere)
    [ -z "$left" ] || { echo "$left" >&2; die "folders inside $ROOT are still mounted: nothing was deleted"; }
    [ -n "$FIND" ] || die "no find on this phone"
    # never leaves the rootfs' filesystem (-xdev), even if a mount appeared in the meantime
    $FIND "$ROOT" -xdev ! -type d -exec rm -f {} + 2>/dev/null
    $FIND "$ROOT" -xdev -depth -type d -exec rmdir {} + 2>/dev/null
    rmdir "$ROOT" 2>/dev/null
    cmd_unregister
    if [ -e "$ROOT" ]; then
        echo "partly deleted: $(ls -A "$ROOT" 2>/dev/null | head -5 | tr '\n' ' ')"
        exit 3
    fi
    echo "deleted"
}

# ---------------------------------------------------------------- internal (started by the commands above)
cmd__holder() {
    read_conf
    leave_app_cgroup
    # do_mounts dies on an error: in a subshell, so the failure is reported to the starter at once
    if ! make_slave || ! ( do_mounts ); then touch "$RUN/holder.failed"; exit 1; fi
    readlink /proc/$$/ns/mnt > "$RUN/holder.ns"
    echo $$ > "$RUN/holder.pid"
    exec $SLEEP 2147483647
}

cmd__mount() {
    read_conf
    do_mounts
}

# The X11 desktop: AntiGem's Termux:X11 X server runs as the app, outside the chroot, and puts its socket in a folder of
# the app's own storage (--path). Bound onto the chroot's /tmp/.X11-unix (in the chroot's mount namespace), X programs
# in the chroot reach it as display :0. The chroot user has the app's uid, so the socket is theirs to use.
cmd_x11_bind() {
    read_conf
    src="${TARGET%/}"
    plain "$src" || die "unsupported characters in the path: $src"
    case "$src" in */../*|*/..|*/./*|*/.|*//*) die "the path must be normalized: $src";; esac
    case "$src" in
        /data/data/*/files/*|/data/user/[0-9]*/*/files/*) ;;
        *) die "not a folder of an app's own storage: $src";;
    esac
    [ -d "$src" ] && [ ! -L "$src" ] || die "no folder $src"
    start_mounts
    pre=$(ns_prefix) || exit 1
    $pre /system/bin/sh "$SELF" _x11_mount --root "$ROOT" --path "$src" || die "could not share the X11 socket folder"
}

cmd__x11_mount() {  # inside the chroot's mount namespace
    dst="$ROOT/tmp/.X11-unix"
    safe_target "$dst"
    is_mp "$dst" && $UMOUNT -l "$dst" 2>/dev/null
    $MOUNT -o bind "$TARGET" "$dst" || die "cannot bind $TARGET onto /tmp/.X11-unix"
    echo "x11=bound"
}

cmd__bridge_run() {
    echo $$ > "$RUN/bridge.pid"
    leave_app_cgroup
    read_conf
    pre=$(ns_prefix) || exit 1
    exec $ENV -i PATH="$CPATH" TERM=xterm-256color LANG=C.UTF-8 $pre $CHROOT "$ROOT" /bin/su -l "$USER_NAME" -c ". $EXEC_FILE"
}

[ "$(id -u)" = 0 ] || die "chrootctl must run as root"
case "$CMD" in
    probe) cmd_probe;;
    scan) cmd_scan;;
    version) echo "$CTL_VERSION";;
    *)
        check_root_path "$ROOT"
        case "$CMD" in
            register) cmd_register;;
            unregister) cmd_unregister;;
            prepare) cmd_prepare;;
            extract) cmd_extract;;
            put-meta) cmd_put_meta;;
            start) require_rootfs; cmd_start;;
            exec) require_rootfs; cmd_exec;;
            bridge-start) require_rootfs; cmd_bridge_start;;
            bridge-stop) read_conf; bridge_stop; echo "bridge stopped";;
            bridge-log) exec $TAIL -n 400 -f "$RUN/bridge.log";;
            status) cmd_status;;
            du) require_rootfs; cmd_du;;
            stop) cmd_stop;;
            umount) cmd_umount;;
            mounts-all) cmd_mounts_all;;
            backup) require_rootfs; cmd_backup;;
            kill) cmd_kill;;
            delete) cmd_delete;;
            _holder) cmd__holder;;
            _clean_backup) require_rootfs; cmd__clean_backup;;
            _mount) cmd__mount;;
            x11-bind) require_rootfs; cmd_x11_bind;;
            _x11_mount) cmd__x11_mount;;
            _bridge_run) cmd__bridge_run;;
            *) die "unknown command: $CMD";;
        esac
        ;;
esac
