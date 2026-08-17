import unittest
from upstream_normalize import apply_substitutions


class TestSubstitutions(unittest.TestCase):
    def test_package_declaration(self):
        self.assertEqual(
            apply_substitutions("package com.overdrive.app.server;"),
            "package app.wheelstop.android.server;")

    def test_jni_symbol(self):
        self.assertEqual(
            apply_substitutions("Java_com_overdrive_app_NativeMotion_init"),
            "Java_app_wheelstop_android_NativeMotion_init")

    def test_underscore_form_not_degraded_by_prefix_rule(self):
        # Regression: `overdrive_` -> `wheelstop_` must not fire first and
        # turn com_overdrive_app into com_wheelstop_app.
        self.assertEqual(
            apply_substitutions("com_overdrive_app"), "app_wheelstop_android")

    def test_runtime_identifier_prefix(self):
        self.assertEqual(
            apply_substitutions("/data/local/tmp/overdrive_config.json"),
            "/data/local/tmp/wheelstop_config.json")

    def test_mqtt_topic_root(self):
        self.assertEqual(
            apply_substitutions("overdrive/vehicle/telemetry"),
            "wheelstop/vehicle/telemetry")

    def test_display_strings_are_left_alone(self):
        # MUST-PRESERVE: storage folder, KD_SALT, BUNDLE_FORMAT depend on this.
        for s in ['name.equals("Overdrive")', 'String BRAND = "Overdrive";']:
            self.assertEqual(apply_substitutions(s), s)

    def test_class_rename(self):
        self.assertEqual(
            apply_substitutions("class OverdriveApplication"),
            "class WheelstopApplication")


if __name__ == "__main__":
    unittest.main()
