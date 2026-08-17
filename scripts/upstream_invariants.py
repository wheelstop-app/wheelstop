#!/usr/bin/env python3
"""Assert that Wheelstop-specific behaviour survived an upstream sync merge.

Upstream has previously deleted a community driving-safety fix (see
yash-srivastava/Overdrive-release#233). Because such a deletion looks like
rebrand churn in a large merge, it is easy to accept by accident. This check
fails the sync PR if any protected property disappeared.

Usage:
  scripts/upstream_invariants.py [tree-root]     # default: repo root
"""
import collections
import glob
import json
import os
import pathlib
import sys

Violation = collections.namedtuple("Violation", "name reason detail")

DEFAULT_MANIFEST = pathlib.Path(__file__).with_name("upstream_invariants.json")


def load_manifest(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def check_tree(root, manifest):
    """Return a list of Violation for every unmet invariant."""
    root = pathlib.Path(root)
    violations = []
    for inv in manifest:
        matches = glob.glob(str(root / inv["glob"]), recursive=True)
        total = 0
        for path in matches:
            try:
                total += pathlib.Path(path).read_text(
                    encoding="utf-8", errors="replace").count(inv["needle"])
            except OSError:
                continue
        if total < inv["min_total"]:
            violations.append(Violation(
                inv["name"], inv["reason"],
                f"found {total} occurrence(s) of {inv['needle']!r} in "
                f"{len(matches)} file(s) matching {inv['glob']!r}; "
                f"need at least {inv['min_total']}"))
    return violations


def main(argv):
    root = argv[1] if len(argv) > 1 else os.getcwd()
    violations = check_tree(root, load_manifest(DEFAULT_MANIFEST))
    for v in violations:
        print(f"INVARIANT VIOLATED: {v.name}\n  why: {v.reason}\n  {v.detail}",
              file=sys.stderr)
    if violations:
        print(f"\n{len(violations)} invariant(s) violated.", file=sys.stderr)
        return 1
    print("all upstream-sync invariants hold")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
