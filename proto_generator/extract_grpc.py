import re
import subprocess
from collections import defaultdict

def analyze():
    binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"
    with open(binary_path, "rb") as f:
        content = f.read()

    # Match /package.ServiceName/MethodName in ASCII bytes
    # gRPC format: /[a-zA-Z0-9_.]+/([A-Z][a-zA-Z0-9_]+)
    pattern = re.compile(rb'/([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/([A-Za-z0-9_]+)')

    matches = pattern.findall(content)
    
    services = defaultdict(set)
    for svc_b, method_b in matches:
        try:
            svc = svc_b.decode('ascii')
            method = method_b.decode('ascii')
        except:
            continue
        
        # Filter obvious web URLs or file paths or Go packages
        if any(svc.startswith(p) for p in [
            'github.com', 'golang.org', 'google.golang.org', 'gopkg.in',
            'w3.org', 'schema.org', 'xml.org', 'googleapis.com', 'example.com', 'corp.google.com'
        ]):
            continue
        if svc.startswith(('bin', 'usr', 'etc', 'var', 'tmp', 'data', 'proc', 'sys')):
            continue
        if any(term in svc for term in ['third_party', 'vendor', 'node_modules', 'dist', 'src']):
            continue

        # Keep if it looks like protobuf/gRPC package (e.g. contains . or _pb or google or exa or cloud or internal or grpc)
        # Also check that method starts with uppercase or valid RPC identifier
        if method and method[0].isupper() and ('.' in svc):
            services[svc].add(method)

    return services

if __name__ == "__main__":
    services = analyze()
    for svc in sorted(services.keys()):
        # Filter services that have reasonable gRPC names
        methods = sorted(list(services[svc]))
        print(f"### `{svc}` ({len(methods)} RPC methods)")
        for m in methods:
            print(f"- `/{svc}/{m}`")
        print()
