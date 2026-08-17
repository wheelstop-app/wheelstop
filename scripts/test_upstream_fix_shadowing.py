import unittest

from upstream_fix_shadowing import fix_text, needs_fix


def java(*body_lines, package="app.wheelstop.android.ui"):
    head = f"package {package};\n\n"
    return head + "".join(body_lines)


def kotlin(*body_lines, package="app.wheelstop.android.ui"):
    head = f"package {package}\n\n"
    return head + "".join(body_lines)


class TestNeedsFix(unittest.TestCase):
    def test_no_app_declaration_means_no_fix_needed(self):
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject config = new JSONObject();\n",
            "        config.put(\"a\", 1);\n",
            "    }\n",
            "}\n",
        )
        self.assertFalse(needs_fix(text))
        # Nothing to do -- fix_text must be a byte-identical no-op too.
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertEqual(new_text, text)
        self.assertEqual(imports_added, [])

    def test_java_local_declaration_is_detected(self):
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "    }\n",
            "}\n",
        )
        self.assertTrue(needs_fix(text))

    def test_java_parameter_declaration_is_detected(self):
        text = java(
            "public class Foo {\n",
            "    void bar(JSONObject app) {\n",
            "    }\n",
            "}\n",
        )
        self.assertTrue(needs_fix(text))

    def test_kotlin_val_declaration_is_detected(self):
        text = kotlin(
            "fun bar() {\n",
            "    val app = JSONObject()\n",
            "}\n",
        )
        self.assertTrue(needs_fix(text))

    def test_kotlin_parameter_declaration_is_detected(self):
        text = kotlin(
            "fun bar(app: JSONObject) {\n",
            "}\n",
        )
        self.assertTrue(needs_fix(text))


class TestImportLineRegression(unittest.TestCase):
    """The regression that broke 43 files on a previous attempt: rewriting
    FQNs inside `import` statements themselves, turning
    `import app.wheelstop.android.foo.Bar;` into the broken `import Bar;`.
    Any line whose first non-whitespace token is `import` must survive
    byte-identical, no matter what else is in the file.
    """

    def test_import_line_survives_byte_identical(self):
        import_line = "import app.wheelstop.android.foo.Bar;\n"
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "        Bar.doThing();\n",
            "    }\n",
            "}\n",
        )
        # Splice the import in after the package line, as it would appear
        # in a real file.
        head, rest = text.split("\n\n", 1)
        text = head + "\n\n" + import_line + "\n" + rest

        new_text, _imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertIn(import_line, new_text)

    def test_indented_import_line_survives_byte_identical(self):
        # Belt-and-braces: an oddly-indented import line (still the first
        # non-whitespace token on its line) must also be left alone.
        import_line = "  import app.wheelstop.android.foo.Bar;\n"
        text = (
            "package app.wheelstop.android.ui;\n\n"
            + import_line
            + "\npublic class Foo {\n"
            "    void bar() {\n"
            "        JSONObject app = new JSONObject();\n"
            "    }\n"
            "}\n"
        )
        new_text, _imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertIn(import_line, new_text)


class TestBodyRewrite(unittest.TestCase):
    def test_fqn_in_body_becomes_simple_name_and_gains_import(self):
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "        app.wheelstop.android.config.UnifiedConfigManager.getAppearance();\n",
            "    }\n",
            "}\n",
            package="app.wheelstop.android.ui",
        )
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertIn("        UnifiedConfigManager.getAppearance();\n", new_text)
        self.assertNotIn(
            "        app.wheelstop.android.config.UnifiedConfigManager", new_text)
        self.assertEqual(
            imports_added, ["app.wheelstop.android.config.UnifiedConfigManager"])
        self.assertIn(
            "import app.wheelstop.android.config.UnifiedConfigManager;", new_text)

    def test_same_package_class_rewritten_but_no_import_added(self):
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "        app.wheelstop.android.ui.Helper.run();\n",
            "    }\n",
            "}\n",
            package="app.wheelstop.android.ui",
        )
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertIn("Helper.run();", new_text)
        self.assertNotIn("app.wheelstop.android.ui.Helper", new_text)
        self.assertEqual(imports_added, [])
        self.assertNotIn("import ", new_text)  # no import line was inserted at all

    def test_simple_name_bound_by_different_import_stays_qualified(self):
        text = (
            "package app.wheelstop.android.ui;\n\n"
            "import app.wheelstop.android.other.Bar;\n\n"
            "public class Foo {\n"
            "    void bar() {\n"
            "        JSONObject app = new JSONObject();\n"
            "        app.wheelstop.android.foo.Bar.doThing();\n"
            "    }\n"
            "}\n"
        )
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        # The body reference must stay fully qualified -- rewriting it to
        # bare `Bar` would silently resolve to the OTHER Bar (the imported
        # one), which is a different class.
        self.assertIn("app.wheelstop.android.foo.Bar.doThing();", new_text)
        self.assertEqual(imports_added, [])
        # The pre-existing import is untouched.
        self.assertIn("import app.wheelstop.android.other.Bar;", new_text)

    def test_two_distinct_fqns_sharing_a_simple_name_both_stay_qualified(self):
        # Same-file collision with no pre-existing import at all: two
        # different FQNs both simple-named `Handler`. Rewriting either one
        # unqualified is ambiguous (only one import can exist), so both
        # must be left alone rather than silently picking a winner.
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "        app.wheelstop.android.alpha.Handler.a();\n",
            "        app.wheelstop.android.beta.Handler.b();\n",
            "    }\n",
            "}\n",
            package="app.wheelstop.android.ui",
        )
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertIn("app.wheelstop.android.alpha.Handler.a();", new_text)
        self.assertIn("app.wheelstop.android.beta.Handler.b();", new_text)
        self.assertEqual(imports_added, [])


class TestStringsAndComments(unittest.TestCase):
    def test_fqn_inside_string_literal_is_not_rewritten(self):
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "        Log.d(\"tag\", \"see app.wheelstop.android.config.Thing for details\");\n",
            "    }\n",
            "}\n",
            package="app.wheelstop.android.ui",
        )
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertIn(
            "\"see app.wheelstop.android.config.Thing for details\"", new_text)
        self.assertEqual(imports_added, [])

    def test_fqn_inside_line_comment_is_not_rewritten(self):
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "        // see app.wheelstop.android.config.Thing\n",
            "    }\n",
            "}\n",
            package="app.wheelstop.android.ui",
        )
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertIn("// see app.wheelstop.android.config.Thing", new_text)
        self.assertEqual(imports_added, [])

    def test_fqn_inside_block_comment_is_not_rewritten(self):
        text = java(
            "public class Foo {\n",
            "    /**\n",
            "     * See app.wheelstop.android.config.Thing for details.\n",
            "     */\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "    }\n",
            "}\n",
            package="app.wheelstop.android.ui",
        )
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertIn(
            "     * See app.wheelstop.android.config.Thing for details.\n", new_text)
        self.assertEqual(imports_added, [])


class TestKotlin(unittest.TestCase):
    def test_kotlin_import_added_without_trailing_semicolon(self):
        text = kotlin(
            "fun bar(app: JSONObject) {\n",
            "    app.wheelstop.android.config.UnifiedConfigManager.getAppearance()\n",
            "}\n",
            package="app.wheelstop.android.ui",
        )
        new_text, imports_added = fix_text(text, "app.wheelstop.android.ui")
        self.assertEqual(
            imports_added, ["app.wheelstop.android.config.UnifiedConfigManager"])
        self.assertIn(
            "import app.wheelstop.android.config.UnifiedConfigManager\n", new_text)
        self.assertNotIn(
            "import app.wheelstop.android.config.UnifiedConfigManager;", new_text)
        self.assertIn("UnifiedConfigManager.getAppearance()", new_text)


class TestIdempotency(unittest.TestCase):
    def test_running_twice_is_stable(self):
        text = java(
            "public class Foo {\n",
            "    void bar() {\n",
            "        JSONObject app = new JSONObject();\n",
            "        app.wheelstop.android.config.UnifiedConfigManager.getAppearance();\n",
            "    }\n",
            "}\n",
            package="app.wheelstop.android.ui",
        )
        once, _ = fix_text(text, "app.wheelstop.android.ui")
        twice, imports_added_second = fix_text(once, "app.wheelstop.android.ui")
        self.assertEqual(once, twice)
        self.assertEqual(imports_added_second, [])


if __name__ == "__main__":
    unittest.main()
