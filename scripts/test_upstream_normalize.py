import os
import pathlib
import tempfile
import unittest
from upstream_normalize import (
    REWRITE_CATALOGUE,
    REWRITE_FULL,
    REWRITE_NONE,
    apply_substitutions,
    apply_substitutions_to_catalogue,
    in_scope,
    normalize_tree,
    rewrite_mode,
    should_rewrite,
)


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

    def test_server_i18n_is_in_scope_but_not_rewritten(self):
        # R6: server-i18n is read at runtime by string key (Messages.get),
        # not a compile-time symbol, so an upstream key that never syncs
        # would silently fall back to a missing string on a real car rather
        # than fail the build -- it must be reachable via SCOPE. Its values
        # are still prose, so should_rewrite stays False for it.
        self.assertTrue(in_scope("app/src/main/assets/server-i18n/en.json"))
        self.assertFalse(should_rewrite("app/src/main/assets/server-i18n/en.json"))

    # --- R1: res/ joins SCOPE -------------------------------------------
    # Excluding res/ fails the build outright: Kotlin/Java bind R.id/R.string
    # at compile time.

    def test_res_layout_is_in_scope(self):
        self.assertTrue(in_scope("app/src/main/res/layout/activity_main.xml"))

    def test_res_default_strings_is_in_scope(self):
        self.assertTrue(in_scope("app/src/main/res/values/strings.xml"))

    def test_res_locale_variant_strings_is_in_scope(self):
        self.assertTrue(in_scope("app/src/main/res/values-de/strings.xml"))
        self.assertTrue(should_rewrite("app/src/main/res/values-de/strings.xml"))

    def test_every_strings_catalogue_shares_one_rewrite_rule(self):
        # The default catalogue and its locale variants used to be split
        # (values/ = NO_REWRITE, values-*/ = rewritten whole) on the false
        # premise that only the default one holds identifiers. Upstream's
        # values-de/strings.xml carries the same `name="overdrive_*"` keys,
        # so the split let a new upstream resource arrive under two
        # different names in two catalogues -- and under a third in the
        # code that binds it. One rule, both halves.
        for rel in ("app/src/main/res/values/strings.xml",
                    "app/src/main/res/values-de/strings.xml",
                    "app/src/main/res/values-night/strings.xml"):
            self.assertEqual(rewrite_mode(rel), REWRITE_CATALOGUE, rel)

    def test_non_catalogue_res_xml_still_takes_the_full_rewrite(self):
        self.assertEqual(rewrite_mode("app/src/main/res/values/colors_m3.xml"),
                         REWRITE_FULL)
        self.assertEqual(rewrite_mode("app/src/main/res/layout/main.xml"),
                         REWRITE_FULL)

    # --- R2: NEVER_SYNC (brand assets never take upstream's version) ----

    def test_res_mipmap_is_never_synced(self):
        self.assertFalse(in_scope("app/src/main/res/mipmap-hdpi/ic_launcher.png"))

    def test_colors_m3_light_and_dark_share_one_rule(self):
        # The two halves of the M3 palette must be treated identically.
        # Pinning only the light half (it used to be NEVER_SYNC while
        # values-night/ was not) meant upstream's semantic colour aliases
        # landed in the dark configuration only, while the synced
        # themes_wheelstop.xml referenced them from the default one --
        # a dangling @color reference outside any conflict marker.
        light = "app/src/main/res/values/colors_m3.xml"
        dark = "app/src/main/res/values-night/colors_m3.xml"
        self.assertEqual(in_scope(light), in_scope(dark))
        self.assertEqual(rewrite_mode(light), rewrite_mode(dark))
        self.assertTrue(in_scope(light))

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

    def test_out_of_scope_path_is_never_rewritten(self):
        self.assertFalse(should_rewrite("app/build.gradle.kts"))
        self.assertEqual(rewrite_mode("app/build.gradle.kts"), REWRITE_NONE)

    def test_ordering_regression_holds_for_scope_paths(self):
        # Same regression as Task 2: `overdrive_` must not fire before the
        # more specific underscore-joined package form.
        self.assertEqual(
            apply_substitutions("com_overdrive_app"), "app_wheelstop_android")


class TestCatalogueRewrite(unittest.TestCase):
    """res/values*/strings.xml: the resource KEY is a compile-time symbol,
    the body is wording, and the two take different substitution sets."""

    def test_resource_key_is_rewritten(self):
        self.assertEqual(
            apply_substitutions_to_catalogue(
                '    <string name="overdrive_status_summary_title">Overdrive'
                '</string>\n'),
            '    <string name="wheelstop_status_summary_title">Overdrive'
            '</string>\n')

    def test_brand_wording_is_left_for_the_locale_merger_to_sweep(self):
        # SUBSTITUTIONS carries no bare-brand rule, so the transform must
        # not be what rebrands wording -- upstream_merge_locales.brand_sweep
        # is, and it is separately reviewable.
        self.assertIn(
            ">Overdrive is running<",
            apply_substitutions_to_catalogue(
                '<string name="a">Overdrive is running</string>\n'))

    def test_identifier_inside_a_value_is_rewritten(self):
        # Upstream really ships this one: the PIN-recovery sentinel, whose
        # Java side (PinManager.RESET_FLAG_FILE) the transform rewrites. A
        # key-only rule would print a path the app no longer watches.
        self.assertIn(
            "/data/local/tmp/.wheelstop_pin_reset",
            apply_substitutions_to_catalogue(
                '<string name="settings_security_recovery_command">'
                'adb shell touch /data/local/tmp/.overdrive_pin_reset'
                '</string>\n'))

    def test_old_package_name_in_wording_is_preserved(self):
        # `com.overdrive.app` is the OLD app's package -- a MUST-PRESERVE
        # literal elsewhere (exclusivity-preflight-old-package) and a real
        # thing a user may be told to look for. It survives in a value...
        text = apply_substitutions_to_catalogue(
            '<string name="a">Uninstall com.overdrive.app first</string>\n')
        self.assertIn("Uninstall com.overdrive.app first", text)

    def test_old_package_name_in_a_key_is_still_rewritten(self):
        # ...but a key is a symbol, never wording, so the full set applies.
        self.assertIn(
            'name="app_wheelstop_android_notice"',
            apply_substitutions_to_catalogue(
                '<string name="com_overdrive_app_notice">x</string>\n'))

    def test_normalize_tree_uses_the_catalogue_rule_for_both_halves(self):
        entry = ('<resources>\n    <string name="overdrive_a">'
                 'Uninstall com.overdrive.app</string>\n</resources>\n')
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            for rel in ("app/src/main/res/values/strings.xml",
                        "app/src/main/res/values-de/strings.xml"):
                p = root / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(entry, encoding="utf-8")
            rewritten, _moved = normalize_tree(root)
            self.assertEqual(rewritten, 2)
            for rel in ("app/src/main/res/values/strings.xml",
                        "app/src/main/res/values-de/strings.xml"):
                got = (root / rel).read_text(encoding="utf-8")
                self.assertIn('name="wheelstop_a"', got)
                self.assertIn("Uninstall com.overdrive.app", got)


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

    def test_svg_content_is_rewritten(self):
        # .svg joins TEXT_SUFFIXES: robustness for future upstream files
        # (the three SVGs currently vendored contain no identifiers).
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            src = root / "app/src/main/assets/web/icon.svg"
            src.parent.mkdir(parents=True)
            src.write_text('<svg data-app="com.overdrive.app"></svg>',
                            encoding="utf-8")
            rewritten, moved = normalize_tree(root)
            self.assertEqual(src.read_text(encoding="utf-8"),
                             '<svg data-app="app.wheelstop.android"></svg>')
            self.assertEqual((rewritten, moved), (1, 0))

    def test_never_sync_file_is_left_completely_alone(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            src = root / "app/src/main/res/drawable/ic_sidebar_logo.xml"
            src.parent.mkdir(parents=True)
            src.write_text('<vector android:name="overdrive_logo"/>',
                            encoding="utf-8")
            rewritten, moved = normalize_tree(root)
            self.assertEqual(src.read_text(encoding="utf-8"),
                             '<vector android:name="overdrive_logo"/>')
            self.assertEqual((rewritten, moved), (0, 0))


if __name__ == "__main__":
    unittest.main()
