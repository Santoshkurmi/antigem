package script

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/dop251/goja"

	"antigem/netcap/internal/flow"
)

// worker is one goja runtime with the script loaded. A runtime is used by one goroutine at a time; a script has a
// small pool of them, so top-level variables are per worker (shared state goes into ctx.vars or ctx.store).
type worker struct {
	s            *Script // nil for js: filters
	vm           *goja.Runtime
	fns          map[string]goja.Callable // by hook name
	commands     map[string]goja.Callable
	commandNames []string
	everies      []everyFn
	ctxObj       *goja.Object
	jsonParse    goja.Callable
	jsonString   goja.Callable
	errorCtor    goja.Value
	cur          *call
	loading      bool
}

type everyFn struct {
	every time.Duration
	fn    goja.Callable
}

// call is what the running hook may do.
type call struct {
	kind    string // request, response, message, complete, command, every, load, test
	traffic bool   // on the app's traffic path: no ctx.send / ctx.query
	b       *budget
	flowID  int64
	test    *Sandbox
	gctx    context.Context
}

func (s *Script) newWorker() (*worker, error) {
	w := newVM(s)
	w.loading = true
	w.cur = &call{kind: "load", gctx: context.Background()}
	_, err := w.run(LoadLimit, func() (goja.Value, error) { return w.vm.RunProgram(s.prog) })
	w.cur = nil
	w.loading = false
	if err != nil {
		return nil, err
	}
	for k, name := range hookFuncs {
		if fn, ok := goja.AssertFunction(w.vm.Get(name)); ok {
			w.fns[k] = fn
		}
	}
	return w, nil
}

func newVM(s *Script) *worker {
	vm := goja.New()
	vm.SetMaxCallStackSize(400)
	w := &worker{s: s, vm: vm, fns: map[string]goja.Callable{}, commands: map[string]goja.Callable{}}
	js := vm.Get("JSON").ToObject(vm)
	w.jsonParse, _ = goja.AssertFunction(js.Get("parse"))
	w.jsonString, _ = goja.AssertFunction(js.Get("stringify"))
	w.errorCtor = vm.Get("Error")
	if s != nil {
		w.installGlobals()
	}
	return w
}

// throw raises a JavaScript Error with the message.
func (w *worker) throw(format string, args ...any) {
	obj, err := w.vm.New(w.errorCtor, w.vm.ToValue(fmt.Sprintf(format, args...)))
	if err != nil {
		panic(w.vm.NewGoError(fmt.Errorf(format, args...)))
	}
	panic(obj)
}

// run calls fn with a time limit. A Promise result counts only when it is already settled.
func (w *worker) run(limit time.Duration, fn func() (goja.Value, error)) (goja.Value, error) {
	b := &budget{vm: w.vm, left: limit}
	if w.cur != nil {
		w.cur.b = b
	}
	b.start()
	v, err := fn()
	fired := b.stop()
	w.vm.ClearInterrupt()
	if err != nil {
		var ie *goja.InterruptedError
		if errors.As(err, &ie) || fired {
			return nil, fmt.Errorf("took longer than %s and was stopped", limit)
		}
		return nil, errors.New(cleanErr(err))
	}
	if v != nil {
		if p, ok := v.Export().(*goja.Promise); ok {
			switch p.State() {
			case goja.PromiseStateFulfilled:
				v = p.Result()
			case goja.PromiseStateRejected:
				return nil, fmt.Errorf("the hook's Promise was rejected: %s", p.Result().String())
			default:
				return nil, errors.New("the hook returned a Promise that is still pending: write hooks as plain synchronous functions")
			}
		}
	}
	return v, nil
}

// io runs blocking work (body reads, files, ctx.send) without counting it against the time limit.
func (w *worker) io(fn func()) {
	if w.cur != nil && w.cur.b != nil {
		w.cur.b.pause()
		defer w.cur.b.resume()
	}
	fn()
}

// budget is a hook's time limit; paused while the hook waits for I/O.
type budget struct {
	vm     *goja.Runtime
	mu     sync.Mutex
	left   time.Duration
	since  time.Time
	t      *time.Timer
	gen    int
	paused bool
	done   bool
	fired  bool
}

func (b *budget) start() {
	b.mu.Lock()
	b.arm()
	b.mu.Unlock()
}

func (b *budget) arm() {
	b.gen++
	g := b.gen
	b.since = time.Now()
	b.t = time.AfterFunc(b.left, func() { b.fire(g) })
}

func (b *budget) fire(g int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if g != b.gen || b.paused || b.done {
		return
	}
	b.fired = true
	b.vm.Interrupt("timeout")
}

func (b *budget) pause() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.paused || b.done {
		return
	}
	b.t.Stop()
	b.left -= time.Since(b.since)
	b.paused = true
}

func (b *budget) resume() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.paused || b.done {
		return
	}
	b.paused = false
	if b.left <= 0 {
		b.fired = true
		b.vm.Interrupt("timeout")
		return
	}
	b.arm()
}

func (b *budget) stop() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.done = true
	if b.t != nil {
		b.t.Stop()
	}
	return b.fired
}

// ---- the pool ----

var errBusy = errors.New("all workers busy")

func (s *Script) get(wait time.Duration) (*worker, error) {
	select {
	case w := <-s.pool:
		return w, nil
	default:
	}
	s.poolMu.Lock()
	if s.created < s.Workers {
		s.created++
		s.poolMu.Unlock()
		w, err := s.newWorker()
		if err != nil {
			s.poolMu.Lock()
			s.created--
			s.poolMu.Unlock()
			return nil, err
		}
		return w, nil
	}
	s.poolMu.Unlock()
	t := time.NewTimer(wait)
	defer t.Stop()
	select {
	case w := <-s.pool:
		return w, nil
	case <-t.C:
		return nil, errBusy
	}
}

func (s *Script) put(w *worker) {
	select {
	case s.pool <- w:
	default:
	}
}

// with runs fn on a worker of the script, counting calls, time and failures.
func (s *Script) with(gctx context.Context, kind string, flowID int64, test *Sandbox, wait time.Duration, fn func(w *worker) error) error {
	var w *worker
	var err error
	if test != nil {
		w, err = s.newWorker() // tests don't touch the live workers' top-level state
	} else {
		w, err = s.get(wait)
	}
	if err != nil {
		if errors.Is(err, errBusy) {
			s.busy.Add(1)
			return fmt.Errorf("%s: skipped, all %d workers were busy", kind, s.Workers)
		}
		return err
	}
	if test == nil {
		defer s.put(w)
	}
	if gctx == nil {
		gctx = context.Background()
	}
	w.cur = &call{kind: kind, traffic: kind == "request" || kind == "response" || kind == "message", flowID: flowID, test: test, gctx: gctx}
	defer func() { w.cur = nil }()
	start := time.Now()
	if test == nil {
		s.calls.Add(1)
	}
	err = fn(w)
	if test != nil {
		return err
	}
	if err != nil {
		s.failed(err, hookName(kind), flowID)
		return err
	}
	s.succeeded(time.Since(start))
	return nil
}

func hookName(kind string) string {
	if fn, ok := hookFuncs[kind]; ok {
		return fn
	}
	return kind
}

// ---- entry points ----

// Exchange is one request (and in the response phase its answer) given to onRequest / onResponse.
type Exchange struct {
	Flow    *flow.Flow
	Req     *http.Request
	Resp    *http.Response // response phase
	ReqBody func() ([]byte, error)
	ResBody func() ([]byte, error)
	Ctx     context.Context
}

// Response is an answer a script gives instead of the server (f.respond).
type Response struct {
	Status  int
	Headers map[string]string
	Body    []byte
}

// Result is what a hook changed. The caller applies it only when the hook finished without an error, so a failed
// or stopped hook leaves the flow unchanged.
type Result struct {
	Script   string
	Header   http.Header // the whole new header set of the changed side; nil = unchanged
	Host     string      // request: new Host
	Method   string
	Path     *string
	RawQuery *string
	Status   int
	Body     []byte
	BodySet  bool
	Respond  *Response
	Drop     bool
	Delay    time.Duration
	Redirect string
	Tags     []string
	Note     string
	Changes  []string // what changed, in words (tests, logs)
}

// Traffic reports whether the result changes what is sent.
func (r *Result) Traffic() bool {
	return r.Header != nil || r.Host != "" || r.Method != "" || r.Path != nil || r.RawQuery != nil || r.Status != 0 || r.BodySet ||
		r.Respond != nil || r.Drop || r.Delay > 0 || r.Redirect != ""
}

// RunHTTP runs onRequest (phase "request") or onResponse ("response").
func (s *Script) RunHTTP(phase string, ex *Exchange, test *Sandbox) (*Result, error) {
	fnName := phase
	var res *Result
	wait := s.Timeout
	if test != nil {
		wait = time.Second
	}
	if s.Body["req"] && ex.ReqBody != nil {
		_, _ = ex.ReqBody()
	}
	if phase == "response" && s.Body["res"] && ex.ResBody != nil {
		_, _ = ex.ResBody()
	}
	err := s.with(ex.Ctx, phase, ex.Flow.ID, test, wait, func(w *worker) error {
		fn := w.fns[fnName]
		if fn == nil {
			return nil
		}
		st := newStage(w, phase, ex.Flow)
		st.ex = ex
		st.prepare()
		obj := st.object()
		limit := s.Timeout
		if test != nil {
			limit = CompleteLimit
		}
		if _, err := w.run(limit, func() (goja.Value, error) { return fn(goja.Undefined(), obj, w.ctxObj) }); err != nil {
			return err
		}
		res = st.result()
		return nil
	})
	if err != nil {
		return nil, err
	}
	if res == nil {
		res = &Result{Script: s.Name}
	}
	return res, nil
}

// RunComplete runs onComplete on a finished flow (off the traffic path). Only tags and notes can change.
func (s *Script) RunComplete(gctx context.Context, f *flow.Flow, bodies func(side string) ([]byte, error), test *Sandbox) (*Result, error) {
	var res *Result
	err := s.with(gctx, "complete", f.ID, test, 2*time.Second, func(w *worker) error {
		fn := w.fns["complete"]
		if fn == nil {
			return nil
		}
		st := newStage(w, "complete", f)
		st.bodies = bodies
		obj := st.object()
		if _, err := w.run(CompleteLimit, func() (goja.Value, error) { return fn(goja.Undefined(), obj, w.ctxObj) }); err != nil {
			return err
		}
		res = st.result()
		return nil
	})
	if res == nil {
		res = &Result{Script: s.Name}
	}
	return res, err
}

// Message is one WebSocket message given to onMessage.
type Message struct {
	Dir  string // out = app → server, in = server → app
	Type string // text, binary
	Data []byte
}

// MsgResult is what onMessage changed.
type MsgResult struct {
	Changed bool
	Drop    bool
	Type    string
	Data    []byte
	Extra   []Message // sent after it, the same direction
	Replies []Message // sent back, the other direction
	Tags    []string
	Note    string
	Changes []string
}

// RunMessage runs onMessage.
func (s *Script) RunMessage(f *flow.Flow, m Message, index int, test *Sandbox) (*MsgResult, error) {
	res := &MsgResult{}
	wait := s.Timeout
	if test != nil {
		wait = time.Second
	}
	err := s.with(nil, "message", f.ID, test, wait, func(w *worker) error {
		fn := w.fns["message"]
		if fn == nil {
			return nil
		}
		st := newStage(w, "message", f)
		mobj := w.messageObject(st, m, index, res)
		limit := s.Timeout
		if test != nil {
			limit = CompleteLimit
		}
		_, err := w.run(limit, func() (goja.Value, error) { return fn(goja.Undefined(), mobj, w.ctxObj) })
		if err != nil {
			return err
		}
		r := st.result()
		res.Tags, res.Note = r.Tags, r.Note
		return nil
	})
	if err != nil {
		return nil, err
	}
	return res, nil
}

// Command runs a command("name", fn) of the script: fn(args, ctx). The answer is text (strings as they are,
// other values as JSON).
func (s *Script) Command(gctx context.Context, name string, args any) (string, error) {
	var out string
	err := s.with(gctx, "command", 0, nil, CommandLimit, func(w *worker) error {
		fn := w.commands[name]
		if fn == nil {
			if len(w.commandNames) == 0 {
				return fmt.Errorf("%s has no commands", s.Name)
			}
			return fmt.Errorf("%s has no command %q (commands: %s)", s.Name, name, strings.Join(w.commandNames, ", "))
		}
		v, err := w.run(CommandLimit, func() (goja.Value, error) { return fn(goja.Undefined(), w.fromGo(args), w.ctxObj) })
		if err != nil {
			return err
		}
		out = w.display(v)
		return nil
	})
	return out, err
}

func (s *Script) runEvery(i int) {
	_ = s.with(nil, "every", 0, nil, EveryLimit, func(w *worker) error {
		if i >= len(w.everies) {
			return nil
		}
		_, err := w.run(EveryLimit, func() (goja.Value, error) { return w.everies[i].fn(goja.Undefined(), w.ctxObj) })
		return err
	})
}

// display turns a value into text for the AI: strings as they are, the rest as JSON.
func (w *worker) display(v goja.Value) string {
	if v == nil || goja.IsUndefined(v) {
		return "ok (no value returned)"
	}
	if goja.IsNull(v) {
		return "null"
	}
	if s, ok := v.Export().(string); ok {
		return s
	}
	out, err := w.jsonString(goja.Undefined(), v)
	if err != nil || out == nil || goja.IsUndefined(out) {
		return v.String()
	}
	return out.String()
}
