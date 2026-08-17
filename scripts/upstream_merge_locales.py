#!/usr/bin/env python3
"""Key-level 3-way merge for Wheelstop's locale catalogues.

Line-level merging is the wrong tool for key/value catalogues. When upstream
adds a string next to one we rebranded, a line-level `git merge` reports a
textual conflict even though the two changes are independent -- they just
happen to sit on adjacent lines. Merging per key instead resolves those
automatically and surfaces only genuine same-key disagreements. On the merge
this module was built from, key-level merging collapsed 34 conflicted
`strings.xml` files down to 40 genuinely-clashing keys.

Handles two catalogue shapes:
  - Android `res/values*/strings.xml` -- flat `<string>`, `<string-array>`
    and `<plurals>` entries, merged per `name=` attribute.
  - Nested web i18n JSON (`assets/web/i18n/*.json`) -- merged per leaf key,
    recursing into nested objects rather than replacing a whole subtree.

Rule per key (either shape): if our value differs from base, ours wins --
that's a deliberate rebrand. Otherwise take theirs -- an upstream edit, or a
brand-new key we never had. A key upstream deleted that we never touched is
dropped; one we changed is kept even though upstream deleted it. Keys where
BOTH sides changed are reported as clashes (ours is kept, but the caller
needs to know).

Because upstream's brand-new keys carry "OverDrive"/"Overdrive" in their
translated values, and those keys produce no conflict (we never had them),
the brand walks straight into merged output unless swept afterwards. On the
merge this was built from, that affected 80 values across 16 locale files.
`brand_sweep` fixes that up by rewriting merged VALUES only -- never key
names.

Usage:
  scripts/upstream_merge_locales.py <base-ref> <theirs-ref>

Merges every `values*/strings.xml` and `web/i18n/*.json` locale catalogue in
the working tree: base-ref/theirs-ref supply the "base" and "theirs" side of
the 3-way merge, "ours" is always the current on-disk file. Results are
written back to the working tree (not staged) -- review and `git add` them
after.
"""
import collections
import fnmatch
import json
import os
import pathlib
import re
import subprocess
import sys

# --- strings.xml ------------------------------------------------------

# `name=` must be the first attribute -- validated against every entry in
# this project's strings.xml files. `re.S` so `.*?` can cross the newlines
# inside multi-line <string-array>/<plurals> blocks.
ENTRY_RE = re.compile(
    r'[ \t]*<(string|string-array|plurals)\s+name="([^"]+)".*?</\1>\s*\n|'
    r'[ \t]*<string\s+name="([^"]+)"[^>]*/>\s*\n',
    re.S)

XML_PATTERN = "app/src/main/res/values*/strings.xml"
JSON_PATTERN = "app/src/main/assets/web/i18n/*.json"

BRAND_TERMS = ("OverDrive", "Overdrive")
BRAND_REPLACEMENT = "Wheelstop"


def _iter_entries(text):
    """Yield (key, raw_block, start, end) for each <string>/<string-array>/
    <plurals> entry in text, in document order, with byte offsets into
    `text` -- the offsets are what let callers edit around entries without
    reconstructing the file from parsed fragments. Empty for None text."""
    if text is None:
        return
    for m in ENTRY_RE.finditer(text):
        key = m.group(2) or m.group(3)
        yield key, m.group(0), m.start(), m.end()


def parse_xml_entries(text):
    """-> ordered {name: raw_block} for every entry in text ({} for None)."""
    return collections.OrderedDict((k, b) for k, b, _s, _e in _iter_entries(text))


def merge_xml(base, ours, theirs):
    """3-way merge of a strings.xml catalogue, per <string>/<string-array>/
    <plurals> entry (matched by its `name=` attribute).

    R7: this edits `ours` surgically in place rather than reconstructing
    the file from parsed fragments. `ours` is the base of the output;
    entries the merge rule resolves to "take theirs" are spliced over their
    span in `ours`, entries resolved to "drop" have just their span
    removed, and everything else -- comments, blank-line grouping,
    indentation, the XML declaration, `<resources>` attributes -- passes
    through byte-identical. Keys present only in `theirs` are inserted
    immediately after the last existing entry (or, if `ours` has no
    entries at all, just before the closing `</resources>` tag, or at the
    very end if that tag isn't found either).

    base/ours/theirs are the file's text at each side, or None if that
    side doesn't have the file at all. Returns (merged_text, clash_keys):
    clash_keys lists names where BOTH ours and theirs changed the entry
    (ours is kept, but it's reported).
    """
    base_entries = parse_xml_entries(base)
    theirs_entries = parse_xml_entries(theirs)

    if ours is None:
        # Nothing of "ours" to preserve -- the merged file is theirs
        # verbatim (or empty, if neither side has it).
        return (theirs or ""), []

    ours_spans = list(_iter_entries(ours))
    ours_entries = collections.OrderedDict((k, b) for k, b, _s, _e in ours_spans)

    clashes = []
    pieces = []
    cursor = 0
    for key, block, start, end in ours_spans:
        pieces.append(ours[cursor:start])  # verbatim gap: comments, blank lines, indentation
        bv, ov, tv = base_entries.get(key), block, theirs_entries.get(key)
        if ov != bv:
            pieces.append(ov)  # deliberate rebrand -- ours wins, span untouched
            if tv is not None and tv != bv and tv != ov:
                clashes.append(key)  # both changed differently; ours kept
        elif tv is not None:
            pieces.append(tv)  # upstream edit -- splice theirs' block in place
        # else: unchanged, and upstream deleted it -> drop (append nothing)
        cursor = end

    new_keys = [k for k in theirs_entries if k not in ours_entries]
    insertion = "".join(theirs_entries[k] for k in new_keys)
    if ours_spans:
        pieces.append(insertion)
        pieces.append(ours[cursor:])
    else:
        close = ours.rfind("</resources>")
        if close == -1:
            pieces.append(ours[cursor:])
            pieces.append(insertion)
        else:
            pieces.append(ours[cursor:close])
            pieces.append(insertion)
            pieces.append(ours[close:])

    return "".join(pieces), clashes


# --- i18n JSON ----------------------------------------------------------

def merge_json(base, ours, theirs):
    """3-way merge of a nested i18n JSON catalogue, per leaf key. Nested
    objects are merged key-by-key at every depth, not replaced wholesale --
    an object where we changed one leaf and upstream added a sibling leaf
    merges both changes without conflict.

    base/ours/theirs are parsed JSON values (normally dicts), or None if
    that side doesn't have the file. Returns (merged_obj, clash_keys):
    clash_keys are dotted paths ("common.save") where BOTH sides changed
    the same leaf (ours is kept, but it's reported).
    """
    clashes = []

    def walk(b, o, t, path):
        if not isinstance(o, dict) or not isinstance(t, dict):
            if b is not None and o != b:
                if t is not None and t != b and t != o:
                    clashes.append(path)
                return o  # deliberate rebrand -- ours wins
            return t if t is not None else o

        out = {}
        for key in list(o.keys()) + [k for k in t if k not in o]:
            bv = b.get(key) if isinstance(b, dict) else None
            ov = o.get(key)
            tv = t.get(key)
            child_path = f"{path}.{key}" if path else key
            if ov is None:
                if tv is not None:
                    out[key] = tv  # brand-new upstream key
            elif tv is None:
                if bv is not None and ov == bv:
                    continue  # upstream deleted it, we never touched it
                out[key] = ov  # deleted upstream, but we changed/added it
            else:
                out[key] = walk(bv, ov, tv, child_path)
        return out

    merged = walk(base, ours if ours is not None else {},
                  theirs if theirs is not None else {}, "")
    return merged, clashes


# --- brand sweep ----------------------------------------------------------

def _sweep_text(text):
    n = 0
    for term in BRAND_TERMS:
        n += text.count(term)
        text = text.replace(term, BRAND_REPLACEMENT)
    return text, n


def _sweep_xml_text(text):
    # R7 applies here too: walk the original text by entry span rather than
    # reconstructing from parsed fragments, so gaps between entries (and
    # any text outside entries entirely) come through untouched in
    # structure -- only ever swept for brand terms, never dropped.
    total = 0
    pieces = []
    cursor = 0
    for _key, block, start, end in _iter_entries(text):
        gap, n_gap = _sweep_text(text[cursor:start])
        pieces.append(gap)
        total += n_gap
        # End of the entry's opening tag -- `name="..."` lives entirely
        # before this and is never swept.
        tag_end = block.index(">") + 1
        prefix, rest = block[:tag_end], block[tag_end:]
        new_rest, n = _sweep_text(rest)
        pieces.append(prefix + new_rest)
        total += n
        cursor = end
    tail, n_tail = _sweep_text(text[cursor:])
    pieces.append(tail)
    total += n_tail
    return "".join(pieces), total


def _sweep_json_value(value):
    if isinstance(value, dict):
        out, n = {}, 0
        for k, v in value.items():
            out[k], c = _sweep_json_value(v)
            n += c
        return out, n
    if isinstance(value, list):
        out, n = [], 0
        for v in value:
            sv, c = _sweep_json_value(v)
            out.append(sv)
            n += c
        return out, n
    if isinstance(value, str):
        return _sweep_text(value)
    return value, 0


def brand_sweep(obj_or_text):
    """Rewrite "OverDrive"/"Overdrive" -> "Wheelstop" in merged VALUES only
    -- never in key names. A str argument is treated as strings.xml text
    (each entry's `name=` attribute is protected by construction); a
    dict/list argument is treated as a parsed JSON catalogue (dict keys are
    preserved verbatim, only string leaves are rewritten). Returns
    (result, n_replaced).
    """
    if isinstance(obj_or_text, str):
        return _sweep_xml_text(obj_or_text)
    return _sweep_json_value(obj_or_text)


# --- CLI ------------------------------------------------------------------

def is_xml_path(rel):
    return fnmatch.fnmatch(rel.replace(os.sep, "/"), XML_PATTERN)


def is_locale_path(rel):
    rel = rel.replace(os.sep, "/")
    return fnmatch.fnmatch(rel, XML_PATTERN) or fnmatch.fnmatch(rel, JSON_PATTERN)


def repo_root(cwd=None):
    result = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                             cwd=cwd, capture_output=True, text=True, check=True)
    return pathlib.Path(result.stdout.strip())


def git_show(ref, rel_path, cwd):
    """-> file text at ref, or None if the ref/path doesn't exist."""
    result = subprocess.run(["git", "show", f"{ref}:{rel_path}"],
                             cwd=cwd, capture_output=True)
    if result.returncode != 0:
        return None
    return result.stdout.decode("utf-8", "replace")


def discover_locale_paths(root, theirs_ref):
    """Repo-relative paths of every locale catalogue: what's on disk now,
    unioned with what theirs_ref has -- so a brand-new locale file upstream
    adds is picked up even though it doesn't exist in the working tree yet.
    """
    found = set()
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            rel = str((pathlib.Path(dirpath) / name).relative_to(root)).replace(os.sep, "/")
            if is_locale_path(rel):
                found.add(rel)
    result = subprocess.run(["git", "ls-tree", "-r", "--name-only", theirs_ref],
                             cwd=root, capture_output=True, text=True)
    if result.returncode == 0:
        for rel in result.stdout.splitlines():
            if is_locale_path(rel):
                found.add(rel)
    return sorted(found)


def merge_one(root, rel, base_ref, theirs_ref):
    """Merge a single locale catalogue in place. Returns (clash_keys, n_branded)."""
    abs_path = root / rel
    ours = abs_path.read_text(encoding="utf-8") if abs_path.exists() else None
    base = git_show(base_ref, rel, cwd=root)
    theirs = git_show(theirs_ref, rel, cwd=root)

    if is_xml_path(rel):
        merged, clashes = merge_xml(base, ours, theirs)
        merged, n = brand_sweep(merged)
    else:
        base_obj = json.loads(base) if base else None
        ours_obj = json.loads(ours) if ours else None
        theirs_obj = json.loads(theirs) if theirs else None
        merged_obj, clashes = merge_json(base_obj, ours_obj, theirs_obj)
        merged_obj, n = brand_sweep(merged_obj)
        merged = json.dumps(merged_obj, ensure_ascii=False, indent=2) + "\n"

    abs_path.parent.mkdir(parents=True, exist_ok=True)
    abs_path.write_text(merged, encoding="utf-8")
    return clashes, n


def run_merge(root, base_ref, theirs_ref):
    """Merge every locale catalogue in root's working tree against
    base_ref/theirs_ref, writing results back in place. Returns
    (files_merged, clash_report) where clash_report maps path -> clash
    keys. Prints per-file progress."""
    clash_report = {}
    files_merged = 0
    for rel in discover_locale_paths(root, theirs_ref):
        clashes, n = merge_one(root, rel, base_ref, theirs_ref)
        files_merged += 1
        if clashes:
            clash_report[rel] = clashes
        flag = f" clashes={len(clashes)}" if clashes else ""
        branded = f" branded={n}" if n else ""
        print(f"  {rel}{flag}{branded}")
    return files_merged, clash_report


def main(argv, cwd=None):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    base_ref, theirs_ref = argv[1], argv[2]
    root = repo_root(cwd)
    files_merged, clash_report = run_merge(root, base_ref, theirs_ref)
    total_clashes = sum(len(v) for v in clash_report.values())
    print(f"\nmerged {files_merged} locale catalogue(s)")
    if clash_report:
        print(f"{total_clashes} key(s) where both sides changed (ours kept):")
        for rel, keys in sorted(clash_report.items()):
            print(f"  {rel}: {', '.join(keys)}")
    else:
        print("no clashing keys")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
