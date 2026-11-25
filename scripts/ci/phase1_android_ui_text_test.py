"""Regression checks for the localized production OIDC smoke assertions."""

import unittest
from xml.sax.saxutils import quoteattr

from phase1_android_ui_text import has_ui_text


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


if __name__ == "__main__":
    unittest.main()
