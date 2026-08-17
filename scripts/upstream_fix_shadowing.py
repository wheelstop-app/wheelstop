#!/usr/bin/env python3
"""Fix `app` package-root shadowing in upstream-synced Java/Kotlin sources.

Wheelstop's package root is literally `app.` (the package is
`app.wheelstop.android`), which collides with `app`, an extremely common
local-variable/parameter name. A declaration as ordinary as
`JSONObject app = ...` shadows the package root for the rest of its scope,
so a fully-qualified reference like
`app.wheelstop.android.config.UnifiedConfigManager.getAppearance()` stops
resolving:

    error: cannot find symbol
        symbol:   variable wheelstop
        location: variable app of type JSONObject

Upstream's package (`com.overdrive.app`) never collides with anything, and
upstream code writes fully-qualified references liberally, so every sync
can drag in code that won't compile here until those FQNs become simple
names + imports. This module does that rewrite, but only where it is
provably safe:

  * `import` lines are never touched. Rewriting inside an import produces
    `import Bar;`, which doesn't compile -- this exact regression broke 43
    files on a previous attempt and required a full revert.
  * String and character literals and line/block comments are never
    touched (a lightweight Java/Kotlin tokenizer skips them), so e.g. a log
    tag or a doc comment that happens to spell out the FQN is left alone.
  * A class already resolvable via a different existing import, or that
    collides (same simple name, different FQN) with another reference in
    the same file, is left fully qualified rather than risk resolving to
    the wrong class.
  * A class in the file's own package is rewritten to a simple name but
    gets no import -- it's already in scope.

Known limitation: a reference inside a Kotlin string template (`"${...}"`)
is treated as string content and never rewritten, even though the
interpolated expression is real code and could theoretically shadow too.
That is the conservative failure mode (a leftover compile error, not a
silently wrong rewrite), so it is left alone rather than guessed at.

Usage:
  scripts/upstream_fix_shadowing.py <path>...
"""
import pathlib
import re
import sys

OWN_ROOT = "app.wheelstop.android"

FQN_RE = re.compile(
    r'\bapp\.wheelstop\.android\.((?:[a-z][\w]*\.)*)([A-Z][\w]*)\b')

IMPORT_LINE_RE = re.compile(r'^[ \t]*import\b')

# `package` line, captured so both its own name (group 1) and its exact
# terminator (with/without `;`) can be read back out of the match.
PACKAGE_LINE_RE = re.compile(r'^[ \t]*package\s+([\w.]+)\s*;?[ \t]*\r?\n', re.M)

# Heuristics for "this file declares a local/parameter/val/var literally
# named `app`, so the `app.` package root may be shadowed somewhere in its
# scope." Kept intentionally loose: a false positive here just means
# fix_text() runs and finds nothing to rewrite (a harmless no-op). A false
# negative would skip a file that actually needs fixing, which is the
# dangerous direction, so this errs toward over-matching.
DECL_RE = re.compile(
    r'\b(?:val|var)\s+app\b'                                    # Kotlin val/var app
    r'|\bapp\s*:\s*[A-Za-z_]'                                    # Kotlin `app: Type`
    r'|\b[A-Za-z_][\w.]*(?:<[^<>]*>)?(?:\[\])*\s+app\s*[=;,)]'   # Java local/param
    r'|\(\s*app\s*\)\s*->'                                       # Java lambda (app) ->
    r'|\bapp\s*->'                                                # unparenthesized lambda app ->
)


def needs_fix(text):
    """True if `text` declares a local variable, parameter, or Kotlin
    val/var literally named `app` -- meaning the `app.` package root may be
    shadowed somewhere in its scope. Files without such a declaration need
    no change."""
    return DECL_RE.search(text) is not None


def _code_mask(text):
    """Boolean list, one entry per character in `text`: True where that
    character is ordinary Java/Kotlin code, False where it's inside a
    string literal, a char literal, or a line/block comment.

    This is a lightweight tokenizer, not a full parser -- it knows just
    enough to keep the FQN rewrite out of literals and comments, which is
    all it needs to do. It does not special-case Kotlin string-template
    expressions (`"${...}"`); those are treated as string content (masked
    out), which is the conservative choice -- see the module docstring.
    """
    n = len(text)
    mask = [True] * n
    state = "code"
    i = 0
    while i < n:
        c = text[i]
        if state == "code":
            two = text[i:i + 2]
            if two == "//":
                mask[i] = mask[i + 1] = False
                state = "line_comment"
                i += 2
                continue
            if two == "/*":
                mask[i] = mask[i + 1] = False
                state = "block_comment"
                i += 2
                continue
            if text[i:i + 3] == '"""':
                mask[i] = mask[i + 1] = mask[i + 2] = False
                state = "triple_string"
                i += 3
                continue
            if c == '"':
                mask[i] = False
                state = "string"
                i += 1
                continue
            if c == "'":
                mask[i] = False
                state = "char"
                i += 1
                continue
            i += 1
            continue
        if state == "line_comment":
            mask[i] = False
            if c == "\n":
                state = "code"
            i += 1
            continue
        if state == "block_comment":
            if text[i:i + 2] == "*/":
                mask[i] = mask[i + 1] = False
                i += 2
                state = "code"
                continue
            mask[i] = False
            i += 1
            continue
        if state == "string" or state == "char":
            mask[i] = False
            if c == "\\":
                if i + 1 < n:
                    mask[i + 1] = False
                i += 2
                continue
            end_char = '"' if state == "string" else "'"
            if c == end_char:
                state = "code"
            i += 1
            continue
        if state == "triple_string":
            if text[i:i + 3] == '"""':
                mask[i] = mask[i + 1] = mask[i + 2] = False
                i += 3
                state = "code"
                continue
            mask[i] = False
            i += 1
            continue
    return mask


def _import_line_mask(text):
    """Boolean list, one per character: True for every character on a line
    whose first non-whitespace token is `import`. These lines are never
    rewritten -- see the module docstring."""
    n = len(text)
    mask = [False] * n
    pos = 0
    for line in text.splitlines(keepends=True):
        end = pos + len(line)
        if IMPORT_LINE_RE.match(line):
            for i in range(pos, end):
                mask[i] = True
        pos = end
    return mask


def _uses_semicolons(text, package_match):
    """True if this file's import statements should end with `;` (Java),
    False if not (Kotlin). Inferred from content, since fix_text() only
    receives text -- not a filename or language flag."""
    if package_match is not None:
        line = package_match.group(0).rstrip()
        return line.endswith(";")
    for line in re.findall(r'^[ \t]*import[^\n]*', text, re.M):
        stripped = line.rstrip()
        if stripped:
            return stripped.endswith(";")
    return True  # no signal at all -- default to Java, the common case


def fix_text(text, own_package):
    """Rewrite `app.wheelstop.android.<pkg>.<ClassName>` references in
    `text` to simple names, adding an `import` for each newly-needed class
    right after the `package` line. `own_package` is the file's own
    declared package -- classes already in it need no import.

    Returns (new_text, imports_added), where imports_added is the sorted
    list of fully-qualified names an import was inserted for. `import`
    lines, string/char literals, and comments are never touched. A simple
    name that would collide with a different class already in scope --
    either via an existing import, or via another distinct FQN referenced
    elsewhere in the same file -- is left fully qualified rather than risk
    resolving to the wrong class.
    """
    code_mask = _code_mask(text)
    import_mask = _import_line_mask(text)
    safe = [c and not m for c, m in zip(code_mask, import_mask)]

    existing_imports = set(re.findall(r'^[ \t]*import\s+([\w.]+)', text, re.M))
    bound = {imp.rsplit(".", 1)[1]: imp for imp in existing_imports}

    # Pass 1: collect, for every safe-to-touch FQN occurrence, the set of
    # distinct fully-qualified names its simple name could mean in this
    # file (its own occurrences, plus whatever an existing import already
    # binds that name to). A simple name with more than one candidate is
    # ambiguous -- rewriting any occurrence of it would silently pick a
    # winner -- so every occurrence of it is left fully qualified.
    seen = {}
    for m in FQN_RE.finditer(text):
        start, end = m.span()
        if not all(safe[start:end]):
            continue
        pkg_path, cls = m.group(1), m.group(2)
        fq = f"{OWN_ROOT}.{pkg_path}{cls}"
        seen.setdefault(cls, set()).add(fq)

    ambiguous = set()
    for cls, fqs in seen.items():
        candidates = set(fqs)
        if cls in bound:
            candidates.add(bound[cls])
        if len(candidates) > 1:
            ambiguous.add(cls)

    imports_needed = {}

    def rep(m):
        start, end = m.span()
        if not all(safe[start:end]):
            return m.group(0)
        pkg_path, cls = m.group(1), m.group(2)
        if cls in ambiguous:
            return m.group(0)
        fq = f"{OWN_ROOT}.{pkg_path}{cls}"
        fq_package = fq.rsplit(".", 1)[0]
        if fq_package != own_package and fq not in existing_imports:
            imports_needed[cls] = fq
        return cls

    new_text = FQN_RE.sub(rep, text)

    imports_added = sorted(set(imports_needed.values()))
    if imports_added:
        package_match = PACKAGE_LINE_RE.search(new_text)
        semi = ";" if _uses_semicolons(text, package_match) else ""
        block = "".join(f"import {fq}{semi}\n" for fq in imports_added)
        insert_at = package_match.end() if package_match else 0
        new_text = new_text[:insert_at] + block + new_text[insert_at:]

    return new_text, imports_added


def _own_package(text):
    m = PACKAGE_LINE_RE.search(text)
    return m.group(1) if m else ""


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    for arg in argv[1:]:
        path = pathlib.Path(arg)
        text = path.read_text(encoding="utf-8")
        if not needs_fix(text):
            print(f"  {path}: no `app` declaration found, unchanged")
            continue
        new_text, imports_added = fix_text(text, _own_package(text))
        if new_text != text:
            path.write_text(new_text, encoding="utf-8")
            print(f"  {path}: fixed, {len(imports_added)} import(s) added")
        else:
            print(f"  {path}: declares `app`, but nothing to rewrite")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
