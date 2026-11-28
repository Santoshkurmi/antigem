// Package cli is the netcap command line: `serve` runs the engine, `hook` connects an external hook, any other
// word runs a network_mcp tool on the running engine. cmd/netcap (the PC binary) and the IDE bridge's Android build
// (`agy_ide_bridge netcap …`, or the bin/netcap link to it) both call Run.
package cli

import (
	"fmt"
	"os"
	"path/filepath"
)

// Version is the engine's version (the bridge sets it to the app's version).
var Version = "dev"

// Run runs the command line (args without the program name) and returns the exit code.
func Run(args []string) int {
	if len(args) < 1 {
		usage()
		return 2
	}
	var err error
	prefix := "netcap:"
	switch args[0] {
	case "serve":
		err = serve(args[1:])
	case "hook":
		prefix = "netcap hook:"
		err = hook(args[1:])
	case "version", "--version":
		fmt.Println("netcap", Version)
	case "help", "-h", "--help":
		usage()
	default:
		err = client(args[0], args[1:])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, prefix, err)
		return 1
	}
	return 0
}

func defaultDataDir() string {
	if d := os.Getenv("NETCAP_DATA"); d != "" {
		return d
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".antigem", "netcap")
}

func usage() {
	fmt.Fprint(os.Stderr, `netcap: AntiGem Network Interceptor engine

  netcap serve [--data DIR] [--api ADDR] [--proxy ADDR] [--capture]
  netcap tools                        list the tools
  netcap <tool> [key=value …]         e.g. netcap list filter="host:api.example.com" limit=5
                                      netcap get id=f12 view=jq jq=.data
  netcap hook [--mode observe|modify] [--filter F] [--phases request,response,message,complete]
              [--timeout 200ms] [--name N] [--no-body] -- cmd args…
                                      run cmd as an external hook: the engine's messages arrive on its stdin, one
                                      JSON object per line; it answers on stdout ({"id":…,"action":"modify",…})
`)
}

type runInfo struct {
	API   string `json:"api"`
	Proxy string `json:"proxy"`
	Token string `json:"token"`
	PID   int    `json:"pid"`
}
