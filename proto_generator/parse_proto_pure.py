import re
import gzip
from io import BytesIO

def read_varint(stream):
    res = 0
    shift = 0
    while True:
        b = stream.read(1)
        if not b:
            return None
        byte = b[0]
        res |= (byte & 0x7f) << shift
        if not (byte & 0x80):
            break
        shift += 7
    return res

def parse_proto(stream, length=None):
    fields = []
    start_pos = stream.tell()
    while True:
        if length is not None and (stream.tell() - start_pos) >= length:
            break
        tag = read_varint(stream)
        if tag is None:
            break
        field_num = tag >> 3
        wire_type = tag & 0x07
        
        if wire_type == 0: # Varint
            val = read_varint(stream)
        elif wire_type == 1: # 64-bit
            val = stream.read(8)
        elif wire_type == 2: # Length-delimited
            l = read_varint(stream)
            val = stream.read(l)
        elif wire_type == 5: # 32-bit
            val = stream.read(4)
        else:
            break
        fields.append((field_num, wire_type, val))
    return fields

def parse_method(data):
    fields = parse_proto(BytesIO(data))
    name = ""
    in_type = ""
    out_type = ""
    client_streaming = False
    server_streaming = False
    for num, wt, val in fields:
        if num == 1 and wt == 2:
            name = val.decode('utf-8', errors='ignore')
        elif num == 2 and wt == 2:
            in_type = val.decode('utf-8', errors='ignore')
        elif num == 3 and wt == 2:
            out_type = val.decode('utf-8', errors='ignore')
        elif num == 5 and wt == 0:
            client_streaming = bool(val)
        elif num == 6 and wt == 0:
            server_streaming = bool(val)
    return {
        "name": name,
        "input_type": in_type,
        "output_type": out_type,
        "client_streaming": client_streaming,
        "server_streaming": server_streaming
    }

def parse_service(data):
    fields = parse_proto(BytesIO(data))
    name = ""
    methods = []
    for num, wt, val in fields:
        if num == 1 and wt == 2:
            name = val.decode('utf-8', errors='ignore')
        elif num == 2 and wt == 2:
            methods.append(parse_method(val))
    return name, methods

def parse_file_descriptor(data):
    fields = parse_proto(BytesIO(data))
    filename = ""
    package = ""
    services = []
    for num, wt, val in fields:
        if num == 1 and wt == 2:
            filename = val.decode('utf-8', errors='ignore')
        elif num == 2 and wt == 2:
            package = val.decode('utf-8', errors='ignore')
        elif num == 6 and wt == 2:
            services.append(parse_service(val))
    return filename, package, services

def main():
    binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"
    with open(binary_path, "rb") as f:
        data = f.read()

    gzip_offsets = [m.start() for m in re.finditer(b'\x1f\x8b\x08', data)]
    
    discovered_services = {}

    for offset in gzip_offsets:
        # Try finding the exact valid gzip stream
        for length in [1000, 2500, 5000, 10000, 25000, 50000, 100000, 200000, 500000]:
            chunk = data[offset:offset+length]
            try:
                decomp = gzip.decompress(chunk)
                fname, pkg, services = parse_file_descriptor(decomp)
                if services:
                    for svc_name, methods in services:
                        full_svc = f"{pkg}.{svc_name}" if pkg else svc_name
                        discovered_services[full_svc] = {
                            "package": pkg,
                            "file": fname,
                            "service": svc_name,
                            "methods": methods
                        }
                break
            except:
                continue

    return discovered_services

if __name__ == "__main__":
    svcs = main()
    print(f"TOTAL SERVICES FOUND: {len(svcs)}\n")
    for svc_name in sorted(svcs.keys()):
        info = svcs[svc_name]
        print(f"### `{svc_name}`")
        print(f"- **Proto File**: `{info['file']}`")
        print(f"- **Methods** ({len(info['methods'])}):")
        for m in info['methods']:
            flags = []
            if m['client_streaming'] and m['server_streaming']:
                flags.append("bidi-streaming")
            elif m['client_streaming']:
                flags.append("client-streaming")
            elif m['server_streaming']:
                flags.append("server-streaming")
            flag_str = f" *[{', '.join(flags)}]*" if flags else ""
            print(f"  - `/{svc_name}/{m['name']}`: `{m['input_type']}` -> `{m['output_type']}`{flag_str}")
        print()
