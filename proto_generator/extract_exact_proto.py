import os
import re
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
            if val is None:
                break
        elif wire_type == 1: # 64-bit
            val = stream.read(8)
            if len(val) < 8:
                break
        elif wire_type == 2: # Length-delimited
            l = read_varint(stream)
            if l is None:
                break
            val = stream.read(l)
            if len(val) < l:
                break
        elif wire_type == 5: # 32-bit
            val = stream.read(4)
            if len(val) < 4:
                break
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

def scan_file_descriptors():
    binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"
    with open(binary_path, "rb") as f:
        data = f.read()

    # Search for \n<filename>.proto (tag 1: 0x0a = 1 << 3 | 2)
    # in protobuf FileDescriptorProto: field 1 is name (string) -> starts with 0x0a, length, name
    proto_tag_matches = [m.start() for m in re.finditer(b'\x0a[a-zA-Z0-9_/.-]+\.proto', data)]
    
    discovered = {}
    for offset in proto_tag_matches:
        # try parsing FileDescriptorProto starting at offset
        chunk = data[offset:offset+200000]
        try:
            fname, pkg, svcs = parse_file_descriptor(chunk)
            if svcs and fname.endswith('.proto'):
                for s_name, methods in svcs:
                    if methods:
                        full_name = f"{pkg}.{s_name}" if pkg else s_name
                        discovered[full_name] = {
                            "package": pkg,
                            "file": fname,
                            "service": s_name,
                            "methods": methods
                        }
        except Exception as e:
            continue

    return discovered

if __name__ == "__main__":
    svcs = scan_file_descriptors()
    print(f"Total services successfully decoded directly from protobuf descriptors: {len(svcs)}\n")
    for s_name, info in sorted(svcs.items()):
        print(f"============================================================")
        print(f"Service: {s_name}")
        print(f"Proto File: {info['file']}")
        print(f"Methods: {len(info['methods'])}")
        print(f"============================================================")
        for m in info['methods']:
            streaming = " [Unary (Request-Response)]"
            if m['client_streaming'] and m['server_streaming']:
                streaming = " [Bidirectional Stream (stream -> stream)]"
            elif m['server_streaming']:
                streaming = " [Server Streaming (unary -> stream)]"
            elif m['client_streaming']:
                streaming = " [Client Streaming (stream -> unary)]"
            
            in_t = m['input_type'].lstrip('.')
            out_t = m['output_type'].lstrip('.')
            print(f"  rpc {m['name']}({in_t}) returns ({out_t}){streaming}")
        print()
