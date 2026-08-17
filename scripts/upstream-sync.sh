#!/usr/bin/env bash
# Sync upstream Overdrive changes into Wheelstop via the normalized vendor branch.
#
# Never writes to main. Produces a sync branch (and optionally a PR) for review.
#
# Usage:
#   scripts/upstream-sync.sh [--ref <upstream-ref>] [--open-pr]
set -euo pipefail

UPSTREAM_URL="https://github.com/yash-srivastava/Overdrive-release.git"
FORK_POINT="170c10d955d64f9a01963d5e3fcbdf0723a05188"
REF="main"
OPEN_PR=0

while [ $# -gt 0 ]; do
  case "$1" in
    --ref) REF="$2"; shift 2 ;;
    --open-pr) OPEN_PR=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

# SCOPE / NEVER_SYNC are owned by upstream_normalize.py. Query the module
# instead of re-declaring the lists here -- two independent copies would
# drift silently the next time SCOPE grows (it already has, twice: res/ via
# R1, server-i18n/ via R6).
mapfile -t SCOPE < <(PYTHONPATH="$ROOT/scripts" python3 -c '
from upstream_normalize import SCOPE
for p in SCOPE:
    print(p)
')
if [ "${#SCOPE[@]}" -eq 0 ]; then
  echo "upstream_normalize.SCOPE is empty -- refusing to sync" >&2
  exit 1
fi

git fetch --no-tags "$UPSTREAM_URL" "$REF"
NEW=$(git rev-parse FETCH_HEAD)
SHORT=$(git rev-parse --short "$NEW")

LAST=$(git log -1 --format=%B vendor/upstream | sed -n 's/^Upstream-Commit: //p' | head -1)
if [ "$LAST" = "$NEW" ]; then
  echo "vendor/upstream already at $SHORT — nothing to sync"
  exit 0
fi

BRANCH="sync/upstream-${SHORT}"
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  echo "$BRANCH already exists on origin — nothing to do"
  exit 0
fi

# 1. Rebuild the vendor tree at the new upstream ref.
WT=$(mktemp -d)
trap '
  git worktree remove --force "$WT" >/dev/null 2>&1 || true
  rm -rf "$WT"
  git worktree remove --force "$WT-merge" >/dev/null 2>&1 || true
  rm -rf "$WT-merge"
' EXIT
git worktree add --quiet "$WT" vendor/upstream
( cd "$WT"
  git rm -r --quiet --cached "${SCOPE[@]}" >/dev/null 2>&1 || true
  rm -rf "${SCOPE[@]}"
  git checkout "$NEW" -- "${SCOPE[@]}"

  # NEVER_SYNC entries (brand assets) are STRING PREFIXES inside upstream_normalize.py
  # (e.g. "app/src/main/res/mipmap-" matches every density bucket), not literal
  # directory paths, so they can't be handed to `git checkout`/`rm` directly. Discover
  # the real on-disk files the SCOPE checkout above just pulled in that fall under a
  # NEVER_SYNC prefix, using the module's own startswith() semantics, then pin exactly
  # those files back to fork-point content -- upstream's copy is not merely left
  # unrewritten (as with NO_REWRITE paths), it never lands at all.
  mapfile -t NEVER_SYNC_FILES < <(
    find "${SCOPE[@]}" -type f 2>/dev/null | PYTHONPATH="$ROOT/scripts" python3 -c '
import sys
from upstream_normalize import NEVER_SYNC
for line in sys.stdin:
    rel = line.rstrip("\n")
    if rel.startswith(NEVER_SYNC):
        print(rel)
'
  )
  for f in "${NEVER_SYNC_FILES[@]:+${NEVER_SYNC_FILES[@]}}"; do
    rm -f -- "$f"
    git checkout "$FORK_POINT" -- "$f" 2>/dev/null || true
  done

  python3 "$ROOT/scripts/upstream_normalize.py" .

  # Locale catalogues are key/value data, not identifiers -- normalize.py
  # deliberately leaves their content alone (NO_REWRITE). A line-level `git merge`
  # of these files later would report a conflict for every upstream string added
  # next to one we rebranded, so merge them key-by-key now (base=fork point,
  # theirs=the new upstream ref) and brand-sweep the merged values. Mandatory:
  # skipping this step is what let the "Overdrive" brand walk back into merged
  # locale output during the stage-A merge.
  python3 "$ROOT/scripts/upstream_merge_locales.py" "$FORK_POINT" "$NEW"

  # Wheelstop's package root is literally `app.` (app.wheelstop.android), which
  # collides with `app` as a local variable/parameter name -- extremely common in
  # upstream's Java/Kotlin, which writes fully-qualified references liberally
  # because its own package (com.overdrive.app) never collides with anything.
  # Any file declaring a local named `app` can shadow the package root and break
  # every FQN in scope, so this must run over every in-scope .java/.kt file,
  # not just app/src/main/java -- app/src/test is in SCOPE too.
  mapfile -t JAVA_KT_FILES < <(
    find "${SCOPE[@]}" -type f \( -name '*.java' -o -name '*.kt' \) 2>/dev/null
  )
  if [ "${#JAVA_KT_FILES[@]}" -gt 0 ]; then
    python3 "$ROOT/scripts/upstream_fix_shadowing.py" "${JAVA_KT_FILES[@]}"
  fi

  git add -A
  git commit -q -m "vendor: upstream ${SHORT} normalized to Wheelstop identifiers

Upstream-Commit: ${NEW}" )

# 2. Merge into a sync branch off main.
git branch -f "$BRANCH" main
git worktree add --quiet "$WT-merge" "$BRANCH"
STATUS=clean
( cd "$WT-merge"
  git -c merge.renameLimit=20000 merge --no-ff --no-commit vendor/upstream || true

  # R10: git's merge above just ran a LINE-level merge on the locale
  # catalogues, which is exactly what key-level merging exists to avoid --
  # an upstream string added next to one we rebranded reads as a textual
  # conflict even though the two changes are independent. Running the
  # key-level merger back in step 1 (against $FORK_POINT/$NEW, before this
  # branch even exists) can't fix that: at that point "ours" on disk is the
  # freshly-checked-out upstream tree itself, so ours==theirs and the merge
  # degenerates to a no-op there -- its only real effect in step 1 is the
  # brand sweep. The genuine reconciliation needs main's actual branding as
  # "ours", which only exists here. So: discard whatever git's line-level
  # pass did to these paths (conflict markers or a wrong-but-clean
  # auto-merge alike), restore them to main's real content, and rerun the
  # key-level merger with base=fork point, theirs=the vendor tip just built
  # (normalized identifiers + brand-swept locale values).
  shopt -s nullglob
  LOCALE_FILES=(app/src/main/res/values*/strings.xml
                app/src/main/assets/web/i18n/*.json
                app/src/main/assets/server-i18n/*.json)
  shopt -u nullglob
  for f in "${LOCALE_FILES[@]:+${LOCALE_FILES[@]}}"; do
    # A path main never had (a locale upstream just introduced) has nothing
    # to restore from -- leave whatever the merge attempt already placed
    # there; the key-level merger below treats a missing "ours" as "take
    # theirs" anyway.
    git checkout main -- "$f" 2>/dev/null || true
  done
  python3 "$ROOT/scripts/upstream_merge_locales.py" "$FORK_POINT" vendor/upstream

  # Re-glob: the merger may have written a locale file that didn't exist on
  # disk before it ran (a catalogue only vendor/upstream has).
  shopt -s nullglob
  LOCALE_FILES=(app/src/main/res/values*/strings.xml
                app/src/main/assets/web/i18n/*.json
                app/src/main/assets/server-i18n/*.json)
  shopt -u nullglob
  if [ "${#LOCALE_FILES[@]}" -gt 0 ]; then
    git add -- "${LOCALE_FILES[@]}"
  fi

  if git diff --name-only --diff-filter=U | grep -q .; then
    echo "CONFLICTS — committing the conflict map for review"
    git add -A
    git commit -q -m "merge: upstream ${SHORT} (CONFLICTS — resolve before merging)"
    exit 3
  fi
  git commit -q -m "merge: upstream ${SHORT}

Upstream-Commit: ${NEW}"
) || STATUS=conflicted

# 3. Invariants. Reported, never swallowed: this is the only guard against a
# clean, conflict-free merge that silently dropped something we protect (the
# upstream deletion that motivated this checker produced zero merge conflicts).
INV=pass
python3 "$ROOT/scripts/upstream_invariants.py" "$WT-merge" || INV=FAIL

echo "sync branch: $BRANCH   merge: $STATUS   invariants: $INV"

if [ "$OPEN_PR" = "1" ]; then
  # Locally this is "origin". In CI it is a tokenized URL built from a GitHub App
  # token, because (a) the workflow checks out with persist-credentials: false and
  # (b) pushes made with the built-in GITHUB_TOKEN do not trigger other workflows,
  # so build.yml would never run on the sync PR.
  REMOTE="${SYNC_PUSH_REMOTE:-origin}"
  git push -u "$REMOTE" vendor/upstream "$BRANCH"
  DRAFT=""; [ "$STATUS" = "conflicted" ] && DRAFT="--draft"
  gh pr create $DRAFT --base main --head "$BRANCH" \
    --title "merge: upstream ${SHORT}" \
    --body "Automated upstream sync to \`${NEW}\`.

- merge: **${STATUS}**
- invariants: **${INV}**

If conflicted, this branch carries conflict markers as a precise conflict map. Resolve them before merging. See \`docs/upstream-sync.md\`."
fi
