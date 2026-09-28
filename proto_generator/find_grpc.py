import re
import os
from collections import defaultdict

def run():
    binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"
    with open(binary_path, "rb") as f:
        content = f.read()

    # Look for exact gRPC method paths: /package.ServiceName/MethodName
    pattern = re.compile(rb'/([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/([A-Za-z0-9_]+)')
    
    matches = pattern.findall(content)
    services = defaultdict(set)
    
    for s_b, m_b in matches:
        try:
            s = s_b.decode('ascii')
            m = m_b.decode('ascii')
        except:
            continue
        
        # We only want gRPC services
        # Known packages: google.*, exa.*, grpc.*
        if not (s.startswith(('google.', 'exa.', 'grpc.')) or 'jetski' in s.lower() or 'cloudcode' in s.lower() or 'cascade' in s.lower()):
            continue
        
        # Filter out Go package import paths or proto file paths
        if any(bad in s for bad in ['.go', '.proto', '/']):
            continue
        
        # Check method is PascalCase
        if m and m[0].isupper() and not any(bad in m for bad in ['.', '/', '-', ' ', '(', ')']):
            services[s].add(m)
            
    for s in sorted(services.keys()):
        print(f"SERVICE: {s}")
        for m in sorted(services[s]):
            print(f"  METHOD: /{s}/{m}")
        print()

if __name__ == "__main__":
    run()
