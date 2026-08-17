"""Acceptance gate: the production transform must reproduce the hand-made
stage-A vendor tree byte for byte.

Deliberately NOT named `test_*.py`. It is the one check in scripts/ that
needs an UPSTREAM ref (alpha-v36.6, 3b8c4889) which this repository does
not contain, so it cannot run in build.yml's per-PR job without adding an
upstream fetch to every unrelated build. R15: it runs from the weekly
upstream-sync workflow instead, which already fetches upstream and checks
out full history. Keeping it outside the `test_*.py` glob is what makes
that split structural rather than a convention someone will re-break.

Run it directly:

    python3 scripts/acceptance_upstream_fixture.py

It never skips. The previous version keyed on a fixture stored under
docs/superpowers/, which is gitignored -- so in CI the file was simply
absent, the class skipped, and the job printed OK. An acceptance gate that
reports success when it did not run is worse than no gate, so a missing
fixture or a missing ref is now a failure that says which one and how to
fix it.

R4 (controller ruling): the fixture's vendor commit (91b38903) covered
code, web and tests only -- res/ joined upstream_normalize.SCOPE in a later
vendor commit. Reproducing the fixture means normalizing with that narrower
stage-A scope explicitly, via normalize_tree()'s `scope` kwarg, not the
module's current (wider) default. The res/ branch of the transform is
exercised by test_upstream_normalize.py instead.
"""
import pathlib
import re
import subprocess
import tempfile
import unittest

from upstream_normalize import normalize_tree

# Anchored on this file, not on the working directory: the sync workflow,
# a developer in scripts/, and `unittest discover` all invoke it from
# different places, and a CWD-relative fixture path silently resolved to
# "absent" in exactly the environment the gate was meant to guard.
REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
FIXTURE = REPO_ROOT / "scripts/fixtures/2026-08-17-stageA.md"

FORK_POINT = "170c10d955d64f9a01963d5e3fcbdf0723a05188"
V366 = "3b8c488970acae73868bf59ef16f966c917541c4"
SYNC_SCRIPT = REPO_ROOT / "scripts/upstream-sync.sh"


def upstream_url():
    """The upstream remote, read out of the sync script that owns it. The
    "how to fix it" message below is only useful if it names the remote the
    sync actually uses, and a second copy of the URL here would be one more
    thing to keep in step."""
    match = re.search(r'^UPSTREAM_URL="([^"]+)"',
                      SYNC_SCRIPT.read_text(encoding="utf-8"), re.M)
    return match.group(1) if match else "<upstream>"

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


def _have_commit(sha):
    return subprocess.run(["git", "cat-file", "-e", f"{sha}^{{commit}}"],
                          cwd=REPO_ROOT, capture_output=True).returncode == 0


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


class TestFixtureReproduction(unittest.TestCase):
    def test_prerequisites_are_present(self):
        # Its own test, so a missing prerequisite is reported as a distinct
        # red line rather than as "the transform diverged".
        self.assertTrue(
            FIXTURE.exists(),
            f"stage-A fixture missing at {FIXTURE}. It is tracked in git; a "
            f"checkout without it is broken, not a reason to skip.")
        self.assertTrue(
            _have_commit(FORK_POINT),
            f"fork point {FORK_POINT} is not in this repository -- check out "
            f"with full history (fetch-depth: 0).")
        self.assertTrue(
            _have_commit(V366),
            f"upstream alpha-v36.6 ({V366}) is not in this repository. Fetch "
            f"it first: git fetch --no-tags {upstream_url()} main")

    def test_production_transform_matches_hand_vendor_tree(self):
        text = FIXTURE.read_text(encoding="utf-8")
        match = re.search(r"## Vendor tree\s+`?([0-9a-f]{40})`?", text)
        self.assertIsNotNone(
            match, f"no '## Vendor tree <sha>' section in {FIXTURE}")
        expected = match.group(1)
        with tempfile.TemporaryDirectory() as tmp:
            wt = pathlib.Path(tmp) / "wt"
            _sh("git", "worktree", "add", "--quiet", "--detach", str(wt),
                FORK_POINT, cwd=REPO_ROOT)
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
                _sh("git", "worktree", "remove", "--force", str(wt),
                    cwd=REPO_ROOT)
        self.assertEqual(actual, expected,
                         "production transform diverged from the hand-made "
                         "stage-A vendor tree")


if __name__ == "__main__":
    unittest.main()
