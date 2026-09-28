import re
import subprocess
from collections import defaultdict

binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"

with open(binary_path, "rb") as f:
    data = f.read()

# Let's find Go symbols or method descriptors
# Search for grpc client/server method strings in Go
# Format: (*Package.ServiceClient).MethodName or ServiceClient_MethodName_Client
# Or grpc.MethodDesc struct fields

# Let's find all gRPC full method names: /<Service>/<Method>
# We can find known method names by checking Go interface / client struct methods
go_client_methods = re.findall(rb'\(\*([a-zA-Z0-9_]+Client)\)\.([A-Z][a-zA-Z0-9_]+)', data)
client_method_map = defaultdict(set)
for client, method in go_client_methods:
    try:
        client_method_map[client.decode()].add(method.decode())
    except:
        pass

# Also look for ServiceDesc ServiceName
service_names = set(re.findall(rb'(?:google\.[a-zA-Z0-9_.]+|exa\.[a-zA-Z0-9_.]+|jetski\.[a-zA-Z0-9_.]+|grpc\.[a-zA-Z0-9_.]+Service|CloudCode|Operations)', data))

print("Go Client Structs found:")
for client in sorted(client_method_map.keys()):
    print(f"Client: {client} -> {sorted(list(client_method_map[client]))}")
