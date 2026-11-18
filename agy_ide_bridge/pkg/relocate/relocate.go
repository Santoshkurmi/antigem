// Package relocate rewrites Termux .deb packages for an app with another package name of the same length
// (com.termux -> ai.antigem), in one streaming pass per package and several packages at once. It is the bridge's
// `relocate-debs` command (see Main).
//
// AntiGem's apt hook (libexec/antigem/relocate-debs, written by TermuxRelocation.kt) runs
// `agy_ide_bridge relocate-debs` with the .deb paths on stdin, as apt's DPkg::Pre-Install-Pkgs gives them; the dpkg
// wrapper runs it for .debs given by hand. Each package is decompressed once, every "from" in its file paths,
// contents, symlinks and maintainer scripts becomes "to" while the bytes stream past (both names have the same
// length, so no size changes: only the tar headers' checksums and the md5sums list are recomputed), and the result is
// written as a new .deb that replaces the old one. Packages without "from" are left untouched.
package relocate

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"os"
	"runtime"
	"strings"
	"sync"
)

// Capability is a fixed text in the bridge binary: the apt hook looks for it in the file (grep) to know this bridge
// has the command, without running an older bridge that would start its server instead.
const Capability = "antigem-relocate-debs/1"

type stringList []string

func (s *stringList) String() string     { return strings.Join(*s, ",") }
func (s *stringList) Set(v string) error { *s = append(*s, v); return nil }

// Main runs `relocate-debs` with its arguments (after the command name) and returns the exit code.
func Main(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("relocate-debs", flag.ContinueOnError)
	fs.SetOutput(stderr)
	from := fs.String("from", "com.termux", "the package name the .debs are built for")
	to := fs.String("to", "", "the package name to rewrite them for (same length)")
	var keep stringList
	fs.Var(&keep, "keep", `"from" followed by this stays (Java names inside zips), repeatable`)
	skipExt := fs.String("skip-ext", "apk,jar,zip,aar,whl,dex,jmod", "file extensions left as they are (zip-based: CRCs)")
	aptMode := fs.Bool("apt", false, "apt's hook: only the .debs apt downloaded (in cache/apt/archives) are rewritten")
	jobs := fs.Int("jobs", runtime.NumCPU(), "packages rewritten at the same time")
	check := fs.Bool("check", false, "print "+Capability+" and exit")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *check {
		fmt.Fprintln(stdout, Capability)
		return 0
	}
	if len(*to) == 0 || len(*to) != len(*from) {
		fmt.Fprintf(stderr, "relocate-debs: --to must have the length of %q\n", *from)
		return 2
	}
	rw := newRewriter(*from, *to, keep, strings.Split(*skipExt, ","))

	paths := append([]string(nil), fs.Args()...)
	if len(paths) == 0 {
		sc := bufio.NewScanner(stdin)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			paths = append(paths, sc.Text())
		}
	}
	var debs []string
	for _, p := range paths {
		if !strings.HasSuffix(p, ".deb") {
			continue
		}
		if st, err := os.Stat(p); err != nil || !st.Mode().IsRegular() {
			continue
		}
		// apt also passes a .deb of the user's (apt install ./x.deb): the dpkg wrapper rewrites a copy of it
		if *aptMode && !strings.Contains(p, "/cache/apt/archives/") {
			continue
		}
		debs = append(debs, p)
	}

	if *jobs < 1 {
		*jobs = 1
	}
	work := make(chan string)
	var mu sync.Mutex
	failed := false
	var wg sync.WaitGroup
	for i := 0; i < *jobs; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for p := range work {
				changed, err := relocateDeb(p, rw)
				mu.Lock()
				if err != nil {
					fmt.Fprintf(stderr, "AntiGem: could not adapt %s: %v\n", p, err)
					failed = true
				} else if changed {
					fmt.Fprintf(stdout, "AntiGem: adapted %s for %s\n", p[strings.LastIndex(p, "/")+1:], *to)
				}
				mu.Unlock()
			}
		}()
	}
	for _, p := range debs {
		work <- p
	}
	close(work)
	wg.Wait()
	if failed {
		return 1
	}
	return 0
}
