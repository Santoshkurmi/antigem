package handlers

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/buildinfo"
	"gemini-server/pkg/hub"
	"gemini-server/pkg/security"
)

// GET /api/debug/report: everything the app's Settings → Troubleshoot page shows about this bridge, read when the page
// asks (never in the background). It only reads: files, /proc, the hub's own GetStatus (read-only), and, with net=1,
// a HEAD request to a few public hosts. Nothing is written, started or stopped.
//
//	agy=1      scan the agy file and the running hub for the CSRF header (reads the ~200 MB binary, ~1 s on a phone)
//	net=1      check DNS + HTTPS to the hosts the agents use
//	log=N      last N lines of this run's log (default 400, at most 3000)
//
// The security token, the framed header name and the hub's CSRF value never appear in the answer (they are masked).

const (
	debugLogDefault  = 400
	debugLogMax      = 3000
	debugLineMaxLen  = 2000
	debugScanChunk   = 4 << 20
	debugMaxProcs    = 80
	debugProbeMethod = "/exa.language_server_pb.LanguageServerService/GetStatus"
)

var debugANSI = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*\x07|\r`)

// a secret in a URL's query or fragment: agy prints its web page as http://localhost:<port>/#t=<token>
var debugURLSecret = regexp.MustCompile(`([#?&](?:t|token|key|auth|access_token|code)=)[^&\s#"']+`)

func (h *Handler) DebugReportHandler(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	logLines := debugLogDefault
	if v, err := strconv.Atoi(q.Get("log")); err == nil && v >= 0 {
		logLines = v
	}
	if logLines > debugLogMax {
		logLines = debugLogMax
	}

	var hubInfo *hub.DebugInfo
	csrf := ""
	if h.HubManager != nil {
		d := h.HubManager.DebugInfo()
		hubInfo = &d
		csrf = h.HubManager.CurrentCsrf()
	}
	mask := h.debugMasker(csrf)

	resp := map[string]interface{}{"success": true, "report_version": 1, "time_ms": time.Now().UnixMilli()}
	resp["bridge"] = h.debugBridgeInfo(mask)
	resp["system"] = debugSystemInfo(h.homeDir())

	procs := debugListProcs()
	resp["processes"] = debugInterestingProcs(procs, mask)

	ports := map[string]interface{}{}
	if h.Cfg != nil {
		ports["bridge"] = debugDial(h.Cfg.Port)
	}
	if hubInfo != nil {
		ports["hub"] = debugDial(hubInfo.Port)
	}
	resp["ports"] = ports

	if hubInfo != nil {
		hi := *hubInfo
		hi.LastError = mask(hi.LastError)
		hi.LastExitErr = mask(hi.LastExitErr)
		hi.CrashLines = debugMaskLines(hi.CrashLines, mask, 60)
		hi.RecentOutput = debugMaskLines(hi.RecentOutput, mask, 50)
		resp["hub"] = hi
		// the agy file is only read when Antigravity is on (it is ~200 MB)
		resp["agy"] = h.debugAgy(hubInfo, procs, csrf, q.Get("agy") == "1" && !hubInfo.Disabled, mask)
	}

	if q.Get("net") == "1" {
		resp["net"] = debugNetChecks(h.AgentsOff)
	}
	resp["log"] = h.debugLogs(logLines, mask)
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) homeDir() string {
	if h.Cfg != nil && h.Cfg.HomeDir != "" {
		return h.Cfg.HomeDir
	}
	home, _ := os.UserHomeDir()
	return home
}

// debugMasker hides the token, the framed header name and the hub's CSRF value in any text.
func (h *Handler) debugMasker(csrf string) func(string) string {
	var pairs []string
	if h.SecToken != "" {
		framed := security.BuildFramedHeader(h.SecToken)
		pairs = append(pairs, framed, "<framed-header>", strings.ToLower(framed), "<framed-header>")
		if len(strings.TrimSpace(h.SecToken)) >= 6 {
			pairs = append(pairs, h.SecToken, "<token>")
		}
	}
	if len(csrf) >= 6 {
		pairs = append(pairs, csrf, "<csrf>")
	}
	rep := strings.NewReplacer(pairs...)
	return func(s string) string { return debugURLSecret.ReplaceAllString(rep.Replace(s), "${1}<hidden>") }
}

func debugMaskLines(lines []string, mask func(string) string, keep int) []string {
	if len(lines) > keep {
		lines = lines[len(lines)-keep:]
	}
	out := make([]string, 0, len(lines))
	for _, l := range lines {
		l = debugANSI.ReplaceAllString(l, "")
		if len(l) > debugLineMaxLen {
			l = l[:debugLineMaxLen] + "…"
		}
		out = append(out, mask(l))
	}
	return out
}

// ---- the bridge itself ----

func (h *Handler) debugBridgeInfo(mask func(string) string) map[string]interface{} {
	exe, _ := os.Executable()
	info := map[string]interface{}{
		"version":     buildinfo.Version(),
		"pid":         os.Getpid(),
		"ppid":        os.Getppid(),
		"uid":         os.Getuid(),
		"uptime_s":    int(time.Since(startTime).Seconds()),
		"os":          runtime.GOOS,
		"arch":        runtime.GOARCH,
		"go":          runtime.Version(),
		"executable":  exe,
		"home":        h.homeDir(),
		"run_log":     h.RunLogPath,
		"agents_off":  h.AgentsOff,
		"token_set":   h.SecToken != "",
		"proxy_hdrs":  security.TrustProxyHeaders,
		"hub_enabled": h.HubManager != nil && !h.HubManager.Disabled,
	}
	if h.Cfg != nil {
		info["bind"] = h.Cfg.Bind
		info["port"] = h.Cfg.Port
		info["workspace"] = h.Cfg.WorkspaceDir
		info["agy_data_dir"] = h.Cfg.AppDataDir
	}
	args := make([]string, 0, len(h.StartArgs))
	for i, a := range h.StartArgs {
		if i > 0 && (h.StartArgs[i-1] == "--token" || h.StartArgs[i-1] == "-t") {
			a = "<token>"
		}
		args = append(args, mask(a))
	}
	info["args"] = args

	env := map[string]string{}
	for _, k := range []string{"HOME", "PREFIX", "PATH", "TMPDIR", "SHELL", "LANG", "LD_PRELOAD", "LD_LIBRARY_PATH",
		"ANDROID_DATA", "ANDROID_ROOT", "TERMUX_VERSION", "TERMUX_APP__PACKAGE_NAME",
		"HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "no_proxy", "all_proxy"} {
		if v, ok := os.LookupEnv(k); ok {
			if strings.Contains(strings.ToLower(k), "proxy") {
				v = debugHideUserinfo(v)
			}
			env[k] = mask(v)
		}
	}
	info["env"] = env
	return info
}

var debugUserinfo = regexp.MustCompile(`(://)[^/@\s]+@`)

func debugHideUserinfo(s string) string { return debugUserinfo.ReplaceAllString(s, "${1}<user>@") }

func debugSystemInfo(home string) map[string]interface{} {
	out := map[string]interface{}{}
	if total, free := fsDiskSpace(home); total > 0 {
		out["home_disk_total_mb"] = total >> 20
		out["home_disk_free_mb"] = free >> 20
	}
	if data, err := os.ReadFile("/proc/meminfo"); err == nil {
		for _, line := range strings.Split(string(data), "\n") {
			f := strings.Fields(line)
			if len(f) >= 2 && (f[0] == "MemTotal:" || f[0] == "MemAvailable:" || f[0] == "SwapFree:") {
				if kb, err := strconv.ParseInt(f[1], 10, 64); err == nil {
					out[strings.ToLower(strings.TrimSuffix(f[0], ":"))+"_mb"] = kb >> 10
				}
			}
		}
	}
	if data, err := os.ReadFile("/proc/sys/kernel/osrelease"); err == nil {
		out["kernel"] = strings.TrimSpace(string(data))
	}
	if data, err := os.ReadFile("/proc/loadavg"); err == nil {
		out["loadavg"] = strings.TrimSpace(string(data))
	}
	return out
}

func debugDial(port string) map[string]interface{} {
	if port == "" {
		return map[string]interface{}{"open": false, "error": "no port"}
	}
	t0 := time.Now()
	c, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", port), 800*time.Millisecond)
	ms := time.Since(t0).Milliseconds()
	if err != nil {
		return map[string]interface{}{"port": port, "open": false, "ms": ms, "error": err.Error()}
	}
	_ = c.Close()
	return map[string]interface{}{"port": port, "open": true, "ms": ms}
}

// ---- processes (this user's; Android shows only those) ----

type debugProc struct {
	Pid    int      `json:"pid"`
	PPid   int      `json:"ppid"`
	AgeS   int64    `json:"age_s"`
	RssMB  int64    `json:"rss_mb"`
	Args   string   `json:"args"`
	Exe    string   `json:"exe,omitempty"`
	argv   []string // not masked, not sent
	exeRaw string
}

func debugListProcs() []debugProc {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	uptime := debugUptime()
	page := int64(os.Getpagesize())
	uid := os.Getuid()
	var out []debugProc
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		raw, err := os.ReadFile(filepath.Join("/proc", e.Name(), "cmdline"))
		if err != nil || len(raw) == 0 {
			continue // other users' processes and kernel threads
		}
		if !debugSameUser(e.Name(), uid) {
			continue
		}
		argv := strings.Split(strings.TrimRight(string(raw), "\x00"), "\x00")
		p := debugProc{Pid: pid, argv: argv, Args: strings.Join(argv, " ")}
		if stat, err := os.ReadFile(filepath.Join("/proc", e.Name(), "stat")); err == nil {
			s := string(stat)
			if i := strings.LastIndexByte(s, ')'); i > 0 {
				f := strings.Fields(s[i+1:])
				if len(f) > 19 {
					p.PPid, _ = strconv.Atoi(f[1])
					if st, err := strconv.ParseFloat(f[19], 64); err == nil && uptime > 0 {
						p.AgeS = int64(uptime - st/100)
					}
				}
			}
		}
		if statm, err := os.ReadFile(filepath.Join("/proc", e.Name(), "statm")); err == nil {
			if f := strings.Fields(string(statm)); len(f) > 1 {
				if pages, err := strconv.ParseInt(f[1], 10, 64); err == nil {
					p.RssMB = pages * page >> 20
				}
			}
		}
		if exe, err := os.Readlink(filepath.Join("/proc", e.Name(), "exe")); err == nil {
			p.exeRaw = exe
			p.Exe = exe
		}
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Pid < out[j].Pid })
	return out
}

// debugUptime is the seconds since boot: /proc/uptime, or (Android does not let apps read it) this process's start tick
// in /proc/self/stat plus how long this process has run.
func debugUptime() float64 {
	if data, err := os.ReadFile("/proc/uptime"); err == nil {
		if f := strings.Fields(string(data)); len(f) > 0 {
			if v, err := strconv.ParseFloat(f[0], 64); err == nil && v > 0 {
				return v
			}
		}
	}
	if stat, err := os.ReadFile("/proc/self/stat"); err == nil {
		s := string(stat)
		if i := strings.LastIndexByte(s, ')'); i > 0 {
			if f := strings.Fields(s[i+1:]); len(f) > 19 {
				if st, err := strconv.ParseFloat(f[19], 64); err == nil {
					return st/100 + time.Since(startTime).Seconds()
				}
			}
		}
	}
	return 0
}

var debugProcWords = []string{"agy", "antigravity", "claude", "opencode", "codex", "node", "bun", "language_server", "bridge"}

// debugSameUser: the process runs as [uid] (its /proc/<pid>/status "Uid:" line, real uid first).
func debugSameUser(pid string, uid int) bool {
	data, err := os.ReadFile(filepath.Join("/proc", pid, "status"))
	if err != nil {
		return false
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "Uid:") {
			f := strings.Fields(line)
			return len(f) > 1 && f[1] == strconv.Itoa(uid)
		}
	}
	return false
}

// debugInterestingProcs: this bridge's children (and theirs) and anything that looks like an agent, masked.
func debugInterestingProcs(procs []debugProc, mask func(string) string) []debugProc {
	parent := map[int]int{}
	for _, p := range procs {
		parent[p.Pid] = p.PPid
	}
	self := os.Getpid()
	isDescendant := func(pid int) bool {
		for i := 0; i < 64 && pid > 1; i++ {
			if pid == self {
				return true
			}
			pid = parent[pid]
		}
		return false
	}
	var out []debugProc
	for _, p := range procs {
		lower := strings.ToLower(p.Args)
		keep := p.Pid == self || isDescendant(p.Pid)
		for _, w := range debugProcWords {
			if !keep && strings.Contains(lower, w) {
				keep = true
			}
		}
		if !keep {
			continue
		}
		c := p
		c.Args = mask(debugMaskTokenArg(p.argv))
		if len(c.Args) > 400 {
			c.Args = c.Args[:400] + "…"
		}
		out = append(out, c)
		if len(out) >= debugMaxProcs {
			break
		}
	}
	return out
}

// debugMaskTokenArg joins argv with the value after --token / -t hidden (another bridge's token is not ours to mask).
func debugMaskTokenArg(argv []string) string {
	parts := make([]string, len(argv))
	for i, a := range argv {
		if i > 0 && (argv[i-1] == "--token" || argv[i-1] == "-t") {
			a = "<token>"
		}
		parts[i] = a
	}
	return strings.Join(parts, " ")
}

// ---- Antigravity: the agy file, the running hub and whether it takes the app's header ----

type debugHeaderCounts struct {
	Ours    int              `json:"ours"`
	Default int              `json:"default"`
	Other   []debugOtherHead `json:"other"`
	Error   string           `json:"error,omitempty"`
	Ms      int64            `json:"ms"`
}

type debugOtherHead struct {
	Token string `json:"token"` // first 4 characters only
	Count int    `json:"count"`
}

func (h *Handler) debugAgy(info *hub.DebugInfo, procs []debugProc, csrf string, scan bool, mask func(string) string) map[string]interface{} {
	out := map[string]interface{}{"scanned": scan}
	target := info.PatchTarget
	fileInfo := map[string]interface{}{"configured": info.AgyBin, "target": target}
	if hub.UsesAgyLinuxBuild() {
		fileInfo["linux_build"] = true
	}
	var targetStat os.FileInfo
	if target != "" {
		if lfi, err := os.Lstat(info.AgyBin); err == nil && lfi.Mode()&os.ModeSymlink != 0 {
			if dest, err := filepath.EvalSymlinks(info.AgyBin); err == nil {
				fileInfo["symlink_to"] = dest
			}
		}
		if fi, err := os.Stat(target); err != nil {
			fileInfo["exists"] = false
			fileInfo["error"] = err.Error()
		} else {
			targetStat = fi
			fileInfo["exists"] = true
			fileInfo["size"] = fi.Size()
			fileInfo["modified_ms"] = fi.ModTime().UnixMilli()
			fileInfo["mode"] = fi.Mode().String()
			if scan {
				fileInfo["headers"] = debugScanHeaders(target, h.SecToken)
			}
		}
		// agy files the updater left next to it (agy.<n>.old) and the installer's temp files
		if matches, _ := filepath.Glob(filepath.Join(filepath.Dir(target), "agy*")); len(matches) > 0 {
			var names []string
			for _, m := range matches {
				if fi, err := os.Stat(m); err == nil && !fi.IsDir() {
					names = append(names, fmt.Sprintf("%s (%d MB, %s)", filepath.Base(m), fi.Size()>>20, fi.ModTime().Format("2006-01-02 15:04")))
				}
			}
			fileInfo["siblings"] = names
		}
	}

	// who runs it: the hub(s), other agy processes, the processes using the file (Linux refuses to patch it then)
	hubs, others := []map[string]interface{}{}, []map[string]interface{}{}
	inUse := []int{}
	for _, p := range procs {
		if !debugIsAgy(p) {
			continue
		}
		running := debugRunningFile(p)
		same := targetStat != nil && running != "" && debugSameFile(running, targetStat)
		if same {
			inUse = append(inUse, p.Pid)
		}
		entry := map[string]interface{}{
			"pid": p.Pid, "ppid": p.PPid, "age_s": p.AgeS, "args": mask(debugMaskTokenArg(p.argv)),
			"exe": p.exeRaw, "running_file": running, "same_as_file": same,
			"replaced_since_start": strings.HasSuffix(p.exeRaw, " (deleted)") || strings.HasSuffix(running, " (deleted)"),
		}
		if debugArgsHave(p.argv, "--hub") {
			port := debugHubPort(p.argv)
			entry["own_child"] = p.Pid == info.OwnPid
			entry["port"] = port
			entry["on_our_port"] = port == info.Port
			if scan && !same && running != "" && (port == info.Port || p.Pid == info.OwnPid) {
				entry["headers"] = debugScanHeaders(debugReadablePath(p, running), h.SecToken)
			}
			hubs = append(hubs, entry)
		} else {
			others = append(others, entry)
		}
	}
	fileInfo["in_use_by"] = inUse
	out["file"] = fileInfo
	out["hub_processes"] = hubs
	out["other_agy_processes"] = others
	out["other_bridges"] = debugOtherBridges(procs, mask)
	out["discovery"] = h.debugDiscovery(info.Port)
	out["probe"] = h.debugProbeHub(info, csrf)
	return out
}

func debugIsAgy(p debugProc) bool {
	if len(p.argv) == 0 || p.Pid == os.Getpid() || debugIsBridge(p) {
		return false
	}
	first := filepath.Base(p.argv[0])
	if strings.HasPrefix(first, "agy") {
		return true
	}
	// agy behind a loader (Android x86_64: ld-linux … agy.va39 …)
	if strings.HasPrefix(first, "ld-linux") || strings.HasPrefix(first, "linker") {
		for _, a := range p.argv[1:] {
			if b := filepath.Base(a); strings.HasPrefix(b, "agy") && !strings.HasPrefix(b, "agy_ide_bridge") {
				return true
			}
		}
	}
	return false
}

// debugRunningFile is the agy file a process runs: its executable, or for a loader the agy file it mapped.
func debugRunningFile(p debugProc) string {
	base := filepath.Base(strings.TrimSuffix(p.exeRaw, " (deleted)"))
	if p.exeRaw != "" && !strings.HasPrefix(base, "ld-linux") && !strings.HasPrefix(base, "linker") {
		return p.exeRaw
	}
	data, err := os.ReadFile(filepath.Join("/proc", strconv.Itoa(p.Pid), "maps"))
	if err != nil {
		return p.exeRaw
	}
	for _, line := range strings.Split(string(data), "\n") {
		f := strings.Fields(line)
		if len(f) >= 6 {
			path := strings.Join(f[5:], " ")
			if b := filepath.Base(strings.TrimSuffix(path, " (deleted)")); strings.HasPrefix(b, "agy") && !strings.HasPrefix(b, "agy_ide_bridge") {
				return path
			}
		}
	}
	return p.exeRaw
}

// debugReadablePath: a path the file of [running] can be read from (a replaced executable still opens as /proc/<pid>/exe).
func debugReadablePath(p debugProc, running string) string {
	if running == p.exeRaw {
		return filepath.Join("/proc", strconv.Itoa(p.Pid), "exe")
	}
	return strings.TrimSuffix(running, " (deleted)")
}

func debugSameFile(running string, target os.FileInfo) bool {
	fi, err := os.Stat(strings.TrimSuffix(running, " (deleted)"))
	return err == nil && os.SameFile(fi, target)
}

func debugArgsHave(argv []string, flag string) bool {
	for _, a := range argv {
		if a == flag || strings.HasPrefix(a, flag+"=") {
			return true
		}
	}
	return false
}

func debugHubPort(argv []string) string {
	for i, a := range argv {
		if strings.HasPrefix(a, "--hub-port=") {
			return strings.TrimPrefix(a, "--hub-port=")
		}
		if a == "--hub-port" && i+1 < len(argv) {
			return argv[i+1]
		}
	}
	return ""
}

// debugIsBridge: an AntiGem bridge (this app's, an older one, or the VS Code extension's).
func debugIsBridge(p debugProc) bool {
	if len(p.argv) == 0 {
		return false
	}
	first := filepath.Base(p.argv[0])
	return strings.HasPrefix(first, "agy_ide_bridge") || strings.HasPrefix(first, "antigem-bridge") || strings.Contains(first, "gemini-server")
}

func debugOtherBridges(procs []debugProc, mask func(string) string) []map[string]interface{} {
	out := []map[string]interface{}{}
	self := os.Getpid()
	for _, p := range procs {
		if p.Pid == self || !debugIsBridge(p) {
			continue
		}
		out = append(out, map[string]interface{}{"pid": p.Pid, "ppid": p.PPid, "age_s": p.AgeS, "args": mask(debugMaskTokenArg(p.argv))})
	}
	return out
}

// debugScanHeaders counts the CSRF header names in an agy file, reading it in chunks (the file is ~200 MB).
func debugScanHeaders(path, token string) debugHeaderCounts {
	t0 := time.Now()
	res := debugHeaderCounts{Other: []debugOtherHead{}}
	f, err := os.Open(path)
	if err != nil {
		res.Error = err.Error()
		return res
	}
	defer f.Close()
	ours := []byte(nil)
	if strings.TrimSpace(token) != "" {
		ours = []byte(security.BuildFramedHeader(token))
	}
	def := []byte(security.DefaultHeader)
	prefix, suffix := []byte(security.Prefix), []byte(security.Suffix)
	const n = security.HeaderLen // both header forms are 20 bytes
	other := map[string]int{}
	buf := make([]byte, debugScanChunk+n)
	carry := 0
	for {
		read, rerr := io.ReadFull(f, buf[carry:])
		data := buf[:carry+read]
		eof := rerr == io.EOF || errors.Is(rerr, io.ErrUnexpectedEOF)
		if rerr != nil && !eof {
			res.Error = rerr.Error()
			break
		}
		limit := len(data)
		if !eof {
			limit = len(data) - (n - 1) // a header starting later may run past this chunk: counted with the next one
		}
		for pos := 0; pos < limit; {
			i := bytes.Index(data[pos:], prefix)
			if i < 0 || pos+i >= limit {
				break
			}
			at := pos + i
			if at+n <= len(data) && bytes.Equal(data[at+n-len(suffix):at+n], suffix) {
				h := data[at : at+n]
				if ours != nil && bytes.Equal(h, ours) {
					res.Ours++
				} else if debugTokenChars(h[len(prefix) : n-len(suffix)]) {
					other[string(h[len(prefix):len(prefix)+4])]++
				}
			}
			pos = at + 1
		}
		for pos := 0; pos < limit; {
			i := bytes.Index(data[pos:], def)
			if i < 0 || pos+i >= limit {
				break
			}
			res.Default++
			pos += i + 1
		}
		if eof {
			break
		}
		carry = copy(buf, data[limit:])
	}
	for t, c := range other {
		res.Other = append(res.Other, debugOtherHead{Token: t + "…", Count: c})
	}
	sort.Slice(res.Other, func(i, j int) bool { return res.Other[i].Count > res.Other[j].Count })
	res.Ms = time.Since(t0).Milliseconds()
	return res
}

func debugTokenChars(b []byte) bool {
	for _, c := range b {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == ' ') {
			return false
		}
	}
	return true
}

// debugDiscovery: the file agy writes for the hub on this port (who agy thinks runs it). The CSRF value in it is not read out.
func (h *Handler) debugDiscovery(port string) map[string]interface{} {
	dir := ""
	if h.HubManager != nil && h.HubManager.AppDataDir != "" {
		dir = h.HubManager.AppDataDir
	} else if h.Cfg != nil {
		dir = h.Cfg.AppDataDir
	}
	path := filepath.Join(dir, "daemon", "hub_"+port+".json")
	out := map[string]interface{}{"file": path}
	data, err := os.ReadFile(path)
	if err != nil {
		out["error"] = err.Error()
		return out
	}
	if fi, err := os.Stat(path); err == nil {
		out["modified_ms"] = fi.ModTime().UnixMilli()
	}
	for _, key := range []string{"pid", "httpPort", "httpsPort", "lsVersion"} {
		if m := regexp.MustCompile(`"` + key + `"\s*:\s*"?([^",}\s]+)`).FindSubmatch(data); m != nil {
			out[key] = string(m[1])
		}
	}
	if pid, err := strconv.Atoi(fmt.Sprint(out["pid"])); err == nil {
		_, statErr := os.Stat(filepath.Join("/proc", strconv.Itoa(pid)))
		out["pid_alive"] = statErr == nil
	}
	return out
}

// debugProbeHub asks the hub for its status (GetStatus, read-only) once with the app's header and once with agy's
// standard one. The two answers tell apart: works / agy not patched / patched for another token / stale CSRF value.
func (h *Handler) debugProbeHub(info *hub.DebugInfo, csrf string) map[string]interface{} {
	out := map[string]interface{}{"method": "GetStatus"}
	if info.Disabled {
		out["verdict"] = "hub_off"
		return out
	}
	if csrf == "" && h.HubManager != nil {
		csrf = h.HubManager.FetchCsrfToken()
		out["csrf_fetched_now"] = csrf != ""
	}
	out["csrf_known"] = csrf != ""
	value := csrf
	if value == "" {
		value = "true"
	}
	url := "http://127.0.0.1:" + info.Port + debugProbeMethod
	var appRes, defRes map[string]interface{}
	if strings.TrimSpace(h.SecToken) != "" {
		appRes = debugProbeOnce(url, security.BuildFramedHeader(h.SecToken), value)
		out["app_header"] = appRes
	}
	defRes = debugProbeOnce(url, security.DefaultHeader, value)
	out["standard_header"] = defRes

	verdict := "unknown"
	app := ""
	if appRes != nil {
		app = fmt.Sprint(appRes["result"])
	}
	std := fmt.Sprint(defRes["result"])
	switch {
	case app == "unreachable" || (appRes == nil && std == "unreachable"):
		verdict = "unreachable"
	case app == "ok":
		verdict = "ok"
	case appRes == nil:
		verdict = "no_token" // a bridge started without a token: the hub keeps agy's standard header
		if std == "ok" {
			verdict = "ok_standard"
		}
	case app == "invalid_csrf":
		verdict = "stale_csrf"
	case app == "missing_csrf" && (std == "ok" || std == "invalid_csrf"):
		verdict = "unpatched"
	case app == "missing_csrf" && std == "missing_csrf":
		verdict = "other_token"
	default:
		verdict = "error"
	}
	out["verdict"] = verdict
	return out
}

func debugProbeOnce(url, header, value string) map[string]interface{} {
	req, err := http.NewRequest(http.MethodPost, url, strings.NewReader("{}"))
	if err != nil {
		return map[string]interface{}{"result": "error", "error": err.Error()}
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(header, value)
	client := &http.Client{Timeout: 4 * time.Second}
	t0 := time.Now()
	resp, err := client.Do(req)
	ms := time.Since(t0).Milliseconds()
	if err != nil {
		res := "error"
		var opErr *net.OpError
		if errors.As(err, &opErr) && opErr.Op == "dial" {
			res = "unreachable"
		}
		return map[string]interface{}{"result": res, "error": err.Error(), "ms": ms}
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 600))
	text := string(body)
	res := "other"
	switch {
	case resp.StatusCode == http.StatusOK:
		res = "ok"
	case strings.Contains(text, "missing CSRF"):
		res = "missing_csrf"
	case strings.Contains(text, "invalid CSRF"):
		res = "invalid_csrf"
	}
	out := map[string]interface{}{"result": res, "code": resp.StatusCode, "ms": ms}
	if res != "ok" {
		if len(text) > 300 {
			text = text[:300] + "…"
		}
		out["body"] = text
	}
	return out
}

// ---- internet from where the agents run ----

// Agent: the agent the host is for ("" = always checked); skipped when that agent is turned off.
type debugNetTarget struct{ Name, URL, Agent string }

var debugNetTargets = []debugNetTarget{
	{"Internet (Google)", "https://www.gstatic.com/generate_204", ""},
	{"Antigravity (Google Cloud Code)", "https://cloudcode-pa.googleapis.com/", "antigravity"},
	{"Claude (Anthropic)", "https://api.anthropic.com/", "claude"},
	{"Codex (OpenAI)", "https://api.openai.com/", "codex"},
	{"OpenCode (models.dev)", "https://models.dev/", "opencode"},
	{"GitHub", "https://api.github.com/", ""},
}

func debugNetChecks(agentsOff []string) []map[string]interface{} {
	off := map[string]bool{}
	for _, a := range agentsOff {
		off[a] = true
	}
	var targets []debugNetTarget
	for _, t := range debugNetTargets {
		if t.Agent == "" || !off[t.Agent] {
			targets = append(targets, t)
		}
	}
	out := make([]map[string]interface{}, len(targets))
	var wg sync.WaitGroup
	for i, t := range targets {
		wg.Add(1)
		go func(i int, t debugNetTarget) {
			defer wg.Done()
			out[i] = debugNetCheck(t)
		}(i, t)
	}
	wg.Wait()
	return out
}

func debugNetCheck(t debugNetTarget) map[string]interface{} {
	res := map[string]interface{}{"name": t.Name, "url": t.URL}
	host := strings.TrimPrefix(strings.TrimPrefix(t.URL, "https://"), "http://")
	if i := strings.IndexByte(host, '/'); i >= 0 {
		host = host[:i]
	}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	t0 := time.Now()
	ips, err := net.DefaultResolver.LookupHost(ctx, host)
	res["dns_ms"] = time.Since(t0).Milliseconds()
	if err != nil {
		res["ok"] = false
		res["error"] = "DNS: " + err.Error()
		return res
	}
	res["dns_answers"] = len(ips)
	client := &http.Client{
		Timeout:       10 * time.Second,
		Transport:     &http.Transport{Proxy: http.ProxyFromEnvironment, TLSHandshakeTimeout: 8 * time.Second},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	req, _ := http.NewRequest(http.MethodHead, t.URL, nil)
	req.Header.Set("User-Agent", "AntiGem-Troubleshoot")
	t1 := time.Now()
	resp, err := client.Do(req)
	res["http_ms"] = time.Since(t1).Milliseconds()
	if err != nil {
		res["ok"] = false
		res["error"] = err.Error()
		return res
	}
	resp.Body.Close()
	res["status"] = resp.StatusCode
	res["ok"] = resp.StatusCode < 500
	return res
}

// ---- logs ----

func (h *Handler) debugLogs(lines int, mask func(string) string) map[string]interface{} {
	out := map[string]interface{}{"file": h.RunLogPath}
	if h.RunLogPath != "" && lines > 0 {
		out["lines"] = debugMaskLines(debugTail(h.RunLogPath, lines), mask, lines)
	}
	// how the run before this one ended (a crash or a kill shows there)
	if h.RunLogPath != "" {
		files, _ := filepath.Glob(filepath.Join(filepath.Dir(h.RunLogPath), "agy-ide-bridge_*.log"))
		sort.Strings(files)
		var recent []string
		for i := len(files) - 1; i >= 0 && len(recent) < 6; i-- {
			if fi, err := os.Stat(files[i]); err == nil {
				recent = append(recent, fmt.Sprintf("%s (%d KB)", filepath.Base(files[i]), fi.Size()>>10))
			}
		}
		out["recent_files"] = recent
		current := filepath.Clean(h.RunLogPath)
		for i := len(files) - 1; i >= 0; i-- {
			if filepath.Clean(files[i]) < current {
				out["previous_file"] = filepath.Base(files[i])
				out["previous_lines"] = debugMaskLines(debugTail(files[i], 60), mask, 60)
				break
			}
		}
	}
	// the hub's own newest log
	dir := ""
	if h.HubManager != nil {
		dir = h.HubManager.AppDataDir
	}
	if dir != "" {
		matches, _ := filepath.Glob(filepath.Join(dir, "log", "cli-*.log"))
		newest, newestMod := "", int64(0)
		for _, m := range matches {
			if fi, err := os.Stat(m); err == nil && fi.ModTime().UnixMilli() > newestMod {
				newest, newestMod = m, fi.ModTime().UnixMilli()
			}
		}
		if newest != "" {
			out["hub_file"] = filepath.Base(newest)
			out["hub_modified_ms"] = newestMod
			out["hub_lines"] = debugMaskLines(debugTail(newest, 150), mask, 150)
		}
	}
	return out
}

// debugTail returns the last [n] lines of a text file, reading at most its last 1 MB.
func debugTail(path string, n int) []string {
	f, err := os.Open(path)
	if err != nil {
		return []string{"(could not read: " + err.Error() + ")"}
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return nil
	}
	const maxRead = 1 << 20
	start := int64(0)
	if fi.Size() > maxRead {
		start = fi.Size() - maxRead
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return nil
	}
	data, _ := io.ReadAll(f)
	if start > 0 {
		if i := bytes.IndexByte(data, '\n'); i >= 0 {
			data = data[i+1:]
		}
	}
	lines := strings.Split(strings.TrimRight(string(data), "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines
}
