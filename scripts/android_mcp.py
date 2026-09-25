#!/usr/bin/env python3
import sys
import json
import urllib.request
import urllib.error

BRIDGE_URL = "http://127.0.0.1:8765/mcp"

def call_bridge(payload):
    try:
        req_data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            BRIDGE_URL,
            data=req_data,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            resp_bytes = resp.read()
            return json.loads(resp_bytes.decode("utf-8"))
    except Exception as e:
        return {
            "jsonrpc": "2.0",
            "id": payload.get("id"),
            "error": {
                "code": -32603,
                "message": f"Device Bridge Connection Error: {e}. Make sure the Android app is open or running in background."
            }
        }

def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            continue

        res = call_bridge(req)
        sys.stdout.write(json.dumps(res) + "\n")
        sys.stdout.flush()

if __name__ == "__main__":
    main()
