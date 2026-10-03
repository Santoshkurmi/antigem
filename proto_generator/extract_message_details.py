import re
from io import BytesIO
import json
from scratch.extract_exact_proto import parse_proto, read_varint

def parse_field_descriptor(data):
    fields = parse_proto(BytesIO(data))
    name = ""
    number = 0
    label = 0 # 1=optional, 2=required, 3=repeated
    type_id = 0
    type_name = ""
    default_value = ""
    json_name = ""
    for num, wt, val in fields:
        if num == 1 and wt == 2: name = val.decode('utf-8', errors='ignore')
        elif num == 3 and wt == 0: number = val
        elif num == 4 and wt == 0: label = val
        elif num == 5 and wt == 0: type_id = val
        elif num == 6 and wt == 2: type_name = val.decode('utf-8', errors='ignore')
        elif num == 7 and wt == 2: default_value = val.decode('utf-8', errors='ignore')
        elif num == 10 and wt == 2: json_name = val.decode('utf-8', errors='ignore')
    return {
        "name": name,
        "number": number,
        "label": label,
        "type": type_id,
        "type_name": type_name,
        "json_name": json_name
    }

def parse_enum_value(data):
    fields = parse_proto(BytesIO(data))
    name = ""
    number = 0
    for num, wt, val in fields:
        if num == 1 and wt == 2: name = val.decode('utf-8', errors='ignore')
        elif num == 2 and wt == 0: number = val
    return {"name": name, "number": number}

def parse_enum(data):
    fields = parse_proto(BytesIO(data))
    name = ""
    values = []
    for num, wt, val in fields:
        if num == 1 and wt == 2: name = val.decode('utf-8', errors='ignore')
        elif num == 2 and wt == 2: values.append(parse_enum_value(val))
    return {"name": name, "values": values}

def parse_message_descriptor(data):
    fields = parse_proto(BytesIO(data))
    name = ""
    msg_fields = []
    nested_types = []
    enum_types = []
    for num, wt, val in fields:
        if num == 1 and wt == 2: name = val.decode('utf-8', errors='ignore')
        elif num == 2 and wt == 2: msg_fields.append(parse_field_descriptor(val))
        elif num == 3 and wt == 2: nested_types.append(parse_message_descriptor(val))
        elif num == 4 and wt == 2: enum_types.append(parse_enum(val))
    return {
        "name": name,
        "fields": msg_fields,
        "nested_types": nested_types,
        "enum_types": enum_types
    }

def parse_full_file(data):
    fields = parse_proto(BytesIO(data))
    filename = ""
    package = ""
    messages = []
    enums = []
    for num, wt, val in fields:
        if num == 1 and wt == 2: filename = val.decode('utf-8', errors='ignore')
        elif num == 2 and wt == 2: package = val.decode('utf-8', errors='ignore')
        elif num == 4 and wt == 2: messages.append(parse_message_descriptor(val))
        elif num == 5 and wt == 2: enums.append(parse_enum(val))
    return filename, package, messages, enums

def extract_all_messages():
    binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"
    with open(binary_path, "rb") as f:
        data = f.read()

    proto_tag_matches = [m.start() for m in re.finditer(b'\x0a[a-zA-Z0-9_/.-]+\.proto', data)]
    all_msgs = {}
    all_enums = {}

    for offset in proto_tag_matches:
        try:
            fname, pkg, msgs, enums = parse_full_file(data[offset:offset+2000000])
            for m in msgs:
                full_name = f".{pkg}.{m['name']}" if pkg else f".{m['name']}"
                all_msgs[full_name] = m
            for e in enums:
                full_name = f".{pkg}.{e['name']}" if pkg else f".{e['name']}"
                all_enums[full_name] = e
        except:
            pass
    return all_msgs, all_enums

if __name__ == "__main__":
    msgs, enums = extract_all_messages()
    print(f"Decoded {len(msgs)} messages and {len(enums)} enums.")
    
    target_req = ".exa.jetski_cortex_pb.StreamAgentStateUpdatesRequest"
    target_resp = ".exa.jetski_cortex_pb.StreamAgentStateUpdatesResponse"

    print("REQ:", msgs.get(target_req))
    print("RESP:", msgs.get(target_resp))

    with open("/data/user/0/com.termux/files/home/.gemini/antigravity-cli/brain/37642fbe-6e57-4020-96b7-236e0a38422e/scratch/agent_state_messages.json", "w") as f:
        json.dump({"msgs": msgs, "enums": enums}, f, indent=2)
