package cli

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"syscall"
	"time"

	"antigem/netcap/internal/httpapi"
	"antigem/netcap/internal/ingress/proxy"
	"antigem/netcap/internal/mcp"
	"antigem/netcap/internal/service"
)

// limitProcs: the engine is I/O bound; on phones with many cores more Go threads mostly add wake-ups (futex).
func limitProcs() {
	if os.Getenv("GOMAXPROCS") == "" && runtime.GOOS == "android" && runtime.NumCPU() > 4 {
		runtime.GOMAXPROCS(4)
	}
}

func serve(args []string) error {
	limitProcs()
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	data := fs.String("data", defaultDataDir(), "data folder")
	apiAddr := fs.String("api", "127.0.0.1:8777", "API and MCP address")
	proxyAddr := fs.String("proxy", "127.0.0.1:8787", "proxy port (HTTP + SOCKS5); empty = off")
	token := fs.String("token", os.Getenv("NETCAP_TOKEN"), "API token (default: random, written to DATA/run.json)")
	capture := fs.Bool("capture", false, "start recording at once (PC: everything sent through the proxy)")
	control := fs.String("control", os.Getenv("NETCAP_CONTROL"), "Android: abstract socket name of the app's control channel")
	verbose := fs.Bool("v", false, "log more")
	fs.Parse(args)

	logf := func(string, ...any) {}
	if *verbose {
		logf = log.Printf
	}
	if err := os.MkdirAll(*data, 0o700); err != nil {
		return err
	}
	if *token == "" {
		b := make([]byte, 16)
		rand.Read(b)
		*token = hex.EncodeToString(b)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	opts := service.Options{DataDir: *data, APIAddr: *apiAddr, ProxyAddr: *proxyAddr, Logf: logf}
	var ctl controller
	if *control != "" {
		c, err := newController(ctx, *control, *token)
		if err != nil {
			return fmt.Errorf("control channel: %w", err)
		}
		ctl = c
		opts.Platform = c
		opts.CA = c.CA()
	}
	svc, err := service.New(opts)
	if err != nil {
		return err
	}
	defer svc.Close()
	if ctl != nil {
		ctl.Attach(svc)
	}

	mcpSrv := mcp.New(svc)
	api := &httpapi.API{Svc: svc, MCP: mcpSrv, Token: *token}
	if ctl != nil {
		api.Extra = ctl.Routes()
	}
	ln, err := net.Listen("tcp", *apiAddr)
	if err != nil {
		return fmt.Errorf("API port: %w", err)
	}
	httpSrv := &http.Server{Handler: api.Handler(), ReadHeaderTimeout: 10 * time.Second}
	go httpSrv.Serve(ln)

	var px *proxy.Server
	if *proxyAddr != "" {
		px = &proxy.Server{Engine: svc.Engine, Addr: *proxyAddr, CertPEM: svc.CA.CertPEM, Logf: logf}
		if err := px.Listen(); err != nil {
			return fmt.Errorf("proxy port: %w", err)
		}
		go px.Serve(ctx)
	}
	info := runInfo{API: ln.Addr().String(), Token: *token, PID: os.Getpid()}
	if px != nil {
		info.Proxy = px.Addr
	}
	b, _ := json.MarshalIndent(info, "", "  ")
	_ = os.WriteFile(filepath.Join(*data, "run.json"), b, 0o600)
	defer os.Remove(filepath.Join(*data, "run.json"))

	if *capture {
		if err := svc.StartCapture(service.StartOptions{}); err != nil {
			return err
		}
	}
	fmt.Fprintf(os.Stderr, "netcap %s · API+MCP http://%s/mcp · proxy %s · data %s\n", Version, info.API, info.Proxy, *data)
	if ctl != nil {
		go ctl.Run(ctx)
	}
	<-ctx.Done()
	sctx, scancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer scancel()
	_ = httpSrv.Shutdown(sctx)
	return nil
}
