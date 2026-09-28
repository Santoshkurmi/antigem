#!/usr/bin/env python3
import os
import sys
import re
from collections import deque

def parse_proto_file(file_path):
    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    # Remove single line comments
    content_clean = re.sub(r'//.*', '', content)
    
    # 1. Extract Services and Methods
    # rpc MethodName (InputType) returns (stream? OutputType);
    service_pattern = re.compile(r'service\s+(\w+)\s*\{(.*?)\}', re.DOTALL)
    rpc_pattern = re.compile(r'rpc\s+(\w+)\s*\(\s*(?:stream\s+)?([\w\.]+)\s*\)\s*returns\s*\(\s*(?:stream\s+)?([\w\.]+)\s*\)', re.DOTALL)

    services = {}
    methods = {}

    for s_match in service_pattern.finditer(content_clean):
        s_name = s_match.group(1)
        s_body = s_match.group(2)
        s_methods = []
        for r_match in rpc_pattern.finditer(s_body):
            m_name = r_match.group(1)
            in_t = r_match.group(2).split('.')[-1]
            out_t = r_match.group(3).split('.')[-1]
            method_info = {
                "service": s_name,
                "method": m_name,
                "input_type": in_t,
                "output_type": out_t
            }
            s_methods.append(method_info)
            methods[m_name.lower()] = method_info
        services[s_name] = s_methods

    # Also extract individual RPCs outside service block if any
    for r_match in rpc_pattern.finditer(content_clean):
        m_name = r_match.group(1)
        if m_name.lower() not in methods:
            in_t = r_match.group(2).split('.')[-1]
            out_t = r_match.group(3).split('.')[-1]
            methods[m_name.lower()] = {
                "service": "Unknown",
                "method": m_name,
                "input_type": in_t,
                "output_type": out_t
            }

    # 2. Extract Message Definitions with balanced braces
    messages = {}
    message_regex = re.compile(r'\bmessage\s+(\w+)\s*\{')
    for m in message_regex.finditer(content):
        msg_name = m.group(1)
        start_pos = m.start()
        open_brace = content.find('{', start_pos)
        if open_brace == -1:
            continue
        
        depth = 1
        pos = open_brace + 1
        while depth > 0 and pos < len(content):
            if content[pos] == '{':
                depth += 1
            elif content[pos] == '}':
                depth -= 1
            pos += 1
        
        if depth == 0:
            full_body = content[start_pos:pos]
            messages[msg_name] = full_body.strip()

    # 3. Extract Enum Definitions
    enums = {}
    enum_regex = re.compile(r'\benum\s+(\w+)\s*\{')
    for e in enum_regex.finditer(content):
        enum_name = e.group(1)
        start_pos = e.start()
        open_brace = content.find('{', start_pos)
        if open_brace == -1:
            continue
        
        depth = 1
        pos = open_brace + 1
        while depth > 0 and pos < len(content):
            if content[pos] == '{':
                depth += 1
            elif content[pos] == '}':
                depth -= 1
            pos += 1
        
        if depth == 0:
            full_body = content[start_pos:pos]
            enums[enum_name] = full_body.strip()

    return services, methods, messages, enums

def extract_type_dependencies(type_body):
    # Find all referenced identifiers in fields
    # e.g.: repeated Step steps = 2; or CascadeRunStatus status = 3; or map<string, MyType>
    clean = re.sub(r'//.*', '', type_body)
    tokens = re.findall(r'\b[A-Z]\w+\b', clean)
    # Filter out common primitives/keywords if capitalized
    ignore = {'String', 'Int32', 'Int64', 'Uint32', 'Uint64', 'Bool', 'Bytes', 'Float', 'Double', 'Message', 'Enum', 'Rpc', 'Returns', 'Service', 'True', 'False', 'None'}
    return [t for t in tokens if t not in ignore]

def collect_all_proto_files(search_paths):
    proto_files = []
    for p in search_paths:
        if os.path.isfile(p) and p.endswith('.proto'):
            proto_files.append(p)
        elif os.path.isdir(p):
            for root, _, files in os.walk(p):
                for f in files:
                    if f.endswith('.proto'):
                        proto_files.append(os.path.join(root, f))
    return proto_files

def get_all_types_for_api(api_name, search_paths):
    proto_files = collect_all_proto_files(search_paths)
    
    all_methods = {}
    all_messages = {}
    all_enums = {}

    for pf in proto_files:
        _, methods, messages, enums = parse_proto_file(pf)
        all_methods.update(methods)
        all_messages.update(messages)
        all_enums.update(enums)

    target_method = all_methods.get(api_name.lower())
    
    # If not found directly as an RPC method, check if api_name is directly a Message or Enum name
    matched_entry_types = []
    if target_method:
        matched_entry_types = [target_method["input_type"], target_method["output_type"]]
    else:
        # Check case-insensitive match for message or enum
        for name in list(all_messages.keys()) + list(all_enums.keys()):
            if name.lower() == api_name.lower():
                matched_entry_types.append(name)
                break

    if not matched_entry_types and not target_method:
        # Search for partial match in RPCs
        similar = [m["method"] for k, m in all_methods.items() if api_name.lower() in k]
        return None, similar

    queue = deque(matched_entry_types)
    visited = set()

    collected_messages = []
    collected_enums = []

    while queue:
        t = queue.popleft()
        if t in visited:
            continue
        visited.add(t)

        if t in all_messages:
            msg_body = all_messages[t]
            collected_messages.append((t, msg_body))
            for dep in extract_type_dependencies(msg_body):
                if dep not in visited:
                    if dep in all_messages or dep in all_enums:
                        queue.append(dep)

        elif t in all_enums:
            enum_body = all_enums[t]
            collected_enums.append((t, enum_body))

    return {
        "method": target_method,
        "entry_types": matched_entry_types,
        "messages": collected_messages,
        "enums": collected_enums
    }, []

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 get_all_type_of_api.py <api_name_or_type>")
        print("Example: python3 get_all_type_of_api.py StreamAgentStateUpdates")
        print("Example: python3 get_all_type_of_api.py SendUserCascadeMessage")
        sys.exit(1)

    api_query = sys.argv[1].strip()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.abspath(os.path.join(script_dir, "..", ".."))

    search_paths = [
        os.path.join(repo_root, "antiGem", "app", "src"),
        os.path.join(repo_root, "antiGem", "proto_generator")
    ]

    result, similar = get_all_types_for_api(api_query, search_paths)

    if not result:
        print(f"❌ Error: API or Type '{api_query}' not found in proto files.")
        if similar:
            print(f"Did you mean one of these?")
            for s in similar[:10]:
                print(f"  - {s}")
        sys.exit(1)

    print("=" * 80)
    if result["method"]:
        m = result["method"]
        print(f"🎯 API RPC: {m['service']}.{m['method']}")
        print(f"📥 Request Type:  {m['input_type']}")
        print(f"📤 Response Type: {m['output_type']}")
    else:
        print(f"🎯 Type Query: {result['entry_types']}")
    print(f"📦 Total Message Types: {len(result['messages'])}")
    print(f"🔢 Total Enum Types:    {len(result['enums'])}")
    print("=" * 80)
    print()

    if result["messages"]:
        print("/* ==========================================================================")
        print(" * MESSAGES")
        print(" * ========================================================================== */")
        for name, body in result["messages"]:
            print(body)
            print()

    if result["enums"]:
        print("/* ==========================================================================")
        print(" * ENUMS")
        print(" * ========================================================================== */")
        for name, body in result["enums"]:
            print(body)
            print()

if __name__ == "__main__":
    main()
