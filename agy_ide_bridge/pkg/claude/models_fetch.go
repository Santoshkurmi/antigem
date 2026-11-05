package claude

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ModelsMapping tells the model-list fetch how to read a provider's answer. Every field is optional: empty means
// "detect it", so providers that follow Anthropic's, OpenAI's, OpenRouter's or Google's shape need no mapping.
type ModelsMapping struct {
	// ListPath is where the array of models is, e.g. "data", "models" or "result.items" ("" = detect).
	ListPath string `json:"list_path,omitempty"`
	// IDField / NameField / ContextField / MaxOutputField are paths inside one list item, e.g. "id" or
	// "top_provider.context_length" ("" = detect).
	IDField        string `json:"id_field,omitempty"`
	NameField      string `json:"name_field,omitempty"`
	ContextField   string `json:"context_field,omitempty"`
	MaxOutputField string `json:"max_output_field,omitempty"`
	// StripPrefix is removed from the start of every id (Google's "models/").
	StripPrefix string `json:"strip_prefix,omitempty"`

	// Pagination: "" = detect from the first answer, "none", "cursor" (more-flag + last id), "token" (next-page
	// token) or "next_url" (the answer carries the URL of the next page).
	Pagination string `json:"pagination,omitempty"`
	// PageSize (0 = the provider's default) is sent as LimitParam ("limit") on every request.
	PageSize   int    `json:"page_size,omitempty"`
	LimitParam string `json:"limit_param,omitempty"`
	// PageParam is the query parameter that carries the cursor / token ("after_id" / "page_token").
	PageParam string `json:"page_param,omitempty"`
	// NextField is the answer field holding the next cursor / token / URL ("last_id" / "next_page_token" / "next").
	NextField string `json:"next_field,omitempty"`
	// MoreField is the boolean answer field that says more pages exist (cursor mode; "has_more").
	MoreField string `json:"more_field,omitempty"`
}

// RawKey is one value found in an answer: its path and a short sample.
type RawKey struct {
	Path   string `json:"path"`
	Sample string `json:"sample,omitempty"`
}

// RawList is an array found in an answer that could be the list of models.
type RawList struct {
	Path  string `json:"path"`
	Count int    `json:"count"`
	// Strings is true when the items are plain strings (the model ids themselves).
	Strings bool     `json:"strings,omitempty"`
	Keys    []RawKey `json:"keys,omitempty"`
}

const (
	maxModelPages  = 100
	maxModelsTotal = 20000
)

// fetchProviderModelsRaw reads the provider's model list, following pagination, and returns the raw exchange of the
// first page too (also when the fetch fails), so a server problem can be told from an app problem.
func fetchProviderModelsRaw(ctx context.Context, p Provider, freeOnly bool) ([]ProviderModel, *FetchRaw, error) {
	listURL := p.ModelsURL
	if listURL == "" && isOpenRouter(p.BaseURL) {
		listURL = openRouterModelsURL
	}
	if listURL == "" {
		return nil, nil, errors.New("set the provider's models URL first")
	}
	mp := p.ModelsMap
	if mp == nil {
		mp = &ModelsMapping{}
	}
	raw := &FetchRaw{URL: listURL}
	ctx, cancel := context.WithTimeout(ctx, 90*time.Second)
	defer cancel()

	var (
		all  []ProviderModel
		seen = map[string]bool{}
		next = withQuery(listURL, mp.limitParam(), pageSizeValue(mp))
	)
	for page := 1; next != ""; page++ {
		body, status, err := fetchModelsPage(ctx, p, next, raw, page == 1)
		if err != nil {
			if page == 1 {
				return nil, raw, err
			}
			raw.PagingError = fmt.Sprintf("page %d: %v", page, err)
			break
		}
		if status/100 != 2 {
			err = fmt.Errorf("HTTP %d: %s", status, strings.TrimSpace(string(body[:min(len(body), 200)])))
			if page == 1 {
				return nil, raw, err
			}
			raw.PagingError = fmt.Sprintf("page %d: %v", page, err)
			break
		}
		var doc interface{}
		dec := json.NewDecoder(bytes.NewReader(body))
		dec.UseNumber()
		if err := dec.Decode(&doc); err != nil {
			if page == 1 {
				return nil, raw, fmt.Errorf("not JSON: %w", err)
			}
			raw.PagingError = fmt.Sprintf("page %d: not JSON", page)
			break
		}
		if page == 1 {
			raw.Lists, raw.TopKeys = analyzeShape(doc)
		}
		entries, err := extractEntries(doc, mp)
		if err != nil {
			if page == 1 {
				return nil, raw, err
			}
			break
		}
		raw.Entries += len(entries)
		fresh := 0
		for _, e := range entries {
			m, ok := entryToModel(e, mp)
			if !ok || seen[m.ID] {
				continue
			}
			seen[m.ID] = true
			fresh++
			if freeOnly && !isFreeToolModel(e, m.ID) {
				continue
			}
			all = append(all, m)
		}
		raw.Pages = page
		var mode string
		next, mode = nextPage(doc, mp, next, listURL)
		if page == 1 {
			raw.Pagination = mode
		}
		if fresh == 0 || page >= maxModelPages || len(seen) >= maxModelsTotal {
			if next != "" && fresh == 0 {
				raw.PagingError = fmt.Sprintf("stopped at page %d: it listed nothing new", page)
			}
			break
		}
	}
	sort.Slice(all, func(i, j int) bool { return strings.ToLower(all[i].ID) < strings.ToLower(all[j].ID) })
	raw.Kept = len(all)
	return all, raw, nil
}

func (mp *ModelsMapping) limitParam() string {
	if mp.PageSize <= 0 {
		return ""
	}
	if mp.LimitParam != "" {
		return mp.LimitParam
	}
	return "limit"
}

func pageSizeValue(mp *ModelsMapping) string {
	if mp.PageSize <= 0 {
		return ""
	}
	return strconv.Itoa(mp.PageSize)
}

// withQuery returns rawURL with query parameter name set to value (unchanged when name or value is empty).
func withQuery(rawURL, name, value string) string {
	if name == "" || value == "" {
		return rawURL
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return rawURL
	}
	q := u.Query()
	q.Set(name, value)
	u.RawQuery = q.Encode()
	return u.String()
}

// fetchModelsPage GETs one page of the list. The first page's request and answer are kept in raw.
func fetchModelsPage(ctx context.Context, p Provider, pageURL string, raw *FetchRaw, first bool) ([]byte, int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, pageURL, nil)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Authorization", "Bearer "+p.APIKey)
	if !isOpenRouter(pageURL) {
		// Anthropic-style lists want these; OpenRouter answers them with a short Anthropic-only list instead
		req.Header.Set("x-api-key", p.APIKey)
		req.Header.Set("anthropic-version", "2023-06-01")
	}
	for k, v := range p.Headers {
		req.Header.Set(k, v)
	}
	if first {
		for k, v := range req.Header {
			val := strings.Join(v, ", ")
			if strings.EqualFold(k, "Authorization") {
				val = "Bearer " + maskKey(p.APIKey)
			} else if strings.EqualFold(k, "X-Api-Key") {
				val = maskKey(p.APIKey)
			}
			raw.RequestHeaders = append(raw.RequestHeaders, k+": "+val)
		}
		sort.Strings(raw.RequestHeaders)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if first {
		raw.Status = resp.StatusCode
		shown := body
		var pretty bytes.Buffer
		if json.Indent(&pretty, body, "", "  ") == nil {
			shown = pretty.Bytes()
		}
		if len(shown) > 256<<10 {
			shown, raw.Truncated = shown[:256<<10], true
		}
		raw.Body = string(shown)
	}
	return body, resp.StatusCode, err
}

// ---------------------------------------------------------------- generic JSON access

// lookup follows a dotted path ("top_provider.context_length") through nested objects.
func lookup(v interface{}, path string) interface{} {
	if path == "" {
		return v
	}
	for _, part := range strings.Split(path, ".") {
		obj, ok := v.(map[string]interface{})
		if !ok {
			return nil
		}
		if v, ok = obj[part]; !ok {
			return nil
		}
	}
	return v
}

func asString(v interface{}) string {
	switch t := v.(type) {
	case string:
		return strings.TrimSpace(t)
	case json.Number:
		return t.String()
	case bool:
		return strconv.FormatBool(t)
	}
	return ""
}

func asInt64(v interface{}) int64 {
	switch t := v.(type) {
	case json.Number:
		if n, err := t.Int64(); err == nil {
			return n
		}
		if f, err := t.Float64(); err == nil {
			return int64(f)
		}
	case float64:
		return int64(t)
	case string:
		if n, err := strconv.ParseInt(strings.TrimSpace(t), 10, 64); err == nil {
			return n
		}
	}
	return 0
}

// firstString returns the first non-empty string found at one of the paths.
func firstString(obj map[string]interface{}, paths ...string) (string, string) {
	for _, p := range paths {
		if s := asString(lookup(obj, p)); s != "" {
			return s, p
		}
	}
	return "", ""
}

func firstNumber(obj map[string]interface{}, paths ...string) int64 {
	for _, p := range paths {
		if n := asInt64(lookup(obj, p)); n > 0 {
			return n
		}
	}
	return 0
}

// listKeys are the places model lists usually are, in the order they are tried.
var listKeys = []string{"data", "models", "items", "results", "result", "list", "model_list"}

// extractEntries finds the array of models in doc (mp.ListPath, else the usual places, else the first array).
// String items become {"id": item}.
func extractEntries(doc interface{}, mp *ModelsMapping) ([]map[string]interface{}, error) {
	var arr []interface{}
	switch {
	case mp.ListPath != "":
		a, ok := lookup(doc, mp.ListPath).([]interface{})
		if !ok {
			return nil, fmt.Errorf("no list at %q in the answer: check the list path in Field mapping", mp.ListPath)
		}
		arr = a
	default:
		if a, ok := doc.([]interface{}); ok {
			arr = a
			break
		}
		obj, ok := doc.(map[string]interface{})
		if !ok {
			return nil, errors.New("not a model list: the answer is neither an array nor an object")
		}
		found := false
		for _, k := range listKeys {
			if a, ok := obj[k].([]interface{}); ok {
				arr, found = a, true
				break
			}
		}
		if !found {
			keys := make([]string, 0, len(obj))
			for k := range obj {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			for _, k := range keys {
				if a, ok := obj[k].([]interface{}); ok && len(a) > 0 {
					arr, found = a, true
					break
				}
			}
		}
		if !found {
			// nested somewhere ("result.items"): the first array of objects or strings found by the shape analysis
			if lists, _ := analyzeShape(doc); len(lists) > 0 {
				if a, ok := lookup(doc, lists[0].Path).([]interface{}); ok {
					arr, found = a, true
				}
			}
		}
		if !found {
			return nil, errors.New("not a model list: no array of models found in the answer (open Field mapping to point at it)")
		}
	}
	out := make([]map[string]interface{}, 0, len(arr))
	for _, it := range arr {
		switch t := it.(type) {
		case map[string]interface{}:
			out = append(out, t)
		case string:
			out = append(out, map[string]interface{}{"id": t})
		}
	}
	return out, nil
}

// entryToModel reads one list item with the mapping (detecting what the mapping leaves empty).
func entryToModel(e map[string]interface{}, mp *ModelsMapping) (ProviderModel, bool) {
	var id, idPath string
	if mp.IDField != "" {
		id, idPath = asString(lookup(e, mp.IDField)), mp.IDField
	} else {
		id, idPath = firstString(e, "id", "model", "name", "slug", "model_id", "modelId")
	}
	if id == "" {
		return ProviderModel{}, false
	}
	prefix := mp.StripPrefix
	if prefix == "" && idPath == "name" {
		prefix = "models/" // Google lists ids as "models/gemini-…" under "name"
	}
	id = strings.TrimSpace(strings.TrimPrefix(id, prefix))
	if id == "" {
		return ProviderModel{}, false
	}

	var name string
	if mp.NameField != "" {
		name = asString(lookup(e, mp.NameField))
	} else if n, p := firstString(e, "display_name", "displayName", "name", "title"); n != "" && (p != "name" || n != id) && (n != "models/"+id) {
		name = n
	}
	var ctxLen, maxOut int64
	if mp.ContextField != "" {
		ctxLen = asInt64(lookup(e, mp.ContextField))
	} else {
		ctxLen = firstNumber(e, "context_length", "max_input_tokens", "context_window", "contextWindow", "max_context_length",
			"context_size", "input_token_limit", "inputTokenLimit", "max_model_len", "top_provider.context_length")
	}
	if mp.MaxOutputField != "" {
		maxOut = asInt64(lookup(e, mp.MaxOutputField))
	} else {
		maxOut = firstNumber(e, "max_output_tokens", "max_completion_tokens", "output_token_limit", "outputTokenLimit", "top_provider.max_completion_tokens")
		if maxOut == 0 && asInt64(lookup(e, "max_input_tokens")) > 0 { // Anthropic style: max_input_tokens + max_tokens (output)
			maxOut = asInt64(lookup(e, "max_tokens"))
		}
	}
	canThink := false
	if sp, ok := lookup(e, "supported_parameters").([]interface{}); ok {
		for _, v := range sp {
			if s := asString(v); s == "reasoning" || s == "include_reasoning" {
				canThink = true
			}
		}
	}
	return ProviderModel{ID: id, Name: name, ContextLength: ctxLen, MaxOutput: maxOut, CanThink: canThink}, true
}

// isFreeToolModel is the OpenRouter "free models that can call tools" filter.
func isFreeToolModel(e map[string]interface{}, id string) bool {
	free := strings.HasSuffix(id, ":free")
	if pr, ok := lookup(e, "pricing").(map[string]interface{}); ok {
		free = free || (asString(pr["prompt"]) == "0" && asString(pr["completion"]) == "0")
	}
	if !free {
		return false
	}
	sp, ok := lookup(e, "supported_parameters").([]interface{})
	if !ok {
		return true // no information: keep it
	}
	for _, v := range sp {
		if asString(v) == "tools" {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------- pagination

// nextPage returns the URL of the next page ("" = none) and the pagination mode that was used, from the answer doc of
// the page at pageURL. mp.Pagination forces a mode; otherwise it is detected from fields in the answer.
func nextPage(doc interface{}, mp *ModelsMapping, pageURL, listURL string) (string, string) {
	obj, ok := doc.(map[string]interface{})
	if !ok || mp.Pagination == "none" {
		return "", "none"
	}
	mode := mp.Pagination
	if mode == "" {
		switch {
		case asString(obj["next"]) != "" && strings.HasPrefix(asString(obj["next"]), "http"):
			mode = "next_url"
		case obj["has_more"] == true && asString(obj["last_id"]) != "":
			mode = "cursor"
		case asString(obj["next_page_token"]) != "" || asString(obj["nextPageToken"]) != "":
			mode = "token"
		default:
			return "", "none"
		}
	}
	pick := func(configured string, fallbacks ...string) (string, string) {
		if configured != "" {
			return asString(lookup(obj, configured)), configured
		}
		return firstString(obj, fallbacks...)
	}
	switch mode {
	case "cursor":
		moreField := mp.MoreField
		if moreField == "" {
			moreField = "has_more"
		}
		if more, _ := lookup(obj, moreField).(bool); !more {
			return "", mode
		}
		cur, _ := pick(mp.NextField, "last_id")
		if cur == "" {
			return "", mode
		}
		param := mp.PageParam
		if param == "" {
			param = "after_id"
		}
		next := withQuery(pageURL, param, cur)
		if mp.PageSize <= 0 { // Anthropic's list gives 20 per page unless asked for more
			if _, has := obj["first_id"]; has {
				next = withQuery(next, "limit", "1000")
			}
		}
		return next, mode
	case "token":
		tok, path := pick(mp.NextField, "next_page_token", "nextPageToken")
		if tok == "" {
			return "", mode
		}
		param := mp.PageParam
		if param == "" {
			param = "page_token"
			if strings.HasPrefix(path, "nextPageToken") {
				param = "pageToken"
			}
		}
		return withQuery(pageURL, param, tok), mode
	case "next_url":
		nu, _ := pick(mp.NextField, "next", "next_url", "links.next")
		if nu == "" {
			return "", mode
		}
		base, err := url.Parse(listURL)
		ref, err2 := url.Parse(nu)
		if err != nil || err2 != nil {
			return "", mode
		}
		return base.ResolveReference(ref).String(), mode
	}
	return "", mode
}

// ---------------------------------------------------------------- shape analysis (for the Field mapping dialog)

func sample(v interface{}) string {
	s := asString(v)
	if s == "" {
		switch t := v.(type) {
		case nil:
			return "null"
		case []interface{}:
			return fmt.Sprintf("[%d items]", len(t))
		case map[string]interface{}:
			return "{…}"
		}
	}
	if len([]rune(s)) > 48 {
		s = string([]rune(s)[:48]) + "…"
	}
	return s
}

// leafKeys lists the values of obj with their dotted paths, up to depth 2 (objects inside objects).
func leafKeys(obj map[string]interface{}, prefix string, depth int, out *[]RawKey) {
	names := make([]string, 0, len(obj))
	for k := range obj {
		names = append(names, k)
	}
	sort.Strings(names)
	for _, k := range names {
		if len(*out) >= 60 {
			return
		}
		v := obj[k]
		if child, ok := v.(map[string]interface{}); ok && depth < 2 {
			leafKeys(child, prefix+k+".", depth+1, out)
			continue
		}
		*out = append(*out, RawKey{Path: prefix + k, Sample: sample(v)})
	}
}

// analyzeShape finds the arrays that could be model lists (depth ≤ 3) and the plain values at the top of the answer
// (has_more, last_id, nextPageToken…), for the mapping dialog's dropdowns.
func analyzeShape(doc interface{}) ([]RawList, []RawKey) {
	var lists []RawList
	var top []RawKey
	var walk func(v interface{}, path string, depth int)
	walk = func(v interface{}, path string, depth int) {
		switch t := v.(type) {
		case []interface{}:
			if len(t) == 0 || len(lists) >= 6 {
				return
			}
			l := RawList{Path: path, Count: len(t)}
			switch first := t[0].(type) {
			case string:
				l.Strings = true
			case map[string]interface{}:
				leafKeys(first, "", 0, &l.Keys)
			default:
				return
			}
			lists = append(lists, l)
		case map[string]interface{}:
			if depth >= 3 {
				return
			}
			names := make([]string, 0, len(t))
			for k := range t {
				names = append(names, k)
			}
			sort.Strings(names)
			for _, k := range names {
				child := k
				if path != "" {
					child = path + "." + k
				}
				walk(t[k], child, depth+1)
			}
		}
	}
	walk(doc, "", 0)
	if obj, ok := doc.(map[string]interface{}); ok {
		names := make([]string, 0, len(obj))
		for k := range obj {
			names = append(names, k)
		}
		sort.Strings(names)
		for _, k := range names {
			switch obj[k].(type) {
			case map[string]interface{}, []interface{}:
			default:
				top = append(top, RawKey{Path: k, Sample: sample(obj[k])})
			}
		}
	}
	return lists, top
}
