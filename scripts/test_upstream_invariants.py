import json, tempfile, pathlib, unittest

import upstream_invariants
from upstream_invariants import load_manifest, check_tree

MANIFEST = [
    {"name": "driving-safety-gate",
     "reason": "PR #165 gate; upstream deleted theirs",
     "glob": "app/src/main/java/**/server/VehicleControlApiHandler.java",
     "needle": "DrivingSafetyGuard.isMovementBlocked",
     "min_total": 2,
     "code_only": True},
    {"name": "update-repo",
     "reason": "never re-point the updater upstream",
     "glob": "app/build.gradle.kts",
     "needle": "wheelstop-app/wheelstop",
     "min_total": 1,
     # The needle is the string-literal repo slug itself (written as
     # `"wheelstop-app/wheelstop"` in build.gradle.kts), so this entry
     # needs code_only: false -- it still can never match inside a
     # comment, only the string-literal restriction is lifted.
     "code_only": False},
]


class TestInvariants(unittest.TestCase):
    def _tree(self, d, rel, body):
        p = pathlib.Path(d) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")

    def test_satisfied_manifest_yields_no_violations(self):
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "app/src/main/java/x/server/VehicleControlApiHandler.java",
                       "DrivingSafetyGuard.isMovementBlocked();\n" * 2)
            self._tree(d, "app/build.gradle.kts", 'UPDATE_REPO "wheelstop-app/wheelstop"')
            self.assertEqual(check_tree(d, MANIFEST), [])

    def test_missing_needle_is_a_violation(self):
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "app/src/main/java/x/server/VehicleControlApiHandler.java",
                       "// gate removed\n")
            self._tree(d, "app/build.gradle.kts", 'UPDATE_REPO "wheelstop-app/wheelstop"')
            v = check_tree(d, MANIFEST)
            self.assertEqual(len(v), 1)
            self.assertEqual(v[0].name, "driving-safety-gate")
            self.assertIn("PR #165", v[0].reason)

    def test_too_few_occurrences_is_a_violation(self):
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "app/src/main/java/x/server/VehicleControlApiHandler.java",
                       "DrivingSafetyGuard.isMovementBlocked();\n")
            self._tree(d, "app/build.gradle.kts", 'UPDATE_REPO "wheelstop-app/wheelstop"')
            v = check_tree(d, MANIFEST)
            self.assertEqual([x.name for x in v], ["driving-safety-gate"])

    def test_missing_file_is_a_violation(self):
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "app/build.gradle.kts", 'UPDATE_REPO "wheelstop-app/wheelstop"')
            v = check_tree(d, MANIFEST)
            self.assertEqual([x.name for x in v], ["driving-safety-gate"])

    def test_manifest_loads_from_disk(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(MANIFEST, f)
            name = f.name
        self.assertEqual(len(load_manifest(name)), 2)


class TestCodeAwareMatching(unittest.TestCase):
    """R8: needle matching must be code-aware. A needle that appears only
    inside a comment (dead text) must never satisfy an invariant. A needle
    that legitimately lives inside a string literal must still be
    matchable, but only when the manifest entry opts out of code_only."""

    def _tree(self, d, rel, body):
        p = pathlib.Path(d) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")

    def test_code_only_true_ignores_a_comment_only_match(self):
        manifest = [{"name": "n", "reason": "r", "glob": "f.java",
                     "needle": "NEEDLE", "min_total": 1, "code_only": True}]
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "f.java", "// NEEDLE mentioned only in a comment\n")
            v = check_tree(d, manifest)
        self.assertEqual([x.name for x in v], ["n"])

    def test_code_only_true_ignores_a_string_literal_match(self):
        manifest = [{"name": "n", "reason": "r", "glob": "f.java",
                     "needle": "NEEDLE", "min_total": 1, "code_only": True}]
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "f.java", 'String s = "NEEDLE";\n')
            v = check_tree(d, manifest)
        self.assertEqual([x.name for x in v], ["n"])

    def test_code_only_true_accepts_a_real_code_match(self):
        manifest = [{"name": "n", "reason": "r", "glob": "f.java",
                     "needle": "NEEDLE", "min_total": 1, "code_only": True}]
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "f.java", "call(NEEDLE);\n")
            self.assertEqual(check_tree(d, manifest), [])

    def test_code_only_false_accepts_a_string_literal_match(self):
        manifest = [{"name": "n", "reason": "r", "glob": "f.java",
                     "needle": "NEEDLE", "min_total": 1, "code_only": False}]
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "f.java", 'String s = "NEEDLE";\n')
            self.assertEqual(check_tree(d, manifest), [])

    def test_code_only_false_still_ignores_a_comment_only_match(self):
        # The opt-out lifts the string-literal restriction only. A
        # comment is never evidence of real behaviour, regardless of
        # code_only -- otherwise the opt-out would reopen exactly the
        # bypass this whole check exists to close.
        manifest = [{"name": "n", "reason": "r", "glob": "f.java",
                     "needle": "NEEDLE", "min_total": 1, "code_only": False}]
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "f.java", "// NEEDLE mentioned only in a comment\n")
            v = check_tree(d, manifest)
        self.assertEqual([x.name for x in v], ["n"])

    def test_code_only_defaults_to_true_when_omitted(self):
        manifest = [{"name": "n", "reason": "r", "glob": "f.java",
                     "needle": "NEEDLE", "min_total": 1}]
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, "f.java", 'String s = "NEEDLE";\n')
            v = check_tree(d, manifest)
        self.assertEqual([x.name for x in v], ["n"])


class TestExploitRegression(unittest.TestCase):
    """Reproduces the reviewer's two live-tree exploits against the real,
    shipped manifest (scripts/upstream_invariants.json). Both defeated the
    pre-R8 checker (exit 0); both must now fail it."""

    def _tree(self, d, rel, body):
        p = pathlib.Path(d) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")

    def test_exploit_a_comment_masquerading_as_the_gate_is_rejected(self):
        # Reproduces the real incident: both genuine gate call sites are
        # gone; a merge-resolution-style comment is left in their place,
        # mentioning both needles twice each (once per removed call site)
        # -- enough text to satisfy min_total under naive substring
        # counting, which is exactly how the pre-R8 checker was fooled.
        # The comment alone must not satisfy either safety-gate invariant.
        manifest = load_manifest(upstream_invariants.DEFAULT_MANIFEST)
        with tempfile.TemporaryDirectory() as d:
            self._tree(
                d,
                "app/src/main/java/app/wheelstop/android/server/"
                "VehicleControlApiHandler.java",
                "// Removed dead code that used to call "
                "DrivingSafetyGuard.isMovementBlocked()\n"
                "// and return outcome blocked_driving in the first "
                "blocked call site.\n"
                "// Removed dead code that used to call "
                "DrivingSafetyGuard.isMovementBlocked()\n"
                "// and return outcome blocked_driving in the second "
                "blocked call site.\n")
            names = {v.name for v in check_tree(d, manifest)}
        self.assertIn("driving-safety-gate-handler", names)
        self.assertIn("driving-safety-gate-outcome", names)

    def test_exploit_b_comment_in_an_unrelated_file_is_rejected(self):
        # Both real outcomes are removed from the handler file; the
        # needle is planted twice as a comment in a different server/
        # file. Pre-R8 this defeated driving-safety-gate-outcome two ways
        # at once: its glob covered all of server/*.java, and matching
        # was plain substring (so even a comment counted).
        manifest = load_manifest(upstream_invariants.DEFAULT_MANIFEST)
        with tempfile.TemporaryDirectory() as d:
            self._tree(
                d,
                "app/src/main/java/app/wheelstop/android/server/"
                "VehicleControlApiHandler.java",
                "// gate removed\n")
            self._tree(
                d,
                "app/src/main/java/app/wheelstop/android/server/"
                "AudioApiHandler.java",
                "// debug: outcome used to be blocked_driving here\n"
                "// debug: outcome used to be blocked_driving here too\n")
            names = {v.name for v in check_tree(d, manifest)}
        self.assertIn("driving-safety-gate-outcome", names)

    def test_driving_safety_gate_outcome_glob_is_scoped_to_the_handler_file(self):
        # Regression guard for the glob-narrowing fix itself: a future
        # broadening back to server/*.java would silently reopen exploit B.
        manifest = load_manifest(upstream_invariants.DEFAULT_MANIFEST)
        entry = next(e for e in manifest if e["name"] == "driving-safety-gate-outcome")
        self.assertTrue(entry["glob"].endswith("VehicleControlApiHandler.java"),
                         entry["glob"])


class TestManifestValidation(unittest.TestCase):
    """min_total < 1 must be rejected at load time -- such an entry is
    satisfied by construction (0 occurrences already meets "at least 0"),
    so it silently provides no protection."""

    def _load_bad(self, entry):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump([entry], f)
            name = f.name
        return name

    def test_min_total_zero_is_rejected_naming_the_entry(self):
        name = self._load_bad({"name": "decorative", "reason": "r",
                                "glob": "f", "needle": "x", "min_total": 0})
        with self.assertRaises(ValueError) as ctx:
            load_manifest(name)
        self.assertIn("decorative", str(ctx.exception))

    def test_min_total_negative_is_also_rejected(self):
        name = self._load_bad({"name": "n", "reason": "r", "glob": "f",
                                "needle": "x", "min_total": -1})
        with self.assertRaises(ValueError):
            load_manifest(name)

    def test_real_manifest_has_no_decorative_entries(self):
        # Loading the shipped manifest must not raise.
        manifest = load_manifest(upstream_invariants.DEFAULT_MANIFEST)
        self.assertTrue(all(e["min_total"] >= 1 for e in manifest))


class TestFailureDetail(unittest.TestCase):
    """The violation detail must say in words which of the two failure
    shapes occurred: no file matched the glob at all, vs. files matched
    but the needle wasn't found (or wasn't found enough times) in them."""

    def test_missing_file_detail_says_no_file_matched(self):
        manifest = [{"name": "n", "reason": "r", "glob": "nope/*.java",
                     "needle": "X", "min_total": 1}]
        with tempfile.TemporaryDirectory() as d:
            v = check_tree(d, manifest)
        self.assertEqual(len(v), 1)
        self.assertIn("no file matched", v[0].detail)

    def test_present_file_missing_needle_detail_says_found_count(self):
        manifest = [{"name": "n", "reason": "r", "glob": "f.java",
                     "needle": "X", "min_total": 1}]
        with tempfile.TemporaryDirectory() as d:
            (pathlib.Path(d) / "f.java").write_text("no match here\n")
            v = check_tree(d, manifest)
        self.assertEqual(len(v), 1)
        self.assertIn("found 0 time(s)", v[0].detail)
        self.assertIn("1 file(s)", v[0].detail)


if __name__ == "__main__":
    unittest.main()
