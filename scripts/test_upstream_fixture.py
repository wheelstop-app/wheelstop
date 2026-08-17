"""Acceptance: the production transform must reproduce the hand-made stage-A
vendor tree byte for byte. Skips cleanly if the fixture is absent.

R4 (controller ruling): the fixture's vendor commit (91b38903) covered code,
web and tests only -- res/ joined upstream_normalize.SCOPE in a later vendor
commit. Reproducing the fixture means normalizing with that narrower stage-A
scope explicitly, via normalize_tree()'s `scope` kwarg, not the module's
current (wider) default. The res/ branch of the transform is exercised by
test_upstream_normalize.py instead.
"""
import pathlib
import re
import subprocess
import tempfile
import unittest

from upstream_normalize import normalize_tree

FIXTURE = pathlib.Path("docs/superpowers/fixtures/2026-08-17-stageA.md")
FORK_POINT = "170c10d955d64f9a01963d5e3fcbdf0723a05188"
V366 = "3b8c488970acae73868bf59ef16f966c917541c4"

# R4: code, web and tests only -- narrower than upstream_normalize.SCOPE,
# which has since grown to include res/ and server-i18n/.
SCOPE = ("app/src/main/java/", "app/src/main/assets/web/", "app/src/test/")

# The stage-A vendor commit message: "Excluded paths (web/i18n) are held at
# fork-point content so the merge never touches locale wording." Restored
# AFTER the upstream checkout above and BEFORE normalizing, same as the hand
# merge did.
PINNED_AT_FORK_POINT = ("app/src/main/assets/web/i18n",)


def _sh(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True,
                          text=True).stdout.strip()


def _clean_replace(paths, ref, cwd):
    """Remove `paths` from the index and working tree, then check out ref's
    copy of them. A bare `git checkout <ref> -- <paths>` overlays instead --
    it leaves behind anything <ref> doesn't have, which is wrong both for
    the upstream checkout (files upstream deleted would survive) and for
    pinning the excluded path back to fork-point content (files upstream
    added under it would survive)."""
    subprocess.run(["git", "rm", "-r", "--quiet", "--cached", *paths],
                   cwd=cwd, capture_output=True)
    for rel in paths:
        subprocess.run(["rm", "-rf", rel], cwd=cwd, check=True)
    _sh("git", "checkout", ref, "--", *paths, cwd=cwd)


@unittest.skipUnless(FIXTURE.exists(), "stage-A fixture not present")
class TestFixtureReproduction(unittest.TestCase):
    def test_production_transform_matches_hand_vendor_tree(self):
        expected = re.search(r"## Vendor tree\s+`?([0-9a-f]{40})`?",
                             FIXTURE.read_text(encoding="utf-8")).group(1)
        with tempfile.TemporaryDirectory() as tmp:
            wt = pathlib.Path(tmp) / "wt"
            _sh("git", "worktree", "add", "--quiet", "--detach", str(wt), FORK_POINT)
            try:
                # Clean-replace, matching Task 1 Step 3 and the sync script.
                _clean_replace(SCOPE, V366, wt)
                # Pin the excluded locale path back to fork-point content --
                # the hand merge never let upstream's wording through it.
                _clean_replace(PINNED_AT_FORK_POINT, FORK_POINT, wt)
                # R4: explicit stage-A scope, not upstream_normalize's
                # current (wider) module default.
                normalize_tree(wt, scope=SCOPE)
                _sh("git", "add", "-A", cwd=wt)
                actual = _sh("git", "write-tree", cwd=wt)
            finally:
                _sh("git", "worktree", "remove", "--force", str(wt))
        self.assertEqual(actual, expected,
                         "production transform diverged from the hand-made "
                         "stage-A vendor tree")


if __name__ == "__main__":
    unittest.main()
