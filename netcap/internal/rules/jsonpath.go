package rules

import (
	"fmt"
	"strconv"
	"strings"
)

// A small JSON path for edits: .a.b[0].c, ["key with dots"], [] (every element of an array).
type step struct {
	key   string
	index int  // >= 0: array index
	all   bool // []: every element
	isIdx bool
}

func parsePath(p string) ([]step, error) {
	p = strings.TrimSpace(p)
	if p == "" || p == "." {
		return nil, nil
	}
	var steps []step
	i := 0
	if p[0] != '.' && p[0] != '[' {
		p = "." + p
	}
	for i < len(p) {
		switch p[i] {
		case '.':
			i++
			j := i
			for j < len(p) && p[j] != '.' && p[j] != '[' {
				j++
			}
			if j == i {
				return nil, fmt.Errorf("empty key in path %q", p)
			}
			steps = append(steps, step{key: p[i:j]})
			i = j
		case '[':
			j := strings.IndexByte(p[i:], ']')
			if j < 0 {
				return nil, fmt.Errorf("missing ] in path %q", p)
			}
			inner := p[i+1 : i+j]
			i += j + 1
			switch {
			case inner == "":
				steps = append(steps, step{all: true})
			case strings.HasPrefix(inner, `"`) && strings.HasSuffix(inner, `"`) && len(inner) >= 2:
				steps = append(steps, step{key: inner[1 : len(inner)-1]})
			default:
				n, err := strconv.Atoi(inner)
				if err != nil {
					return nil, fmt.Errorf("bad index %q in path %q", inner, p)
				}
				steps = append(steps, step{index: n, isIdx: true})
			}
		default:
			return nil, fmt.Errorf("bad path %q (use .a.b[0].c)", p)
		}
	}
	return steps, nil
}

// jsonSet sets the value at path, creating objects on the way. Returns the new root.
func jsonSet(root any, path string, val any) (any, error) {
	steps, err := parsePath(path)
	if err != nil {
		return root, err
	}
	if len(steps) == 0 {
		return val, nil
	}
	return set(root, steps, val), nil
}

func set(cur any, steps []step, val any) any {
	if len(steps) == 0 {
		return val
	}
	s := steps[0]
	switch {
	case s.all:
		arr, ok := cur.([]any)
		if !ok {
			return cur
		}
		for i := range arr {
			arr[i] = set(arr[i], steps[1:], val)
		}
		return arr
	case s.isIdx:
		arr, ok := cur.([]any)
		if !ok {
			arr = []any{}
		}
		idx := s.index
		if idx < 0 {
			idx = len(arr) + idx
		}
		for idx >= len(arr) {
			arr = append(arr, nil)
		}
		if idx >= 0 {
			arr[idx] = set(arr[idx], steps[1:], val)
		}
		return arr
	default:
		obj, ok := cur.(map[string]any)
		if !ok {
			obj = map[string]any{}
		}
		obj[s.key] = set(obj[s.key], steps[1:], val)
		return obj
	}
}

// jsonDelete removes the value at path (no error when it isn't there).
func jsonDelete(root any, path string) (any, error) {
	steps, err := parsePath(path)
	if err != nil || len(steps) == 0 {
		return root, err
	}
	del(root, steps)
	return root, nil
}

func del(cur any, steps []step) {
	s := steps[0]
	last := len(steps) == 1
	switch {
	case s.all:
		if arr, ok := cur.([]any); ok && !last {
			for _, v := range arr {
				del(v, steps[1:])
			}
		}
	case s.isIdx:
		arr, ok := cur.([]any)
		if !ok || s.index < 0 || s.index >= len(arr) {
			return
		}
		if last {
			arr[s.index] = nil
			return
		}
		del(arr[s.index], steps[1:])
	default:
		obj, ok := cur.(map[string]any)
		if !ok {
			return
		}
		if last {
			delete(obj, s.key)
			return
		}
		if v, ok := obj[s.key]; ok {
			del(v, steps[1:])
		}
	}
}

// jsonGet reads the value at path.
func jsonGet(root any, path string) (any, bool) {
	steps, err := parsePath(path)
	if err != nil {
		return nil, false
	}
	cur := root
	for _, s := range steps {
		switch {
		case s.isIdx:
			arr, ok := cur.([]any)
			if !ok || s.index < 0 || s.index >= len(arr) {
				return nil, false
			}
			cur = arr[s.index]
		case s.all:
			return nil, false
		default:
			obj, ok := cur.(map[string]any)
			if !ok {
				return nil, false
			}
			v, ok := obj[s.key]
			if !ok {
				return nil, false
			}
			cur = v
		}
	}
	return cur, true
}
