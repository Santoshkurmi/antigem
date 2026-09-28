#!/usr/bin/env python3
import os
import sys
import re
import shutil
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
PROTOS_DIR = BASE_DIR / "protos"
FILTERS_DIR = BASE_DIR / "proto_filters"
APP_PROTO_DIR = BASE_DIR.parent / "app" / "src" / "main" / "proto"

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

def main():
    if not FILTERS_DIR.exists():
        print(f"ℹ️ No '{FILTERS_DIR.name}' directory found. Nothing to filter.")
        return

    filter_files = list(FILTERS_DIR.glob("*.filter")) + list(FILTERS_DIR.glob("*.proto.filter"))
    filter_files = sorted(list(set(filter_files)))

    if not filter_files:
        print(f"ℹ️ No .filter files found in '{FILTERS_DIR.name}'. Nothing to do.")
        return

    auto_copy = "--copy" in sys.argv or "-y" in sys.argv
    processed = 0

    for filter_path in filter_files:
        # Resolve target proto file name
        proto_name = filter_path.name
        if proto_name.endswith(".proto.filter"):
            proto_name = proto_name[:-7]  # e.g. LanguageServerService.proto
        elif proto_name.endswith(".filter"):
            proto_name = proto_name[:-7]
            if not proto_name.endswith(".proto"):
                proto_name += ".proto"

        proto_path = PROTOS_DIR / proto_name
        if not proto_path.exists():
            print(f"⚠️ Filter '{filter_path.name}' found, but '{proto_name}' does not exist in '{PROTOS_DIR.name}/'. Skipping.")
            continue

        # Read filter RPC names
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

    if processed == 0:
        print("ℹ️ No proto files processed.")

if __name__ == "__main__":
    main()
