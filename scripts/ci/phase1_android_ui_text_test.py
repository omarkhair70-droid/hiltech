"""Regression checks for the localized production OIDC smoke assertions."""

import io
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch
from xml.sax.saxutils import quoteattr

from phase1_android_ui_text import AndroidShellDump, has_ui_text, main


def dump(text: str) -> str:
    return f'<hierarchy><node text={quoteattr(text)} /></hierarchy>'


class AndroidUiTextTest(unittest.TestCase):
    def test_signed_out_copy_matches_both_supported_languages(self):
        expected = (
            "سجّل الدخول بحساب HILTECH للمتابعة.",
            "Sign in with your HILTECH identity to continue.",
        )
        for text in expected:
            with self.subTest(text=text):
                self.assertTrue(has_ui_text(dump(text), expected))

    def test_sign_in_button_requires_its_own_exact_label(self):
        expected = ("تسجيل الدخول", "Sign in")
        self.assertTrue(has_ui_text(dump("تسجيل الدخول"), expected))
        self.assertTrue(has_ui_text(dump("Sign in"), expected))
        self.assertFalse(has_ui_text(dump("Sign in with your HILTECH identity to continue."), expected))

    def test_callback_error_requires_the_expected_reference(self):
        expected = ("المرجع: OIDC_ATTEMPT_MISSING", "Reference: OIDC_ATTEMPT_MISSING")
        for text in expected:
            with self.subTest(text=text):
                self.assertTrue(has_ui_text(dump(text), expected))
        self.assertFalse(has_ui_text(dump("حدث خطأ"), expected))
        self.assertFalse(has_ui_text(dump("لم يكتمل تسجيل الدخول"), expected))
        self.assertFalse(has_ui_text(dump("Reference: OIDC_STATE_MISMATCH"), expected))

    def test_callback_recovery_requires_its_specific_title_and_action(self):
        title = ("لم يكتمل تسجيل الدخول", "Sign-in was not completed")
        action = ("تسجيل الدخول من جديد", "Start sign-in again")
        for labels in (title, action):
            for text in labels:
                with self.subTest(text=text):
                    self.assertTrue(has_ui_text(dump(text), labels))
            for unrelated in ("حدث خطأ", "Something went wrong", "تسجيل الدخول", "Sign in", "Retry"):
                with self.subTest(unrelated=unrelated):
                    self.assertFalse(has_ui_text(dump(unrelated), labels))

    def test_missing_hidden_or_metadata_text_cannot_pass(self):
        expected = ("Sign in",)
        for xml in (
            "",
            "<hierarchy>",
            "<hierarchy />",
            '<hierarchy><node text="" content-desc="Sign in" /></hierarchy>',
            '<hierarchy><node text="Sign in" visible-to-user="false" /></hierarchy>',
            '<hierarchy><!-- Sign in --></hierarchy>',
        ):
            with self.subTest(xml=xml):
                self.assertFalse(has_ui_text(xml, expected))

    def test_empty_expectations_cannot_pass(self):
        self.assertFalse(has_ui_text(dump(""), ()))
        self.assertFalse(has_ui_text(dump(""), ("",)))


def shell_fixture(language="ar", state="signed-out"):
    """An uncompressed UiAutomator hierarchy with text children of tap targets."""
    labels = {
        "ar": ["HILTECH OS", "إدارة أعمال الشركة", "العربية", "English"],
        "en": ["HILTECH OS", "Company operations", "العربية", "English"],
    }[language]
    if state == "signed-out":
        labels += {
            "ar": ["سجّل الدخول بحساب HILTECH للمتابعة.", "تسجيل الدخول"],
            "en": ["Sign in with your HILTECH identity to continue.", "Sign in"],
        }[language]
    else:
        labels += {
            "ar": [
                "لم يكتمل تسجيل الدخول",
                "ابدأ تسجيل الدخول من التطبيق، وأكمل الخطوات في المتصفح، ثم عد إلى HILTECH.",
                "المرجع: OIDC_ATTEMPT_MISSING", "تسجيل الدخول من جديد",
            ],
            "en": [
                "Sign-in was not completed",
                "Start sign-in from the app, complete the steps in your browser, then return to HILTECH.",
                "Reference: OIDC_ATTEMPT_MISSING", "Start sign-in again",
            ],
        }[language]
    root = ET.Element("hierarchy", rotation="0")
    window = ET.SubElement(root, "node", {
        "package": "com.hiltech.android", "bounds": "[0,0][1080,2400]",
    })
    safe = ET.SubElement(window, "node", {
        "package": "com.hiltech.android", "resource-id": "hiltech_android_safe_content",
        "bounds": "[0,72][1080,2328]", "enabled": "true",
    })
    for index, label in enumerate(labels):
        top = 90 + index * 150
        parent = safe
        if label in ("العربية", "English", labels[-1]):
            parent = ET.SubElement(safe, "node", {
                "package": "com.hiltech.android", "bounds": f"[20,{top}][960,{top + 100}]",
                "clickable": "true", "enabled": "true",
            })
        ET.SubElement(parent, "node", {
            "package": "com.hiltech.android", "text": label, "enabled": "true",
            "bounds": f"[30,{top + 10}][950,{top + 80}]", "clickable": "false",
        })
    return root


def xml_of(root):
    return ET.tostring(root, encoding="unicode")


def text_node(root, label):
    return next(node for node in root.iter("node") if node.get("text") == label)


class AndroidShellGeometryTest(unittest.TestCase):
    def test_both_real_states_require_their_localized_headers(self):
        for language in ("ar", "en"):
            for state in ("signed-out", "callback-failure"):
                with self.subTest(language=language, state=state):
                    AndroidShellDump(xml_of(shell_fixture(language, state))).validate(state, language)

    def test_old_clipped_header_cannot_pass_just_because_sign_in_is_visible(self):
        for label in ("HILTECH OS", "إدارة أعمال الشركة"):
            with self.subTest(label=label):
                root = shell_fixture()
                text_node(root, label).set("text", "")
                with self.assertRaisesRegex(ValueError, "Visible text missing"):
                    AndroidShellDump(xml_of(root)).validate("signed-out", "ar")

    def test_header_above_or_partly_outside_safe_bounds_is_rejected(self):
        for rectangle in ("[30,0][950,71]", "[30,60][950,100]", "[30,100][1081,180]"):
            with self.subTest(rectangle=rectangle):
                root = shell_fixture()
                text_node(root, "HILTECH OS").set("bounds", rectangle)
                with self.assertRaises(ValueError):
                    AndroidShellDump(xml_of(root)).validate("signed-out", "ar")

    def test_empty_malformed_and_negative_text_bounds_are_rejected(self):
        for rectangle in ("", "[0,0][0,0]", "[30,100][30,180]", "[-1,100][950,180]", "30,100,950,180"):
            with self.subTest(rectangle=rectangle):
                root = shell_fixture()
                text_node(root, "HILTECH OS").set("bounds", rectangle)
                with self.assertRaises(ValueError):
                    AndroidShellDump(xml_of(root)).validate("signed-out", "ar")

    def test_foreign_package_and_content_description_do_not_supply_missing_header(self):
        for change in ({"package": "other.app"}, {"text": "", "content-desc": "HILTECH OS"}):
            with self.subTest(change=change):
                root = shell_fixture()
                text_node(root, "HILTECH OS").attrib.update(change)
                with self.assertRaises(ValueError):
                    AndroidShellDump(xml_of(root)).validate("signed-out", "ar")

    def test_hidden_ancestor_makes_its_descendant_text_unusable(self):
        root = shell_fixture()
        safe = root[0][0]
        header = text_node(root, "HILTECH OS")
        safe.remove(header)
        hidden = ET.SubElement(safe, "node", {"visible-to-user": "false"})
        hidden.append(header)
        with self.assertRaises(ValueError):
            AndroidShellDump(xml_of(root)).validate("signed-out", "ar")

    def test_safe_region_must_exist_once_with_real_bounds(self):
        for change in ("missing", "duplicate", "empty", "hidden"):
            with self.subTest(change=change):
                root = shell_fixture()
                safe = root[0][0]
                if change == "missing":
                    safe.set("resource-id", "unrelated")
                elif change == "duplicate":
                    ET.SubElement(root[0], "node", safe.attrib.copy())
                elif change == "empty":
                    safe.set("bounds", "[0,0][0,0]")
                else:
                    safe.set("visible-to-user", "false")
                with self.assertRaises(ValueError):
                    AndroidShellDump(xml_of(root))

    def test_native_action_bar_is_rejected_even_if_compose_copy_is_present(self):
        for resource in ("android:id/action_bar", "android:id/action_bar_container", "android:id/action_bar_title"):
            with self.subTest(resource=resource):
                root = shell_fixture()
                ET.SubElement(root[0], "node", {
                    "package": "com.hiltech.android", "resource-id": resource,
                    "text": "HILTECH", "bounds": "[0,72][1080,276]",
                })
                with self.assertRaisesRegex(ValueError, "native ActionBar"):
                    AndroidShellDump(xml_of(root))

    def test_language_tap_uses_observed_clickable_ancestor_bounds(self):
        shell = AndroidShellDump(xml_of(shell_fixture()))
        self.assertEqual((490, 590), shell.tap_center("English"))
        self.assertEqual((490, 440), shell.tap_center("العربية"))

    def test_language_tap_requires_enabled_clickable_target(self):
        for change in ("disabled", "not-clickable", "outside"):
            with self.subTest(change=change):
                root = shell_fixture()
                target = next(node for node in root.iter("node") if text_node(root, "English") in list(node))
                if change == "disabled":
                    target.set("enabled", "false")
                elif change == "not-clickable":
                    target.set("clickable", "false")
                else:
                    target.set("bounds", "[0,0][1080,2400]")
                with self.assertRaises(ValueError):
                    AndroidShellDump(xml_of(root)).tap_center("English")

    def test_ambiguous_language_controls_are_rejected(self):
        root = shell_fixture()
        ET.SubElement(root[0][0], "node", {
            "package": "com.hiltech.android", "clickable": "true", "enabled": "true",
            "text": "English", "bounds": "[30,2000][950,2100]",
        })
        with self.assertRaises(ValueError):
            AndroidShellDump(xml_of(root)).tap_center("English")

    def test_callback_failure_does_not_accept_wrong_reference_or_signed_out_state(self):
        root = shell_fixture("en", "callback-failure")
        text_node(root, "Reference: OIDC_ATTEMPT_MISSING").set("text", "Reference: OIDC_STATE_MISMATCH")
        with self.assertRaises(ValueError):
            AndroidShellDump(xml_of(root)).validate("callback-failure", "en")
        with self.assertRaises(ValueError):
            AndroidShellDump(xml_of(shell_fixture("en"))).validate("callback-failure", "en")

    def test_language_switch_cannot_pass_with_the_previous_language(self):
        with self.assertRaises(ValueError):
            AndroidShellDump(xml_of(shell_fixture("ar"))).validate("signed-out", "en")

    def test_malformed_or_non_hierarchy_xml_fails_closed(self):
        for xml in ("", "<hierarchy>", "<node />"):
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                AndroidShellDump(xml)

    def test_cli_returns_observed_coordinates_and_fails_for_wrong_language(self):
        for language, expected_code, expected_output in (("ar", 0, "490 590\n"), ("en", 1, "")):
            with self.subTest(language=language):
                output = io.StringIO()
                with patch("sys.argv", ["ui-check", "--state", "signed-out", "--language", language, "--tap-text", "English"]), \
                        patch("sys.stdin", io.StringIO(xml_of(shell_fixture()))), \
                        redirect_stdout(output), redirect_stderr(io.StringIO()):
                    self.assertEqual(expected_code, main())
                self.assertEqual(expected_output, output.getvalue())


if __name__ == "__main__":
    unittest.main()
