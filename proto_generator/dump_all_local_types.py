import re
import os
import json
from io import BytesIO
from collections import defaultdict, deque

def read_varint(stream):
    res, shift = 0, 0
    while True:
        b = stream.read(1)
        if not b: return None
        byte = b[0]
        res |= (byte & 0x7f) << shift
        if not (byte & 0x80): break
        shift += 7
    return res

def parse_proto(stream, length=None):
    fields = []
    start_pos = stream.tell()
    while True:
        if length is not None and (stream.tell() - start_pos) >= length: break
        tag = read_varint(stream)
        if tag is None: break
        fn, wt = tag >> 3, tag & 0x07
        if wt == 0: val = read_varint(stream)
        elif wt == 1:
            val = stream.read(8)
            if len(val) < 8: break
        elif wt == 2:
            l = read_varint(stream)
            if l is None: break
            val = stream.read(l)
            if len(val) < l: break
        elif wt == 5:
            val = stream.read(4)
            if len(val) < 4: break
        else: break
        fields.append((fn, wt, val))
    return fields

def parse_field_descriptor(data):
    fields = parse_proto(BytesIO(data))
    name, number, label, type_id, type_name = "", 0, 1, 0, ""
    for fn, wt, val in fields:
        if fn == 1: name = val.decode('utf-8', errors='ignore')
        elif fn == 3: number = val
        elif fn == 4: label = val
        elif fn == 5: type_id = val
        elif fn == 6: type_name = val.decode('utf-8', errors='ignore')
    return {"name": name, "number": number, "label": label, "type": type_id, "type_name": type_name}

def parse_enum_value(data):
    fields = parse_proto(BytesIO(data))
    name, number = "", 0
    for fn, wt, val in fields:
        if fn == 1: name = val.decode('utf-8', errors='ignore')
        elif fn == 2: number = val
    return {"name": name, "number": number}

def parse_enum(data):
    fields = parse_proto(BytesIO(data))
    name, values = "", []
    for fn, wt, val in fields:
        if fn == 1: name = val.decode('utf-8', errors='ignore')
        elif fn == 2: values.append(parse_enum_value(val))
    return {"name": name, "values": values}

def parse_message_descriptor(data):
    fields = parse_proto(BytesIO(data))
    name, msg_fields, nested_types, enum_types = "", [], [], []
    for fn, wt, val in fields:
        if fn == 1: name = val.decode('utf-8', errors='ignore')
        elif fn == 2: msg_fields.append(parse_field_descriptor(val))
        elif fn == 3: nested_types.append(parse_message_descriptor(val))
        elif fn == 4: enum_types.append(parse_enum(val))
    return {"name": name, "fields": msg_fields, "nested_types": nested_types, "enum_types": enum_types}

def parse_method(data):
    fields = parse_proto(BytesIO(data))
    name, in_t, out_t, cs, ss = "", "", "", False, False
    for fn, wt, val in fields:
        if fn == 1: name = val.decode('utf-8', errors='ignore')
        elif fn == 2: in_t = val.decode('utf-8', errors='ignore')
        elif fn == 3: out_t = val.decode('utf-8', errors='ignore')
        elif fn == 5: cs = bool(val)
        elif fn == 6: ss = bool(val)
    return {"name": name, "input_type": in_t, "output_type": out_t, "client_streaming": cs, "server_streaming": ss}

def parse_service(data):
    fields = parse_proto(BytesIO(data))
    name, methods = "", []
    for fn, wt, val in fields:
        if fn == 1: name = val.decode('utf-8', errors='ignore')
        elif fn == 2: methods.append(parse_method(val))
    return name, methods

def parse_file(data):
    fields = parse_proto(BytesIO(data))
    filename, package, messages, enums, services = "", "", [], [], []
    for fn, wt, val in fields:
        if fn == 1: filename = val.decode('utf-8', errors='ignore')
        elif fn == 2: package = val.decode('utf-8', errors='ignore')
        elif fn == 4: messages.append(parse_message_descriptor(val))
        elif fn == 5: enums.append(parse_enum(val))
        elif fn == 6:
            s = parse_service(val)
            if s[0] and s[1]:
                services.append(s)
    return filename, package, messages, enums, services

TYPE_MAP = {
    1: 'double', 2: 'float', 3: 'int64', 4: 'uint64', 5: 'int32',
    6: 'fixed64', 7: 'fixed32', 8: 'bool', 9: 'string', 11: 'message',
    12: 'bytes', 13: 'uint32', 14: 'enum', 15: 'sfixed32', 16: 'sfixed64',
    17: 'sint32', 18: 'sint64'
}

SERVICE_PACKAGE_MAP = {
    "LanguageServerService": "exa.language_server_pb",
    "ExtensionServerService": "exa.extension_server_pb",
    "RemotingService": "exa.remoting"
}

class ProtoRegistry:
    def __init__(self):
        self.messages = {}
        self.enums = {}
        self.services = {}
        self.enums[".google.protobuf.NullValue"] = {
            "name": "NullValue",
            "values": [{"name": "NULL_VALUE", "number": 0}]
        }
        self.enums[".NullValue"] = self.enums[".google.protobuf.NullValue"]

    def register_file(self, filename, package, messages, enums, services):
        prefix = f".{package}" if package else ""
        for s_name, methods in services:
            full_s_name = f"{prefix}.{s_name}".lstrip('.')
            self.services[full_s_name] = {"file": filename, "service": s_name, "package": package, "methods": methods}
        for e in enums:
            full_e_name = f"{prefix}.{e['name']}"
            self.enums[full_e_name] = e
            if f".{e['name']}" not in self.enums:
                self.enums[f".{e['name']}"] = e
        for m in messages:
            self._register_message(prefix, m)

    def _register_message(self, prefix, msg):
        full_m_name = f"{prefix}.{msg['name']}"
        self.messages[full_m_name] = msg
        if f".{msg['name']}" not in self.messages:
            self.messages[f".{msg['name']}"] = msg
        for nested in msg.get('nested_types', []):
            self._register_message(full_m_name, nested)
        for nested_enum in msg.get('enum_types', []):
            self.enums[f"{full_m_name}.{nested_enum['name']}"] = nested_enum
            if f".{nested_enum['name']}" not in self.enums:
                self.enums[f".{nested_enum['name']}"] = nested_enum

    def find_type(self, type_name, s_pkg="exa.cortex_pb", expected_kind=None):
        if not type_name: return None, None, type_name
        lookup = type_name if type_name.startswith('.') else f".{type_name}"
        short = lookup.split('.')[-1]

        candidates = [
            lookup,
            lookup.replace(".exa.language_server_pb.", ".exa.cortex_pb."),
            lookup.replace(".exa.cortex_pb.", ".exa.language_server_pb."),
            f".{s_pkg}.{short}",
            f".exa.cortex_pb.{short}",
            f".exa.language_server_pb.{short}",
            f".exa.codeium_common_pb.{short}",
            f".exa.extension_server_pb.{short}",
            f".exa.remoting.{short}",
        ]

        if expected_kind in ("enum", 14):
            for c in candidates:
                if c in self.enums: return "enum", self.enums[c], c
            if f".{short}" in self.enums: return "enum", self.enums[f".{short}"], f".{short}"
            for k, v in self.enums.items():
                if k.endswith(f".{short}"): return "enum", v, k

        if expected_kind in ("message", 11):
            for c in candidates:
                if c in self.messages: return "message", self.messages[c], c
            if f".{short}" in self.messages: return "message", self.messages[f".{short}"], f".{short}"
            for k, v in self.messages.items():
                if k.endswith(f".{short}"): return "message", v, k

        for c in candidates:
            if c in self.messages: return "message", self.messages[c], c
            if c in self.enums: return "enum", self.enums[c], c
        if f".{short}" in self.messages: return "message", self.messages[f".{short}"], f".{short}"
        if f".{short}" in self.enums: return "enum", self.enums[f".{short}"], f".{short}"
        return None, None, type_name

def scan_all_descriptors(binary_path="/data/data/com.termux/files/usr/bin/agy.va39"):
    with open(binary_path, "rb") as f: data = f.read()
    registry = ProtoRegistry()
    proto_offsets = [m.start() for m in re.finditer(rb'\.proto', data)]
    seen_offsets = set()

    for p in proto_offsets:
        for back in range(p, max(0, p-250), -1):
            if data[back] == 0x0a:
                stream = BytesIO(data[back+1:back+5])
                l = read_varint(stream)
                if l and l > 0 and (back + 1 + stream.tell() + l) <= len(data):
                    header_len = 1 + stream.tell()
                    name_bytes = data[back+header_len : back+header_len+l]
                    if name_bytes.endswith(b'.proto') and len(name_bytes) == l:
                        if back not in seen_offsets:
                            seen_offsets.add(back)
                            try:
                                fname, pkg, msgs, enums, svcs = parse_file(data[back:back+15000000])
                                if fname.endswith('.proto'):
                                    registry.register_file(fname, pkg, msgs, enums, svcs)
                            except Exception: pass
                        break
    return registry

def generate_per_service_protos(output_dir="/data/data/com.termux/files/home/proto_test/protos"):
    os.makedirs(output_dir, exist_ok=True)
    print("Scanning embedded protobuf descriptors from binary...")
    registry = scan_all_descriptors()
    print(f"Discovered {len(registry.services)} services, {len(registry.messages)} messages, {len(registry.enums)} enums.\n")

    target_service_keys = [
        s for s in registry.services.keys()
        if any(k in s for k in ['LanguageServerService', 'ExtensionServerService', 'RemotingService'])
    ]

    for s_key in sorted(target_service_keys):
        s_info = registry.services[s_key]
        service_name = s_info["service"]
        s_pkg = s_info["package"] or "exa.local_grpc"
        
        # Use exact route package name for gRPC client compatibility
        package_name = SERVICE_PACKAGE_MAP.get(service_name, s_pkg)

        needed_types = deque()
        for m in s_info["methods"]:
            if m["input_type"]: needed_types.append((m["input_type"], "message"))
            if m["output_type"]: needed_types.append((m["output_type"], "message"))

        resolved_messages = {}
        resolved_enums = {}
        short_to_full = {}
        type_name_map = {}
        processed = set()

        while needed_types:
            full_name, expected_kind = needed_types.popleft()
            if not full_name or (full_name, expected_kind) in processed: continue
            processed.add((full_name, expected_kind))

            kind, desc, matched_full_name = registry.find_type(full_name, s_pkg, expected_kind)
            if not kind or not desc:
                short_name = full_name.split('.')[-1]
                if expected_kind == "enum":
                    desc = {"name": short_name, "values": [{"name": f"{short_name.upper()}_UNSPECIFIED", "number": 0}]}
                    kind = "enum"
                else:
                    desc = {"name": short_name, "fields": []}
                    kind = "message"
                matched_full_name = full_name

            orig_short = desc["name"]
            if orig_short not in short_to_full:
                short_to_full[orig_short] = matched_full_name
                unique_name = orig_short
            elif short_to_full[orig_short] == matched_full_name:
                unique_name = orig_short
            else:
                # Collision across packages! Disambiguate with package prefix
                pkg_parts = [p for p in matched_full_name.split(".")[:-1] if p and p not in ["exa", "cortex_pb", "language_server_pb", "extension_server_pb", "remoting"]]
                prefix = "".join([p.capitalize() for p in pkg_parts])
                unique_name = f"{prefix}_{orig_short}" if prefix else f"Internal_{orig_short}"
                if unique_name in short_to_full and short_to_full[unique_name] != matched_full_name:
                    unique_name = matched_full_name.replace(".", "_").strip("_")
                short_to_full[unique_name] = matched_full_name

            type_name_map[full_name] = unique_name
            type_name_map[matched_full_name] = unique_name
            type_name_map[full_name.lstrip(".")] = unique_name
            type_name_map[matched_full_name.lstrip(".")] = unique_name

            if kind == "enum":
                resolved_enums[matched_full_name] = desc
            else:
                resolved_messages[matched_full_name] = desc
                for f in desc.get("fields", []):
                    if f.get("type_name"):
                        child_kind = "enum" if f["type"] == 14 else "message"
                        needed_types.append((f["type_name"], child_kind))

        colls = set([type_name_map[fn] for fn in resolved_messages]).intersection(set([type_name_map[fn] for fn in resolved_enums]))
        enum_rename = {}
        for c in colls:
            enum_rename[c] = f"{c}Enum"

        # Write service file
        proto_file_path = os.path.join(output_dir, f"{service_name}.proto")
        with open(proto_file_path, "w") as f:
            f.write('syntax = "proto3";\n\n')
            f.write(f'package {package_name};\n\n')
            
            f.write(f'// ==========================================================================\n')
            f.write(f'// Service: {service_name} ({len(s_info["methods"])} Methods)\n')
            f.write(f'// Target Route: /{package_name}.{service_name}/<Method>\n')
            f.write(f'// ==========================================================================\n\n')
            
            f.write(f'service {service_name} {{\n')
            for m in s_info["methods"]:
                cs = "stream " if m["client_streaming"] else ""
                ss = "stream " if m["server_streaming"] else ""
                in_lookup = m["input_type"]
                out_lookup = m["output_type"]
                in_t = type_name_map.get(in_lookup, type_name_map.get(in_lookup.lstrip("."), in_lookup.split('.')[-1]))
                out_t = type_name_map.get(out_lookup, type_name_map.get(out_lookup.lstrip("."), out_lookup.split('.')[-1]))
                f.write(f'  rpc {m["name"]} ({cs}{in_t}) returns ({ss}{out_t});\n')
            f.write('}\n\n')

            # Enums
            if resolved_enums:
                f.write('// ==========================================================================\n')
                f.write('// Enumerations\n')
                f.write('// ==========================================================================\n\n')
                written_enums = set()
                for fn in sorted(resolved_enums.keys(), key=lambda x: type_name_map[x]):
                    e_name = type_name_map[fn]
                    if e_name in enum_rename:
                        e_name = enum_rename[e_name]
                    if e_name in written_enums: continue
                    written_enums.add(e_name)
                    e = resolved_enums[fn]
                    f.write(f'enum {e_name} {{\n')
                    for v in sorted(e["values"], key=lambda x: x["number"]):
                        f.write(f'  {v["name"]} = {v["number"]};\n')
                    f.write('}\n\n')

            # Messages
            if resolved_messages:
                f.write('// ==========================================================================\n')
                f.write('// Messages & Structs\n')
                f.write('// ==========================================================================\n\n')
                written_msgs = set()
                for fn in sorted(resolved_messages.keys(), key=lambda x: type_name_map[x]):
                    m_name = type_name_map[fn]
                    if m_name in written_msgs: continue
                    written_msgs.add(m_name)
                    m = resolved_messages[fn]
                    f.write(f'message {m_name} {{\n')
                    for field in sorted(m["fields"], key=lambda x: x["number"]):
                        lbl = "repeated " if field["label"] == 3 else ""
                        if field["type_name"]:
                            fn_lookup = field["type_name"]
                            t_str = type_name_map.get(fn_lookup, type_name_map.get(fn_lookup.lstrip("."), fn_lookup.split('.')[-1]))
                            if field["type"] == 14 and t_str in enum_rename:
                                t_str = enum_rename[t_str]
                        else:
                            t_str = TYPE_MAP.get(field["type"], "string")
                        f.write(f'  {lbl}{t_str} {field["name"]} = {field["number"]};\n')
                    f.write('}\n\n')

        # Validation Check
        all_defined = set([type_name_map[fn] for fn in resolved_messages]).union(set([enum_rename.get(type_name_map[fn], type_name_map[fn]) for fn in resolved_enums]))
        primitive_types = set(TYPE_MAP.values())
        errors = []
        for full_name, m in resolved_messages.items():
            m_name = type_name_map[full_name]
            for field in m["fields"]:
                if field["type_name"]:
                    fn_lookup = field["type_name"]
                    st = type_name_map.get(fn_lookup, type_name_map.get(fn_lookup.lstrip("."), fn_lookup.split('.')[-1]))
                    if field["type"] == 14 and st in enum_rename:
                        st = enum_rename[st]
                    if st not in all_defined and st not in primitive_types:
                        errors.append(f"In message '{m_name}', field '{field['name']}' references undefined type '{st}'")

        status_str = "✅ 100% Validated (0 errors)" if not errors else f"⚠️ {len(errors)} warnings"
        collision_str = f", Resolved {len(colls)} Collisions" if colls else ""
        print(f"Generated: {service_name}.proto -> {len(s_info['methods'])} RPCs, {len(resolved_messages)} Messages, {len(resolved_enums)} Enums{collision_str} [{status_str}]")

    print(f"\nAll service proto files updated in: {output_dir}")

if __name__ == "__main__":
    generate_per_service_protos()
