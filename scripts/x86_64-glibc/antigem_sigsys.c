/*
 * antigem_sigsys: lets glibc programs (Claude Code, OpenCode, agy's Linux build) run in an x86_64 Android app.
 *
 * Android puts every app process under a seccomp filter made for its CPU. On x86_64 the kernel still has the old
 * syscalls (access, open, pipe, dup2, poll, epoll_wait, mkdir, unlink, rename…) and glibc uses them, but bionic does
 * not, so the filter blocks them: the kernel skips the call and sends SIGSYS, which kills the program ("Bad system
 * call"). arm64 has none of these syscalls, so arm64 phones never meet this.
 *
 * This library catches that SIGSYS, makes the same call through the syscall Android allows (access -> faccessat,
 * pipe -> pipe2…), puts the result in the program's return register and lets it go on. Calls the filter allows never
 * come here. Newer syscalls a program can live without (clone3, rseq, set_robust_list…) answer ENOSYS, which glibc
 * and musl handle by using older ones.
 *
 * It is loaded as an audit module (LD_AUDIT): ld.so loads those before anything else, even before its own
 * access("…/etc/ld.so.preload") check, which is the first blocked call of every glibc program. Freestanding (no libc,
 * raw syscalls only), so it does not depend on the glibc version. Built by build.sh in this folder.
 */

typedef unsigned long u64;

#define AT_FDCWD (-100)
#define AT_SYMLINK_NOFOLLOW 0x100
#define AT_REMOVEDIR 0x200
#define O_WRONLY 01
#define O_CREAT 0100
#define O_TRUNC 01000
#define O_APPEND 02000
#define F_GETFD 1
#define SIGSYS 31
#define SIGCHLD 17
#define SYS_SECCOMP 1
#define ENOSYS 38
#define EINVAL 22

#define SA_SIGINFO 0x00000004
#define SA_RESTORER 0x04000000
#define SA_ONSTACK 0x08000000

/* registers in the kernel's ucontext (uc_mcontext starts at byte 40), glibc's REG_* order */
enum { R8, R9, R10, R11, R12, R13, R14, R15, RDI, RSI, RBP, RBX, RDX, RAX, RCX, RSP, RIP };

/* blocked x86_64 syscalls */
enum {
    NR_open = 2, NR_stat = 4, NR_lstat = 6, NR_poll = 7, NR_access = 21, NR_pipe = 22, NR_select = 23,
    NR_dup2 = 33, NR_pause = 34, NR_alarm = 37, NR_fork = 57, NR_vfork = 58, NR_rename = 82, NR_mkdir = 83,
    NR_rmdir = 84, NR_creat = 85, NR_link = 86, NR_unlink = 87, NR_symlink = 88, NR_readlink = 89, NR_chmod = 90,
    NR_chown = 92, NR_lchown = 94, NR_getpgrp = 111, NR_utime = 132, NR_mknod = 133, NR_time = 201,
    NR_epoll_create = 213, NR_epoll_wait = 232, NR_utimes = 235, NR_inotify_init = 253, NR_futimesat = 261,
    NR_signalfd = 282, NR_eventfd = 284, NR_faccessat2 = 439,
};

/* what they become */
enum {
    NR_rt_sigaction = 13, NR_getitimer = 36, NR_setitimer = 38, NR_clone = 56, NR_kill = 62, NR_fcntl = 72,
    NR_getpid = 39, NR_getpgid = 121, NR_clock_gettime = 228, NR_openat = 257, NR_mkdirat = 258, NR_mknodat = 259,
    NR_fchownat = 260, NR_newfstatat = 262, NR_unlinkat = 263, NR_renameat = 264, NR_linkat = 265,
    NR_symlinkat = 266, NR_readlinkat = 267, NR_fchmodat = 268, NR_faccessat = 269, NR_pselect6 = 270,
    NR_ppoll = 271, NR_utimensat = 280, NR_epoll_pwait = 281, NR_signalfd4 = 289, NR_eventfd2 = 290,
    NR_epoll_create1 = 291, NR_dup3 = 292, NR_pipe2 = 293, NR_inotify_init1 = 294, NR_write = 1, NR_close = 3,
};

struct ts { long sec, nsec; };
struct tv { long sec, usec; };
struct itv { struct tv interval, value; };

static inline long sc(long n, long a, long b, long c, long d, long e, long f)
{
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    register long r9 __asm__("r9") = f;
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9)
                     : "rcx", "r11", "memory");
    return ret;
}

#ifdef SIGSYS_LOG
/* test builds: one line per emulated call ("<nr> -> <result>") */
static void log_call(long nr, long r)
{
    char buf[48];
    int i = sizeof buf;
    buf[--i] = '\n';
    unsigned long v = r < 0 ? -r : r;
    do buf[--i] = '0' + v % 10; while ((v /= 10) && i > 24);
    if (r < 0) buf[--i] = '-';
    buf[--i] = ' '; buf[--i] = '>'; buf[--i] = '-'; buf[--i] = ' ';
    v = nr;
    do buf[--i] = '0' + v % 10; while ((v /= 10) && i > 0);
    long fd = sc(NR_openat, AT_FDCWD, (long)SIGSYS_LOG, O_WRONLY | O_CREAT | O_APPEND, 0600, 0, 0);
    if (fd >= 0) {
        sc(NR_write, fd, (long)(buf + i), sizeof buf - i, 0, 0, 0);
        sc(NR_close, fd, 0, 0, 0, 0, 0);
    }
}
#endif

static long emulate(long nr, long a, long b, long c, long d, long e)
{
    struct ts t[2];
    switch (nr) {
    case NR_open: return sc(NR_openat, AT_FDCWD, a, b, c, 0, 0);
    case NR_creat: return sc(NR_openat, AT_FDCWD, a, O_CREAT | O_WRONLY | O_TRUNC, b, 0, 0);
    case NR_stat: return sc(NR_newfstatat, AT_FDCWD, a, b, 0, 0, 0);
    case NR_lstat: return sc(NR_newfstatat, AT_FDCWD, a, b, AT_SYMLINK_NOFOLLOW, 0, 0);
    case NR_access: return sc(NR_faccessat, AT_FDCWD, a, b, 0, 0, 0);
    case NR_faccessat2: return d == 0 ? sc(NR_faccessat, a, b, c, 0, 0, 0) : -ENOSYS;
    case NR_mkdir: return sc(NR_mkdirat, AT_FDCWD, a, b, 0, 0, 0);
    case NR_mknod: return sc(NR_mknodat, AT_FDCWD, a, b, c, 0, 0);
    case NR_rmdir: return sc(NR_unlinkat, AT_FDCWD, a, AT_REMOVEDIR, 0, 0, 0);
    case NR_unlink: return sc(NR_unlinkat, AT_FDCWD, a, 0, 0, 0, 0);
    case NR_rename: return sc(NR_renameat, AT_FDCWD, a, AT_FDCWD, b, 0, 0);
    case NR_link: return sc(NR_linkat, AT_FDCWD, a, AT_FDCWD, b, 0, 0);
    case NR_symlink: return sc(NR_symlinkat, a, AT_FDCWD, b, 0, 0, 0);
    case NR_readlink: return sc(NR_readlinkat, AT_FDCWD, a, b, c, 0, 0);
    case NR_chmod: return sc(NR_fchmodat, AT_FDCWD, a, b, 0, 0, 0);
    case NR_chown: return sc(NR_fchownat, AT_FDCWD, a, b, c, 0, 0);
    case NR_lchown: return sc(NR_fchownat, AT_FDCWD, a, b, c, AT_SYMLINK_NOFOLLOW, 0);
    case NR_pipe: return sc(NR_pipe2, a, 0, 0, 0, 0, 0);
    case NR_dup2:
        if (a != b) return sc(NR_dup3, a, b, 0, 0, 0, 0);
        { long r = sc(NR_fcntl, a, F_GETFD, 0, 0, 0, 0); return r < 0 ? r : b; }
    case NR_poll:
        if ((int)c < 0) return sc(NR_ppoll, a, b, 0, 0, 8, 0);
        t[0].sec = (int)c / 1000; t[0].nsec = ((int)c % 1000) * 1000000L;
        return sc(NR_ppoll, a, b, (long)t, 0, 8, 0);
    case NR_select: {
        struct tv *tv = (struct tv *)e;
        if (tv) { t[0].sec = tv->sec; t[0].nsec = tv->usec * 1000; }
        long r = sc(NR_pselect6, a, b, c, d, tv ? (long)t : 0, 0);
        if (r == 0 && tv) tv->sec = tv->usec = 0;
        return r;
    }
    case NR_epoll_wait: return sc(NR_epoll_pwait, a, b, c, d, 0, 8);
    case NR_epoll_create: return (int)a <= 0 ? -EINVAL : sc(NR_epoll_create1, 0, 0, 0, 0, 0, 0);
    case NR_inotify_init: return sc(NR_inotify_init1, 0, 0, 0, 0, 0, 0);
    case NR_eventfd: return sc(NR_eventfd2, a, 0, 0, 0, 0, 0);
    case NR_signalfd: return sc(NR_signalfd4, a, b, c, 0, 0, 0);
    case NR_pause: return sc(NR_ppoll, 0, 0, 0, 0, 8, 0);
    case NR_alarm: {
        struct itv nv = { { 0, 0 }, { (unsigned)a, 0 } }, ov;
        long r = sc(NR_setitimer, 0, (long)&nv, (long)&ov, 0, 0, 0);
        return r < 0 ? r : ov.value.sec + (ov.value.usec >= 500000);
    }
    case NR_time: {
        long r = sc(NR_clock_gettime, 0, (long)t, 0, 0, 0, 0);
        if (r < 0) return r;
        if (a) *(long *)a = t[0].sec;
        return t[0].sec;
    }
    case NR_getpgrp: return sc(NR_getpgid, 0, 0, 0, 0, 0, 0);
    case NR_fork:
    case NR_vfork: return sc(NR_clone, SIGCHLD, 0, 0, 0, 0, 0);
    case NR_utime: {
        long *u = (long *)b;
        if (!u) return sc(NR_utimensat, AT_FDCWD, a, 0, 0, 0, 0);
        t[0].sec = u[0]; t[0].nsec = 0; t[1].sec = u[1]; t[1].nsec = 0;
        return sc(NR_utimensat, AT_FDCWD, a, (long)t, 0, 0, 0);
    }
    case NR_utimes:
    case NR_futimesat: {
        long dir = nr == NR_futimesat ? a : AT_FDCWD;
        long path = nr == NR_futimesat ? b : a;
        struct tv *v = (struct tv *)(nr == NR_futimesat ? c : b);
        if (!v) return sc(NR_utimensat, dir, path, 0, 0, 0, 0);
        t[0].sec = v[0].sec; t[0].nsec = v[0].usec * 1000; t[1].sec = v[1].sec; t[1].nsec = v[1].usec * 1000;
        return sc(NR_utimensat, dir, path, (long)t, 0, 0, 0);
    }
    default:
        /* clone3, rseq, set_robust_list, … : callers fall back to the older call or go without */
        return -ENOSYS;
    }
}

struct k_sigaction { void (*handler)(int, void *, void *); unsigned long flags; void (*restorer)(void); u64 mask; };

__attribute__((naked)) static void restorer(void)
{
    __asm__ volatile("mov $15, %eax\n\tsyscall\n\thlt");
}

static void on_sigsys(int sig, void *info, void *uc)
{
    (void)sig;
    if (*(int *)((char *)info + 8) != SYS_SECCOMP) {
        /* a SIGSYS someone sent: what it would have done without us */
        struct k_sigaction dfl = { 0, 0, 0, 0 };
        sc(NR_rt_sigaction, SIGSYS, (long)&dfl, 0, 8, 0, 0);
        sc(NR_kill, sc(NR_getpid, 0, 0, 0, 0, 0, 0), SIGSYS, 0, 0, 0, 0);
        return;
    }
    long nr = *(int *)((char *)info + 24);
    long *g = (long *)((char *)uc + 40);
    long r = emulate(nr, g[RDI], g[RSI], g[RDX], g[R10], g[R8]);
#ifdef SIGSYS_LOG
    log_call(nr, r);
#endif
    g[RAX] = r;
}

static int installed;

static void install(void)
{
    if (installed) return;
    installed = 1;
    struct k_sigaction sa = { on_sigsys, SA_SIGINFO | SA_RESTORER | SA_ONSTACK, restorer, 0 };
    sc(NR_rt_sigaction, SIGSYS, (long)&sa, 0, 8, 0, 0);
}

/*
 * Keeping our handler. Bun (Claude Code, OpenCode) puts its own SIGSYS handler in at start, one that answers every
 * blocked call with ENOSYS (OpenCode then fails with "ENOSYS … mkdir"). As an audit module we see every symbol the
 * program binds (la_symbind64) and give it these instead of glibc's sigaction()/signal(): for SIGSYS they keep what
 * the program asked for (so it reads back its own handler) but leave ours in place; every other signal goes to glibc.
 * Calls we cannot rewrite still answer ENOSYS, as Bun's handler would.
 */
#define GLIBC_SIGACTION_SIZE 152 /* x86_64 glibc struct sigaction: handler, 128-byte sa_mask, flags, restorer */

static unsigned char program_sigsys[GLIBC_SIGACTION_SIZE]; /* zeros: SIG_DFL */
static long (*real_sigaction)(int, const void *, void *);
static void *(*real_signal)(int, void *);

static void copy(void *dst, const void *src, unsigned long n)
{
    volatile unsigned char *d = dst;
    const unsigned char *s = src;
    while (n--) *d++ = *s++;
}

static long shim_sigaction(int sig, const void *act, void *old)
{
    if (sig != SIGSYS) return real_sigaction(sig, act, old);
    if (old) copy(old, program_sigsys, sizeof program_sigsys);
    if (act) copy(program_sigsys, act, sizeof program_sigsys);
    return 0;
}

static void *shim_signal(int sig, void *handler)
{
    if (sig != SIGSYS) return real_signal(sig, handler);
    void *prev = *(void **)program_sigsys;
    *(void **)program_sigsys = handler;
    return prev;
}

static int same(const char *a, const char *b)
{
    while (*a && *a == *b) a++, b++;
    return *a == *b;
}

typedef struct { unsigned int st_name; unsigned char st_info, st_other; unsigned short st_shndx; u64 st_value, st_size; } Elf64_Sym;
#define LA_FLG_BINDTO 0x01
#define LA_FLG_BINDFROM 0x02

unsigned int la_objopen(void *map, long lmid, u64 *cookie)
{
    (void)map; (void)lmid; (void)cookie;
    return LA_FLG_BINDTO | LA_FLG_BINDFROM;
}

u64 la_symbind64(Elf64_Sym *sym, unsigned int ndx, u64 *refcook, u64 *defcook, unsigned int *flags, const char *name)
{
    (void)ndx; (void)refcook; (void)defcook; (void)flags;
    if (name[0] == 's' || name[0] == '_' || name[0] == 'b') {
        if (same(name, "sigaction") || same(name, "__sigaction")) {
            if (!real_sigaction) real_sigaction = (long (*)(int, const void *, void *))sym->st_value;
            return (u64)shim_sigaction;
        }
        if (same(name, "signal") || same(name, "bsd_signal")) {
            if (!real_signal) real_signal = (void *(*)(int, void *))sym->st_value;
            return (u64)shim_signal;
        }
    }
    return sym->st_value;
}

/* audit module entry: ld.so calls it right after loading us (LD_AUDIT), before anything else runs */
unsigned int la_version(unsigned int version)
{
    install();
    return version;
}
