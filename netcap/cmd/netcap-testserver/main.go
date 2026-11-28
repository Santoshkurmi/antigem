// Command netcap-testserver is the far end for the Network Interceptor test app (netcap/testapp): every kind of
// traffic the engine handles, on a PC in the same network as the phone. Not shipped.
//
//	netcap-testserver [-http :18080] [-https :18443] [-tcp :18081] [-udp :18053]
//
// HTTP/1.1 + h2c on -http, HTTPS (self-signed, h2 + http/1.1) on -https; both serve the same routes:
//
//	/get /post /json?n= /gzip /download?size= /upload /sse?n=&ms= /chunked?n=&ms= /slow?ms= /status?code= /redirect
//	/cookie /login /me /config /edit /grpc /ws /last?path= (what the server received last on a path) /stats
package main

import (
	"bufio"
	"compress/gzip"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
)

type seen struct {
	Method  string            `json:"method"`
	Path    string            `json:"path"`
	Query   string            `json:"query"`
	Proto   string            `json:"proto"`
	Headers map[string]string `json:"headers"`
	Body    string            `json:"body"`
	BodyLen int               `json:"body_len"`
	At      time.Time         `json:"at"`
}

var (
	mu    sync.Mutex
	last  = map[string]seen{}
	count = map[string]*atomic.Int64{}
	total atomic.Int64
)

func remember(r *http.Request, body []byte) {
	h := map[string]string{}
	for k, v := range r.Header {
		h[k] = strings.Join(v, ", ")
	}
	b := string(body)
	if len(b) > 4096 {
		b = b[:4096]
	}
	mu.Lock()
	last[r.URL.Path] = seen{Method: r.Method, Path: r.URL.Path, Query: r.URL.RawQuery, Proto: r.Proto, Headers: h, Body: b, BodyLen: len(body), At: time.Now()}
	c := count[r.URL.Path]
	if c == nil {
		c = &atomic.Int64{}
		count[r.URL.Path] = c
	}
	mu.Unlock()
	c.Add(1)
	total.Add(1)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

var upgrader = websocket.Upgrader{EnableCompression: true, CheckOrigin: func(*http.Request) bool { return true }}

func handler() http.Handler {
	mux := http.NewServeMux()
	readBody := func(r *http.Request) []byte {
		b, _ := io.ReadAll(io.LimitReader(r.Body, 64<<20))
		return b
	}
	mux.HandleFunc("/get", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		writeJSON(w, map[string]any{"method": r.Method, "path": r.URL.Path, "query": r.URL.Query(), "proto": r.Proto, "ua": r.UserAgent(), "auth": r.Header.Get("Authorization")})
	})
	mux.HandleFunc("/post", func(w http.ResponseWriter, r *http.Request) {
		b := readBody(r)
		remember(r, b)
		sum := sha256.Sum256(b)
		var parsed any
		json.Unmarshal(b, &parsed)
		writeJSON(w, map[string]any{"len": len(b), "sha256": hex.EncodeToString(sum[:]), "json": parsed, "content_type": r.Header.Get("Content-Type")})
	})
	mux.HandleFunc("/upload", func(w http.ResponseWriter, r *http.Request) {
		h := sha256.New()
		n, _ := io.Copy(h, r.Body)
		remember(r, nil)
		writeJSON(w, map[string]any{"len": n, "sha256": hex.EncodeToString(h.Sum(nil))})
	})
	mux.HandleFunc("/json", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		n, _ := strconv.Atoi(r.URL.Query().Get("n"))
		if n <= 0 {
			n = 50
		}
		items := make([]any, n)
		for i := range items {
			items[i] = map[string]any{"id": i + 1, "name": fmt.Sprintf("item %d", i+1), "price": float64(i) * 1.5, "tags": []string{"a", "b"},
				"owner": map[string]any{"id": 1000 + i, "email": fmt.Sprintf("user%d@example.com", i)}}
		}
		writeJSON(w, map[string]any{"total": n, "items": items, "next": "/json?page=2"})
	})
	mux.HandleFunc("/gzip", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Encoding", "gzip")
		gz := gzip.NewWriter(w)
		json.NewEncoder(gz).Encode(map[string]any{"gzipped": true, "text": strings.Repeat("hello ", 200)})
		gz.Close()
	})
	mux.HandleFunc("/download", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		size, _ := strconv.ParseInt(r.URL.Query().Get("size"), 10, 64)
		if size <= 0 {
			size = 1 << 20
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
		buf := make([]byte, 64<<10)
		for i := range buf {
			buf[i] = byte(i * 7)
		}
		for left := size; left > 0; {
			n := int64(len(buf))
			if left < n {
				n = left
			}
			if _, err := w.Write(buf[:n]); err != nil {
				return
			}
			left -= n
		}
	})
	mux.HandleFunc("/sse", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		n, _ := strconv.Atoi(r.URL.Query().Get("n"))
		ms, _ := strconv.Atoi(r.URL.Query().Get("ms"))
		if n <= 0 {
			n = 10
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		fl, _ := w.(http.Flusher)
		for i := 0; i < n; i++ {
			fmt.Fprintf(w, "id: %d\nevent: tick\ndata: {\"i\":%d}\n\n", i, i)
			if fl != nil {
				fl.Flush()
			}
			time.Sleep(time.Duration(ms) * time.Millisecond)
		}
	})
	mux.HandleFunc("/chunked", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		n, _ := strconv.Atoi(r.URL.Query().Get("n"))
		ms, _ := strconv.Atoi(r.URL.Query().Get("ms"))
		if n <= 0 {
			n = 10
		}
		w.Header().Set("Content-Type", "text/plain")
		fl, _ := w.(http.Flusher)
		for i := 0; i < n; i++ {
			fmt.Fprintf(w, "line %d\n", i)
			if fl != nil {
				fl.Flush()
			}
			time.Sleep(time.Duration(ms) * time.Millisecond)
		}
	})
	mux.HandleFunc("/slow", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		ms, _ := strconv.Atoi(r.URL.Query().Get("ms"))
		time.Sleep(time.Duration(ms) * time.Millisecond)
		writeJSON(w, map[string]any{"slept_ms": ms})
	})
	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		code, _ := strconv.Atoi(r.URL.Query().Get("code"))
		if code < 100 {
			code = 500
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(code)
		fmt.Fprintf(w, `{"error":"status %d","retry_after":30}`, code)
	})
	mux.HandleFunc("/redirect", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		http.Redirect(w, r, "/get?from=redirect", http.StatusFound)
	})
	mux.HandleFunc("/cookie", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		http.SetCookie(w, &http.Cookie{Name: "session", Value: "sess-" + strconv.FormatInt(time.Now().UnixNano(), 36), Path: "/", HttpOnly: true})
		writeJSON(w, map[string]any{"cookie_in": r.Header.Get("Cookie")})
	})
	mux.HandleFunc("/login", func(w http.ResponseWriter, r *http.Request) {
		b := readBody(r)
		remember(r, b)
		writeJSON(w, map[string]any{"token": "tok-" + strconv.FormatInt(time.Now().Unix(), 10), "user": map[string]any{"id": 42, "name": "Test User"}})
	})
	mux.HandleFunc("/me", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer tok-") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(401)
			io.WriteString(w, `{"error":"invalid_token"}`)
			return
		}
		writeJSON(w, map[string]any{"id": 42, "name": "Test User", "plan": "free"})
	})
	mux.HandleFunc("/config", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		writeJSON(w, map[string]any{"features": map[string]any{"new_ui": false, "beta": true}, "version": 3})
	})
	mux.HandleFunc("/edit", func(w http.ResponseWriter, r *http.Request) {
		b := readBody(r)
		remember(r, b)
		writeJSON(w, map[string]any{"received": string(b), "x_test": r.Header.Get("X-Test")})
	})
	mux.HandleFunc("/grpc", func(w http.ResponseWriter, r *http.Request) {
		b := readBody(r)
		remember(r, b)
		w.Header().Set("Content-Type", "application/grpc")
		w.Header().Set("Trailer", "Grpc-Status, Grpc-Message")
		msg := []byte{0x0a, 0x05, 'h', 'e', 'l', 'l', 'o', 0x10, 0x2a, 0x1a, 0x03, 'a', 'b', 'c'}
		frame := make([]byte, 5)
		binary.BigEndian.PutUint32(frame[1:], uint32(len(msg)))
		w.Write(append(frame, msg...))
		w.Header().Set("Grpc-Status", "0")
		w.Header().Set("Grpc-Message", "ok")
	})
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		remember(r, nil)
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer c.Close()
		c.EnableWriteCompression(true)
		c.WriteMessage(websocket.TextMessage, []byte(`{"type":"welcome","server":"netcap-testserver"}`))
		for {
			t, data, err := c.ReadMessage()
			if err != nil {
				return
			}
			if t == websocket.TextMessage {
				c.WriteMessage(t, append([]byte("echo:"), data...))
			} else {
				c.WriteMessage(t, data)
			}
		}
	})
	mux.HandleFunc("/last", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		s, ok := last[r.URL.Query().Get("path")]
		mu.Unlock()
		if !ok {
			http.Error(w, "nothing yet", 404)
			return
		}
		writeJSON(w, s)
	})
	mux.HandleFunc("/stats", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		out := map[string]int64{}
		for k, v := range count {
			out[k] = v.Load()
		}
		mu.Unlock()
		writeJSON(w, map[string]any{"total": total.Load(), "paths": out})
	})
	return mux
}

func selfSigned(hosts []string) tls.Certificate {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: "netcap test server"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(365 * 24 * time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	for _, h := range hosts {
		if ip := net.ParseIP(h); ip != nil {
			tmpl.IPAddresses = append(tmpl.IPAddresses, ip)
		} else {
			tmpl.DNSNames = append(tmpl.DNSNames, h)
		}
	}
	der, _ := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

func main() {
	httpAddr := flag.String("http", ":18080", "HTTP/1.1 + h2c")
	httpsAddr := flag.String("https", ":18443", "HTTPS (h2 + http/1.1, self-signed)")
	tcpAddr := flag.String("tcp", ":18081", "raw TCP (server speaks first, then echoes lines)")
	udpAddr := flag.String("udp", ":18053", "UDP echo")
	flag.Parse()

	h := handler()
	go func() {
		log.Fatal((&http.Server{Addr: *httpAddr, Handler: h2c.NewHandler(h, &http2.Server{})}).ListenAndServe())
	}()
	go func() {
		ips := []string{"localhost", "127.0.0.1"}
		if addrs, err := net.InterfaceAddrs(); err == nil {
			for _, a := range addrs {
				if n, ok := a.(*net.IPNet); ok {
					ips = append(ips, n.IP.String())
				}
			}
		}
		srv := &http.Server{Addr: *httpsAddr, Handler: h, TLSConfig: &tls.Config{Certificates: []tls.Certificate{selfSigned(ips)}}}
		http2.ConfigureServer(srv, &http2.Server{})
		log.Fatal(srv.ListenAndServeTLS("", ""))
	}()
	go func() {
		ln, err := net.Listen("tcp", *tcpAddr)
		if err != nil {
			log.Fatal(err)
		}
		for {
			c, err := ln.Accept()
			if err != nil {
				continue
			}
			go func(c net.Conn) {
				defer c.Close()
				fmt.Fprint(c, "220 netcap test server\r\n")
				br := bufio.NewReader(c)
				for {
					line, err := br.ReadString('\n')
					if err != nil {
						return
					}
					fmt.Fprint(c, "250 "+line)
				}
			}(c)
		}
	}()
	go func() {
		pc, err := net.ListenPacket("udp", *udpAddr)
		if err != nil {
			log.Fatal(err)
		}
		buf := make([]byte, 65535)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				continue
			}
			pc.WriteTo(buf[:n], from)
		}
	}()
	log.Printf("netcap-testserver: http %s https %s tcp %s udp %s", *httpAddr, *httpsAddr, *tcpAddr, *udpAddr)
	select {}
}
