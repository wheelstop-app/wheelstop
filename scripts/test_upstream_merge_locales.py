import json
import pathlib
import subprocess
import tempfile
import unittest

from upstream_merge_locales import (
    brand_sweep,
    discover_locale_paths,
    main,
    merge_json,
    merge_xml,
    parse_xml_entries,
)


def xml_doc(*entries):
    body = "".join(entries)
    return f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n{body}</resources>\n'


class TestMergeXml(unittest.TestCase):
    def test_independent_adds_do_not_conflict(self):
        # We rebrand key_a; upstream, on its own branch, adds a brand-new
        # key_b. Neither side touched the other's key -- no clash.
        base = xml_doc(
            '    <string name="key_a">Base A</string>\n',
            '    <string name="key_c">Base C</string>\n',
        )
        ours = xml_doc(
            '    <string name="key_a">Wheelstop A</string>\n',
            '    <string name="key_c">Base C</string>\n',
        )
        theirs = xml_doc(
            '    <string name="key_a">Base A</string>\n',
            '    <string name="key_b">New B</string>\n',
            '    <string name="key_c">Base C</string>\n',
        )
        merged, clashes = merge_xml(base, ours, theirs)
        self.assertEqual(clashes, [])
        self.assertIn('<string name="key_a">Wheelstop A</string>', merged)
        self.assertIn('<string name="key_b">New B</string>', merged)
        self.assertIn('<string name="key_c">Base C</string>', merged)

    def test_our_value_survives_same_key_edit_and_is_reported_as_clash(self):
        base = xml_doc('    <string name="key_a">Base A</string>\n')
        ours = xml_doc('    <string name="key_a">Wheelstop A</string>\n')
        theirs = xml_doc('    <string name="key_a">Overdrive A Updated</string>\n')
        merged, clashes = merge_xml(base, ours, theirs)
        self.assertIn('<string name="key_a">Wheelstop A</string>', merged)
        self.assertNotIn("Overdrive A Updated", merged)
        self.assertEqual(clashes, ["key_a"])

    def test_brand_new_upstream_key_is_added(self):
        base = xml_doc('    <string name="key_a">Base A</string>\n')
        ours = xml_doc('    <string name="key_a">Base A</string>\n')
        theirs = xml_doc(
            '    <string name="key_a">Base A</string>\n',
            '    <string name="key_new">Brand New</string>\n',
        )
        merged, clashes = merge_xml(base, ours, theirs)
        self.assertIn('<string name="key_new">Brand New</string>', merged)
        self.assertEqual(clashes, [])

    def test_key_deleted_upstream_and_never_touched_by_us_is_dropped(self):
        base = xml_doc(
            '    <string name="key_a">Base A</string>\n',
            '    <string name="key_d">Base D</string>\n',
        )
        ours = xml_doc(
            '    <string name="key_a">Base A</string>\n',
            '    <string name="key_d">Base D</string>\n',
        )
        theirs = xml_doc('    <string name="key_a">Base A</string>\n')
        merged, clashes = merge_xml(base, ours, theirs)
        self.assertNotIn("key_d", merged)
        self.assertEqual(clashes, [])

    def test_key_deleted_upstream_but_changed_by_us_is_kept(self):
        base = xml_doc('    <string name="key_e">Base E</string>\n')
        ours = xml_doc('    <string name="key_e">Wheelstop E</string>\n')
        theirs = xml_doc("")
        merged, clashes = merge_xml(base, ours, theirs)
        self.assertIn('<string name="key_e">Wheelstop E</string>', merged)
        self.assertEqual(clashes, [])

    def test_string_array_and_plurals_blocks_preserved_intact(self):
        array_block = (
            '    <string-array name="weekdays">\n'
            "        <item>Mon</item>\n"
            "        <item>Tue</item>\n"
            "    </string-array>\n"
        )
        plurals_block = (
            '    <plurals name="clip_count">\n'
            '        <item quantity="one">%d clip</item>\n'
            '        <item quantity="other">%d clips</item>\n'
            "    </plurals>\n"
        )
        base = xml_doc(array_block, plurals_block)
        ours = xml_doc(array_block, plurals_block)
        theirs = xml_doc(array_block, plurals_block)
        merged, clashes = merge_xml(base, ours, theirs)
        self.assertIn(array_block, merged)
        self.assertIn(plurals_block, merged)
        self.assertEqual(clashes, [])

    def test_self_merge_is_byte_identical(self):
        # R7 regression: a logical no-op merge (base == ours == theirs)
        # must return the input BYTE-FOR-BYTE identical, including every
        # comment -- free-standing comments between entries, a comment in
        # the header, and one just before the closing tag. Earlier, the
        # merger reconstructed the file from parsed <string>/<string-array>/
        # <plurals> fragments (head + entries + tail), which silently
        # dropped anything living in the gaps between entries. On the live
        # branch this was built from, that stripped all 7140 XML comments
        # across 34 strings.xml files on a single by-hand merge run.
        text = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Header comment: generated file, do not hand-edit. -->\n"
            "<resources>\n"
            "    <!-- Common buttons / labels -->\n"
            '    <string name="key_a">A</string>\n'
            "\n"
            "    <!-- Section: navigation -->\n"
            '    <string name="key_b">B</string>\n'
            "    <!-- trailing comment before close -->\n"
            "</resources>\n"
        )
        merged, clashes = merge_xml(text, text, text)
        self.assertEqual(merged, text)
        self.assertEqual(clashes, [])

    def test_theirs_entry_splice_leaves_comment_above_it_intact(self):
        # An entry taken from theirs (upstream edited it, we didn't) must
        # be spliced into its own span only -- the section-header comment
        # immediately above it, which lives in the untouched gap before the
        # entry's span, must survive verbatim.
        base = xml_doc(
            "    <!-- Section: navigation -->\n",
            '    <string name="key_a">Base A</string>\n',
        )
        ours = base  # we never touched key_a
        theirs = xml_doc(
            "    <!-- Section: navigation -->\n",
            '    <string name="key_a">Upstream A</string>\n',
        )
        merged, clashes = merge_xml(base, ours, theirs)
        self.assertIn("<!-- Section: navigation -->", merged)
        self.assertIn('<string name="key_a">Upstream A</string>', merged)
        self.assertEqual(clashes, [])


class TestMergeJson(unittest.TestCase):
    def test_nested_objects_merge_per_leaf_not_wholesale(self):
        base = {"common": {"save": "Base Save", "cancel": "Base Cancel"},
                "nav": {"home": "Base Home"}}
        ours = {"common": {"save": "Wheelstop Save", "cancel": "Base Cancel"},
                "nav": {"home": "Base Home"}}
        theirs = {"common": {"save": "Base Save", "cancel": "Base Cancel",
                              "delete": "New Delete"},
                  "nav": {"home": "Base Home"}}
        merged, clashes = merge_json(base, ours, theirs)
        # ours' rebrand of "save" survived, theirs' new "delete" arrived,
        # and "cancel"/"nav" (untouched by anyone) are unaffected -- proof
        # the "common" subtree was merged leaf-by-leaf, not replaced by
        # either side wholesale.
        self.assertEqual(merged["common"]["save"], "Wheelstop Save")
        self.assertEqual(merged["common"]["delete"], "New Delete")
        self.assertEqual(merged["common"]["cancel"], "Base Cancel")
        self.assertEqual(merged["nav"]["home"], "Base Home")
        self.assertEqual(clashes, [])

    def test_our_value_survives_same_leaf_edit_and_is_reported_as_clash(self):
        base = {"common": {"save": "Base Save"}}
        ours = {"common": {"save": "Wheelstop Save"}}
        theirs = {"common": {"save": "Overdrive Save Updated"}}
        merged, clashes = merge_json(base, ours, theirs)
        self.assertEqual(merged["common"]["save"], "Wheelstop Save")
        self.assertEqual(clashes, ["common.save"])

    def test_brand_new_upstream_key_is_added(self):
        base = {"common": {"save": "Base Save"}}
        ours = {"common": {"save": "Base Save"}}
        theirs = {"common": {"save": "Base Save", "delete": "Brand New"}}
        merged, clashes = merge_json(base, ours, theirs)
        self.assertEqual(merged["common"]["delete"], "Brand New")
        self.assertEqual(clashes, [])

    def test_key_deleted_upstream_and_never_touched_by_us_is_dropped(self):
        base = {"common": {"save": "Base Save", "old_key": "Base Old"}}
        ours = {"common": {"save": "Base Save", "old_key": "Base Old"}}
        theirs = {"common": {"save": "Base Save"}}
        merged, clashes = merge_json(base, ours, theirs)
        self.assertNotIn("old_key", merged["common"])
        self.assertEqual(clashes, [])

    def test_key_deleted_upstream_but_changed_by_us_is_kept(self):
        base = {"common": {"legacy": "Base Legacy"}}
        ours = {"common": {"legacy": "Wheelstop Legacy"}}
        theirs = {"common": {}}
        merged, clashes = merge_json(base, ours, theirs)
        self.assertEqual(merged["common"]["legacy"], "Wheelstop Legacy")
        self.assertEqual(clashes, [])


class TestBrandSweep(unittest.TestCase):
    def test_xml_value_rewritten_key_name_untouched(self):
        text = '    <string name="overdrive_mode">Overdrive Mode</string>\n'
        result, n = brand_sweep(text)
        self.assertIn('name="overdrive_mode"', result)  # key untouched
        self.assertIn(">Wheelstop Mode<", result)         # value rewritten
        self.assertNotIn("Overdrive", result)
        self.assertEqual(n, 1)

    def test_xml_sweeps_both_capitalizations(self):
        text = xml_doc(
            '    <string name="a">OverDrive Pro</string>\n',
            '    <string name="b">Overdrive Basic</string>\n',
        )
        result, n = brand_sweep(text)
        self.assertIn(">Wheelstop Pro<", result)
        self.assertIn(">Wheelstop Basic<", result)
        self.assertEqual(n, 2)

    def test_json_value_rewritten_key_name_untouched(self):
        obj = {
            "overdrive_mode": "Enable Overdrive Mode",
            "nested": {"label": "OverDrive Pro"},
        }
        result, n = brand_sweep(obj)
        self.assertIn("overdrive_mode", result)  # key literally unchanged
        self.assertEqual(result["overdrive_mode"], "Enable Wheelstop Mode")
        self.assertEqual(result["nested"]["label"], "Wheelstop Pro")
        self.assertEqual(n, 2)

    def test_json_sweep_preserves_list_values(self):
        obj = {"items": ["Overdrive One", "plain two"]}
        result, n = brand_sweep(obj)
        self.assertEqual(result["items"], ["Wheelstop One", "plain two"])
        self.assertEqual(n, 1)

    def test_no_brand_terms_is_a_no_op(self):
        result, n = brand_sweep(xml_doc('    <string name="a">Plain</string>\n'))
        self.assertEqual(n, 0)
        obj_result, obj_n = brand_sweep({"a": "Plain"})
        self.assertEqual(obj_n, 0)
        self.assertEqual(obj_result, {"a": "Plain"})


class TestParseXmlEntries(unittest.TestCase):
    def test_self_closing_entry_is_captured(self):
        text = xml_doc('    <string name="k" translatable="false"/>\n')
        entries = parse_xml_entries(text)
        self.assertIn("k", entries)

    def test_none_input_yields_empty(self):
        self.assertEqual(parse_xml_entries(None), {})


class TestCliIntegration(unittest.TestCase):
    """End-to-end: a real git repo, two refs, a working-tree file, run
    through main() -- exercises discovery, git-ref reads, both catalogue
    shapes, brand sweep and non-ASCII-safe JSON writing together."""

    @staticmethod
    def _git(args, cwd):
        subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)

    @staticmethod
    def _rev_parse(cwd):
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=cwd, check=True,
            capture_output=True, text=True).stdout.strip()

    def test_end_to_end_merge_against_two_refs(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self._git(["init", "-q"], cwd=root)
            self._git(["config", "user.email", "test@example.com"], cwd=root)
            self._git(["config", "user.name", "Test"], cwd=root)
            self._git(["checkout", "-q", "-b", "trunk"], cwd=root)

            xml_path = root / "app/src/main/res/values/strings.xml"
            xml_path.parent.mkdir(parents=True)
            xml_path.write_text(
                xml_doc('    <string name="key_a">Base A</string>\n'),
                encoding="utf-8")

            json_path = root / "app/src/main/assets/web/i18n/en.json"
            json_path.parent.mkdir(parents=True)
            base_catalogue = {"common": {"save": "Base Save",
                                          "greeting": "مرحبا"}}
            json_path.write_text(
                json.dumps(base_catalogue, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8")

            self._git(["add", "-A"], cwd=root)
            self._git(["commit", "-q", "-m", "base"], cwd=root)
            base_sha = self._rev_parse(root)

            self._git(["checkout", "-q", "-b", "theirs"], cwd=root)
            xml_path.write_text(
                xml_doc(
                    '    <string name="key_a">Base A</string>\n',
                    '    <string name="key_b">Overdrive Feature</string>\n',
                ),
                encoding="utf-8")
            theirs_catalogue = {
                "common": {"save": "Base Save",
                           "greeting": "مرحبا",
                           "delete": "Overdrive Delete"},
            }
            json_path.write_text(
                json.dumps(theirs_catalogue, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8")
            self._git(["commit", "-a", "-q", "-m", "theirs"], cwd=root)
            theirs_sha = self._rev_parse(root)

            self._git(["checkout", "-q", "trunk"], cwd=root)
            xml_path.write_text(
                xml_doc('    <string name="key_a">Wheelstop A</string>\n'),
                encoding="utf-8")
            ours_catalogue = {"common": {"save": "Wheelstop Save",
                                          "greeting": "مرحبا"}}
            json_path.write_text(
                json.dumps(ours_catalogue, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8")
            self._git(["commit", "-a", "-q", "-m", "ours rebrand"], cwd=root)

            rc = main(["upstream_merge_locales.py", base_sha, theirs_sha], cwd=root)
            self.assertEqual(rc, 0)

            merged_xml = xml_path.read_text(encoding="utf-8")
            self.assertIn('<string name="key_a">Wheelstop A</string>', merged_xml)
            self.assertIn('<string name="key_b">Wheelstop Feature</string>', merged_xml)
            self.assertNotIn("Overdrive", merged_xml)

            merged_json_raw = json_path.read_text(encoding="utf-8")
            self.assertNotIn("\\u", merged_json_raw)  # ensure_ascii=False held
            self.assertTrue(merged_json_raw.endswith("\n"))
            merged_json = json.loads(merged_json_raw)
            self.assertEqual(merged_json["common"]["save"], "Wheelstop Save")
            self.assertEqual(merged_json["common"]["delete"], "Wheelstop Delete")
            self.assertEqual(merged_json["common"]["greeting"], "مرحبا")

    def test_discover_locale_paths_includes_theirs_only_file(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self._git(["init", "-q"], cwd=root)
            self._git(["config", "user.email", "test@example.com"], cwd=root)
            self._git(["config", "user.name", "Test"], cwd=root)
            self._git(["checkout", "-q", "-b", "trunk"], cwd=root)
            (root / "README.md").write_text("x", encoding="utf-8")
            self._git(["add", "-A"], cwd=root)
            self._git(["commit", "-q", "-m", "base"], cwd=root)

            self._git(["checkout", "-q", "-b", "theirs"], cwd=root)
            new_json = root / "app/src/main/assets/web/i18n/fr.json"
            new_json.parent.mkdir(parents=True)
            new_json.write_text('{"a": "b"}\n', encoding="utf-8")
            self._git(["add", "-A"], cwd=root)
            self._git(["commit", "-q", "-m", "theirs adds locale"], cwd=root)
            theirs_sha = self._rev_parse(root)

            self._git(["checkout", "-q", "trunk"], cwd=root)
            paths = discover_locale_paths(root, theirs_sha)
            self.assertIn("app/src/main/assets/web/i18n/fr.json", paths)


if __name__ == "__main__":
    unittest.main()
