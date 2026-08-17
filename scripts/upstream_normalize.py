#!/usr/bin/env python3
"""Normalize an upstream Overdrive tree into Wheelstop identifiers.

Identifier-only by design. Display strings ("Overdrive" -> "Wheelstop") are
deliberately NOT rewritten: the external storage folder name,
CredentialCipher.KD_SALT and ConfigBackupService.BUNDLE_FORMAT are
MUST-PRESERVE, and locale wording needs human judgement. Those surface as
review conflicts instead.

"Identifier" is a property of a span of bytes, not of a whole file: an
Android strings.xml catalogue holds a compile-time resource key and
human-facing wording in the same element, so keys and values get different
substitution sets rather than the file getting one verdict (see
CATALOGUE_REWRITE and apply_substitutions_to_catalogue).

Usage:
  scripts/upstream_normalize.py <tree-root>
"""
import fnmatch
import os
import pathlib
import re
import sys

# "Which paths are Android string catalogues" is owned by
# upstream_merge_locales -- the module that merges them. Importing the
# pattern instead of restating it here keeps the key-only rewrite rule
# below and the key-level merger operating on exactly the same set of
# files; two copies would drift the first time either grows a directory.
from upstream_merge_locales import XML_PATTERN as STRINGS_XML_PATTERN

# Order is load-bearing: the JNI and underscore forms MUST precede the bare
# `overdrive_` prefix rule, or `com_overdrive_app` degrades to
# `com_wheelstop_app`.
SUBSTITUTIONS = [
    ("Java_com_overdrive_app", "Java_app_wheelstop_android"),
    ("com_overdrive_app",      "app_wheelstop_android"),
    ("com.overdrive.app",      "app.wheelstop.android"),
    ("com/overdrive/app",      "app/wheelstop/android"),
    ("overdrive/vehicle/telemetry", "wheelstop/vehicle/telemetry"),
    ("overdrive_",             "wheelstop_"),
]

# Classes the rebrand renamed beyond the package move. Extend as found.
CLASS_RENAMES = [
    ("OverdriveApplication", "WheelstopApplication"),
]


def apply_substitutions(text):
    """Apply the identifier transform to a string. Pure."""
    for old, new in SUBSTITUTIONS:
        text = text.replace(old, new)
    for old, new in CLASS_RENAMES:
        text = text.replace(old, new)
    return text


# --- Resource catalogues: keys and values are not the same thing --------
#
# The `name=` attribute of a resource entry, captured so the attribute
# VALUE (group 2) can be transformed while the surrounding markup and the
# entry body are left byte-identical. Anchored on the opening tag of the
# three entry kinds a strings.xml catalogue can hold, so a stray `name="`
# inside wording cannot be mistaken for a resource key.
RESOURCE_NAME_RE = re.compile(
    r'(<(?:string|string-array|plurals)\s+name=")([^"]*)(")')

# Substitutions that are safe to apply to a resource VALUE -- text a human
# reads on screen.
#
# Every token in SUBSTITUTIONS is identifier-SHAPED: `overdrive_` with a
# trailing underscore, the JNI forms, a slash-separated namespace. None of
# them is a word, so a value containing one is quoting an identifier, not
# writing prose -- and upstream really does ship one: the PIN-recovery
# sentinel `/data/local/tmp/.overdrive_pin_reset`, whose Java side
# (PinManager.RESET_FLAG_FILE) the transform rewrites. Leaving the wording
# behind would tell the user to touch a file the app no longer watches.
#
# The two bare package-path forms are the exception and are deliberately
# withheld from values. `com.overdrive.app` is the OLD APP's package name,
# which this fork preserves on purpose (see the
# `exclusivity-preflight-old-package` invariant) and which can legitimately
# appear verbatim in text instructing the user about the app they still
# have installed. Rewriting it there would print a package name that
# identifies nothing.
_VALUE_UNSAFE = ("com.overdrive.app", "com/overdrive/app")
VALUE_SUBSTITUTIONS = [(old, new) for old, new in SUBSTITUTIONS
                       if old not in _VALUE_UNSAFE]


def apply_substitutions_to_catalogue(text):
    """Apply the identifier transform to a strings.xml catalogue, keys and
    values treated as the different things they are. Pure.

    `name=` is a compile-time symbol (`R.string.overdrive_foo`,
    `@string/overdrive_foo`) bound by code the transform DOES rewrite, so
    it takes the full substitution set. Everything else in the file is
    wording, and takes only VALUE_SUBSTITUTIONS.

    Applying the value pass over the whole text afterwards is equivalent to
    applying it to the values alone: the key pass has already consumed
    every token a resource name could physically contain (names are
    `[A-Za-z0-9_]`, so only the underscore forms are even expressible, and
    those are gone by then).

    Note what this transform must NOT do: rewrite the brand itself.
    "Overdrive" -> "Wheelstop" in wording is upstream_merge_locales'
    brand_sweep, which is value-only in the opposite direction -- it never
    touches a key name.
    """
    text = RESOURCE_NAME_RE.sub(
        lambda m: m.group(1) + apply_substitutions(m.group(2)) + m.group(3),
        text)
    for old, new in VALUE_SUBSTITUTIONS:
        text = text.replace(old, new)
    for old, new in CLASS_RENAMES:
        text = text.replace(old, new)
    return text


# --- Scope ---------------------------------------------------------------
#
# R1: res/ is in SCOPE. Excluding it fails the build outright -- Kotlin/Java
# bind R.id/R.string at compile time, and omitting it produced ~17
# unresolved-reference errors during the stage-A merge.
#
# R6: assets/server-i18n/ is in SCOPE. It is read at runtime by
# Messages.get("errors...."), a string-key lookup rather than a compile-time
# symbol, so an upstream-added key that never syncs would NOT fail the
# build -- it silently falls back to a missing string on a real car. That
# is a worse failure mode than the loud compile error res/ already gives
# us, so this directory must be reachable, not just listed in NO_REWRITE.
SCOPE = (
    "app/src/main/java/",
    "app/src/main/assets/web/",
    "app/src/main/assets/server-i18n/",
    "app/src/main/res/",
    "app/src/test/",
)

# R2: two distinct concepts, previously conflated under one EXCLUDED list.
#
# NO_REWRITE: still synced (upstream's new keys must arrive, and code + tests
# reference them), but identifier substitution must never touch these --
# every byte of them is prose or a runtime lookup key, not a compile-time
# identifier. The JSON i18n catalogues qualify: their keys are addressed as
# runtime strings (`Messages.get("errors.foo")`) that the code-side rewrite
# leaves alone too, so key and lookup stay in step by both sides doing
# nothing.
#
# res/values*/strings.xml is deliberately NOT here -- see KEYS_ONLY_REWRITE.
NO_REWRITE = (
    "app/src/main/assets/web/i18n/",
    "app/src/main/assets/server-i18n/",
)

# CATALOGUE_REWRITE: paths where "rewrite the whole file" and "rewrite
# nothing" are BOTH wrong, because the file mixes compile-time identifiers
# with human-facing wording and the two need different substitution sets
# (see apply_substitutions_to_catalogue). Matched as fnmatch globs, unlike
# the prefix tuples around it.
#
# Every res/values*/strings.xml is such a file, default and locale variant
# alike. Splitting them was the bug: values/strings.xml was NO_REWRITE and
# values-*/strings.xml was rewritten whole, on the stated grounds that
# "values-*/strings.xml contain no identifiers to rewrite either" -- which
# is simply false. Upstream's values-de/strings.xml carries
# `name="overdrive_status_summary_title"` and `name="overdrive_status_
# summary_text"`, the same two keys as the default catalogue. The
# asymmetry meant a new upstream `overdrive_`-prefixed resource would
# arrive unrewritten in the DEFAULT catalogue, rewritten in every locale
# variant, and bound as `R.string.wheelstop_*` by code the transform did
# rewrite: a broken build and two catalogues disagreeing about the key's
# name.
CATALOGUE_REWRITE = (STRINGS_XML_PATTERN,)

# NEVER_SYNC: brand ASSETS -- icons, launcher art, the sidebar logo. Never
# take upstream's version at all; these are excluded from scope entirely,
# not merely from content rewriting. The test is "would a 3-way merge of
# this file be meaningless?": for an image or an icon-shaped vector it is,
# so pinning is the only sane rule.
#
# Structured configuration does NOT belong here even when it is
# brand-coloured. res/values/colors_m3.xml used to be listed and its
# values-night/ twin was not -- an asymmetry that broke the build outside
# any conflict marker: upstream added semantic aliases
# (`md_sys_color_primary` -> `..._light` / `..._dark`) to both halves and to
# themes_overdrive.xml, which IS synced. The pinned light half never
# delivered its alias, the dark half did, and the normalized
# values/themes_wheelstop.xml then referenced a colour nothing defined in
# the default configuration. A colour table and the theme that consumes it
# have to move together, so both halves now take the ordinary path: the
# 17 lines we deliberately rebranded win the 3-way merge (ours-changed
# beats theirs), upstream's genuinely new tokens arrive, and a same-line
# disagreement conflicts for a human -- which is the reviewable outcome,
# unlike a dangling @color reference.
NEVER_SYNC = (
    "app/src/main/res/mipmap-",
    "app/src/main/res/drawable/ic_launcher_background.xml",
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
    "app/src/main/res/drawable/ic_sidebar_logo.xml",
)

REWRITE_NONE = "none"
REWRITE_CATALOGUE = "catalogue"
REWRITE_FULL = "full"

TEXT_SUFFIXES = {".java", ".kt", ".kts", ".js", ".mjs", ".css", ".html",
                 ".json", ".xml", ".pro", ".txt", ".md", ".cpp", ".h", ".svg"}


def in_scope(rel, scope=SCOPE):
    """True if a repo-relative path participates in upstream parity."""
    rel = rel.replace(os.sep, "/")
    if rel.startswith(NEVER_SYNC):
        return False
    return rel.startswith(scope)


def rewrite_mode(rel, scope=SCOPE):
    """How much of an in-scope path's CONTENT gets the identifier
    transform:

      REWRITE_FULL      -- ordinary source; substitute throughout.
      REWRITE_CATALOGUE -- a strings.xml catalogue; resource keys take the
                           full substitution set, wording takes only
                           VALUE_SUBSTITUTIONS.
      REWRITE_NONE      -- out of scope, or in scope but all wording /
                           runtime lookup keys (NO_REWRITE).
    """
    rel = rel.replace(os.sep, "/")
    if not in_scope(rel, scope):
        return REWRITE_NONE
    if rel.startswith(NO_REWRITE):
        return REWRITE_NONE
    if any(fnmatch.fnmatch(rel, pat) for pat in CATALOGUE_REWRITE):
        return REWRITE_CATALOGUE
    return REWRITE_FULL


def should_rewrite(rel, scope=SCOPE):
    """True if ANY identifier substitution applies to an in-scope path's
    content -- i.e. rewrite_mode() is not REWRITE_NONE. Callers that care
    whether the wording is swept too (as opposed to just the resource
    keys) must ask rewrite_mode() instead."""
    return rewrite_mode(rel, scope) != REWRITE_NONE


def normalize_tree(root, scope=SCOPE):
    """Rewrite and move every in-scope file under root. Content is rewritten
    as much as rewrite_mode() allows (everything, `name=` attributes only,
    or nothing); an in-scope file is still MOVED if its path changes under
    the transform, even when its content is not rewritten. Returns
    (files_rewritten, files_moved).

    `scope` defaults to the module SCOPE and only needs overriding by callers
    reproducing a narrower historical scope (see the stage-A fixture test) --
    the sync script and CLI never pass it, so their behaviour is unchanged."""
    root = pathlib.Path(root)
    rewritten = moved = 0
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            path = pathlib.Path(dirpath) / name
            rel = str(path.relative_to(root)).replace(os.sep, "/")
            if not in_scope(rel, scope):
                continue
            mode = rewrite_mode(rel, scope)
            if mode != REWRITE_NONE and path.suffix in TEXT_SUFFIXES:
                try:
                    old = path.read_text(encoding="utf-8")
                except (UnicodeDecodeError, OSError):
                    old = None
                if old is not None:
                    new = (apply_substitutions_to_catalogue(old)
                           if mode == REWRITE_CATALOGUE
                           else apply_substitutions(old))
                    if new != old:
                        path.write_text(new, encoding="utf-8")
                        rewritten += 1
            new_rel = apply_substitutions(rel)
            if new_rel != rel:
                dst = root / new_rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                path.rename(dst)
                moved += 1
    for dirpath, _dirs, _files in sorted(os.walk(root), reverse=True):
        try:
            os.rmdir(dirpath)
        except OSError:
            pass
    return rewritten, moved


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    rewritten, moved = normalize_tree(argv[1])
    print(f"normalized: {rewritten} files rewritten, {moved} files moved")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
