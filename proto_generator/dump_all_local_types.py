import re
import os
import sys
import shutil
import subprocess
from io import BytesIO
from collections import deque

# ---------------------------------------------------------------------------
# Binary protobuf parser (raw varint / length-delimited fields)
# ---------------------------------------------------------------------------

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
        if wt == 0:
            val = read_varint(stream)
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
        else:
            break
        fields.append((fn, wt, val))
    return fields

def parse_field_descriptor(data):
    fields = parse_proto(BytesIO(data))
    name, number, label, type_id, type_name = "", 0, 1, 0, ""
    for fn, wt, val in fields:
        if fn == 1:   name      = val.decode('utf-8', errors='ignore')
        elif fn == 3: number    = val
        elif fn == 4: label     = val
        elif fn == 5: type_id   = val
        elif fn == 6: type_name = val.decode('utf-8', errors='ignore')
    return {"name": name, "number": number, "label": label, "type": type_id, "type_name": type_name}

def parse_enum_value(data):
    fields = parse_proto(BytesIO(data))
    name, number = "", 0
    for fn, wt, val in fields:
        if fn == 1:   name   = val.decode('utf-8', errors='ignore')
        elif fn == 2: number = val
    return {"name": name, "number": number}

def parse_enum(data):
    fields = parse_proto(BytesIO(data))
    name, values = "", []
    for fn, wt, val in fields:
        if fn == 1:   name = val.decode('utf-8', errors='ignore')
        elif fn == 2: values.append(parse_enum_value(val))
    return {"name": name, "values": values}

def parse_message_descriptor(data):
    fields = parse_proto(BytesIO(data))
    name, msg_fields, nested_types, enum_types = "", [], [], []
    for fn, wt, val in fields:
        if fn == 1:   name = val.decode('utf-8', errors='ignore')
        elif fn == 2: msg_fields.append(parse_field_descriptor(val))
        elif fn == 3: nested_types.append(parse_message_descriptor(val))
        elif fn == 4: enum_types.append(parse_enum(val))
    return {"name": name, "fields": msg_fields, "nested_types": nested_types, "enum_types": enum_types}

def parse_method(data):
    fields = parse_proto(BytesIO(data))
    name, in_t, out_t, cs, ss = "", "", "", False, False
    for fn, wt, val in fields:
        if fn == 1:   name = val.decode('utf-8', errors='ignore')
        elif fn == 2: in_t = val.decode('utf-8', errors='ignore')
        elif fn == 3: out_t = val.decode('utf-8', errors='ignore')
        elif fn == 5: cs = bool(val)
        elif fn == 6: ss = bool(val)
    return {"name": name, "input_type": in_t, "output_type": out_t,
            "client_streaming": cs, "server_streaming": ss}

def parse_service(data):
    fields = parse_proto(BytesIO(data))
    name, methods = "", []
    for fn, wt, val in fields:
        if fn == 1:   name = val.decode('utf-8', errors='ignore')
        elif fn == 2: methods.append(parse_method(val))
    return name, methods

def parse_file(data):
    fields = parse_proto(BytesIO(data))
    filename, package, messages, enums, services = "", "", [], [], []
    for fn, wt, val in fields:
        if fn == 1:   filename = val.decode('utf-8', errors='ignore')
        elif fn == 2: package  = val.decode('utf-8', errors='ignore')
        elif fn == 4: messages.append(parse_message_descriptor(val))
        elif fn == 5: enums.append(parse_enum(val))
        elif fn == 6:
            s = parse_service(val)
            if s[0] and s[1]:
                services.append(s)
    return filename, package, messages, enums, services

# ---------------------------------------------------------------------------
# Primitive type map
# ---------------------------------------------------------------------------

TYPE_MAP = {
    1: 'double', 2: 'float',   3: 'int64',    4: 'uint64',   5: 'int32',
    6: 'fixed64', 7: 'fixed32', 8: 'bool',    9: 'string',  11: 'message',
   12: 'bytes',  13: 'uint32', 14: 'enum',    15: 'sfixed32', 16: 'sfixed64',
   17: 'sint32', 18: 'sint64',
}

# Package names used in gRPC route paths (kept only for the output package declaration)
SERVICE_PACKAGE_MAP = {
    "LanguageServerService":  "exa.language_server_pb",
    "ExtensionServerService": "exa.extension_server_pb",
    "RemotingService":        "exa.remoting",
}

# ---------------------------------------------------------------------------
# Registry — stores EVERYTHING keyed by exact full dotted name
# ---------------------------------------------------------------------------

class ProtoRegistry:
    def __init__(self):
        # key: ".pkg.SubPkg.TypeName"  (always fully-qualified, always starts with '.')
        self.messages = {}
        self.enums    = {}
        self.services = {}
        # Built-in well-known
        self.enums[".google.protobuf.NullValue"] = {
            "name": "NullValue",
            "values": [{"name": "NULL_VALUE", "number": 0}]
        }

    def register_file(self, filename, package, messages, enums, services):
        prefix = f".{package}" if package else ""
        for s_name, methods in services:
            full_s_name = f"{prefix}.{s_name}".lstrip('.')
            self.services[full_s_name] = {
                "file": filename, "service": s_name,
                "package": package, "methods": methods
            }
        for e in enums:
            full_e_name = f"{prefix}.{e['name']}"
            # Only register the first occurrence of each full name so that
            # a file registered later doesn't silently overwrite an earlier one.
            if full_e_name not in self.enums:
                self.enums[full_e_name] = e
        for m in messages:
            self._register_message(prefix, m)

    def _register_message(self, prefix, msg):
        full_m_name = f"{prefix}.{msg['name']}"
        if full_m_name not in self.messages:
            self.messages[full_m_name] = msg
        for nested in msg.get('nested_types', []):
            self._register_message(full_m_name, nested)
        for nested_enum in msg.get('enum_types', []):
            full_e_name = f"{full_m_name}.{nested_enum['name']}"
            if full_e_name not in self.enums:
                self.enums[full_e_name] = nested_enum

    # ------------------------------------------------------------------
    # find_type: DIRECT lookup only — no guessing, no short-name fallback.
    # In protobuf descriptors, field type_name is always fully qualified
    # (starts with '.').  We just look it up.
    # ------------------------------------------------------------------
    def find_type(self, type_name):
        if not type_name:
            return None, None, type_name
        key = type_name if type_name.startswith('.') else f".{type_name}"
        if key in self.messages:
            return "message", self.messages[key], key
        if key in self.enums:
            return "enum", self.enums[key], key
        return None, None, key

# ---------------------------------------------------------------------------
# Binary discovery
# ---------------------------------------------------------------------------

def find_all_agy_binaries():
    home = os.path.expanduser("~")
    raw_candidates = [
        os.path.join(home, ".local", "bin", "agy"),
        os.path.join(home, ".gemini", "bin", "agy"),
        os.path.join(home, ".antigravity", "bin", "agy"),
        os.path.join(home, "usr", "bin", "agy"),
        os.path.join(home, "..", "usr", "bin", "agy"),
    ]
    prefix = os.environ.get("PREFIX")
    if prefix:
        raw_candidates.append(os.path.join(prefix, "bin", "agy"))
    which_agy = shutil.which("agy")
    if which_agy:
        raw_candidates.append(which_agy)

    seen = set()
    results = []
    for c in raw_candidates:
        if not c:
            continue
        abs_path = os.path.normpath(os.path.abspath(os.path.expanduser(c)))
        if abs_path in seen:
            continue
        if os.path.isfile(abs_path):
            seen.add(abs_path)
            results.append(abs_path)
    return results

def get_agy_version(bin_path):
    if not bin_path or not os.path.isfile(bin_path):
        return None
    try:
        res = subprocess.run(
            [bin_path, "--version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=2
        )
        out = (res.stdout or res.stderr).strip()
        if out:
            for line in out.splitlines():
                if line.strip():
                    return line.strip()
    except Exception:
        pass
    return None

def resolve_agy_binary(custom_path=None):
    if custom_path:
        expanded = os.path.normpath(os.path.abspath(os.path.expanduser(custom_path)))
        if not os.path.isfile(expanded):
            raise FileNotFoundError(f"Specified AGY binary not found at '{custom_path}' (resolved: '{expanded}')")
        return expanded

    bins = find_all_agy_binaries()
    if not bins:
        raise FileNotFoundError("No AGY binary found in ~/.local/bin, ~/.gemini/bin, ~/.antigravity/bin, ~/usr/bin, ~/../usr/bin, or PATH")

    if len(bins) == 1 or not sys.stdin.isatty():
        return bins[0]

    print("\n\033[1;33m⚡ Multiple AGY binaries detected:\033[0m")
    for idx, b in enumerate(bins, 1):
        ver = get_agy_version(b)
        ver_str = f" \033[2m(v{ver})\033[0m" if ver else ""
        print(f"   \033[1m[{idx}]\033[0m {b}{ver_str}")

    try:
        choice = input(f" \033[1;36m? Select AGY binary to scan [1-{len(bins)}] (default: 1): \033[0m").strip()
        if not choice:
            return bins[0]
        choice_idx = int(choice)
        if 1 <= choice_idx <= len(bins):
            return bins[choice_idx - 1]
    except Exception:
        pass

    return bins[0]

def resolve_dump_target(bin_path):
    if not bin_path or not os.path.isfile(bin_path):
        return bin_path
    size = os.path.getsize(bin_path)
    if size >= 10 * 1024 * 1024:
        return bin_path

    # If < 10 MB (wrapper script), check for agy.va39 in same directory
    bin_dir = os.path.dirname(bin_path)
    companion = os.path.join(bin_dir, "agy.va39")
    if os.path.isfile(companion) and os.path.getsize(companion) >= 10 * 1024 * 1024:
        print(f"Wrapper detected ({bin_path}, {size} bytes). Using companion binary for proto extraction: {companion}")
        return companion

    return bin_path

def find_agy_binary(custom_path=None):
    bin_path = resolve_agy_binary(custom_path)
    return resolve_dump_target(bin_path)

# ---------------------------------------------------------------------------
# Binary scanning
# ---------------------------------------------------------------------------

def scan_all_descriptors(binary_path=None):
    binary_path = find_agy_binary(binary_path)
    ver = get_agy_version(binary_path)
    ver_str = f" (version: {ver})" if ver else ""
    print(f"Using binary: {binary_path}{ver_str}")
    with open(binary_path, "rb") as f:
        data = f.read()
    data_len = len(data)
    registry = ProtoRegistry()

    # ------------------------------------------------------------------
    # Phase 1: Forward scan — find every 0x0a byte that looks like
    # field-1 (name) of a FileDescriptorProto.
    # Pattern: 0x0a <varint length> <bytes ending in b'.proto'>
    # Uses bytes.find() jumps instead of a byte-by-byte loop → fast on
    # a 200 MB binary. No backward walk, no distance limit.
    # ------------------------------------------------------------------
    candidates = []
    pos = 0
    while True:
        pos = data.find(b'\x0a', pos)
        if pos == -1 or pos + 2 >= data_len:
            break
        stream = BytesIO(data[pos + 1: pos + 6])
        l = read_varint(stream)
        if l and 4 <= l <= 512:
            hlen = 1 + stream.tell()
            end  = pos + hlen + l
            if end <= data_len:
                name_bytes = data[pos + hlen: end]
                if name_bytes.endswith(b'.proto') and b'\x00' not in name_bytes:
                    candidates.append(pos)
        pos += 1

    # ------------------------------------------------------------------
    # Phase 2: Validate and deduplicate candidates.
    # For each candidate, try to parse a bounded blob [candidate, next_candidate).
    # A true FileDescriptorProto start will produce a valid filename matching
    # what we detected in Phase 1.  A false positive (dependency string inside
    # a real blob) will either produce a wrong filename or consume 0 useful bytes.
    # We deduplicate by resolved filename — first occurrence of each file wins.
    # ------------------------------------------------------------------
    candidates.sort()
    valid_blobs   = []        # (start, end, fname) for confirmed descriptors
    seen_fnames   = set()     # dedup by resolved filename
    seen_starts   = set()     # dedup by offset

    for idx, start in enumerate(candidates):
        if start in seen_starts:
            continue
        # Bound to next candidate (or end of binary)
        end = candidates[idx + 1] if idx + 1 < len(candidates) else data_len
        blob = data[start:end]
        try:
            fname, pkg, msgs, enums, svcs = parse_file(blob)
        except Exception:
            continue
        if not fname.endswith('.proto'):
            continue
        if fname in seen_fnames:
            # Same file seen before — mark this offset as consumed so we
            # don't let it corrupt the next candidate's boundary
            seen_starts.add(start)
            continue
        seen_fnames.add(fname)
        seen_starts.add(start)
        valid_blobs.append((start, end, fname, pkg, msgs, enums, svcs))

    # ------------------------------------------------------------------
    # Phase 3: Register all validated blobs.
    # ------------------------------------------------------------------
    for start, end, fname, pkg, msgs, enums, svcs in valid_blobs:
        try:
            registry.register_file(fname, pkg, msgs, enums, svcs)
        except Exception:
            pass

    return registry


# ---------------------------------------------------------------------------
# Short-name assignment (display only, done after the walk is complete)
#
# Rules (no hardcoded package names):
#   1. Try the bare leaf name (e.g. "Step").
#   2. If taken by a different full path, build a unique name from the
#      full dotted path itself — strip leading '.', replace '.' with '_'.
#      This is 100% deterministic and requires zero guessing.
# ---------------------------------------------------------------------------

def get_clean_package_prefix(full_name, current_package):
    """
    Extract a clean 1-2 word CamelCase package prefix from full_name.
    Strips noise like .exa., _pb, .v1, etc.
    """
    parts = [p for p in full_name.strip('.').split('.')[:-1] if p]
    filtered = []
    for p in parts:
        p_clean = p.replace('_pb', '').replace('pb', '')
        if p_clean in ['exa', 'proto', 'protos', 'v1', 'v1internal', 'internal']:
            continue
        filtered.append(p_clean)

    if not filtered:
        return "Internal"

    # CamelCase each piece: e.g. "codeium_common" -> "CodeiumCommon"
    clean_parts = []
    for f in filtered[-2:]:  # take at most last 2 distinguishing package elements
        clean = "".join(w.capitalize() for w in f.split('_') if w)
        if clean:
            clean_parts.append(clean)

    return "".join(clean_parts) or "Internal"

def assign_display_names(full_names, current_package=None):
    """
    Given a collection of fully-qualified type names, return a dict
    mapping each full name → a unique display name.
    If multiple types share a leaf name:
      - The type native to current_package keeps the plain leaf name.
      - Other colliding types get a clean package prefix (e.g. CodeiumCommon_UserSettings).
      - If multiple non-native types collide on the same prefix, a deterministic counter is added.
    """
    from collections import defaultdict
    leaf_to_fulls = defaultdict(list)
    for fn in full_names:
        leaf = fn.split('.')[-1]
        leaf_to_fulls[leaf].append(fn)

    display = {}
    for leaf, fulls in leaf_to_fulls.items():
        if len(fulls) == 1:
            # No collision — use the clean short leaf name
            display[fulls[0]] = leaf
        else:
            # Collision across packages
            # Check if one belongs directly to current_package or its primary parent
            native_match = None
            if current_package:
                for fn in fulls:
                    if fn.startswith(f".{current_package}.") or fn.startswith(f".{current_package.split('.')[0]}."):
                        native_match = fn
                        break

            # If no direct match, sort deterministically
            sorted_fulls = sorted(fulls)
            if not native_match:
                native_match = sorted_fulls[0]

            display[native_match] = leaf
            used_names = {leaf}

            for fn in sorted_fulls:
                if fn == native_match:
                    continue
                prefix = get_clean_package_prefix(fn, current_package)
                candidate_name = f"{prefix}_{leaf}"
                
                # Deduplicate prefix if multiple packages share the same clean prefix
                final_name = candidate_name
                count = 2
                while final_name in used_names:
                    final_name = f"{candidate_name}{count}"
                    count += 1

                used_names.add(final_name)
                display[fn] = final_name

    return display

# ---------------------------------------------------------------------------
# Main generation
# ---------------------------------------------------------------------------

def generate_per_service_protos(output_dir=None, binary_path=None):
    # Always output next to this script file regardless of cwd
    if output_dir is None:
        output_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "protos")
    os.makedirs(output_dir, exist_ok=True)
    print("Scanning embedded protobuf descriptors from binary...")
    registry = scan_all_descriptors(binary_path)
    print(f"Discovered {len(registry.services)} services, "
          f"{len(registry.messages)} messages, {len(registry.enums)} enums.\n")

    target_service_keys = [
        s for s in registry.services.keys()
        if any(k in s for k in ['LanguageServerService', 'ExtensionServerService', 'RemotingService'])
    ]

    for s_key in sorted(target_service_keys):
        s_info       = registry.services[s_key]
        service_name = s_info["service"]
        s_pkg        = s_info["package"] or "exa.local_grpc"
        package_name = SERVICE_PACKAGE_MAP.get(service_name, s_pkg)

        # ------------------------------------------------------------------
        # PHASE 1: Dependency walk — use FULL names throughout.
        #   resolved_messages / resolved_enums: full_name → descriptor dict
        #   Each field's type_name references a full name exactly as the
        #   binary encoded it — we do a direct registry lookup, no fallback.
        # ------------------------------------------------------------------
        needed   = deque()
        for m in s_info["methods"]:
            if m["input_type"]:  needed.append((m["input_type"],  "message"))
            if m["output_type"]: needed.append((m["output_type"], "message"))

        resolved_messages = {}   # full_name → desc
        resolved_enums    = {}   # full_name → desc
        processed         = set()

        while needed:
            type_ref, expected_kind = needed.popleft()
            if not type_ref or type_ref in processed:
                continue
            processed.add(type_ref)

            kind, desc, full_name = registry.find_type(type_ref)

            if not kind or not desc:
                # Unknown type — emit a stub so the file is still valid
                leaf = type_ref.split('.')[-1]
                if expected_kind == "enum":
                    desc = {"name": leaf,
                            "values": [{"name": f"{leaf.upper()}_UNSPECIFIED", "number": 0}]}
                    kind = "enum"
                else:
                    desc = {"name": leaf, "fields": []}
                    kind = "message"
                full_name = type_ref

            if full_name in processed and full_name != type_ref:
                continue  # already walked via another reference alias

            if kind == "enum":
                resolved_enums[full_name] = desc
            else:
                resolved_messages[full_name] = desc
                # Enqueue every field's type reference (still fully qualified)
                for field in desc.get("fields", []):
                    if field.get("type_name"):
                        child_kind = "enum" if field["type"] == 14 else "message"
                        child_ref  = field["type_name"]
                        if child_ref not in processed:
                            needed.append((child_ref, child_kind))

            processed.add(full_name)

        # ------------------------------------------------------------------
        # PHASE 2: Assign display names.
        #   Short leaf name if unique across the resolved set.
        #   Full-path-derived name (no dots) if there's a collision.
        #   Message and enum name spaces are kept separate so an enum and
        #   a message can share a leaf name without conflict.
        # ------------------------------------------------------------------
        msg_display  = assign_display_names(resolved_messages.keys(), package_name)
        enum_display = assign_display_names(resolved_enums.keys(), package_name)

        # If a message and an enum share the SAME display name, suffix the enum
        msg_display_values = set(msg_display.values())
        for fn, dn in list(enum_display.items()):
            if dn in msg_display_values:
                enum_display[fn] = f"{dn}Enum"

        # Build a unified lookup: type_ref (full name) → display name
        # used when writing field references
        type_name_map = {}
        for fn, dn in msg_display.items():
            type_name_map[fn] = dn
        for fn, dn in enum_display.items():
            type_name_map[fn] = dn

        # ------------------------------------------------------------------
        # PHASE 3: Write .proto file
        # ------------------------------------------------------------------
        proto_file_path = os.path.join(output_dir, f"{service_name}.proto")
        with open(proto_file_path, "w") as f:
            f.write('syntax = "proto3";\n\n')
            f.write(f'package {package_name};\n\n')

            f.write(f'// {"=" * 72}\n')
            f.write(f'// Service: {service_name} ({len(s_info["methods"])} Methods)\n')
            f.write(f'// Target Route: /{package_name}.{service_name}/<Method>\n')
            f.write(f'// {"=" * 72}\n\n')

            f.write(f'service {service_name} {{\n')
            for m in s_info["methods"]:
                cs   = "stream " if m["client_streaming"]  else ""
                ss   = "stream " if m["server_streaming"]   else ""
                in_t  = type_name_map.get(m["input_type"],  m["input_type"].split('.')[-1])
                out_t = type_name_map.get(m["output_type"], m["output_type"].split('.')[-1])
                f.write(f'  rpc {m["name"]} ({cs}{in_t}) returns ({ss}{out_t});\n')
            f.write('}\n\n')

            # Enums
            if resolved_enums:
                f.write(f'// {"=" * 72}\n// Enumerations\n// {"=" * 72}\n\n')
                written_enums = set()
                for fn in sorted(resolved_enums.keys(), key=lambda x: enum_display[x]):
                    dn = enum_display[fn]
                    if dn in written_enums: continue
                    written_enums.add(dn)
                    e = resolved_enums[fn]
                    # Comment shows the exact origin full name from the binary
                    f.write(f'// origin: {fn}\n')
                    f.write(f'enum {dn} {{\n')
                    for v in sorted(e["values"], key=lambda x: x["number"]):
                        f.write(f'  {v["name"]} = {v["number"]};\n')
                    f.write('}\n\n')

            # Messages
            if resolved_messages:
                f.write(f'// {"=" * 72}\n// Messages & Structs\n// {"=" * 72}\n\n')
                written_msgs = set()
                for fn in sorted(resolved_messages.keys(), key=lambda x: msg_display[x]):
                    dn = msg_display[fn]
                    if dn in written_msgs: continue
                    written_msgs.add(dn)
                    m = resolved_messages[fn]
                    # Comment shows the exact origin full name from the binary
                    f.write(f'// origin: {fn}\n')
                    f.write(f'message {dn} {{\n')
                    for field in sorted(m["fields"], key=lambda x: x["number"]):
                        lbl = "repeated " if field["label"] == 3 else ""
                        if field["type_name"]:
                            t_str = type_name_map.get(field["type_name"],
                                                       field["type_name"].split('.')[-1])
                        else:
                            t_str = TYPE_MAP.get(field["type"], "bytes")
                        f.write(f'  {lbl}{t_str} {field["name"]} = {field["number"]};\n')
                    f.write('}\n\n')

        # ------------------------------------------------------------------
        # PHASE 4: Validate — every field type reference must be defined
        # ------------------------------------------------------------------
        all_defined     = set(msg_display.values()) | set(enum_display.values())
        primitive_types = set(TYPE_MAP.values())
        errors = []
        for full_name, m in resolved_messages.items():
            m_dn = msg_display[full_name]
            for field in m["fields"]:
                if field["type_name"]:
                    t_str = type_name_map.get(field["type_name"],
                                               field["type_name"].split('.')[-1])
                    if t_str not in all_defined and t_str not in primitive_types:
                        errors.append(
                            f"  '{m_dn}'.{field['name']} → '{t_str}' (from {field['type_name']})"
                        )

        status_str = "✅ 100% Validated (0 errors)" if not errors else f"⚠️  {len(errors)} unresolved refs"
        coll_msgs  = sum(1 for dn in msg_display.values()  if '_' in dn and '.' not in dn)
        coll_enums = sum(1 for dn in enum_display.values() if '_' in dn and '.' not in dn)
        coll_str   = f", Collisions: {coll_msgs}msg/{coll_enums}enum" if (coll_msgs or coll_enums) else ""
        print(f"Generated: {service_name}.proto → {len(s_info['methods'])} RPCs, "
              f"{len(resolved_messages)} Messages, {len(resolved_enums)} Enums"
              f"{coll_str} [{status_str}]")
        if errors:
            for e in errors[:10]:
                print(e)

    print(f"\nAll service proto files updated in: {output_dir}")

if __name__ == "__main__":
    custom_bin = None
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        a = args[i]
        if a.startswith("--bin="):
            custom_bin = a.split("=", 1)[1]
        elif a in ("--bin", "-b") and i + 1 < len(args):
            custom_bin = args[i + 1]
            i += 1
        elif not a.startswith("-") and custom_bin is None:
            custom_bin = a
        i += 1

    generate_per_service_protos(binary_path=custom_bin)

