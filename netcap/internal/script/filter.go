package script

import (
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/dop251/goja"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/query"
)

// js:"…" filter terms: a JavaScript expression on the flow f (read only), e.g.
// js:"f.status >= 500 && f.res.json().error.code === 'RATE'".
func init() { query.JSCompiler = compileFilter }

const filterLimit = 50 * time.Millisecond

type filterVM struct {
	w  *worker
	fn goja.Callable
}

func newFilterVM(prog *goja.Program) (*filterVM, error) {
	w := newVM(nil)
	v, err := w.vm.RunProgram(prog)
	if err != nil {
		return nil, fmt.Errorf("js: %s", cleanErr(err))
	}
	fn, ok := goja.AssertFunction(v)
	if !ok {
		return nil, fmt.Errorf("js: not an expression")
	}
	return &filterVM{w: w, fn: fn}, nil
}

func compileFilter(expr string) (func(*flow.Flow, query.Bodies) bool, error) {
	if strings.TrimSpace(expr) == "" {
		return nil, fmt.Errorf("js: give an expression, e.g. js:\"f.status >= 500\"")
	}
	prog, err := goja.Compile("js-filter", "(function (f) { return (\n"+expr+"\n); })", false)
	if err != nil {
		return nil, fmt.Errorf("js: %s", strings.Replace(cleanErr(err), "js-filter:", "line ", 1))
	}
	first, err := newFilterVM(prog)
	if err != nil {
		return nil, err
	}
	var pool sync.Pool
	pool.Put(first)
	return func(f *flow.Flow, b query.Bodies) bool {
		fv, _ := pool.Get().(*filterVM)
		if fv == nil {
			var err error
			if fv, err = newFilterVM(prog); err != nil {
				return false
			}
		}
		defer pool.Put(fv)
		st := newStage(fv.w, "filter", f)
		if b != nil {
			st.bodies = func(side string) ([]byte, error) { return b.Body(f, side), nil }
		}
		var ok bool
		_, err := fv.w.run(filterLimit, func() (goja.Value, error) {
			v, err := fv.fn(goja.Undefined(), st.object())
			if err == nil && v != nil {
				ok = v.ToBoolean()
			}
			return v, err
		})
		return err == nil && ok
	}, nil
}
