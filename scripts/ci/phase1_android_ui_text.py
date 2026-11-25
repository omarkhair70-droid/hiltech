#!/usr/bin/env python3
"""Validate localized native-shell geometry and derive taps from UI XML."""

import argparse
import re
import sys
import xml.etree.ElementTree as ET

SAFE_CONTENT_TAG = "hiltech_android_safe_content"
PACKAGE = "com.hiltech.android"
COPY = {
    "ar": {
        "subtitle": "إدارة أعمال الشركة",
        "signed-out": (
            "سجّل الدخول بحساب HILTECH للمتابعة.",
            "تسجيل الدخول",
        ),
        "callback-failure": (
            "لم يكتمل تسجيل الدخول",
            "ابدأ تسجيل الدخول من التطبيق، وأكمل الخطوات في المتصفح، ثم عد إلى HILTECH.",
            "المرجع: OIDC_ATTEMPT_MISSING",
            "تسجيل الدخول من جديد",
        ),
    },
    "en": {
        "subtitle": "Company operations",
        "signed-out": (
            "Sign in with your HILTECH identity to continue.",
            "Sign in",
        ),
        "callback-failure": (
            "Sign-in was not completed",
            "Start sign-in from the app, complete the steps in your browser, then return to HILTECH.",
            "Reference: OIDC_ATTEMPT_MISSING",
            "Start sign-in again",
        ),
    },
}


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


def bounds(node: ET.Element) -> tuple[int, int, int, int] | None:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if match is None:
        return None
    left, top, right, bottom = map(int, match.groups())
    return (left, top, right, bottom) if left < right and top < bottom else None


def contains(outer: tuple[int, int, int, int], inner: tuple[int, int, int, int]) -> bool:
    return outer[0] <= inner[0] < inner[2] <= outer[2] and outer[1] <= inner[1] < inner[3] <= outer[3]


class AndroidShellDump:
    """Only app nodes inside the inset-consuming Android host can satisfy a check."""

    def __init__(self, xml: str, package: str = PACKAGE):
        try:
            self.root = ET.fromstring(xml)
        except ET.ParseError as error:
            raise ValueError("UI dump is not well-formed XML") from error
        if self.root.tag != "hierarchy":
            raise ValueError("UI dump has no hierarchy")
        self.package = package
        self.parents = {child: parent for parent in self.root.iter() for child in parent}
        safe_nodes = [
            node for node in self.root.iter("node")
            if node.get("package") == package
            and node.get("resource-id") in (SAFE_CONTENT_TAG, f"{package}:id/{SAFE_CONTENT_TAG}")
            and self.visible(node)
        ]
        if len(safe_nodes) != 1:
            raise ValueError("Expected one visible Android safe-content region")
        self.safe = safe_nodes[0]
        self.safe_bounds = bounds(self.safe)
        if self.safe_bounds is None:
            raise ValueError("Android safe-content region has invalid or empty bounds")
        self.content_nodes = set(self.safe.iter("node"))
        for node in self.root.iter("node"):
            resource = node.get("resource-id", "")
            if (
                resource.split(":id/")[-1] in {
                    "action_bar", "action_bar_container", "action_bar_title",
                    "action_bar_subtitle", "action_context_bar",
                }
                and self.visible(node) and bounds(node) is not None
            ):
                raise ValueError("A native ActionBar is still visible")

    def visible(self, node: ET.Element) -> bool:
        current = node
        while current is not None:
            if current.get("visible-to-user") == "false":
                return False
            current = self.parents.get(current)
        return True

    def in_safe_content(self, node: ET.Element) -> bool:
        rectangle = bounds(node)
        return (
            node in self.content_nodes
            and node.get("package") == self.package
            and self.visible(node)
            and rectangle is not None
            and contains(self.safe_bounds, rectangle)
        )

    def matching_text(self, text: str) -> list[ET.Element]:
        return [node for node in self.safe.iter("node") if node.get("text") == text and self.in_safe_content(node)]

    def tap_center(self, text: str) -> tuple[int, int]:
        targets = set()
        for node in self.matching_text(text):
            current = node
            candidate = None
            while current in self.content_nodes:
                if current.get("enabled") == "false":
                    candidate = None
                    break
                if candidate is None and current.get("clickable") == "true":
                    candidate = current
                if current is self.safe:
                    break
                current = self.parents.get(current)
            if candidate is not None and self.in_safe_content(candidate):
                targets.add(candidate)
        if len(targets) != 1:
            raise ValueError(f"Expected one enabled, visible tap target for {text!r}")
        left, top, right, bottom = bounds(targets.pop())
        return ((left + right) // 2, (top + bottom) // 2)

    def validate(self, state: str, language: str) -> None:
        if state not in ("signed-out", "callback-failure") or language not in COPY:
            raise ValueError("Unknown shell state or language")
        labels = ("HILTECH OS", COPY[language]["subtitle"], "العربية", "English", *COPY[language][state])
        for label in labels:
            if not self.matching_text(label):
                raise ValueError(f"Visible text missing or outside safe content: {label!r}")
        for label in ("العربية", "English", COPY[language][state][-1]):
            self.tap_center(label)
        other_language = "en" if language == "ar" else "ar"
        if self.matching_text(COPY[other_language]["subtitle"]):
            raise ValueError("Both localized headers are visible")
        other_state = "callback-failure" if state == "signed-out" else "signed-out"
        if any(self.matching_text(label) for label in COPY[language][other_state]):
            raise ValueError("The previous identity state is still visible")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("expected", nargs="*")
    parser.add_argument("--state", choices=("signed-out", "callback-failure"))
    parser.add_argument("--language", choices=("ar", "en"))
    parser.add_argument("--tap-text")
    parser.add_argument("--package", default=PACKAGE)
    args = parser.parse_args()
    xml = sys.stdin.read()
    if not args.state:
        if args.language or args.tap_text:
            parser.error("--language and --tap-text require --state")
        return 0 if has_ui_text(xml, tuple(args.expected)) else 1
    if not args.language or args.expected:
        parser.error("--state requires --language and cannot be combined with text arguments")
    try:
        shell = AndroidShellDump(xml, args.package)
        shell.validate(args.state, args.language)
        if args.tap_text:
            print(*shell.tap_center(args.tap_text))
    except ValueError as error:
        print(f"Android shell assertion failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
