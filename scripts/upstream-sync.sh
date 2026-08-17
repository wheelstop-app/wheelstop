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

# Same rule for the locale catalogue globs: upstream_merge_locales owns
# XML_PATTERN/JSON_PATTERNS, so ask it rather than hard-coding the same
# three patterns twice more below. They agreed by luck; a fourth catalogue
# directory (server-i18n was the third, via R11) would have been added in
# one place and silently missed in the other, in either direction.
mapfile -t LOCALE_GLOBS < <(PYTHONPATH="$ROOT/scripts" python3 -c '
from upstream_merge_locales import XML_PATTERN, JSON_PATTERNS
print(XML_PATTERN)
for p in JSON_PATTERNS:
    print(p)
')
if [ "${#LOCALE_GLOBS[@]}" -eq 0 ]; then
  echo "upstream_merge_locales exposes no locale globs -- refusing to sync" >&2
  exit 1
fi

# Expand LOCALE_GLOBS against the CURRENT directory into LOCALE_FILES.
# Called from inside the merge worktree, before and after the key-level
# merger runs (it can write a catalogue that did not exist on disk before).
expand_locale_files() {
  local g
  LOCALE_FILES=()
  shopt -s nullglob
  for g in "${LOCALE_GLOBS[@]}"; do
    LOCALE_FILES+=($g)
  done
  shopt -u nullglob
}

git fetch --no-tags "$UPSTREAM_URL" "$REF"
NEW=$(git rev-parse FETCH_HEAD)
SHORT=$(git rev-parse --short "$NEW")

# Resolve vendor/upstream by FULL ref path, and try both places it can live.
# `git log -1 vendor/upstream` worked locally and could never work in CI: an
# actions/checkout workspace has the branch only as
# refs/remotes/origin/vendor/upstream, and git's short-name resolution does
# not reach a two-segment remote branch that way, so the script died
# `fatal: ambiguous argument` at this line before doing anything -- every
# scheduled run, from the day the workflow was added. (Local runs never hit
# it because `git worktree add` further down DWIMs the branch into
# existence.) Empty VENDOR_REF means the branch does not exist anywhere yet,
# i.e. the first ever run; step 1 bootstraps it from the fork point.
VENDOR_REF=""
for candidate in refs/heads/vendor/upstream refs/remotes/origin/vendor/upstream; do
  if git rev-parse -q --verify "$candidate" >/dev/null; then
    VENDOR_REF="$candidate"
    break
  fi
done

LAST=""
if [ -n "$VENDOR_REF" ]; then
  LAST=$(git log -1 --format=%B "$VENDOR_REF" -- | sed -n 's/^Upstream-Commit: //p' | head -1)
else
  echo "note: vendor/upstream does not exist yet -- it will be bootstrapped at the fork point"
fi

BRANCH="sync/upstream-${SHORT}"

# Idempotence keys off the DELIVERABLE, not off the vendor commit.
#
# Step 1 advances vendor/upstream before anything else, so keying "already
# done" on that trailer meant a failure ANY time after it -- a crash in the
# merge, a `gh pr create` that 403s -- burned the token permanently: the job
# is red once, and every weekly run after it exits 0 having done nothing,
# forever, with no PR ever produced. The deliverable is the PR (or, without
# --open-pr, the sync branch), so that is what gets asked about. A stale
# vendor commit from a half-finished run is not "done"; it just means step 1
# can be skipped this time.
#
# `--state all` on purpose: a sync PR a human CLOSED without merging was a
# deliberate decision, and reopening it every Monday would be spam.
DELIVERED=0
if [ "$OPEN_PR" = "1" ]; then
  # Hard-fail rather than assume "no PR" if the query itself fails. Guessing
  # wrong in that direction force-pushes over a branch someone may be
  # reviewing and then dies on a duplicate `gh pr create` anyway.
  EXISTING_PR=$(gh pr list --head "$BRANCH" --state all --limit 1 --json number --jq '.[].number') || {
    echo "::error::could not query pull requests for $BRANCH — refusing to guess whether this sync was already delivered" >&2
    exit 1
  }
  if [ -n "$EXISTING_PR" ]; then
    DELIVERED=1
  fi
elif git rev-parse -q --verify "refs/heads/$BRANCH" >/dev/null \
     || git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  DELIVERED=1
fi
if [ "$DELIVERED" = "1" ]; then
  echo "upstream $SHORT already delivered ($BRANCH) — nothing to do"
  exit 0
fi
if [ "$LAST" = "$NEW" ]; then
  echo "vendor/upstream is already at $SHORT but $BRANCH was never delivered — resuming"
fi

# 1. Rebuild the vendor tree at the new upstream ref.
WT=$(mktemp -d)
trap '
  git worktree remove --force "$WT" >/dev/null 2>&1 || true
  rm -rf "$WT"
  git worktree remove --force "$WT-merge" >/dev/null 2>&1 || true
  rm -rf "$WT-merge"
  rm -f "${MERGE_LOG:-}"
' EXIT

# `git worktree add <path> vendor/upstream` DWIMs a local branch from
# origin's when one exists, which is why this line never showed the ref
# problem above. Do it explicitly instead, so the branch this worktree
# commits onto is unambiguous and the first-ever run has a starting point.
if ! git rev-parse -q --verify refs/heads/vendor/upstream >/dev/null; then
  if [ -n "$VENDOR_REF" ]; then
    git branch --quiet vendor/upstream "$VENDOR_REF"
  else
    # First ever run. The vendor history is rooted at the fork point --
    # that is the last commit where our tree and upstream's agreed, so it
    # is the only honest base for a normalized upstream snapshot.
    git branch --quiet vendor/upstream "$FORK_POINT"
  fi
fi

if [ "$LAST" = "$NEW" ]; then
  echo "step 1 skipped: vendor/upstream already carries Upstream-Commit: $NEW"
else
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

  # Locale catalogues are key/value data. normalize.py has just rewritten
  # their identifiers (resource keys) and left the wording alone. A line-level
  # `git merge` of these files later would report a conflict for every upstream
  # string added next to one we rebranded, so merge them key-by-key now
  # (base=fork point, theirs=the new upstream ref) and brand-sweep the merged
  # values. Mandatory: skipping this step is what let the "Overdrive" brand walk
  # back into merged locale output during the stage-A merge.
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
fi

# 2. Merge into a sync branch off main.
#
# R12: base on origin/main, not the local `main` ref. A local `main` that
# hasn't been fetched in a while is invisible in CI (fresh checkout, always
# current) but silently wrong when this script is run locally after any gap
# -- it would branch from a stale tree, resurrect files real `main` already
# deleted, and report conflict counts that don't reflect reality. Fetching
# origin here is not a new class of dependency: the script already talks to
# a remote (upstream) before this point. BASE_REF is the single source of
# truth for "what is the sync branch based on" -- used for both the branch
# creation below and the locale "ours" restore in the merge subshell, so the
# two can't independently drift onto different bases.
git fetch --quiet origin main
BASE_REF="origin/main"
BASE_SHA=$(git rev-parse "$BASE_REF")
if git rev-parse -q --verify refs/heads/main >/dev/null && \
   [ "$(git rev-parse refs/heads/main)" != "$BASE_SHA" ]; then
  echo "note: local main ($(git rev-parse --short refs/heads/main)) differs from $BASE_REF ($(git rev-parse --short "$BASE_SHA")) -- basing the sync on $BASE_REF, not local main" >&2
fi
echo "base: $BASE_REF @ $(git rev-parse --short "$BASE_SHA")"

git branch -f "$BRANCH" "$BASE_REF"
git worktree add --quiet "$WT-merge" "$BRANCH"
MERGE_LOG="$WT-merge-locale.log"
STATUS=clean
MERGE_RC=0
# Every fallible command in this subshell carries an explicit `|| exit $?`.
# That is not belt-and-braces around `set -e`: bash SUPPRESSES errexit inside
# a compound command that is part of a `||` list, and the suppression is
# inherited by the subshell -- re-arming it with `set -e` inside does not
# restore it either (verified). So `( ... ) || STATUS=conflicted` never had
# the safety net it looked like it had: a python traceback here would run on
# through the rest of the block and be reported as a merge conflict. The
# ONLY non-zero exit this block may produce for a real conflict is 3.
( cd "$WT-merge" || exit $?
  git -c merge.renameLimit=20000 merge --no-ff --no-commit vendor/upstream || true

  # R10: git's merge above just ran a LINE-level merge on the locale
  # catalogues, which is exactly what key-level merging exists to avoid --
  # an upstream string added next to one we rebranded reads as a textual
  # conflict even though the two changes are independent. Running the
  # key-level merger back in step 1 (against $FORK_POINT/$NEW, before this
  # branch even exists) can't fix that: at that point "ours" on disk is the
  # freshly-checked-out upstream tree itself, so ours==theirs and the merge
  # degenerates to a no-op there -- its only real effect in step 1 is the
  # brand sweep. The genuine reconciliation needs $BASE_REF's actual
  # branding as "ours", which only exists here. So: discard whatever git's
  # line-level pass did to these paths (conflict markers or a wrong-but-
  # clean auto-merge alike), restore them to $BASE_REF's real content, and
  # rerun the key-level merger with base=fork point, theirs=the vendor tip
  # just built (normalized identifiers + brand-swept locale values).
  expand_locale_files
  for f in "${LOCALE_FILES[@]:+${LOCALE_FILES[@]}}"; do
    # A path $BASE_REF never had (a locale upstream just introduced) has
    # nothing to restore from -- leave whatever the merge attempt already
    # placed there; the key-level merger below treats a missing "ours" as
    # "take theirs" anyway. Must be the SAME ref the branch was created
    # from (R12) -- restoring from a different "ours" than the branch's
    # actual base would just reintroduce the drift this fix closes.
    git checkout "$BASE_REF" -- "$f" 2>/dev/null || true
  done
  # Tee'd, not just echoed: the merger's summary carries the count of keys
  # the merge DELETED, which is the one outcome a reader of the diff will
  # not spot, and it has to reach the log line and the PR body below.
  python3 "$ROOT/scripts/upstream_merge_locales.py" "$FORK_POINT" vendor/upstream \
    | tee "$MERGE_LOG" || exit $?

  # Re-glob: the merger may have written a locale file that didn't exist on
  # disk before it ran (a catalogue only vendor/upstream has).
  expand_locale_files
  if [ "${#LOCALE_FILES[@]}" -gt 0 ]; then
    git add -- "${LOCALE_FILES[@]}" || exit $?
  fi

  if git diff --name-only --diff-filter=U | grep -q .; then
    echo "CONFLICTS — committing the conflict map for review"
    git add -A || exit $?
    git commit -q -m "merge: upstream ${SHORT} (CONFLICTS — resolve before merging)" || exit $?
    exit 3
  fi
  git commit -q -m "merge: upstream ${SHORT}

Upstream-Commit: ${NEW}" || exit $?
) || MERGE_RC=$?

# Exit 3 is the merge subshell's ONE expected non-zero exit: genuine
# conflicts, committed as a conflict map. Everything else is a crash -- a
# python traceback, a git failure -- surfaced by the explicit `|| exit $?`
# above. It used to land in the same `STATUS=conflicted` bucket, so a run
# that produced NO MERGE COMMIT AT ALL was indistinguishable in the log and
# the PR body from a run that produced a real, reviewable conflict map.
case "$MERGE_RC" in
  0) STATUS=clean ;;
  3) STATUS=conflicted ;;
  *) echo "::error::merge step failed with exit $MERGE_RC (not a merge conflict) — no merge commit was produced" >&2
     exit "$MERGE_RC" ;;
esac

# Keys the locale merge deleted. Read back from the merger's own summary --
# see upstream_merge_locales for why a dropped key is the one outcome that
# leaves no other trace.
DROPPED=$(sed -n 's/^summary-dropped-keys: //p' "$MERGE_LOG" 2>/dev/null | tail -1)
DROPPED_NAMES=$(sed -n 's/^summary-dropped-key-names: //p' "$MERGE_LOG" 2>/dev/null | tail -1)
CLASHES=$(sed -n 's/^summary-clashing-keys: //p' "$MERGE_LOG" 2>/dev/null | tail -1)
: "${DROPPED:=unknown}" "${CLASHES:=unknown}"

# 3. Invariants. Reported, never swallowed: this is the only guard against a
# clean, conflict-free merge that silently dropped something we protect (the
# upstream deletion that motivated this checker produced zero merge conflicts).
#
# M1: not run on a conflicted tree. Verified live -- with both gate call
# sites sitting inside conflict markers, the checker read our side of the
# markers, counted 2, and printed "all invariants hold"; the PR body then
# claimed `invariants: pass` for a tree that does not compile and whose
# final content nobody has chosen yet. A number that cannot be true yet is
# worse than no number.
if [ "$STATUS" = "conflicted" ]; then
  INV="not evaluated (conflicted tree — re-run after resolving)"
  INV_OK=1
else
  INV=pass
  INV_OK=1
  python3 "$ROOT/scripts/upstream_invariants.py" "$WT-merge" || { INV=FAIL; INV_OK=0; }
fi

echo "sync branch: $BRANCH   merge: $STATUS   invariants: $INV   dropped keys: $DROPPED   clashing keys: $CLASHES"
if [ "${DROPPED:-0}" != "0" ] && [ "$DROPPED" != "unknown" ]; then
  echo "dropped keys: $DROPPED_NAMES"
fi

if [ "$OPEN_PR" = "1" ]; then
  # Locally this is "origin". In CI it is a tokenized URL built from a GitHub App
  # token, because (a) the workflow checks out with persist-credentials: false and
  # (b) pushes made with the built-in GITHUB_TOKEN do not trigger other workflows,
  # so build.yml would never run on the sync PR.
  #
  # No `-u`. With a tokenized URL, `-u` writes `branch.<name>.remote = <the
  # URL, token and all>` into .git/config, which re-persists the App token
  # into a workspace checked out with persist-credentials: false -- undoing
  # that setting from inside the job it protects. Nothing here reads the
  # upstream tracking config anyway.
  #
  # Two pushes, not one, because they have different force rules.
  # vendor/upstream is append-only vendor history and must NEVER be forced.
  # The sync branch is machine-owned and is rebuilt from scratch on a resumed
  # run (a previous run that pushed the branch and then failed before opening
  # the PR), so it is forced -- we only reach this point when no PR exists
  # for it, so there is no review in progress to clobber.
  REMOTE="${SYNC_PUSH_REMOTE:-origin}"
  git push "$REMOTE" vendor/upstream
  git push --force "$REMOTE" "$BRANCH"

  # Draft unless the branch is genuinely ready for a human to hit merge.
  # A conflicted tree is obviously not, but neither is a cleanly-merged tree
  # that failed the invariants -- that combination used to open a READY PR
  # and exit 0, i.e. the loudest failure this tooling can detect presented
  # as its best-case outcome.
  DRAFT=""
  if [ "$STATUS" = "conflicted" ] || [ "$INV_OK" = "0" ]; then
    DRAFT="--draft"
  fi
  gh pr create $DRAFT --base main --head "$BRANCH" \
    --title "merge: upstream ${SHORT}" \
    --body "Automated upstream sync to \`${NEW}\`.

- merge: **${STATUS}**
- invariants: **${INV}**
- locale keys DELETED by the merge: **${DROPPED}**${DROPPED_NAMES:+ (\`${DROPPED_NAMES}\`)}
- locale keys where both sides changed (ours kept): **${CLASHES}**

A deleted key is upstream retiring a string we had never edited. That is
the intended policy, and it is also the only change in this PR that
produces no conflict and no error — check that none of them is
load-bearing before merging.

If conflicted, this branch carries conflict markers as a precise conflict map. Resolve them before merging. See \`docs/upstream-sync.md\`."
fi

# The job's own success signal. A violated invariant is the single loudest
# thing this script can find; it must not exit 0 just because the merge
# itself was tidy.
if [ "$INV_OK" = "0" ]; then
  echo "::error::upstream-sync invariants FAILED on $BRANCH — do not merge" >&2
  exit 1
fi
