#!/usr/bin/env python3
"""Match exact visible text in a uiautomator dump, including localized copy."""

import sys
import xml.etree.ElementTree as ET


def has_ui_text(xml: str, expected: tuple[str, ...]) -> bool:
    """Reject malformed/empty dumps and matches in metadata or partial labels."""
    if not expected or any(not text for text in expected):
        return False
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return False
    return any(
        node.attrib.get("text") in expected
        and node.attrib.get("visible-to-user") != "false"
        for node in root.iter("node")
    )


if __name__ == "__main__":
    sys.exit(0 if has_ui_text(sys.stdin.read(), tuple(sys.argv[1:])) else 1)
