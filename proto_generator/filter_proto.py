#!/usr/bin/env python3
import os
import sys
import re
import shutil
import subprocess
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
PROTOS_DIR = BASE_DIR / "protos"
FILTERS_DIR = BASE_DIR / "proto_filters"
APP_PROTO_DIR = BASE_DIR.parent / "app" / "src" / "main" / "proto"
BRIDGE_DIR = BASE_DIR.parent / "agy_ide_bridge"
BRIDGE_PROTO_DIR = BRIDGE_DIR / "proto"
BRIDGE_PKG_PROTO_DIR = BRIDGE_DIR / "pkg" / "proto"

LOCALHOST_PROTOS = {
    "LanguageServerService.proto",
    "ExtensionServerService.proto",
    "RemotingService.proto",
}

WKT_IMPORTS = {
    "Timestamp": "google/protobuf/timestamp.proto",
    "Struct": "google/protobuf/struct.proto",
    "Value": "google/protobuf/struct.proto",
    "ListValue": "google/protobuf/struct.proto",
    "NullValue": "google/protobuf/struct.proto",
    "Any": "google/protobuf/any.proto",
    "Duration": "google/protobuf/duration.proto",
    "Empty": "google/protobuf/empty.proto",
}

GO_PKG_OVERRIDES = {
    "CloudCode.proto": "gemini-server/pkg/proto/cloudcode",
    "CloudCode_PredictionService.proto": "gemini-server/pkg/proto/prediction",
    "JetskiService.proto": "gemini-server/pkg/proto/jetski",
}

PRIMITIVE_TYPES = {
    "double", "float", "int32", "int64", "uint32", "uint64", "sint32", "sint64",
    "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes"
}

def clean_type_name(t: str) -> str:
    return t.split(".")[-1].strip()

class ProtoParser:
    def __init__(self, content: str):
        self.raw_content = content
        self.syntax = "proto3"
        self.package = "exa.language_server_pb"
        self.services = {} # service_name -> list of rpc dicts
        self.messages = {} # message_name -> { "raw": str, "fields": list of types }
        self.enums = {}    # enum_name -> { "raw": str, "constants": list of str }
        self._parse()

    def _parse(self):
        # Syntax & Package
        syntax_match = re.search(r'syntax\s*=\s*"([^"]+)";', self.raw_content)
        if syntax_match:
            self.syntax = syntax_match.group(1)

        pkg_match = re.search(r'package\s+([^;]+);', self.raw_content)
        if pkg_match:
            self.package = pkg_match.group(1).strip()
        else:
            self.package = "exa.language_server_pb"

        # 1. Parse Enums
        enum_pattern = re.compile(r'(enum\s+([A-Za-z0-9_]+)\s*\{([^}]+)\})', re.MULTILINE)
        for full_block, enum_name, inner in enum_pattern.findall(self.raw_content):
            consts = []
            for line in inner.strip().split('\n'):
                line = line.strip()
                if line and not line.startswith('//'):
                    m = re.match(r'([A-Za-z0-9_]+)\s*=', line)
                    if m:
                        consts.append(m.group(1))
            self.enums[enum_name] = {
                "raw": full_block,
                "constants": consts
            }

        # 2. Parse Services and RPCs
        service_pattern = re.compile(r'service\s+([A-Za-z0-9_]+)\s*\{([^}]+)\}', re.MULTILINE)
        for service_name, inner in service_pattern.findall(self.raw_content):
            rpc_pattern = re.compile(
                r'rpc\s+([A-Za-z0-9_]+)\s*\(\s*(stream\s+)?([A-Za-z0-9_.]+)\s*\)\s*returns\s*\(\s*(stream\s+)?([A-Za-z0-9_.]+)\s*\);'
            )
            rpcs = []
            for rpc_match in rpc_pattern.finditer(inner):
                name = rpc_match.group(1)
                req_stream = bool(rpc_match.group(2))
                req_type = clean_type_name(rpc_match.group(3))
                resp_stream = bool(rpc_match.group(4))
                resp_type = clean_type_name(rpc_match.group(5))
                rpcs.append({
                    "name": name,
                    "req_type": req_type,
                    "resp_type": resp_type,
                    "req_stream": req_stream,
                    "resp_stream": resp_stream,
                    "raw": rpc_match.group(0).strip()
                })
            self.services[service_name] = rpcs

        # 3. Parse Messages
        pos = 0
        text = self.raw_content
        msg_start_re = re.compile(r'\bmessage\s+([A-Za-z0-9_]+)\s*\{')
        while True:
            m = msg_start_re.search(text, pos)
            if not m:
                break
            msg_name = m.group(1)
            start_idx = m.start()
            brace_count = 0
            curr = m.end() - 1
            while curr < len(text):
                if text[curr] == '{':
                    brace_count += 1
                elif text[curr] == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        break
                curr += 1
            end_idx = curr + 1
            full_msg = text[start_idx:end_idx]
            pos = end_idx

            inner_body = full_msg[full_msg.find('{') + 1 : -1]
            field_types = []
            for f_line in inner_body.split('\n'):
                f_line = f_line.strip()
                if not f_line or f_line.startswith('//'):
                    continue
                map_m = re.search(r'map\s*<\s*([A-Za-z0-9_.]+)\s*,\s*([A-Za-z0-9_.]+)\s*>', f_line)
                if map_m:
                    field_types.append(clean_type_name(map_m.group(1)))
                    field_types.append(clean_type_name(map_m.group(2)))
                    continue

                fm = re.match(r'(?:repeated\s+|optional\s+)?([A-Za-z0-9_.]+)\s+([A-Za-z0-9_]+)\s*=\s*\d+', f_line)
                if fm:
                    t_name = clean_type_name(fm.group(1))
                    field_types.append(t_name)

            self.messages[msg_name] = {
                "raw": full_msg,
                "fields": field_types
            }

def filter_proto(proto_file: Path, selected_rpcs: set) -> str:
    content = proto_file.read_text(encoding="utf-8")
    
    # If selected_rpcs is empty, it means filter file exists but is empty -> include ALL methods
    if not selected_rpcs:
        print(f"📋 Filter file is empty: Including ALL RPC methods for '{proto_file.name}'")
        return content

    parser = ProtoParser(content)
    print(f"🎯 Filtering '{proto_file.name}' with {len(selected_rpcs)} selected RPC methods...")

    needed_messages = set()
    needed_enums = set()
    kept_services = {}

    for s_name, rpc_list in parser.services.items():
        kept_rpcs = []
        for rpc in rpc_list:
            if rpc["name"] in selected_rpcs:
                kept_rpcs.append(rpc)
                needed_messages.add(rpc["req_type"])
                needed_messages.add(rpc["resp_type"])
        if kept_rpcs:
            kept_services[s_name] = kept_rpcs

    if not kept_services:
        print(f"⚠️ None of the RPC names in filter matched services in '{proto_file.name}'.")
        return ""

    # Recursive type dependency traversal
    queue = list(needed_messages)
    visited = set(queue)

    while queue:
        curr_type = queue.pop(0)
        if curr_type in PRIMITIVE_TYPES:
            continue

        if curr_type in parser.enums:
            needed_enums.add(curr_type)

        if curr_type in parser.messages:
            needed_messages.add(curr_type)
            msg_data = parser.messages[curr_type]
            for child_type in msg_data["fields"]:
                if child_type not in visited and child_type not in PRIMITIVE_TYPES:
                    visited.add(child_type)
                    queue.append(child_type)
                    if child_type in parser.enums:
                        needed_enums.add(child_type)

    print(f"📦 Extracted: {len(kept_services)} service(s), {len(needed_messages)} messages, {len(needed_enums)} enums (pruned from {len(parser.messages)} total messages)")

    # Build output proto content
    out_lines = []
    out_lines.append('syntax = "proto3";\n')
    out_lines.append(f'package {parser.package};\n')
    out_lines.append('// Filtered subset for Android app compilation\n')

    # Add services
    for s_name, rpcs in kept_services.items():
        out_lines.append(f"// Service: {s_name} ({len(rpcs)} Methods)")
        out_lines.append(f"service {s_name} {{")
        for r in rpcs:
            req_s = "stream " if r["req_stream"] else ""
            resp_s = "stream " if r["resp_stream"] else ""
            out_lines.append(f"  rpc {r['name']} ({req_s}{r['req_type']}) returns ({resp_s}{r['resp_type']});")
        out_lines.append("}\n")

    # Add Enums
    out_lines.append("// ==========================================================================")
    out_lines.append("// Enumerations")
    out_lines.append("// ==========================================================================\n")
    
    enum_constants = {}
    for e_name in sorted(needed_enums):
        if e_name in parser.enums:
            for c in parser.enums[e_name]["constants"]:
                enum_constants.setdefault(c, []).append(e_name)

    duplicates = {c: enums for c, enums in enum_constants.items() if len(enums) > 1}

    for e_name in sorted(needed_enums):
        if e_name in parser.enums:
            raw_enum = parser.enums[e_name]["raw"]
            for dup_c, enums in duplicates.items():
                if e_name in enums:
                    prefix = re.sub(r'(?<!^)(?=[A-Z])', '_', e_name).upper() + "_"
                    new_c = f"{prefix}{dup_c}" if not dup_c.startswith(prefix) else f"{prefix}VAL_{dup_c}"
                    raw_enum = re.sub(rf'\b{dup_c}\b(\s*=\s*\d+;)', rf'{new_c}\1', raw_enum)
            out_lines.append(raw_enum)
            out_lines.append("")

    # Add Messages
    out_lines.append("// ==========================================================================")
    out_lines.append("// Messages")
    out_lines.append("// ==========================================================================\n")
    for m_name in sorted(needed_messages):
        if m_name in parser.messages:
            msg_text = parser.messages[m_name]["raw"]
            if m_name == "Operation":
                msg_text = msg_text.replace("Status error = 4;", "RpcStatus error = 4;")
            out_lines.append(msg_text)
            out_lines.append("")

    return "\n".join(out_lines)

def remove_dummy_block(text: str, block_name: str, kind: str = 'message') -> str:
    pattern = re.compile(rf'(?://[^\n]*\n)*\s*{kind}\s+{block_name}\s*\{{[^}}]*\}}\n*', re.MULTILINE)
    return pattern.sub('', text)

def convert_proto_for_go_bridge(proto_name: str, raw_content: str) -> str:
    content = raw_content
    needed_imports = set()

    for wkt, imp in WKT_IMPORTS.items():
        if re.search(rf'\b{wkt}\b', content):
            needed_imports.add(imp)
            if wkt == 'NullValue':
                content = remove_dummy_block(content, wkt, kind='enum')
            else:
                content = remove_dummy_block(content, wkt, kind='message')

    # Replace field usages with standard google.protobuf types
    for wkt in ['Timestamp', 'Struct', 'ListValue', 'NullValue', 'Any', 'Duration', 'Empty']:
        content = re.sub(rf'(?<![A-Za-z0-9_.])\b{wkt}\b', f'google.protobuf.{wkt}', content)
    # Value specifically
    content = re.sub(r'(?<![A-Za-z0-9_.])\bValue\b', 'google.protobuf.Value', content)

    # Determine Go package
    go_pkg = GO_PKG_OVERRIDES.get(proto_name)
    if not go_pkg:
        stem = proto_name[:-6] if proto_name.endswith(".proto") else proto_name
        pkg_suffix = stem.split('_')[-1].lower()
        go_pkg = f"gemini-server/pkg/proto/{pkg_suffix}"

    # Build imports and go_package option
    import_lines = '\n'.join([f'import "{imp}";' for imp in sorted(needed_imports)])
    go_opt = f'option go_package = "{go_pkg}";'

    # Insert after package declaration
    pkg_match = re.search(r'package\s+[^;]+;', content)
    if pkg_match:
        idx = pkg_match.end()
        insert_block = f'\n\n{go_opt}\n\n{import_lines}\n'
        content = content[:idx] + insert_block + content[idx:]
    else:
        content = f'{go_opt}\n{import_lines}\n\n' + content

    return content

def sync_bridge_internal_protos():
    print("\n🌉 Syncing internal cloud protos to agy_ide_bridge/proto...")
    BRIDGE_PROTO_DIR.mkdir(parents=True, exist_ok=True)
    BRIDGE_PKG_PROTO_DIR.mkdir(parents=True, exist_ok=True)

    all_proto_files = sorted(list(PROTOS_DIR.glob("*.proto")))
    internal_proto_files = [p for p in all_proto_files if p.name not in LOCALHOST_PROTOS]

    if not internal_proto_files:
        print("ℹ️ No internal proto files found to sync.")
        return

    written_files = []
    for proto_file in internal_proto_files:
        raw_text = proto_file.read_text(encoding="utf-8")
        converted_text = convert_proto_for_go_bridge(proto_file.name, raw_text)
        dest_path = BRIDGE_PROTO_DIR / proto_file.name
        dest_path.write_text(converted_text, encoding="utf-8")
        written_files.append(dest_path)
        print(f"  ✓ Processed: {proto_file.name} -> {dest_path.relative_to(BASE_DIR.parent)}")

    # Compile with protoc for Go
    print("\n🛠️ Compiling Go protobufs with protoc...")
    env = os.environ.copy()
    go_bin = str(Path.home() / "go" / "bin")
    if go_bin not in env.get("PATH", ""):
        env["PATH"] = f"{env.get('PATH', '')}:{go_bin}"

    proto_paths = [str(f) for f in written_files]
    cmd = [
        "protoc",
        f"-I={BRIDGE_PROTO_DIR}",
        "-I=/usr/include",
        f"--go_out={BRIDGE_DIR}",
        "--go_opt=module=gemini-server",
    ] + proto_paths

    res = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"❌ protoc compilation failed:\n{res.stderr}")
    else:
        print("✅ Go protobuf packages compiled successfully into agy_ide_bridge/pkg/proto/!")

def main():
    auto_copy = "--copy" in sys.argv or "-y" in sys.argv

    # 1. Filter and copy localhost protos to Android app if filters exist
    if FILTERS_DIR.exists():
        filter_files = list(FILTERS_DIR.glob("*.filter")) + list(FILTERS_DIR.glob("*.proto.filter"))
        filter_files = sorted(list(set(filter_files)))

        processed = 0
        for filter_path in filter_files:
            proto_name = filter_path.name
            if proto_name.endswith(".proto.filter"):
                proto_name = proto_name[:-7]
            elif proto_name.endswith(".filter"):
                proto_name = proto_name[:-7]
                if not proto_name.endswith(".proto"):
                    proto_name += ".proto"

            proto_path = PROTOS_DIR / proto_name
            if not proto_path.exists():
                print(f"⚠️ Filter '{filter_path.name}' found, but '{proto_name}' does not exist in '{PROTOS_DIR.name}/'. Skipping.")
                continue

            selected_rpcs = set()
            for line in filter_path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith('#'):
                    selected_rpcs.add(line)

            filtered_content = filter_proto(proto_path, selected_rpcs)
            if not filtered_content:
                continue

            processed += 1
            dest_file = APP_PROTO_DIR / proto_name

            should_copy = auto_copy
            if not should_copy:
                try:
                    ans = input(f"\n❓ Do you want to copy '{proto_name}' to app/src/main/proto/? (y/N): ").strip().lower()
                    should_copy = (ans == 'y' or ans == 'yes')
                except EOFError:
                    should_copy = False

            if should_copy:
                APP_PROTO_DIR.mkdir(parents=True, exist_ok=True)
                dest_file.write_text(filtered_content, encoding="utf-8")
                print(f"🚀 Successfully updated: {dest_file}")
            else:
                print(f"⏭️ Skipped copying '{proto_name}'.")

    # 2. Sync internal cloud protos to agy_ide_bridge
    sync_bridge_internal_protos()

if __name__ == "__main__":
    main()
