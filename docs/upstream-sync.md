# Syncing changes from upstream Overdrive

Wheelstop is an independent project that **selectively adopts** app-level fixes from
`yash-srivastava/Overdrive-release`. It does **not** track upstream automatically into
`main`. Every sync lands through a reviewed pull request.

This document is for anyone resolving a sync PR. It exists because the first sync
(`alpha-v36.6`, PR #13) turned up a series of traps that are not obvious and will recur.

---

## The model

`vendor/upstream` is a branch off the **fork point** `170c10d9` — a genuine ancestor of
`main`, so merges have a real merge base. Each sync commits upstream's in-scope tree with
a deterministic rename transform applied (`com.overdrive.app` → `app.wheelstop.android`
and friends), then `main` merges it.

The tip commit of `vendor/upstream` carries the watermark:

```
Upstream-Commit: <full upstream sha>
```

That is the only record of how far we have synced. Do not rewrite it.

## Scope

**In scope:** `app/src/main/java/`, `app/src/main/assets/web/`, `app/src/test/`,
`app/src/main/res/`.

**Never synced** (pinned to fork-point content so `main`'s version always wins):

- `app/src/main/res/mipmap-*` — launcher icons
- `app/src/main/res/values/colors_m3.xml` — the Chock Amber palette
- `app/src/main/res/drawable/ic_launcher_{background,foreground}.xml`, `ic_sidebar_logo.xml`

Build config, manifest, ProGuard, CI, and docs are out of scope entirely — we diverged
there on purpose.

---

## Traps

### 1. The dangerous change is the one that does NOT conflict

This is the single most important thing on this page.

Upstream deleted the PR #165 driving-safety gate from `VehicleControlApiHandler`. In the
merge, **both gate bodies were removed with no conflict at all** — git applied the
deletion cleanly because we had never touched those lines. The only thing that conflicted
was the `import DrivingSafetyGuard` line, which looks exactly like rebrand churn.

Resolving that one hunk "the obvious way" would have removed a driving-safety gate from a
car app. It still compiled. It still passed CI.

**Conflicts are visible; clean deletions are not.** After any sync merge, check the
protected invariants on the *merged tree* — never assume the conflict list is the whole
story:

```bash
python3 scripts/upstream_invariants.py
```

Add an invariant whenever you find something of ours worth protecting. It is cheap and it
is the only mechanism that catches this class of loss.

### 2. Excluding a path from the transform does not exclude it from the checkout

`assets/web/i18n/` sits *inside* an in-scope directory. Checking out `assets/web` pulls
upstream's locale files in even though the normalizer skips them, and they then conflict
against our translations. Excluded paths must be **explicitly restored to fork-point
content** after the checkout.

### 3. `res/` cannot be left out of scope

The first attempt excluded `res/`. The build failed with ~17 unresolved references
(`railExpandButton`, `dashboard_tunnel_stopping`, `projectionPresetGroup`, …). Kotlin and
Java bind `R.id`/`R.string` **at compile time**, so upstream code needs upstream layouts
and strings. The same applies to the web i18n catalogues — application code and tests
reference their keys.

Code and its resources move together. You cannot sync one without the other.

### 4. Locale resources need a KEY-level merge, not a line-level one

`values-*/strings.xml` and `web/i18n/*.json` are key/value catalogues. Line-level merging
produces a conflict whenever upstream adds a string next to one we rebranded, even though
the two changes are independent.

Merging per key collapsed **34 conflicted locale files into 40 genuinely-clashing keys**
(essentially `app_name` in each). Rule: if we changed a key from base, ours wins; else
take upstream's value; new upstream keys are added.

Watch for the tail case: upstream sometimes adds *new* keys whose translations contain
"Overdrive". We never had those keys, so nothing conflicts and the brand walks straight
in. Grep the merged catalogues for the brand afterwards.

### 5. Our package root is literally `app.`

`app.wheelstop.android` begins with `app` — a very common local variable name. Any
`JSONObject app = …` **shadows the package root**, so upstream's fully-qualified
`app.wheelstop.android.config.UnifiedConfigManager.getAppearance()` stops resolving:

```
error: cannot find symbol
    symbol:   variable wheelstop
    location: variable app of type JSONObject
```

Upstream's `com.overdrive.app` never hits this, and upstream writes a lot of FQNs. When it
bites, convert the FQNs in that file to imports.

**Do not do this with a blind find-and-replace.** Rewriting FQNs inside `import` lines
produces `import Bar;` and breaks the file — that mistake cost a full revert. Skip any
line whose first token is `import`.

### 6. Check for references to things we deliberately deleted

We removed the `od` package (libod) in favour of `BsCoefficients`/`BsNativeLayer`. Taking
upstream's version of a file wholesale silently reintroduced `app.wheelstop.android.od.Od`
references. After resolving, grep for anything we have removed:

```bash
grep -rn "od\.Od" app/src/main/java/   # must be empty
```

### 7. A broader rename transform is not automatically better

An extended substitution set (adding `.overdrive`, `overdrive://`, `overdrive-`,
`overdrive/`) was tried on the assumption it would reduce conflicts. Measured: **62
conflicts instead of 60.** The broader rules create new divergence wherever upstream and
we both legitimately kept the word (repository URLs, ported-from comments).

Measure before extending. The transform deliberately does **not** rewrite display strings
either — `CredentialCipher.KD_SALT`, `ConfigBackupService.BUNDLE_FORMAT` and the external
storage folder name depend on that.

---

## A resolution workflow that works

This triage resolved 30 of 60 conflicts mechanically and safely.

**Step 1 — did we ever touch this file?** Compare our side against the fork-point base run
through the rename transform. If they match, we have no stake: take upstream wholesale.

**Step 2 — was our divergence only branding?** Compare against base *plus* a brand
substitution. If it matches, take upstream and re-apply the brand pass.

This test is **necessary but not sufficient**. "Our side contains no `Overdrive` string"
does *not* mean our divergence was cosmetic — that shortcut let 14 structurally-diverged
files through, including the ones that reintroduced the `od` dependency. Confirm the
divergence really is brand-only before trusting it.

**Step 3 — everything else is per-hunk judgment.** Useful questions:

- Does upstream's side reference a symbol that exists in the merged tree? (If not, ours.)
- Did upstream *extract* our inline code into a shared method? (Take theirs — check the
  method exists, then keeping ours would duplicate it.)
- Did upstream de-duplicate something? Keeping ours reintroduces the bug they fixed.
- Is the variable ours removed actually still used? (`localBsEncoder` was dead code in
  comments only — upstream was right to drop it.)
- Are both sides adding *different features* in the same place? Prefer ours and port
  theirs deliberately afterwards. Half-merging two implementations of one subsystem is
  how you get a shader that renders black.

---

## Building locally

The host JDK may be too new for the project's Gradle (Gradle 8.13 rejects Java 26). Use
the build container:

```bash
docker run --rm -v "$PWD":/work \
  -v and-gradle:/root/.gradle -v and-sdk:/opt/android-sdk \
  -w /work overdrive-build:latest \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

## Running a sync

```bash
scripts/upstream-sync.sh [--ref <upstream-ref>] [--open-pr]
python3 scripts/upstream_invariants.py     # check the current tree
```

A conflicted sync PR is committed **with conflict markers** so the diff reads as a precise
conflict map. Those PRs open as drafts. Never merge one as-is.
