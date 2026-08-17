#!/usr/bin/env python3
"""Normalize an upstream Overdrive tree into Wheelstop identifiers.

Identifier-only by design. Display strings ("Overdrive" -> "Wheelstop") are
deliberately NOT rewritten: the external storage folder name,
CredentialCipher.KD_SALT and ConfigBackupService.BUNDLE_FORMAT are
MUST-PRESERVE, and locale wording needs human judgement. Those surface as
review conflicts instead.

Usage:
  scripts/upstream_normalize.py <tree-root>
"""
import os
import pathlib
import sys

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
# their values are prose, not identifiers. Only the default values/strings.xml
# is listed; values-*/strings.xml locale variants contain no identifiers to
# rewrite either, but are handled by a separate locale merger in a later
# task, so ordinary scope/rewrite rules apply to them here.
NO_REWRITE = (
    "app/src/main/assets/web/i18n/",
    "app/src/main/assets/server-i18n/",
    "app/src/main/res/values/strings.xml",
)

# NEVER_SYNC: brand assets. Never take upstream's version at all -- these are
# excluded from scope entirely, not merely from content rewriting.
NEVER_SYNC = (
    "app/src/main/res/mipmap-",
    "app/src/main/res/values/colors_m3.xml",
    "app/src/main/res/drawable/ic_launcher_background.xml",
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
    "app/src/main/res/drawable/ic_sidebar_logo.xml",
)

TEXT_SUFFIXES = {".java", ".kt", ".kts", ".js", ".mjs", ".css", ".html",
                 ".json", ".xml", ".pro", ".txt", ".md", ".cpp", ".h", ".svg"}


def in_scope(rel):
    """True if a repo-relative path participates in upstream parity."""
    rel = rel.replace(os.sep, "/")
    if rel.startswith(NEVER_SYNC):
        return False
    return rel.startswith(SCOPE)


def should_rewrite(rel):
    """True if an in-scope path's CONTENT should have identifiers
    substituted. False for in-scope-but-NO_REWRITE paths (locale
    catalogues) and for anything out of scope."""
    rel = rel.replace(os.sep, "/")
    if not in_scope(rel):
        return False
    return not rel.startswith(NO_REWRITE)


def normalize_tree(root):
    """Rewrite and move every in-scope file under root. Content is rewritten
    only where should_rewrite() is true; an in-scope file is still MOVED if
    its path changes under the transform, even when its content is not
    rewritten. Returns (files_rewritten, files_moved)."""
    root = pathlib.Path(root)
    rewritten = moved = 0
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            path = pathlib.Path(dirpath) / name
            rel = str(path.relative_to(root)).replace(os.sep, "/")
            if not in_scope(rel):
                continue
            if should_rewrite(rel) and path.suffix in TEXT_SUFFIXES:
                try:
                    old = path.read_text(encoding="utf-8")
                except (UnicodeDecodeError, OSError):
                    old = None
                if old is not None:
                    new = apply_substitutions(old)
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
