package checkpoint

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// never saved, on top of the project's own .gitignore files
const defaultExcludes = `# AntiGem project checkpoints: never saved
node_modules/
.gradle/
.cxx/
__pycache__/
.venv/
venv/
.next/
.nuxt/
.turbo/
.DS_Store
.idea/
`

// shadow is a git call on the project's checkpoint repository (work tree: the unit at abs, or none).
func (m *Manager) shadow(p *project, abs string) gitCall {
	c := gitCall{dir: p.dir, gitDir: p.gitDir()}
	if abs != "" {
		c.dir = abs
		c.work = abs
	}
	return c
}

func (m *Manager) shadowStdin(p *project, stdin []byte) gitCall {
	c := m.shadow(p, "")
	c.stdin = stdin
	return c
}

func (m *Manager) gitArgs(p *project, args ...string) []string {
	return append(shadowOpts(p.opts), args...)
}

// prepareLocked makes sure the checkpoint repository exists and copies the user's repository settings and
// ignore list (both re-read every few minutes).
func (m *Manager) prepareLocked(p *project) error {
	if err := ensureShadowRepo(p.gitDir()); err != nil {
		return fmt.Errorf("could not create the checkpoint store: %w", err)
	}
	if p.opts.at.IsZero() || time.Since(p.opts.at) > 10*time.Minute {
		p.opts = readProjectOpts(p.path)
		excl := defaultExcludes
		if b, err := os.ReadFile(filepath.Join(p.path, ".git", "info", "exclude")); err == nil {
			excl += "\n# the project's own .git/info/exclude\n" + string(b)
		}
		target := filepath.Join(p.gitDir(), "info", "exclude")
		if cur, err := os.ReadFile(target); err != nil || string(cur) != excl {
			_ = os.MkdirAll(filepath.Dir(target), 0o700)
			_ = os.WriteFile(target, []byte(excl), 0o600)
		}
	}
	return nil
}

func (m *Manager) maxFileBytes() int64 {
	return int64(m.Settings().MaxFileMB) * 1024 * 1024
}

// snapshotLocked copies the project's current files into the checkpoint repository: the project, then each nested
// git repository found inside it (on its own, since git only records a nested repository's commit, not its files).
func (m *Manager) snapshotLocked(p *project) ([]UnitSnap, error) {
	if err := m.prepareLocked(p); err != nil {
		return nil, err
	}
	maxFile := m.maxFileBytes()
	type pending struct {
		rel   string
		abs   string
		depth int
	}
	queue := []pending{{rel: ".", abs: p.path}}
	var units []UnitSnap
	for len(queue) > 0 && len(units) < maxUnits {
		cur := queue[0]
		queue = queue[1:]
		snap, children, err := m.snapUnit(p, cur.rel, cur.abs, maxFile)
		if err != nil {
			if cur.rel == "." {
				return nil, err
			}
			continue // a nested repository that can't be read: left out
		}
		units = append(units, snap)
		if cur.depth < maxUnitDepth {
			for _, c := range children {
				queue = append(queue, pending{rel: joinUnit(cur.rel, c), abs: filepath.Join(cur.abs, filepath.FromSlash(c)), depth: cur.depth + 1})
			}
		}
	}
	return units, nil
}

// snapUnit updates one unit's index from the files that changed since its last copy and writes its tree.
// It returns the nested repositories found directly inside the unit.
func (m *Manager) snapUnit(p *project, rel, abs string, maxFile int64) (UnitSnap, []string, error) {
	snap, children, err := m.snapUnitOnce(p, rel, abs, maxFile)
	if err != nil {
		// a damaged index (or objects removed under it): start this unit over once
		_ = os.Remove(p.indexPath(rel))
		snap, children, err = m.snapUnitOnce(p, rel, abs, maxFile)
	}
	return snap, children, err
}

func (m *Manager) snapUnitOnce(p *project, rel, abs string, maxFile int64) (UnitSnap, []string, error) {
	snap := UnitSnap{Path: rel}
	if fi, err := os.Stat(abs); err != nil || !fi.IsDir() {
		return snap, nil, fmt.Errorf("%s is not a folder", abs)
	}
	c := m.shadow(p, abs)
	c.index = p.indexPath(rel)
	out, err := c.run(m.gitArgs(p, "ls-files", "-z", "--others", "--modified", "--deleted", "--exclude-standard")...)
	if err != nil {
		return snap, nil, err
	}
	seen := map[string]bool{}
	var add, remove []string
	var children []string
	for _, e := range splitZ(out) {
		if seen[e] {
			continue
		}
		seen[e] = true
		if strings.HasSuffix(e, "/") {
			// a folder git does not go into: a nested repository (or a submodule)
			d := strings.TrimSuffix(e, "/")
			if _, err := os.Lstat(filepath.Join(abs, filepath.FromSlash(d), ".git")); err == nil {
				children = append(children, d)
			}
			continue
		}
		full := filepath.Join(abs, filepath.FromSlash(e))
		fi, err := os.Lstat(full)
		switch {
		case err != nil:
			remove = append(remove, e)
		case fi.IsDir():
			remove = append(remove, e)
		case fi.Mode()&os.ModeSymlink != 0:
			add = append(add, e)
		case fi.Mode().IsRegular():
			if maxFile > 0 && fi.Size() > maxFile {
				snap.Skipped = append(snap.Skipped, e)
				remove = append(remove, e)
				continue
			}
			f, err := os.Open(full)
			if err != nil {
				snap.Skipped = append(snap.Skipped, e)
				remove = append(remove, e)
				continue
			}
			_ = f.Close()
			add = append(add, e)
		default:
			remove = append(remove, e) // sockets, pipes, devices
		}
	}
	if len(remove) > 0 {
		rc := c
		rc.stdin = []byte(strings.Join(remove, "\x00") + "\x00")
		if _, err := rc.run(m.gitArgs(p, "update-index", "--force-remove", "-z", "--stdin")...); err != nil {
			return snap, nil, err
		}
	}
	if len(add) > 0 {
		ac := c
		ac.stdin = []byte(strings.Join(add, "\x00") + "\x00")
		if _, err := ac.run(m.gitArgs(p, "update-index", "--add", "--remove", "--replace", "-z", "--stdin")...); err != nil {
			return snap, nil, err
		}
	}
	tree, err := c.run(m.gitArgs(p, "write-tree")...)
	if err != nil {
		return snap, nil, err
	}
	snap.Tree = strings.TrimSpace(string(tree))
	// the index's files stay reachable (an automatic cleanup never removes what the next copy builds on)
	if _, err := m.shadow(p, "").run("update-ref", "refs/antigem/last/"+unitKey(rel), snap.Tree); err != nil {
		return snap, nil, err
	}
	snap.Head = headOf(abs)
	sort.Strings(snap.Skipped)
	if len(snap.Skipped) > maxSkippedList {
		snap.Skipped = snap.Skipped[:maxSkippedList]
	}
	sort.Strings(children)
	return snap, children, nil
}

// ---------------------------------------------------------------------------------------------- preview / restore

// FileChange is one file a restore changes, as seen from now: "modified" (content goes back), "deleted" (created
// after the checkpoint: removed), "recreated" (deleted after the checkpoint: put back).
type FileChange struct {
	Path    string `json:"path"`
	Change  string `json:"change"`
	Added   int    `json:"added"`
	Removed int    `json:"removed"`
	Binary  bool   `json:"binary,omitempty"`
}

type Warning struct {
	Kind string `json:"kind"`
	Text string `json:"text"`
}

type LaterChat struct {
	Agent  string `json:"agent"`
	ChatID string `json:"chat_id"`
	Title  string `json:"title"`
	Time   int64  `json:"time"`
	Turns  int    `json:"turns"`
}

type Preview struct {
	Checkpoint  Summary      `json:"checkpoint"`
	Files       []FileChange `json:"files"`
	Modified    int          `json:"modified"`
	Deleted     int          `json:"deleted"`
	Recreated   int          `json:"recreated"`
	Kept        []string     `json:"kept,omitempty"` // left as they are (see warnings)
	Warnings    []Warning    `json:"warnings"`
	LaterChats  []LaterChat  `json:"later_chats"`
	LaterInChat int          `json:"later_in_chat"`
}

type RestoreResult struct {
	Preview
	Errors []string `json:"errors,omitempty"`
	UndoID string   `json:"undo_id"`
}

type unitPlan struct {
	unit    UnitSnap
	abs     string
	cur     string // current tree
	write   []string
	del     []string
	changes []FileChange
}

// planLocked compares the checkpoint with the files now (a fresh copy, which is also what "undo" goes back to).
func (m *Manager) planLocked(p *project, rec *Record) (*Preview, []unitPlan, []UnitSnap, error) {
	cur, err := m.snapshotLocked(p)
	if err != nil {
		return nil, nil, nil, err
	}
	curBy := map[string]UnitSnap{}
	for _, u := range cur {
		curBy[u.Path] = u
	}
	pv := &Preview{Checkpoint: rec.summary(), Files: []FileChange{}, Warnings: []Warning{}, LaterChats: []LaterChat{}}
	var plans []unitPlan
	recUnits := map[string]bool{}
	for _, u := range rec.Units {
		recUnits[u.Path] = true
		now, ok := curBy[u.Path]
		if !ok {
			if u.Path != "." {
				pv.Warnings = append(pv.Warnings, Warning{Kind: "repo_gone", Text: fmt.Sprintf(
					"The git repository in %s is gone or can't be read now: its files are left as they are.", u.Path)})
			}
			continue
		}
		abs := p.path
		if u.Path != "." {
			abs = filepath.Join(p.path, filepath.FromSlash(u.Path))
		}
		plan := unitPlan{unit: u, abs: abs, cur: now.Tree}
		if now.Tree != u.Tree {
			if err := m.diffUnit(p, &plan, now, pv); err != nil {
				return nil, nil, nil, err
			}
		}
		if u.Head != "" && now.Head != "" && u.Head != now.Head {
			where := "the project"
			if u.Path != "." {
				where = u.Path
			}
			pv.Warnings = append(pv.Warnings, Warning{Kind: "git_head", Text: fmt.Sprintf(
				"Git in %s moved since then (%s → %s): files go back, but commits, branches and the staging area stay as they are now.",
				where, u.Head, now.Head)})
		}
		plans = append(plans, plan)
	}
	for _, u := range cur {
		if !recUnits[u.Path] {
			pv.Warnings = append(pv.Warnings, Warning{Kind: "repo_new", Text: fmt.Sprintf(
				"%s is a git repository that was added after this point: it is left as it is (delete it yourself if you don't need it).", u.Path)})
		}
	}
	for _, f := range pv.Files {
		switch f.Change {
		case "modified":
			pv.Modified++
		case "deleted":
			pv.Deleted++
		case "recreated":
			pv.Recreated++
		}
	}
	m.addContextWarnings(p, rec, pv)
	return pv, plans, cur, nil
}

// diffUnit fills the plan with what going from now (cur) back to the checkpoint changes.
func (m *Manager) diffUnit(p *project, plan *unitPlan, now UnitSnap, pv *Preview) error {
	c := m.shadow(p, "")
	out, err := c.run(m.gitArgs(p, "diff-tree", "-r", "-z", "--no-renames", "--name-status", now.Tree, plan.unit.Tree)...)
	if err != nil {
		return err
	}
	stats := map[string][2]int{}
	binary := map[string]bool{}
	if ns, err := c.run(m.gitArgs(p, "diff-tree", "-r", "-z", "--no-renames", "--numstat", now.Tree, plan.unit.Tree)...); err == nil {
		for _, e := range splitZ(ns) {
			f := strings.SplitN(e, "\t", 3)
			if len(f) != 3 {
				continue
			}
			if f[0] == "-" {
				binary[f[2]] = true
				continue
			}
			a, _ := strconv.Atoi(f[0])
			r, _ := strconv.Atoi(f[1])
			stats[f[2]] = [2]int{a, r}
		}
	}
	skippedThen := map[string]bool{}
	for _, s := range plan.unit.Skipped {
		skippedThen[s] = true
	}
	tok := splitZ(out)
	for i := 0; i+1 < len(tok); i += 2 {
		status, path := tok[i], tok[i+1]
		shown := joinUnit(plan.unit.Path, path)
		fc := FileChange{Path: shown, Added: stats[path][0], Removed: stats[path][1], Binary: binary[path]}
		switch status[0] {
		case 'D': // only there now: made after the checkpoint
			if skippedThen[path] {
				pv.Kept = append(pv.Kept, shown)
				pv.Warnings = append(pv.Warnings, Warning{Kind: "kept", Text: shown + " was too large to save at that time: it is left as it is."})
				continue
			}
			fc.Change = "deleted"
			plan.del = append(plan.del, path)
		case 'A': // only in the checkpoint: deleted since
			if _, err := os.Lstat(filepath.Join(plan.abs, filepath.FromSlash(path))); err == nil {
				// on disk but not in today's copy (ignored or too large now): never overwritten unsaved
				pv.Kept = append(pv.Kept, shown)
				pv.Warnings = append(pv.Warnings, Warning{Kind: "kept", Text: shown + " exists now but is not saved by checkpoints (ignored or too large): it is left as it is."})
				continue
			}
			fc.Change = "recreated"
			plan.write = append(plan.write, path)
		default: // M, T
			fc.Change = "modified"
			plan.write = append(plan.write, path)
		}
		plan.changes = append(plan.changes, fc)
		pv.Files = append(pv.Files, fc)
	}
	return nil
}

func (m *Manager) addContextWarnings(p *project, rec *Record, pv *Preview) {
	if rec.MidTurn {
		pv.Warnings = append(pv.Warnings, Warning{Kind: "mid_turn", Text: "This message was sent while the agent was still working: going back here may leave its earlier work half done."})
	}
	if rec.Late {
		pv.Warnings = append(pv.Warnings, Warning{Kind: "late", Text: "This copy took longer than expected: the agent may already have started changing files when it was made."})
	}
	sk := 0
	for _, u := range rec.Units {
		sk += len(u.Skipped)
	}
	if sk > 0 {
		pv.Warnings = append(pv.Warnings, Warning{Kind: "skipped", Text: fmt.Sprintf("%d large or unreadable file(s) were not saved in this checkpoint and are left as they are.", sk)})
	}
	byChat := map[string]*LaterChat{}
	var order []string
	for _, r := range p.records {
		if r.Kind != KindTurn || r.Time <= rec.Time {
			continue
		}
		if rec.ChatID != "" && r.ChatID == rec.ChatID {
			pv.LaterInChat++
			continue
		}
		k := r.Agent + "\x00" + r.ChatID
		lc := byChat[k]
		if lc == nil {
			lc = &LaterChat{Agent: r.Agent, ChatID: r.ChatID}
			byChat[k] = lc
			order = append(order, k)
		}
		lc.Turns++
		lc.Time = r.Time
		lc.Title = r.Title
	}
	for _, k := range order {
		pv.LaterChats = append(pv.LaterChats, *byChat[k])
	}
	if len(pv.LaterChats) > 0 {
		pv.Warnings = append(pv.Warnings, Warning{Kind: "other_chats", Text: fmt.Sprintf(
			"%d other chat(s) worked in this project after this point: their changes to these files are undone too.", len(pv.LaterChats))})
	}
	pv.Warnings = append(pv.Warnings, Warning{Kind: "outside", Text: "Only files inside the project go back. What the agent did outside it (installed packages, files elsewhere, servers it started, things it sent) is not undone."})
}

// PreviewRestore says what restoring checkpoint id would change, without changing anything.
func (m *Manager) PreviewRestore(path, id string) (*Preview, error) {
	p, rec, err := m.lockRecord(path, id)
	if err != nil {
		return nil, err
	}
	defer p.mu.Unlock()
	pv, _, _, err := m.planLocked(p, rec)
	return pv, err
}

// Restore puts the project's files back as they were at checkpoint id. A copy of the files right before is saved
// first (UndoID), so the restore itself can be undone.
func (m *Manager) Restore(path, id string) (*RestoreResult, error) {
	p, rec, err := m.lockRecord(path, id)
	if err != nil {
		return nil, err
	}
	pv, plans, cur, err := m.planLocked(p, rec)
	if err != nil {
		p.mu.Unlock()
		return nil, err
	}
	undo := &Record{ID: newID(), Time: time.Now().UnixMilli(), Kind: KindPreRestore, RestoredFrom: rec.ID,
		Title: "Before going back to: " + firstNonEmpty(rec.Title, time.UnixMilli(rec.Time).Format("Jan 2 15:04")), Units: cur}
	if err := m.addRecordLocked(p, undo); err != nil {
		p.mu.Unlock()
		return nil, fmt.Errorf("could not save the copy for undo (nothing was changed): %w", err)
	}
	res := &RestoreResult{Preview: *pv, UndoID: undo.ID}
	for _, plan := range plans {
		res.Errors = append(res.Errors, m.applyUnit(p, plan)...)
	}
	p.mu.Unlock()
	return res, nil
}

func (m *Manager) lockRecord(path, id string) (*project, *Record, error) {
	path = normPath(path)
	p, err := m.project(path, false)
	if err != nil {
		return nil, nil, errors.New("this project has no checkpoints")
	}
	p.mu.Lock()
	rec := p.find(id)
	if rec == nil {
		p.mu.Unlock()
		return nil, nil, errors.New("this checkpoint was deleted")
	}
	return p, rec, nil
}

// applyUnit deletes the files made after the checkpoint, then writes back the changed and deleted ones.
func (m *Manager) applyUnit(p *project, plan unitPlan) []string {
	var errs []string
	dirs := map[string]bool{}
	for _, rel := range plan.del {
		full, err := safeJoin(plan.abs, rel)
		if err != nil {
			errs = append(errs, joinUnit(plan.unit.Path, rel)+": "+err.Error())
			continue
		}
		if err := os.Remove(full); err != nil && !os.IsNotExist(err) {
			errs = append(errs, joinUnit(plan.unit.Path, rel)+": "+err.Error())
			continue
		}
		for d := filepath.Dir(full); len(d) > len(plan.abs) && strings.HasPrefix(d, plan.abs); d = filepath.Dir(d) {
			dirs[d] = true
		}
	}
	// folders left empty by those deletions go too (deepest first; a folder with anything left in it stays)
	var ds []string
	for d := range dirs {
		ds = append(ds, d)
	}
	sort.Slice(ds, func(i, j int) bool { return len(ds[i]) > len(ds[j]) })
	for _, d := range ds {
		_ = os.Remove(d)
	}
	if len(plan.write) == 0 {
		return errs
	}
	tmpIndex := filepath.Join(p.dir, "index", "restore-"+unitKey(plan.unit.Path))
	_ = os.Remove(tmpIndex)
	defer os.Remove(tmpIndex)
	c := m.shadow(p, plan.abs)
	c.index = tmpIndex
	if _, err := c.run(m.gitArgs(p, "read-tree", plan.unit.Tree)...); err != nil {
		return append(errs, err.Error())
	}
	var safe []string
	for _, rel := range plan.write {
		if _, err := safeJoin(plan.abs, rel); err != nil {
			errs = append(errs, joinUnit(plan.unit.Path, rel)+": "+err.Error())
			continue
		}
		// a folder where the file has to go back (made after the checkpoint and not empty): left, reported
		full := filepath.Join(plan.abs, filepath.FromSlash(rel))
		if fi, err := os.Lstat(full); err == nil && fi.IsDir() {
			errs = append(errs, joinUnit(plan.unit.Path, rel)+": a folder is in the way now")
			continue
		}
		safe = append(safe, rel)
	}
	if len(safe) == 0 {
		return errs
	}
	wc := c
	wc.stdin = []byte(strings.Join(safe, "\x00") + "\x00")
	if _, err := wc.run(m.gitArgs(p, "checkout-index", "-f", "-z", "--stdin")...); err != nil {
		errs = append(errs, err.Error())
	}
	return errs
}

// safeJoin is root/rel, refusing paths that leave root or go through a symlinked folder.
func safeJoin(root, rel string) (string, error) {
	clean := filepath.Clean(filepath.FromSlash(rel))
	if clean == "." || filepath.IsAbs(clean) || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errors.New("path outside the project")
	}
	parts := strings.Split(clean, string(filepath.Separator))
	cur := root
	for _, part := range parts[:len(parts)-1] {
		cur = filepath.Join(cur, part)
		fi, err := os.Lstat(cur)
		if err != nil {
			break // the rest does not exist yet: nothing to follow
		}
		if fi.Mode()&os.ModeSymlink != 0 {
			return "", errors.New("goes through a symlinked folder: left as it is")
		}
	}
	return filepath.Join(root, clean), nil
}

// FileDiff is the unified diff of one file from now back to the checkpoint (what the restore does to it).
func (m *Manager) FileDiff(path, id, file string) (string, error) {
	p, rec, err := m.lockRecord(path, id)
	if err != nil {
		return "", err
	}
	defer p.mu.Unlock()
	// the unit holding the file: the longest nested repository path it is in
	var unit *UnitSnap
	for i := range rec.Units {
		u := &rec.Units[i]
		if u.Path == "." {
			if unit == nil {
				unit = u
			}
			continue
		}
		if strings.HasPrefix(file, u.Path+"/") && (unit == nil || unit.Path == "." || len(u.Path) > len(unit.Path)) {
			unit = u
		}
	}
	if unit == nil {
		return "", errors.New("file not in this checkpoint")
	}
	rel := file
	if unit.Path != "." {
		rel = strings.TrimPrefix(file, unit.Path+"/")
	}
	if err := m.prepareLocked(p); err != nil {
		return "", err
	}
	c := m.shadow(p, "")
	c.okCodes = []int{1}
	out, err := c.run(m.gitArgs(p, "diff", "--no-color", "--no-ext-diff", "--no-renames", "refs/antigem/last/"+unitKey(unit.Path), unit.Tree, "--", rel)...)
	if err != nil {
		return "", err
	}
	const max = 400 * 1024
	if len(out) > max {
		out = append(out[:max:max], []byte("\n… (diff cut: too long)\n")...)
	}
	return string(bytes.ToValidUTF8(out, []byte("?"))), nil
}

func firstNonEmpty(a, b string) string {
	if strings.TrimSpace(a) != "" {
		return a
	}
	return b
}
