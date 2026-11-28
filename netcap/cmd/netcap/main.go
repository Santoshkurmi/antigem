// Command netcap is the AntiGem Network Interceptor engine for PCs (on Android it is part of the IDE bridge).
//
//	netcap serve [--data DIR] [--api 127.0.0.1:8777] [--proxy 127.0.0.1:8787] [--capture]
//	netcap <tool> [key=value …]     run a network_mcp tool on the running engine (status, list, get, summarize, …)
//	netcap tools                    list the tools
//	netcap hook [options] -- cmd …  connect a command as an external hook (JSON lines on its stdin/stdout)
package main

import (
	"os"

	"antigem/netcap/cli"
)

func main() { os.Exit(cli.Run(os.Args[1:])) }
