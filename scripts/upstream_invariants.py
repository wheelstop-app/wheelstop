#!/usr/bin/env python3
"""Assert that Wheelstop-specific behaviour survived an upstream sync merge.

Upstream has previously deleted a community driving-safety fix (see
yash-srivastava/Overdrive-release#233). Because such a deletion looks like
rebrand churn in a large merge, it is easy to accept by accident -- in the
real incident, both gate bodies vanished with zero merge conflicts, and
only an unrelated import line conflicted. This check fails the sync PR if
any protected property disappeared.

Needle matching is code-aware (reusing the Java/Kotlin lexer from
upstream_fix_shadowing.py): a needle that appears only inside a comment
never satisfies an invariant, because a comment is inert text, not
behaviour. Merge resolutions routinely leave a comment like
"// removed dead code that used to call X" behind, and that must not read
as X still being present. By default an invariant also requires its
needle to be outside string/char literals too (appropriate for needles
that are identifiers or method calls in real code); set "code_only": false
on a manifest entry whose needle is legitimately part of a string literal
(e.g. a JSON outcome value or a hard-coded package name) -- such an entry
still never matches inside a comment, only the string/char-literal
restriction is lifted.

Usage:
  scripts/upstream_invariants.py [tree-root]     # default: repo root
"""
import collections
import glob
import json
import os
import pathlib
import sys

from upstream_fix_shadowing import _char_states

Violation = collections.namedtuple("Violation", "name reason detail")

DEFAULT_MANIFEST = pathlib.Path(__file__).with_name("upstream_invariants.json")

# States `_char_states` never treats as "real behaviour" for the purposes
# of this checker, regardless of an entry's code_only setting: a comment
# can say anything without it being true of the code around it.
_COMMENT_STATES = ("line_comment", "block_comment")


def load_manifest(path):
    """Load and validate the invariants manifest.

    Raises ValueError, naming the offending entry, if any entry has
    min_total < 1 -- such an entry is satisfied by construction (0
    occurrences already meets "at least 0"), so it would silently provide
    no protection and no test could ever catch its own typo.
    """
    with open(path, encoding="utf-8") as fh:
        manifest = json.load(fh)
    for inv in manifest:
        if inv.get("min_total", 0) < 1:
            raise ValueError(
                f"invariant {inv.get('name', '<unnamed>')!r} has "
                f"min_total={inv.get('min_total')!r}; min_total must be >= 1 "
                f"or the invariant is satisfied by construction and checks "
                f"nothing")
    return manifest


def _count_needle(text, needle, code_only):
    """Count non-overlapping occurrences of `needle` in `text` that count
    as real behaviour rather than dead text.

    A needle occurrence spanning only comment characters never counts,
    regardless of `code_only`. When `code_only` is also true (the
    default), an occurrence must additionally lie entirely outside string
    and char literals.
    """
    if not needle:
        return 0
    states = _char_states(text)
    total = 0
    start = 0
    step = len(needle)
    while True:
        i = text.find(needle, start)
        if i == -1:
            break
        span = states[i:i + step]
        if code_only:
            counts = all(s == "code" for s in span)
        else:
            counts = all(s not in _COMMENT_STATES for s in span)
        if counts:
            total += 1
        start = i + step  # non-overlapping, like str.count
    return total


def check_tree(root, manifest):
    """Return a list of Violation for every unmet invariant."""
    root = pathlib.Path(root)
    violations = []
    for inv in manifest:
        code_only = inv.get("code_only", True)
        matches = glob.glob(str(root / inv["glob"]), recursive=True)
        total = 0
        for path in matches:
            try:
                text = pathlib.Path(path).read_text(
                    encoding="utf-8", errors="replace")
            except OSError:
                continue
            total += _count_needle(text, inv["needle"], code_only)
        if total < inv["min_total"]:
            if not matches:
                detail = f"no file matched glob {inv['glob']!r}"
            else:
                detail = (
                    f"needle {inv['needle']!r} found {total} time(s) "
                    f"(code-only: {code_only}) across {len(matches)} "
                    f"file(s) matching {inv['glob']!r}; need at least "
                    f"{inv['min_total']}")
            violations.append(Violation(inv["name"], inv["reason"], detail))
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
