import re
import gzip
from google.protobuf import descriptor_pb2

binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"
with open(binary_path, "rb") as f:
    data = f.read()

# In Go protobuf, descriptors are often stored as gzip-compressed raw bytes or uncompressed FileDescriptorProto
# Let's search for gzip header \x1f\x8b\x08
gzip_offsets = [m.start() for m in re.finditer(b'\x1f\x8b\x08', data)]

proto_services = {}

for offset in gzip_offsets:
    # Try decompressing chunks
    for length in [500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000]:
        chunk = data[offset:offset+length]
        try:
            decompressed = gzip.decompress(chunk)
            fd = descriptor_pb2.FileDescriptorProto()
            fd.ParseFromString(decompressed)
            if fd.service:
                pkg = fd.package
                for svc in fd.service:
                    full_name = f"{pkg}.{svc.name}" if pkg else svc.name
                    methods = []
                    for m in svc.method:
                        methods.append({
                            "name": m.name,
                            "input_type": m.input_type,
                            "output_type": m.output_type,
                            "client_streaming": m.client_streaming,
                            "server_streaming": m.server_streaming
                        })
                    proto_services[full_name] = {
                        "file": fd.name,
                        "methods": methods
                    }
            break
        except Exception:
            continue

print(f"Discovered {len(proto_services)} gRPC services via Protobuf Descriptors:\n")
for svc_name, info in sorted(proto_services.items()):
    print(f"Service: {svc_name} (from `{info['file']}`)")
    for m in info["methods"]:
        stream_str = ""
        if m["client_streaming"] and m["server_streaming"]:
            stream_str = " (bidi stream)"
        elif m["client_streaming"]:
            stream_str = " (client stream)"
        elif m["server_streaming"]:
            stream_str = " (server stream)"
        print(f"  rpc {m['name']}({m['input_type']}) returns ({m['output_type']}){stream_str}")
    print()
