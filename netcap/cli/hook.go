package cli

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"antigem/netcap/internal/hookclient"
)

// hook runs a command as an external hook of the running engine.
func hook(args []string) error {
	var cmd []string
	for i, a := range args {
		if a == "--" {
			cmd, args = args[i+1:], args[:i]
			break
		}
	}
	fs := flag.NewFlagSet("hook", flag.ExitOnError)
	data := fs.String("data", defaultDataDir(), "the engine's data folder (for run.json)")
	mode := fs.String("mode", "observe", "observe (never waits) or modify (answers within --timeout)")
	filter := fs.String("filter", "", "only flows matching this filter")
	phases := fs.String("phases", "", "request,response,message,complete (default: modify request,response; observe complete)")
	timeout := fs.String("timeout", "200ms", "modify: how long the engine waits for an answer")
	name := fs.String("name", "", "a name shown in net_script action=list")
	noBody := fs.Bool("no-body", false, "don't send bodies (faster, headers only)")
	fs.Parse(args)
	if len(cmd) == 0 {
		cmd = fs.Args()
	}
	b, err := os.ReadFile(filepath.Join(*data, "run.json"))
	if err != nil {
		return errors.New("the engine is not running (start it with: netcap serve)")
	}
	var info runInfo
	if err := json.Unmarshal(b, &info); err != nil {
		return err
	}
	if *name == "" && len(cmd) > 0 {
		*name = filepath.Base(cmd[len(cmd)-1])
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	return hookclient.Run(ctx, hookclient.Options{API: info.API, Token: info.Token, Name: *name, Filter: *filter, Mode: *mode,
		Phases: *phases, Timeout: *timeout, NoBody: *noBody}, cmd)
}
