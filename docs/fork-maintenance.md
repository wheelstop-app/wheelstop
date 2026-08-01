# Fork maintenance

This fork carries substantial changes on top of upstream `yash-srivastava/Overdrive-release`
(open `BsCoefficients` replacing `libod.so`, hardened CI, digest-pinned native fetch,
signed-release pipeline + fork updater, blind-spot features). Upstream ships as large,
squashed "export" commits from a private tree, with no release CI. These two workflows keep
the fork current and honest about what upstream is actually shipping.

## The reality of syncing

Because the fork and upstream both edit the same files (`build.gradle.kts`, `CMakeLists.txt`,
the updater, proguard rules) and upstream lands as one giant commit, **every sync will
conflict.** No automation can auto-resolve that. What the automation does: detect that
upstream moved, build the merge branch, open the PR, and hand you an exact conflict map. You
resolve the conflicts. For a clean merge (rare) the PR is directly mergeable.

## `upstream-sync.yml` — the sync assistant

Runs weekly (Mon 08:00 UTC) and on demand (`gh workflow run upstream-sync.yml`).

- If `upstream/main` is ahead, it creates `sync/upstream-<shortsha>` and merges.
- **Clean merge →** a ready PR. Review and merge.
- **Conflicts →** a **draft** PR. The branch is committed *with* conflict markers, so the
  PR diff is a precise map of every collision. To resolve:
  ```
  git fetch origin sync/upstream-<sha> && git checkout sync/upstream-<sha>
  # edit out the <<<<<<< / ======= / >>>>>>> markers in the listed files
  git commit && git push
  ```
  Then mark the PR ready and merge.
- **CI note:** token-created PRs don't auto-trigger `build.yml` (GitHub's loop guard). Your
  resolving push *does* trigger it — so the build runs before you merge.

Conflicts you'll see most often, and how they resolve:
- `app/src/main/cpp/CMakeLists.txt` — upstream re-adds the `od` target (`od/od.cpp`); the
  fork removed it. **Keep the fork's version** (we build `BsCoefficients` in Kotlin, not the
  blob). (the open-`libod` reimplementation).
- `app/build.gradle.kts` — version handling + native-dep fetch. Keep the fork's digest-pinned
  `downloadOpenCV`/`downloadOpenH264` and `UPDATE_REPO`; take upstream's real feature changes.
- Deleted `libod.so` reappears as a modify/delete conflict — keep it **deleted**.

## `upstream-completeness.yml` — the "is a feature missing from source?" monitor

Runs weekly (Mon 07:00 UTC) and on demand. It answers the worry that upstream ships features
whose source never lands in the repo.

- Downloads the **latest upstream release APK** and its **tagged source**, then runs
  `.github/scripts/upstream-completeness.sh`: every `com/overdrive` class in the binary that
  has no matching `.java`/`.kt` file or type declaration in the source is flagged.
- **Green run →** every kept class resolves to source (the v36.6 state).
- **Failed run →** the release ships code not in its source (the v35.1 state — e.g. the
  `VehicleActuatorService` / cluster-mirror classes that shipped without source). Issues are
  disabled on this fork, so the failure itself is the alert (GitHub emails the owner on a
  failed scheduled run); the flagged classes are in the **job summary** and the
  `completeness-result` artifact.

**Heuristic limit:** R8 renames non-kept classes, so this only sees the classes upstream keeps
un-obfuscated — which is exactly where past gaps appeared, but it is not a proof of total
completeness. For a definitive answer, do a full rebuild-and-diff (below).

When it fires, decide per case: wait for upstream's next export to include the source, raise it
upstream, or reverse-engineer the feature from the binary (as was done for `libod`).

## Definitive check: rebuild a tag and diff

The monitor is a cheap early-warning. To prove a specific release corresponds to its source:

1. `gh api repos/yash-srivastava/Overdrive-release/tarball/<tag> | tar xz`
2. Seed `app/src/main/cpp/{opencv,openh264}` (fetched at build; a network-less container needs
   them copied from this fork's tree) and, if `od/od.cpp` is missing, comment out the `od`
   target in `CMakeLists.txt` so the committed `libod.so` blob ships.
3. Build `:app:assembleRelease -PoverdriveVersionCode=<n> -PoverdriveVersionName=<v>`.
4. Diff class lists, native-lib hashes, permissions, and assets against the downloaded release
   APK. `libsurveillance.so` differing (cross-toolchain) and a different signing cert are
   expected; anything else is a finding.

See the provenance memory / `overdrive-audit` for the full method.
