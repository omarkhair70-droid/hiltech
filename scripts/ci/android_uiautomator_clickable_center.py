#!/usr/bin/env python3
"""Find the center of a truly clickable ancestor of a visible UI text node.

Input: Android uiautomator XML on stdin. Output: 'x y' for adb input tap.
This does not turn a rendered label into a fake success: the actual button
must be exposed as clickable and the script must navigate to its real screen.
"""
import re
import sys
import xml.etree.ElementTree as ET

if len(sys.argv) != 2 or not sys.argv[1]:
    sys.exit(2)
needle = sys.argv[1]
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit(1)

parent = {child: item for item in root.iter() for child in item}
for item in root.iter():
    if needle not in item.attrib.get("text", ""):
        continue
    node = item
    while node is not None and node.attrib.get("clickable") != "true":
        node = parent.get(node)
    if node is None:
        continue
    coords = re.findall(r"\d+", node.attrib.get("bounds", ""))
    if len(coords) != 4:
        continue
    x0, y0, x1, y1 = map(int, coords)
    if x0 < x1 and y0 < y1:
        print((x0 + x1) // 2, (y0 + y1) // 2)
        sys.exit(0)
sys.exit(1)
