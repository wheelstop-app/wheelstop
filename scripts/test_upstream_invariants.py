import json, tempfile, pathlib, unittest
from upstream_invariants import load_manifest, check_tree

MANIFEST = [
    {"name": "driving-safety-gate",
     "reason": "PR #165 gate; upstream deleted theirs",
     "glob": "app/src/main/java/**/server/VehicleControlApiHandler.java",
     "needle": "DrivingSafetyGuard.isMovementBlocked",
     "min_total": 2},
    {"name": "update-repo",
     "reason": "never re-point the updater upstream",
     "glob": "app/build.gradle.kts",
     "needle": "wheelstop-app/wheelstop",
     "min_total": 1},
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

if __name__ == "__main__":
    unittest.main()
