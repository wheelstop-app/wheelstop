#!/usr/bin/env bash
# Heuristic source-completeness check for an upstream Overdrive release.
#
# Given a release APK and the source tree it claims to be built from, list every
# `com/overdrive/*` class that ships in the binary but has NO corresponding source
# (neither a matching .java/.kt file nor a same-named type declaration anywhere in
# the tree). A non-empty list means the release contains code that isn't in the
# published source — the "feature shipped, source withheld" pattern.
#
# Heuristic, not proof: R8 renames non-kept classes, so this only sees the classes
# upstream chose to keep un-obfuscated (which is exactly where past gaps appeared,
# e.g. VehicleActuatorService in v35.1). Kotlin allows a type in a differently-named
# file, so the declaration grep is what suppresses those false positives.
#
# Usage: upstream-completeness.sh <release.apk> <source-root>
# Needs on PATH: unzip, dexdump (Android build-tools). Writes upstream-missing.txt.
# Exit 0 always (the caller decides what to do with the list).
set -uo pipefail

APK="${1:?usage: upstream-completeness.sh <release.apk> <source-root>}"
SRC="${2:?usage: upstream-completeness.sh <release.apk> <source-root>}"
JAVA_ROOT="$SRC/app/src/main/java"
OUT="upstream-missing.txt"

command -v dexdump >/dev/null || { echo "::error::dexdump not on PATH (install Android build-tools)"; exit 1; }
command -v unzip   >/dev/null || { echo "::error::unzip not on PATH"; exit 1; }
[ -d "$JAVA_ROOT" ] || { echo "::error::no source at $JAVA_ROOT"; exit 1; }

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
export LC_ALL=C

# 1. Class descriptors shipped in the APK, restricted to com/overdrive.
for dex in $(unzip -Z1 "$APK" 'classes*.dex'); do unzip -oq "$APK" "$dex" -d "$tmp"; done
for dex in "$tmp"/classes*.dex; do dexdump "$dex" 2>/dev/null | grep 'Class descriptor'; done \
  | sed "s/.*'L//; s/;'//" | grep '^com/overdrive/' | sort -u > "$tmp/classes.txt"

# 2. Collapse inner classes to their outer type, drop R8-obfuscated short names.
sed 's/\$.*//' "$tmp/classes.txt" | sort -u > "$tmp/outer.txt"

: > "$OUT"
while IFS= read -r cls; do
  simple="${cls##*/}"
  [ "${#simple}" -le 2 ] && continue                    # a, ab → R8-obfuscated, skip
  [ -f "$JAVA_ROOT/$cls.java" ] && continue             # exact Java file
  [ -f "$JAVA_ROOT/$cls.kt" ]   && continue             # exact Kotlin file
  # Kotlin file-facade class (FooKt is generated from top-level members of Foo.kt)
  if [[ "$simple" == *Kt ]] && [ -f "$JAVA_ROOT/${cls%Kt}.kt" ]; then continue; fi
  # Kotlin type declared in a differently-named file? (class/object/interface/enum)
  if grep -rqE "(class|object|interface|enum)[[:space:]]+$simple([[:space:]<(:{]|$)" "$JAVA_ROOT" 2>/dev/null; then
    continue
  fi
  echo "$cls" >> "$OUT"
done < "$tmp/outer.txt"

total=$(wc -l < "$tmp/classes.txt" | tr -d ' ')
miss=$(wc -l < "$OUT" | tr -d ' ')
echo "com/overdrive classes shipped: $total"
echo "outer types with NO source match: $miss"
if [ "$miss" -gt 0 ]; then
  echo "=== shipped-but-not-in-source candidates ==="
  cat "$OUT"
fi
