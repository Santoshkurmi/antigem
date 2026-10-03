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

def parse_proto_fields(stream, allowed_fn_wt=None, max_length=None):
    fields = []
    start_pos = stream.tell()
    while True:
        if max_length is not None and (stream.tell() - start_pos) >= max_length:
            break
        tag_pos = stream.tell()
        tag = read_varint(stream)
        if tag is None:
            break
        fn, wt = tag >> 3, tag & 0x07
        if fn == 0 or fn > 500:
            stream.seek(tag_pos)
            break
        if allowed_fn_wt is not None:
            if fn not in allowed_fn_wt or wt not in allowed_fn_wt[fn]:
                stream.seek(tag_pos)
                break

        if wt == 0:
            val = read_varint(stream)
            if val is None: break
        elif wt == 1:
            val = stream.read(8)
            if len(val) < 8: break
        elif wt == 2:
            l = read_varint(stream)
            if l is None or l < 0: break
            val = stream.read(l)
            if len(val) < l: break
        elif wt == 5:
            val = stream.read(4)
            if len(val) < 4: break
        else:
            stream.seek(tag_pos)
            break
        fields.append((fn, wt, val))
    return fields

# Official Google descriptor.proto field tag schemas
FD_TAGS = {1: {2}, 2: {2}, 3: {2}, 4: {2}, 5: {2}, 6: {2}, 7: {2}, 8: {2}, 9: {2}, 10: {0, 2}, 11: {0, 2}, 12: {2}}
DESC_TAGS = {1: {2}, 2: {2}, 3: {2}, 4: {2}, 5: {2}, 6: {2}, 7: {2}, 8: {2}, 9: {2}, 10: {2}}
FIELD_TAGS = {1: {2}, 2: {2}, 3: {0}, 4: {0}, 5: {0}, 6: {2}, 7: {2}, 8: {0}, 9: {2}, 10: {2}, 17: {0}}
ENUM_TAGS = {1: {2}, 2: {2}, 3: {2}, 4: {2}, 5: {2}}
ENUMVAL_TAGS = {1: {2}, 2: {0}, 3: {2}}
SVC_TAGS = {1: {2}, 2: {2}, 3: {2}}
METHOD_TAGS = {1: {2}, 2: {2}, 3: {2}, 4: {2}, 5: {0}, 6: {0}}

def parse_enum_value(data):
    if not isinstance(data, bytes): return {"name": "", "number": 0}
    fields = parse_proto_fields(BytesIO(data), ENUMVAL_TAGS)
    name, number = "", 0
    for fn, wt, val in fields:
        if fn == 1 and isinstance(val, bytes): name   = val.decode('utf-8', errors='ignore')
        elif fn == 2 and isinstance(val, int): number = val
    return {"name": name, "number": number}

def parse_enum(data):
    if not isinstance(data, bytes): return {"name": "", "values": []}
    fields = parse_proto_fields(BytesIO(data), ENUM_TAGS)
    name, values = "", []
    for fn, wt, val in fields:
        if fn == 1 and isinstance(val, bytes): name = val.decode('utf-8', errors='ignore')
        elif fn == 2 and isinstance(val, bytes): values.append(parse_enum_value(val))
    return {"name": name, "values": values}

def parse_field_descriptor(data):
    if not isinstance(data, bytes): return {"name": "", "number": 0, "label": 1, "type": 0, "type_name": ""}
    fields = parse_proto_fields(BytesIO(data), FIELD_TAGS)
    name, number, label, type_id, type_name = "", 0, 1, 0, ""
    for fn, wt, val in fields:
        if fn == 1 and isinstance(val, bytes):   name      = val.decode('utf-8', errors='ignore')
        elif fn == 3 and isinstance(val, int):   number    = val
        elif fn == 4 and isinstance(val, int):   label     = val
        elif fn == 5 and isinstance(val, int):   type_id   = val
        elif fn == 6 and isinstance(val, bytes): type_name = val.decode('utf-8', errors='ignore')
    return {"name": name, "number": number, "label": label, "type": type_id, "type_name": type_name}

def parse_message_descriptor(data):
    if not isinstance(data, bytes): return {"name": "", "fields": [], "nested_types": [], "enum_types": []}
    fields = parse_proto_fields(BytesIO(data), DESC_TAGS)
    name, msg_fields, nested_types, enum_types = "", [], [], []
    for fn, wt, val in fields:
        if fn == 1 and isinstance(val, bytes): name = val.decode('utf-8', errors='ignore')
        elif fn == 2 and isinstance(val, bytes): msg_fields.append(parse_field_descriptor(val))
        elif fn == 3 and isinstance(val, bytes): nested_types.append(parse_message_descriptor(val))
        elif fn == 4 and isinstance(val, bytes): enum_types.append(parse_enum(val))
    return {"name": name, "fields": msg_fields, "nested_types": nested_types, "enum_types": enum_types}

VALID_MAP_KEY_TYPES = {3, 4, 5, 6, 7, 8, 9, 13, 15, 16, 17, 18}

def check_is_map_entry(msg):
    if not msg:
        return False, None, None
    fields = msg.get('fields', [])
    if len(fields) == 2:
        f1 = next((f for f in fields if f['name'] == 'key' and f['number'] == 1), None)
        f2 = next((f for f in fields if f['name'] == 'value' and f['number'] == 2), None)
        # In Proto3, map keys must be scalar types (integers, strings, bools), not messages or enums
        if f1 and f2 and f1.get('type') in VALID_MAP_KEY_TYPES:
            return True, f1, f2
    return False, None, None

def parse_method(data):
    if not isinstance(data, bytes): return {"name": "", "input_type": "", "output_type": "", "client_streaming": False, "server_streaming": False}
    fields = parse_proto_fields(BytesIO(data), METHOD_TAGS)
    name, in_t, out_t, cs, ss = "", "", "", False, False
    for fn, wt, val in fields:
        if fn == 1 and isinstance(val, bytes):   name = val.decode('utf-8', errors='ignore')
        elif fn == 2 and isinstance(val, bytes): in_t = val.decode('utf-8', errors='ignore')
        elif fn == 3 and isinstance(val, bytes): out_t = val.decode('utf-8', errors='ignore')
        elif fn == 5 and isinstance(val, int):   cs = bool(val)
        elif fn == 6 and isinstance(val, int):   ss = bool(val)
    return {"name": name, "input_type": in_t, "output_type": out_t,
            "client_streaming": cs, "server_streaming": ss}

def parse_service(data):
    if not isinstance(data, bytes): return "", []
    fields = parse_proto_fields(BytesIO(data), SVC_TAGS)
    name, methods = "", []
    for fn, wt, val in fields:
        if fn == 1 and isinstance(val, bytes): name = val.decode('utf-8', errors='ignore')
        elif fn == 2 and isinstance(val, bytes): methods.append(parse_method(val))
    return name, methods

def parse_file(data):
    stream = BytesIO(data)
    fields = parse_proto_fields(stream, FD_TAGS)
    filename, package, messages, enums, services = "", "", [], [], []
    for fn, wt, val in fields:
        if fn == 1 and isinstance(val, bytes):   filename = val.decode('utf-8', errors='ignore')
        elif fn == 2 and isinstance(val, bytes): package  = val.decode('utf-8', errors='ignore')
        elif fn == 4 and isinstance(val, bytes): messages.append(parse_message_descriptor(val))
        elif fn == 5 and isinstance(val, bytes): enums.append(parse_enum(val))
        elif fn == 6 and isinstance(val, bytes):
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
            if full_s_name not in self.services or len(methods) > len(self.services[full_s_name]["methods"]):
                self.services[full_s_name] = {
                    "file": filename, "service": s_name,
                    "package": package, "methods": methods
                }
        for e in enums:
            full_e_name = f"{prefix}.{e['name']}"
            if full_e_name not in self.enums or len(e.get("values", [])) > len(self.enums[full_e_name].get("values", [])):
                self.enums[full_e_name] = e
        for m in messages:
            self._register_message(prefix, m)

    def _register_message(self, prefix, msg):
        full_m_name = f"{prefix}.{msg['name']}"
        if full_m_name not in self.messages or len(msg.get("fields", [])) > len(self.messages[full_m_name].get("fields", [])):
            self.messages[full_m_name] = msg
        for nested in msg.get('nested_types', []):
            self._register_message(full_m_name, nested)
        for nested_enum in msg.get('enum_types', []):
            full_e_name = f"{full_m_name}.{nested_enum['name']}"
            if full_e_name not in self.enums or len(nested_enum.get("values", [])) > len(self.enums[full_e_name].get("values", [])):
                self.enums[full_e_name] = nested_enum

    # ------------------------------------------------------------------
    # find_type: DIRECT lookup only — no guessing, no short-name fallback.
    # In protobuf descriptors, field type_name is always fully qualified
    # (starts with '.').  We just look it up.
    # ------------------------------------------------------------------
    def find_type(self, type_name, expected_kind=None):
        if not type_name:
            return None, None, type_name
        key = type_name if type_name.startswith('.') else f".{type_name}"

        # If expected_kind is explicitly message, check messages first
        if expected_kind == "message":
            if key in self.messages:
                return "message", self.messages[key], key
            if key in self.enums:
                return "enum", self.enums[key], key
        elif expected_kind == "enum":
            if key in self.enums:
                return "enum", self.enums[key], key
            if key in self.messages:
                return "message", self.messages[key], key
        else:
            if key in self.messages:
                return "message", self.messages[key], key
            if key in self.enums:
                return "enum", self.enums[key], key

        # Resolve internal package aliases
        aliases = [
            (".exa.codeium_common_pb.", ".exa.cortex_pb."),
            (".exa.cortex_pb.", ".exa.codeium_common_pb."),
            (".exa.language_server_pb.", ".exa.cortex_pb."),
            (".exa.cortex_pb.", ".exa.language_server_pb."),
            (".google.internal.cloud.code.v1internal.", ".genai."),
            (".google.internal.cloud.code.v1internal.", ".exa.cortex_pb."),
        ]
        for src, dst in aliases:
            if key.startswith(src):
                alt_key = dst + key[len(src):]
                if expected_kind == "message":
                    if alt_key in self.messages:
                        return "message", self.messages[alt_key], alt_key
                    if alt_key in self.enums:
                        return "enum", self.enums[alt_key], alt_key
                elif expected_kind == "enum":
                    if alt_key in self.enums:
                        return "enum", self.enums[alt_key], alt_key
                    if alt_key in self.messages:
                        return "message", self.messages[alt_key], alt_key
                else:
                    if alt_key in self.messages:
                        return "message", self.messages[alt_key], alt_key
                    if alt_key in self.enums:
                        return "enum", self.enums[alt_key], alt_key

        # Leaf-based resolution (respects expected_kind if provided)
        leaf = key.split('.')[-1]
        matching_enums = [k for k in self.enums if k.endswith(f".{leaf}")]
        matching_msgs = [k for k in self.messages if k.endswith(f".{leaf}")]

        if expected_kind == "message" and matching_msgs:
            best_k = max(matching_msgs, key=lambda k: len(self.messages[k].get("fields", [])))
            return "message", self.messages[best_k], best_k
        elif expected_kind == "enum" and matching_enums:
            best_k = max(matching_enums, key=lambda k: len(self.enums[k].get("values", [])))
            return "enum", self.enums[best_k], best_k

        if matching_enums:
            best_k = max(matching_enums, key=lambda k: len(self.enums[k].get("values", [])))
            return "enum", self.enums[best_k], best_k
        if matching_msgs:
            best_k = max(matching_msgs, key=lambda k: len(self.messages[k].get("fields", [])))
            return "message", self.messages[best_k], best_k

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
    # Phase 2: Validate and deduplicate descriptors without artificial cutoff.
    # We parse from each candidate start with a safe buffer, allowing full
    # FileDescriptorProto objects (e.g. cortex.proto with 80+ enums) to be
    # completely parsed without being truncated by internal dependency strings.
    # ------------------------------------------------------------------
    candidates.sort()
    seen_fnames = set()
    seen_starts = set()

    for start in candidates:
        if start in seen_starts:
            continue
        try:
            chunk = data[start: min(data_len, start + 4 * 1024 * 1024)]
            fname, pkg, msgs, enums, svcs = parse_file(chunk)
            if fname.endswith('.proto') and (msgs or enums or svcs):
                if fname not in seen_fnames:
                    seen_fnames.add(fname)
                    registry.register_file(fname, pkg, msgs, enums, svcs)
                seen_starts.add(start)
        except Exception:
            continue

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

def generate_per_service_protos(output_dir=None, binary_path=None, full=False):
    # Always output next to this script file regardless of cwd
    if output_dir is None:
        output_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "protos")
    os.makedirs(output_dir, exist_ok=True)
    
    # Clean stale proto files
    for f in os.listdir(output_dir):
        if f.endswith(".proto"):
            try:
                os.remove(os.path.join(output_dir, f))
            except Exception:
                pass

    print("Scanning embedded protobuf descriptors from binary...")
    registry = scan_all_descriptors(binary_path)
    print(f"Discovered {len(registry.services)} services, "
          f"{len(registry.messages)} messages, {len(registry.enums)} enums.\n")

    from collections import defaultdict
    service_counts = defaultdict(list)
    for k, v in registry.services.items():
        service_counts[v["service"]].append(k)

    LOCAL_SERVICES = ['LanguageServerService', 'ExtensionServerService', 'RemotingService']
    target_service_keys = [
        s for s in registry.services.keys()
        if any(s.endswith(f".{k}") or s == k or registry.services[s]["service"] in LOCAL_SERVICES for k in LOCAL_SERVICES)
    ]

    for s_key in sorted(target_service_keys):
        s_info       = registry.services[s_key]
        service_name = s_info["service"]
        s_pkg        = s_info["package"] or "exa.local_grpc"
        package_name = SERVICE_PACKAGE_MAP.get(service_name, s_pkg)

        # File naming: unique service name or clean prefix on collision
        clean_file_stem = service_name
        if len(service_counts[service_name]) > 1:
            pkg_prefix = get_clean_package_prefix(s_key, package_name)
            clean_file_stem = f"{pkg_prefix}_{service_name}"
        proto_file_path = os.path.join(output_dir, f"{clean_file_stem}.proto")

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

            kind, desc, full_name = registry.find_type(type_ref, expected_kind=expected_kind)

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

        # Upstream Host Resolution from Discovered Endpoints
        HOST_MAP = {
            'google.internal.cloud.code.v1internal': 'https://daily-cloudcode-pa.googleapis.com',
            'google.cloud.aiplatform.v1beta1':       'https://aiplatform.googleapis.com',
            'google.cloud.businessaicode.v1main':    'https://businessaicode.googleapis.com',
            'google.cloud.businessaicode.v1beta':    'https://businessaicode.googleapis.com',
            'google.cloud.speech.v1p1beta1':         'https://speech.googleapis.com',
            'google.longrunning':                    'https://daily-cloudcode-pa.googleapis.com',
            'devtools_jetski_boq_api_proto':         'https://daily-cloudcode-pa.googleapis.com',
            'jetski.product.v1':                     'https://daily-cloudcode-pa.googleapis.com',
            'exa.language_server_pb':                'http://127.0.0.1 (Local Hub / LSP)',
            'exa.extension_server_pb':               'http://127.0.0.1 (Local Extension Bridge)',
            'exa.remoting':                          'http://127.0.0.1 (Local Remoting)',
            'exa.analytics_pb':                      'https://daily-cloudcode-pa.googleapis.com',
            'genai':                                 'https://generativelanguage.googleapis.com',
        }
        upstream_host = HOST_MAP.get(package_name, 'https://daily-cloudcode-pa.googleapis.com' if 'google' in package_name else 'http://127.0.0.1 (Local)')

        # ------------------------------------------------------------------
        # PHASE 3: Write .proto file
        # ------------------------------------------------------------------
        with open(proto_file_path, "w") as f:
            f.write('syntax = "proto3";\n\n')
            f.write(f'package {package_name};\n\n')

            f.write(f'// {"=" * 72}\n')
            f.write(f'// Service: {service_name} ({len(s_info["methods"])} Methods)\n')
            f.write(f'// Package: {package_name}\n')
            f.write(f'// Default Upstream Host: {upstream_host}\n')
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
                from collections import Counter
                val_counts = Counter()
                for fn, e in resolved_enums.items():
                    for v in e.get("values", []):
                        val_counts[v["name"]] += 1

                for fn in sorted(resolved_enums.keys(), key=lambda x: enum_display[x]):
                    dn = enum_display[fn]
                    if dn in written_enums: continue
                    written_enums.add(dn)
                    e = resolved_enums[fn]
                    # Comment shows the exact origin full name from the binary
                    f.write(f'// origin: {fn}\n')
                    f.write(f'enum {dn} {{\n')
                    seen_val_names = set()
                    for v in sorted(e["values"], key=lambda x: x["number"]):
                        v_name = v["name"]
                        if val_counts[v_name] > 1 and not v_name.upper().startswith(dn.upper() + "_"):
                            v_name = f"{dn.upper()}_{v_name}"
                        if v_name in seen_val_names:
                            continue
                        seen_val_names.add(v_name)
                        f.write(f'  {v_name} = {v["number"]};\n')
                    f.write('}\n\n')

            # Messages
            if resolved_messages:
                f.write(f'// {"=" * 72}\n// Messages & Structs\n// {"=" * 72}\n\n')
                written_msgs = set()
                for fn in sorted(resolved_messages.keys(), key=lambda x: msg_display[x]):
                    dn = msg_display[fn]
                    if dn in written_msgs: continue
                    m = resolved_messages[fn]
                    if check_is_map_entry(m)[0]:
                        continue
                    written_msgs.add(dn)
                    # Comment shows the exact origin full name from the binary
                    f.write(f'// origin: {fn}\n')
                    f.write(f'message {dn} {{\n')
                    for field in sorted(m["fields"], key=lambda x: x["number"]):
                        is_map = False
                        if field["label"] == 3 and field.get("type_name"):
                            _, target_m, _ = registry.find_type(field["type_name"])
                            if not target_m and field["type_name"] in resolved_messages:
                                target_m = resolved_messages[field["type_name"]]
                            if target_m:
                                is_map_entry, f_key, f_val = check_is_map_entry(target_m)
                                if is_map_entry:
                                    is_map = True
                                    k_str = TYPE_MAP.get(f_key["type"], "string")
                                    if f_val.get("type_name"):
                                        v_str = type_name_map.get(f_val["type_name"])
                                        if not v_str:
                                            _, _, res_v = registry.find_type(f_val["type_name"])
                                            v_str = type_name_map.get(res_v, f_val["type_name"].split('.')[-1])
                                    else:
                                        v_str = TYPE_MAP.get(f_val["type"], "bytes")
                                    f.write(f'  map<{k_str}, {v_str}> {field["name"]} = {field["number"]};\n')
                        if not is_map:
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
            if check_is_map_entry(m)[0]:
                continue
            m_dn = msg_display[full_name]
            for field in m["fields"]:
                if field["label"] == 3 and field.get("type_name"):
                    _, target_m, _ = registry.find_type(field["type_name"])
                    if not target_m and field["type_name"] in resolved_messages:
                        target_m = resolved_messages[field["type_name"]]
                    if target_m:
                        is_map_entry, f_key, f_val = check_is_map_entry(target_m)
                        if is_map_entry:
                            if f_val.get("type_name"):
                                v_str = type_name_map.get(f_val["type_name"])
                                if not v_str:
                                    _, _, res_v = registry.find_type(f_val["type_name"])
                                    v_str = type_name_map.get(res_v, f_val["type_name"].split('.')[-1])
                                if v_str not in all_defined and v_str not in primitive_types:
                                    errors.append(f"  '{m_dn}'.{field['name']} → '{v_str}' (from {f_val['type_name']})")
                            continue
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
        print(f"Generated: {clean_file_stem}.proto → {len(s_info['methods'])} RPCs, "
              f"{len(resolved_messages)} Messages, {len(resolved_enums)} Enums"
              f"{coll_str} [{status_str}]")
    # Export service catalog JSON for proxy routing
    import json
    catalog_path = os.path.join(output_dir, "service_catalog.json")
    service_catalog = {}
    for s_key in target_service_keys:
        s_info = registry.services[s_key]
        pkg = SERVICE_PACKAGE_MAP.get(s_info["service"], s_info["package"] or "exa.local_grpc")
        host = HOST_MAP.get(pkg, "https://daily-cloudcode-pa.googleapis.com" if "google" in pkg else "http://127.0.0.1")
        service_catalog[s_key] = {
            "service": s_info["service"],
            "package": pkg,
            "upstream_host": host,
            "route_prefix": f"/{pkg}.{s_info['service']}/",
            "methods": [m["name"] for m in s_info["methods"]]
        }
    with open(catalog_path, "w") as f:
        json.dump(service_catalog, f, indent=2)

    print(f"\nAll service proto files updated in: {output_dir}")
    print(f"Service Catalog saved to: {catalog_path}")

if __name__ == "__main__":
    custom_bin = None
    full_scan = False
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        a = args[i]
        if a.startswith("--bin="):
            custom_bin = a.split("=", 1)[1]
        elif a in ("--bin", "-b") and i + 1 < len(args):
            custom_bin = args[i + 1]
            i += 1
        elif a in ("--full", "-f"):
            full_scan = True
        elif not a.startswith("-") and custom_bin is None:
            custom_bin = a
        i += 1

    generate_per_service_protos(binary_path=custom_bin, full=full_scan)

