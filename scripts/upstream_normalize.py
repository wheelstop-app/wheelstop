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


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    print(apply_substitutions(argv[1]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
