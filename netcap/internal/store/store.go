// Package store keeps a session's flows in SQLite (WAL) and their bodies in Blobs. Writes go through a pending map
// that one writer goroutine flushes in batches, so recording never blocks the traffic and repeated updates of the
// same flow collapse into one row write.
package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	_ "modernc.org/sqlite"

	"antigem/netcap/internal/flow"
)

// Store is one session's database.
type Store struct {
	Dir   string
	Blobs *Blobs
	db    *sql.DB

	nextID  atomic.Int64
	nextDNS atomic.Int64

	mu         sync.Mutex
	pending    map[int64]*flow.Flow
	pendingBlb map[string][]byte
	pendingMsg []flow.Message
	pendingDNS []flow.DNS
	pendingFnd []Finding
	dropped    int64
	flushed    chan struct{} // closed and replaced after every flush
	stop       chan struct{}
	done       chan struct{}
}

const maxPendingMsgs = 50000

const schema = `
CREATE TABLE IF NOT EXISTS flows(
  id INTEGER PRIMARY KEY,
  kind TEXT NOT NULL DEFAULT '',
  app TEXT NOT NULL DEFAULT '',
  uid INTEGER NOT NULL DEFAULT 0,
  start_us INTEGER NOT NULL DEFAULT 0,
  end_us INTEGER NOT NULL DEFAULT 0,
  scheme TEXT NOT NULL DEFAULT '',
  host TEXT NOT NULL DEFAULT '',
  port INTEGER NOT NULL DEFAULT 0,
  method TEXT NOT NULL DEFAULT '',
  path TEXT NOT NULL DEFAULT '',
  http TEXT NOT NULL DEFAULT '',
  status INTEGER NOT NULL DEFAULT 0,
  req_type TEXT NOT NULL DEFAULT '',
  res_type TEXT NOT NULL DEFAULT '',
  req_size INTEGER NOT NULL DEFAULT 0,
  res_size INTEGER NOT NULL DEFAULT 0,
  tls TEXT NOT NULL DEFAULT '',
  error TEXT NOT NULL DEFAULT '',
  tags TEXT NOT NULL DEFAULT '',
  endpoint TEXT NOT NULL DEFAULT '',
  duration_ms REAL NOT NULL DEFAULT 0,
  done INTEGER NOT NULL DEFAULT 0,
  data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS flows_host ON flows(host);
CREATE INDEX IF NOT EXISTS flows_start ON flows(start_us);
CREATE INDEX IF NOT EXISTS flows_endpoint ON flows(endpoint);
CREATE INDEX IF NOT EXISTS flows_app ON flows(app);
CREATE TABLE IF NOT EXISTS messages(
  flow_id INTEGER NOT NULL,
  idx INTEGER NOT NULL,
  dir TEXT NOT NULL,
  t_us INTEGER NOT NULL,
  type TEXT NOT NULL,
  size INTEGER NOT NULL,
  truncated INTEGER NOT NULL DEFAULT 0,
  compressed INTEGER NOT NULL DEFAULT 0,
  data BLOB,
  PRIMARY KEY(flow_id, idx)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS dns(
  id INTEGER PRIMARY KEY,
  t_us INTEGER NOT NULL,
  app TEXT NOT NULL DEFAULT '',
  name TEXT NOT NULL,
  qtype TEXT NOT NULL DEFAULT '',
  answers TEXT NOT NULL DEFAULT '',
  rcode TEXT NOT NULL DEFAULT '',
  ms REAL NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS dns_name ON dns(name);
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS findings(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  t_us INTEGER NOT NULL,
  script TEXT NOT NULL DEFAULT '',
  severity TEXT NOT NULL DEFAULT 'info',
  title TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '',
  flow_id INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS blobs(hash TEXT PRIMARY KEY, data BLOB NOT NULL) WITHOUT ROWID;
`

// Open opens (or creates) the session database in dir.
func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", "file:"+filepath.Join(dir, "index.db")+"?_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(off)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("schema: %w", err)
	}
	blobs, err := NewBlobs(filepath.Join(dir, "blobs"))
	if err != nil {
		db.Close()
		return nil, err
	}
	s := &Store{Dir: dir, Blobs: blobs, db: db, pending: map[int64]*flow.Flow{}, pendingBlb: map[string][]byte{},
		flushed: make(chan struct{}), stop: make(chan struct{}), done: make(chan struct{})}
	blobs.small = s
	var maxID, maxDNS sql.NullInt64
	_ = db.QueryRow(`SELECT max(id) FROM flows`).Scan(&maxID)
	_ = db.QueryRow(`SELECT max(id) FROM dns`).Scan(&maxDNS)
	s.nextID.Store(maxID.Int64)
	s.nextDNS.Store(maxDNS.Int64)
	go s.loop()
	return s, nil
}

// NewID gives the next flow id.
func (s *Store) NewID() int64 { return s.nextID.Add(1) }

// LastID is the newest flow id given out.
func (s *Store) LastID() int64 { return s.nextID.Load() }

// Put queues a snapshot of f for writing (later Puts of the same id replace it).
func (s *Store) Put(f *flow.Flow) {
	c := f.Clone()
	s.mu.Lock()
	s.pending[c.ID] = c
	s.mu.Unlock()
}

func (s *Store) putSmall(hash string, comp []byte) {
	s.mu.Lock()
	s.pendingBlb[hash] = comp
	s.mu.Unlock()
}

func (s *Store) getSmall(hash string) ([]byte, bool) {
	s.mu.Lock()
	if c, ok := s.pendingBlb[hash]; ok {
		s.mu.Unlock()
		return c, true
	}
	s.mu.Unlock()
	var data []byte
	if err := s.db.QueryRow(`SELECT data FROM blobs WHERE hash = ?`, hash).Scan(&data); err != nil {
		return nil, false
	}
	return data, true
}

// PutMessage queues a message (dropped, and counted, when too many are waiting).
func (s *Store) PutMessage(m flow.Message) {
	s.mu.Lock()
	if len(s.pendingMsg) >= maxPendingMsgs {
		s.dropped++
	} else {
		s.pendingMsg = append(s.pendingMsg, m)
	}
	s.mu.Unlock()
}

// PutDNS queues a DNS record.
func (s *Store) PutDNS(d flow.DNS) {
	if d.ID == 0 {
		d.ID = s.nextDNS.Add(1)
	}
	s.mu.Lock()
	if len(s.pendingDNS) < maxPendingMsgs {
		s.pendingDNS = append(s.pendingDNS, d)
	}
	s.mu.Unlock()
}

// Dropped is how many messages were not recorded because the writer fell behind.
func (s *Store) Dropped() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.dropped
}

// Flush waits until everything queued before the call is written.
func (s *Store) Flush() {
	s.mu.Lock()
	empty := len(s.pending) == 0 && len(s.pendingMsg) == 0 && len(s.pendingDNS) == 0 && len(s.pendingBlb) == 0 && len(s.pendingFnd) == 0
	ch := s.flushed
	s.mu.Unlock()
	if empty {
		return
	}
	select {
	case <-ch:
	case <-time.After(10 * time.Second):
	}
	// the batch flushed may have started before our items were queued: wait for one more
	s.mu.Lock()
	empty = len(s.pending) == 0 && len(s.pendingMsg) == 0 && len(s.pendingDNS) == 0 && len(s.pendingBlb) == 0 && len(s.pendingFnd) == 0
	ch = s.flushed
	s.mu.Unlock()
	if !empty {
		select {
		case <-ch:
		case <-time.After(10 * time.Second):
		}
	}
}

// Close flushes and closes the database.
func (s *Store) Close() error {
	close(s.stop)
	<-s.done
	return s.db.Close()
}

func (s *Store) loop() {
	defer close(s.done)
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-tick.C:
			s.flush()
		case <-s.stop:
			s.flush()
			return
		}
	}
}

func (s *Store) flush() {
	s.mu.Lock()
	flows, msgs, dns, blbs, fnds := s.pending, s.pendingMsg, s.pendingDNS, s.pendingBlb, s.pendingFnd
	if len(flows) == 0 && len(msgs) == 0 && len(dns) == 0 && len(blbs) == 0 && len(fnds) == 0 {
		s.mu.Unlock()
		return
	}
	s.pending = map[int64]*flow.Flow{}
	s.pendingBlb = map[string][]byte{}
	s.pendingMsg, s.pendingDNS, s.pendingFnd = nil, nil, nil
	ch := s.flushed
	s.flushed = make(chan struct{})
	s.mu.Unlock()
	defer close(ch)

	tx, err := s.db.Begin()
	if err != nil {
		return
	}
	if len(flows) > 0 {
		st, err := tx.Prepare(`INSERT OR REPLACE INTO flows(id,kind,app,uid,start_us,end_us,scheme,host,port,method,path,http,status,
			req_type,res_type,req_size,res_size,tls,error,tags,endpoint,duration_ms,done,data)
			VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
		if err == nil {
			for _, f := range flows {
				data, _ := json.Marshal(f)
				end := int64(0)
				if !f.End.IsZero() {
					end = f.End.UnixMicro()
				}
				tags := ""
				if len(f.Tags) > 0 {
					tags = "," + strings.ToLower(strings.Join(f.Tags, ",")) + ","
				}
				_, _ = st.Exec(f.ID, string(f.Kind), f.App, f.UID, f.Start.UnixMicro(), end, f.Scheme, strings.ToLower(f.Host), f.Port,
					f.Method, f.Path, f.HTTPVersion, f.Status, f.Req.Type, f.Res.Type, f.Req.Size, f.Res.Size, f.TLS,
					f.Error, tags, f.Endpoint, f.Timings.Total, boolInt(f.Done), string(data))
			}
			st.Close()
		}
	}
	if len(blbs) > 0 {
		st, err := tx.Prepare(`INSERT OR IGNORE INTO blobs(hash,data) VALUES(?,?)`)
		if err == nil {
			for h, d := range blbs {
				_, _ = st.Exec(h, d)
			}
			st.Close()
		}
	}
	if len(msgs) > 0 {
		st, err := tx.Prepare(`INSERT OR REPLACE INTO messages(flow_id,idx,dir,t_us,type,size,truncated,compressed,data) VALUES(?,?,?,?,?,?,?,?,?)`)
		if err == nil {
			for _, m := range msgs {
				_, _ = st.Exec(m.FlowID, m.Index, m.Dir, m.Time.UnixMicro(), m.Type, m.Size, boolInt(m.Truncated), boolInt(m.Compressed), m.Data)
			}
			st.Close()
		}
	}
	if len(dns) > 0 {
		st, err := tx.Prepare(`INSERT OR REPLACE INTO dns(id,t_us,app,name,qtype,answers,rcode,ms) VALUES(?,?,?,?,?,?,?,?)`)
		if err == nil {
			for _, d := range dns {
				_, _ = st.Exec(d.ID, d.Time.UnixMicro(), d.App, d.Name, d.Type, strings.Join(d.Answers, ","), d.RCode, d.Duration)
			}
			st.Close()
		}
	}
	if len(fnds) > 0 {
		st, err := tx.Prepare(`INSERT INTO findings(t_us,script,severity,title,detail,flow_id) VALUES(?,?,?,?,?,?)`)
		if err == nil {
			for _, f := range fnds {
				_, _ = st.Exec(f.Time.UnixMicro(), f.Script, f.Severity, f.Title, f.Detail, f.Flow)
			}
			st.Close()
		}
	}
	_ = tx.Commit()
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// Get returns one flow (a pending, not yet written version wins).
func (s *Store) Get(id int64) (*flow.Flow, error) {
	s.mu.Lock()
	if f, ok := s.pending[id]; ok {
		c := f.Clone()
		s.mu.Unlock()
		return c, nil
	}
	s.mu.Unlock()
	var data string
	err := s.db.QueryRow(`SELECT data FROM flows WHERE id = ?`, id).Scan(&data)
	if err == sql.ErrNoRows {
		return nil, fmt.Errorf("no flow f%d", id)
	}
	if err != nil {
		return nil, err
	}
	var f flow.Flow
	if err := json.Unmarshal([]byte(data), &f); err != nil {
		return nil, err
	}
	return &f, nil
}

// Scan calls fn for flows matching the SQL where clause, newest first (or oldest first when asc), stopping when fn
// returns false. The caller does Flush first when it needs the newest writes.
func (s *Store) Scan(where string, args []any, asc bool, fn func(*flow.Flow) bool) error {
	order := "DESC"
	if asc {
		order = "ASC"
	}
	rows, err := s.db.Query(`SELECT data FROM flows WHERE `+where+` ORDER BY id `+order, args...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var data string
		if err := rows.Scan(&data); err != nil {
			return err
		}
		var f flow.Flow
		if json.Unmarshal([]byte(data), &f) != nil {
			continue
		}
		if !fn(&f) {
			break
		}
	}
	return rows.Err()
}

// Count returns how many flows match a where clause.
func (s *Store) Count(where string, args ...any) int {
	var n int
	_ = s.db.QueryRow(`SELECT count(*) FROM flows WHERE `+where, args...).Scan(&n)
	return n
}

// Messages returns messages of a flow from index from, at most limit.
func (s *Store) Messages(flowID int64, from, limit int) ([]flow.Message, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.db.Query(`SELECT idx,dir,t_us,type,size,truncated,compressed,data FROM messages WHERE flow_id = ? AND idx >= ? ORDER BY idx LIMIT ?`, flowID, from, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []flow.Message
	for rows.Next() {
		var m flow.Message
		var t int64
		var tr, comp int
		if err := rows.Scan(&m.Index, &m.Dir, &t, &m.Type, &m.Size, &tr, &comp, &m.Data); err != nil {
			return out, err
		}
		m.FlowID = flowID
		m.Time = time.UnixMicro(t)
		m.Truncated, m.Compressed = tr == 1, comp == 1
		out = append(out, m)
	}
	return out, rows.Err()
}

// DNSList returns DNS records, newest first.
func (s *Store) DNSList(nameLike string, limit int) ([]flow.DNS, error) {
	if limit <= 0 {
		limit = 50
	}
	q := `SELECT id,t_us,app,name,qtype,answers,rcode,ms FROM dns`
	var args []any
	if nameLike != "" {
		q += ` WHERE name LIKE ?`
		args = append(args, "%"+nameLike+"%")
	}
	q += ` ORDER BY id DESC LIMIT ?`
	args = append(args, limit)
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []flow.DNS
	for rows.Next() {
		var d flow.DNS
		var t int64
		var answers string
		if err := rows.Scan(&d.ID, &t, &d.App, &d.Name, &d.Type, &answers, &d.RCode, &d.Duration); err != nil {
			return out, err
		}
		d.Time = time.UnixMicro(t)
		if answers != "" {
			d.Answers = strings.Split(answers, ",")
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// Finding is something a script reported (or the engine about a script).
type Finding struct {
	ID       int64     `json:"id"`
	Time     time.Time `json:"time"`
	Script   string    `json:"script,omitempty"`
	Severity string    `json:"severity"`
	Title    string    `json:"title"`
	Detail   string    `json:"detail,omitempty"`
	Flow     int64     `json:"flow,omitempty"`
}

const maxFindings = 5000

// AddFinding queues a finding (at most 5000 per session are kept).
func (s *Store) AddFinding(f Finding) {
	if f.Time.IsZero() {
		f.Time = time.Now()
	}
	s.mu.Lock()
	if len(s.pendingFnd) < maxFindings {
		s.pendingFnd = append(s.pendingFnd, f)
	}
	s.mu.Unlock()
}

// FindingCount returns how many findings there are, and how many of each severity.
func (s *Store) FindingCount() (int, map[string]int) {
	s.Flush()
	by := map[string]int{}
	total := 0
	rows, err := s.db.Query(`SELECT severity, count(*) FROM findings GROUP BY severity`)
	if err != nil {
		return 0, by
	}
	defer rows.Close()
	for rows.Next() {
		var sev string
		var n int
		if rows.Scan(&sev, &n) == nil {
			by[sev] = n
			total += n
		}
	}
	return total, by
}

// Findings lists findings, newest first (script and severity filter when set; severity is a minimum: warn = warn
// and error).
func (s *Store) Findings(script, minSeverity string, limit int) ([]Finding, error) {
	s.Flush()
	if limit <= 0 {
		limit = 50
	}
	q := `SELECT id,t_us,script,severity,title,detail,flow_id FROM findings WHERE 1`
	var args []any
	if script != "" {
		q += ` AND script = ?`
		args = append(args, script)
	}
	switch minSeverity {
	case "warn":
		q += ` AND severity IN ('warn','error')`
	case "error":
		q += ` AND severity = 'error'`
	}
	q += ` ORDER BY id DESC LIMIT ?`
	args = append(args, limit)
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Finding
	for rows.Next() {
		var f Finding
		var t int64
		if err := rows.Scan(&f.ID, &t, &f.Script, &f.Severity, &f.Title, &f.Detail, &f.Flow); err != nil {
			return out, err
		}
		f.Time = time.UnixMicro(t)
		out = append(out, f)
	}
	return out, rows.Err()
}

// GetFinding returns one finding.
func (s *Store) GetFinding(id int64) (*Finding, error) {
	s.Flush()
	var f Finding
	var t int64
	err := s.db.QueryRow(`SELECT id,t_us,script,severity,title,detail,flow_id FROM findings WHERE id = ?`, id).
		Scan(&f.ID, &t, &f.Script, &f.Severity, &f.Title, &f.Detail, &f.Flow)
	if err == sql.ErrNoRows {
		return nil, fmt.Errorf("no finding %d", id)
	}
	if err != nil {
		return nil, err
	}
	f.Time = time.UnixMicro(t)
	return &f, nil
}

// ClearFindings deletes findings (of one script, or all).
func (s *Store) ClearFindings(script string) (int64, error) {
	s.Flush()
	var res sql.Result
	var err error
	if script != "" {
		res, err = s.db.Exec(`DELETE FROM findings WHERE script = ?`, script)
	} else {
		res, err = s.db.Exec(`DELETE FROM findings`)
	}
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// Meta reads a meta value.
func (s *Store) Meta(key string) string {
	var v string
	_ = s.db.QueryRow(`SELECT value FROM meta WHERE key = ?`, key).Scan(&v)
	return v
}

// SetMeta writes a meta value.
func (s *Store) SetMeta(key, value string) error {
	_, err := s.db.Exec(`INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)`, key, value)
	return err
}

// Stats is a quick summary of the session.
type Stats struct {
	Flows   int
	Bytes   int64
	First   time.Time
	Last    time.Time
	Dropped int64
}

// Stats returns counts for status lines.
func (s *Store) Stats() Stats {
	var st Stats
	var first, last sql.NullInt64
	var bytes sql.NullInt64
	_ = s.db.QueryRow(`SELECT count(*), min(start_us), max(start_us), sum(req_size+res_size) FROM flows`).Scan(&st.Flows, &first, &last, &bytes)
	if first.Valid {
		st.First = time.UnixMicro(first.Int64)
	}
	if last.Valid {
		st.Last = time.UnixMicro(last.Int64)
	}
	st.Bytes = bytes.Int64
	st.Dropped = s.Dropped()
	return st
}

// DiskBytes is the size of the session folder.
func (s *Store) DiskBytes() int64 {
	var total int64
	_ = filepath.Walk(s.Dir, func(_ string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() {
			total += info.Size()
		}
		return nil
	})
	return total
}
