package plugins

import (
	"bufio"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// Skill is one skills/<name>.md file of a plugin; detail files for it live in skills/<name>/.
// Parsing follows the extension host's get_skills tool (assets/browser-runtime/skills.mjs).
type Skill struct {
	Name        string `json:"name"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Path        string `json:"path"`
	DetailDir   string `json:"detailDir,omitempty"`
}

var (
	skillName  = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)
	frontMeta  = regexp.MustCompile(`^([A-Za-z_-]+):\s*(.*)$`)
	blockStart = regexp.MustCompile(`^(#|` + "```" + `|[-*]\s|\d+\.\s|\||>)`)
	heading    = regexp.MustCompile(`^#\s+\S`)
	mdLink     = regexp.MustCompile(`\[([^\]]*)\]\([^)]*\)`)
)

const skillPeekBytes = 16 * 1024

func listSkills(pluginDir string) []Skill {
	root := filepath.Join(pluginDir, "skills")
	entries, err := os.ReadDir(root)
	if err != nil {
		return []Skill{}
	}
	skills := []Skill{}
	for _, e := range entries {
		n := e.Name()
		if !e.Type().IsRegular() || !strings.HasSuffix(strings.ToLower(n), ".md") || !skillName.MatchString(n[:len(n)-3]) {
			continue
		}
		name := n[:len(n)-3]
		file := filepath.Join(root, n)
		title, description := describe(peek(file), name)
		s := Skill{Name: name, Title: title, Description: description, Path: file}
		if fi, err := os.Stat(filepath.Join(root, name)); err == nil && fi.IsDir() {
			s.DetailDir = filepath.Join(root, name)
		}
		skills = append(skills, s)
	}
	sort.Slice(skills, func(i, j int) bool { return skills[i].Name < skills[j].Name })
	return skills
}

func peek(file string) string {
	f, err := os.Open(file)
	if err != nil {
		return ""
	}
	defer f.Close()
	data, _ := io.ReadAll(io.LimitReader(f, skillPeekBytes))
	return string(data)
}

// describe returns a title and description from optional `---` frontmatter, else the first heading and paragraph.
func describe(text, fallback string) (string, string) {
	body := text
	meta := map[string]string{}
	if strings.HasPrefix(text, "---\n") || strings.HasPrefix(text, "---\r\n") {
		rest := text[strings.Index(text, "\n")+1:]
		if end := regexp.MustCompile(`(?m)^---\r?$`).FindStringIndex(rest); end != nil {
			sc := bufio.NewScanner(strings.NewReader(rest[:end[0]]))
			for sc.Scan() {
				if m := frontMeta.FindStringSubmatch(strings.TrimRight(sc.Text(), "\r")); m != nil {
					meta[strings.ToLower(m[1])] = strings.Trim(strings.TrimSpace(m[2]), `"'`)
				}
			}
			body = strings.TrimPrefix(strings.TrimPrefix(rest[end[1]:], "\r"), "\n")
		}
	}
	lines := strings.Split(strings.ReplaceAll(body, "\r\n", "\n"), "\n")
	headingAt := -1
	for i, l := range lines {
		if heading.MatchString(l) {
			headingAt = i
			break
		}
	}
	description := meta["description"]
	if description == "" {
		var paragraph []string
		for _, line := range lines[headingAt+1:] {
			t := strings.TrimSpace(line)
			if t == "" {
				if len(paragraph) > 0 {
					break
				}
				continue
			}
			if blockStart.MatchString(t) {
				if len(paragraph) > 0 {
					break
				}
				continue
			}
			paragraph = append(paragraph, t)
		}
		description = mdLink.ReplaceAllString(strings.NewReplacer("*", "", "_", "", "`", "").Replace(strings.Join(paragraph, " ")), "$1")
	}
	if r := []rune(description); len(r) > 240 {
		description = strings.TrimRight(string(r[:237]), " ") + "…"
	}
	title := meta["name"]
	if title == "" {
		title = meta["title"]
	}
	if title == "" && headingAt >= 0 {
		title = strings.TrimSpace(strings.TrimPrefix(lines[headingAt], "#"))
	}
	if title == "" {
		title = fallback
	}
	return title, description
}
