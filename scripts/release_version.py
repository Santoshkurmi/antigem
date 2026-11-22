#!/usr/bin/env python3
"""Prints a field of the release that version.json builds.

version.json has the stable release at the top level and maybe a beta in "beta". A build makes the newer of the two,
the one with the higher version_code: the same rule as app/build.gradle.kts (which also checks the file).

Usage: release_version.py <field> [path/to/version.json]
  name       the version name: 2.0.4 or 2.0.4-beta.1
  code       the version code
  channel    stable or beta
  changelog  its changelog, one line per entry
"""
import json
import os
import sys


def release(data):
    beta = data.get("beta")
    if isinstance(beta, dict) and isinstance(beta.get("version_code"), int) \
            and beta["version_code"] > data.get("version_code", 0):
        return beta, "beta"
    return data, "stable"


def main():
    if len(sys.argv) not in (2, 3) or sys.argv[1] not in ("name", "code", "channel", "changelog"):
        sys.exit(__doc__)
    path = sys.argv[2] if len(sys.argv) == 3 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "version.json")
    with open(path, encoding="utf-8") as f:
        r, channel = release(json.load(f))
    field = sys.argv[1]
    if field == "name":
        print(r["version_name"])
    elif field == "code":
        print(r["version_code"])
    elif field == "channel":
        print(channel)
    else:
        changelog = r.get("changelog", [])
        print("\n".join(changelog) if isinstance(changelog, list) else changelog)


if __name__ == "__main__":
    main()
