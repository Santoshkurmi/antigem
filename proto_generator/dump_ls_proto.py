import re
from io import BytesIO
import json
from scratch.extract_exact_proto import parse_file_descriptor, read_varint

binary_path = "/data/data/com.termux/files/usr/bin/agy.va39"
with open(binary_path, "rb") as f:
    data = f.read()

# Let's search for language_server.proto chunks
offsets = [m.start() for m in re.finditer(rb'language_server\.proto', data)]

for off in offsets:
    # Look back up to 200 bytes for the tag 0x0a
    start_search = max(0, off - 100)
    for pos in range(off, start_search, -1):
        if data[pos] == 0x0a:
            chunk = data[pos:pos+2000000]
            try:
                fname, pkg, svcs = parse_file_descriptor(chunk)
                if svcs:
                    print(f"FOUND at {pos}: File={fname} Pkg={pkg}")
                    for s_name, methods in svcs:
                        print(f"Service: {pkg}.{s_name} with {len(methods)} methods")
                        with open("/data/user/0/com.termux/files/home/.gemini/antigravity-cli/brain/37642fbe-6e57-4020-96b7-236e0a38422e/scratch/language_server_schema.json", "w") as jf:
                            json.dump({
                                "file": fname,
                                "package": pkg,
                                "service": s_name,
                                "methods": methods
                            }, jf, indent=2)
                    break
            except:
                pass
