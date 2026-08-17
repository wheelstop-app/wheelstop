import os
import pathlib
import tempfile
import unittest
from upstream_normalize import apply_substitutions, in_scope, normalize_tree, should_rewrite


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


class TestScope(unittest.TestCase):
    def test_java_is_in_scope(self):
        self.assertTrue(in_scope("app/src/main/java/com/overdrive/app/X.java"))

    def test_web_is_in_scope(self):
        self.assertTrue(in_scope("app/src/main/assets/web/local/app.js"))

    def test_tests_are_in_scope(self):
        self.assertTrue(in_scope("app/src/test/java/com/overdrive/app/XTest.java"))

    def test_build_config_is_out_of_scope(self):
        self.assertFalse(in_scope("app/build.gradle.kts"))

    def test_server_i18n_is_out_of_scope(self):
        # Not under any SCOPE prefix (only assets/web/ is scoped, not
        # assets/server-i18n/), independent of the NO_REWRITE/NEVER_SYNC split.
        self.assertFalse(in_scope("app/src/main/assets/server-i18n/en.json"))

    # --- R1: res/ joins SCOPE -------------------------------------------
    # Excluding res/ fails the build outright: Kotlin/Java bind R.id/R.string
    # at compile time.

    def test_res_layout_is_in_scope(self):
        self.assertTrue(in_scope("app/src/main/res/layout/activity_main.xml"))

    def test_res_default_strings_is_in_scope(self):
        self.assertTrue(in_scope("app/src/main/res/values/strings.xml"))

    def test_res_locale_variant_strings_is_in_scope(self):
        # Only the default values/strings.xml is NO_REWRITE; values-*/
        # variants are handled by a later-task locale merger, so ordinary
        # scope/rewrite rules apply to them here.
        self.assertTrue(in_scope("app/src/main/res/values-de/strings.xml"))
        self.assertTrue(should_rewrite("app/src/main/res/values-de/strings.xml"))

    # --- R2: NEVER_SYNC (brand assets never take upstream's version) ----

    def test_res_mipmap_is_never_synced(self):
        self.assertFalse(in_scope("app/src/main/res/mipmap-hdpi/ic_launcher.png"))

    def test_res_colors_m3_is_never_synced(self):
        self.assertFalse(in_scope("app/src/main/res/values/colors_m3.xml"))

    def test_res_launcher_drawables_are_never_synced(self):
        self.assertFalse(in_scope(
            "app/src/main/res/drawable/ic_launcher_background.xml"))
        self.assertFalse(in_scope(
            "app/src/main/res/drawable/ic_launcher_foreground.xml"))
        self.assertFalse(in_scope(
            "app/src/main/res/drawable/ic_sidebar_logo.xml"))

    # --- R2: NO_REWRITE (synced, but identifiers in content untouched) --

    def test_web_i18n_is_in_scope_but_not_rewritten(self):
        self.assertTrue(in_scope("app/src/main/assets/web/i18n/en.json"))
        self.assertFalse(should_rewrite("app/src/main/assets/web/i18n/en.json"))

    def test_res_default_strings_is_not_rewritten(self):
        self.assertFalse(should_rewrite("app/src/main/res/values/strings.xml"))

    def test_out_of_scope_path_is_never_rewritten(self):
        self.assertFalse(should_rewrite("app/build.gradle.kts"))

    def test_ordering_regression_holds_for_scope_paths(self):
        # Same regression as Task 2: `overdrive_` must not fire before the
        # more specific underscore-joined package form.
        self.assertEqual(
            apply_substitutions("com_overdrive_app"), "app_wheelstop_android")


class TestNormalizeTree(unittest.TestCase):
    def test_moves_and_rewrites_in_scope_file(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            src = root / "app/src/main/java/com/overdrive/app/Foo.java"
            src.parent.mkdir(parents=True)
            src.write_text("package com.overdrive.app;\n", encoding="utf-8")
            rewritten, moved = normalize_tree(root)
            dst = root / "app/src/main/java/app/wheelstop/android/Foo.java"
            self.assertTrue(dst.exists())
            self.assertFalse(src.exists())
            self.assertEqual(dst.read_text(encoding="utf-8"),
                             "package app.wheelstop.android;\n")
            self.assertEqual((rewritten, moved), (1, 1))

    def test_no_rewrite_file_keeps_content_but_is_still_synced(self):
        # R2: locale catalogues are in-scope (upstream's new keys must
        # arrive) but NO_REWRITE -- identifier substitution must not touch
        # their content.
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            src = root / "app/src/main/assets/web/i18n/en.json"
            src.parent.mkdir(parents=True)
            src.write_text('{"a":"com.overdrive.app"}', encoding="utf-8")
            rewritten, moved = normalize_tree(root)
            self.assertEqual(src.read_text(encoding="utf-8"),
                             '{"a":"com.overdrive.app"}')
            self.assertEqual((rewritten, moved), (0, 0))

    def test_never_sync_file_is_left_completely_alone(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            src = root / "app/src/main/res/values/colors_m3.xml"
            src.parent.mkdir(parents=True)
            src.write_text('<color name="overdrive_primary">#000</color>',
                            encoding="utf-8")
            rewritten, moved = normalize_tree(root)
            self.assertEqual(src.read_text(encoding="utf-8"),
                             '<color name="overdrive_primary">#000</color>')
            self.assertEqual((rewritten, moved), (0, 0))


if __name__ == "__main__":
    unittest.main()
