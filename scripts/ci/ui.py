#!/usr/bin/env python3
"""Find a node in a `uiautomator dump` XML by text / content-desc / class and print its centre.

usage: ui.py <dump.xml> <attr>=<value> [...]   e.g. text=Continue  content-desc~=What do you
Exit 1 if not found. `~=` means "contains".
"""
import re
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    path, *filters = sys.argv[1:]
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return 1
    for node in root.iter("node"):
        ok = True
        for f in filters:
            if "~=" in f:
                k, v = f.split("~=", 1)
                ok = ok and v.lower() in (node.get(k) or "").lower()
            else:
                k, v = f.split("=", 1)
                ok = ok and (node.get(k) or "") == v
        if ok:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
            return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
