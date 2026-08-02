# Task: fix data-migration blocker — legacy Overdrive media dirs must survive the rename

Branch `cleanup/de-overdrive`. Repo `/home/shaunes/dev/oss/car_stuff/Overdrive-release`.

## Problem (as verified)

`StorageManager.getAll*Dirs()` (recordings/surveillance/proximity) now enumerate only the
renamed `Wheelstop/` roots. `RecordingsIndex.reconcile()` (`app/src/main/java/app/wheelstop/android/server/RecordingsIndex.java:854`)
builds its `diskNames` set purely from those dirs and then deletes every index row whose
filename isn't in that set. On the first run after upgrading an installed-on-car app to this
branch, every pre-existing recording — still physically sitting under
`/storage/emulated/0/Overdrive/...`, `/sdcard/DCIM/Overdrive/...`, `<sd>/Overdrive/...`, or
`<ext>/Overdrive/backups` — is invisible to `getAll*Dirs()`, so `reconcile()` purges its row.
Files stay on disk (leaking space) but vanish from the in-app library.

## Fix: `LegacyMediaMigration` — one-time, first-boot directory rename

New file: `app/src/main/java/app/wheelstop/android/storage/LegacyMediaMigration.kt`.

### Roots covered

| Legacy | Wheelstop | Mirrors |
|---|---|---|
| `/storage/emulated/0/Overdrive` | `/storage/emulated/0/Wheelstop` | `StorageManager.INTERNAL_BASE_DIR` |
| `/storage/emulated/0/DCIM/Overdrive` | `/storage/emulated/0/DCIM/Wheelstop` | `ConfigManager`'s default `outputDir` |
| `<volume>/Overdrive` for every entry under `/storage/` except `emulated`/`self` | `<volume>/Wheelstop` | `StorageManager.initVolumeDirectories()` (`<volumePath>/Wheelstop`, SD/USB) |

`Environment.getExternalStorageDirectory()` (used by `SettingsAboutFragment.publicBackupDir()`
for `/sdcard/Wheelstop/backups`) resolves to the same `/storage/emulated/0` root as the first
row, so renaming that one directory carries the nested `Overdrive/backups` folder along with
it automatically — no separate backups-root case was needed.

Removable volumes are discovered by listing `/storage/` directly with plain `File` I/O
(`entry.name != "emulated" && entry.name != "self"`) rather than by going through
`StorageManager`'s own SD/USB discovery — see "why it must run first" below for why that
matters, not just style.

### Mechanism

For each root pair: if the legacy dir exists and the Wheelstop dir does **not** yet exist,
`legacy.renameTo(wheelstop)` (same-volume, atomic). If the Wheelstop dir already exists, skip
and log — never clobber. Every step (per-dir rename, volume walk, pref rewrite, the whole
`runIfNeeded`) is wrapped in try/catch and only logs a warning on failure; nothing here can
crash startup.

### Guard

New, dedicated prefs file `wheelstop_migrations` (SharedPreferences, `Context.MODE_PRIVATE`),
key `legacy_overdrive_dirs_migrated_v1`. Deliberately **not** `overdrive_config` or any other
preserved `overdrive_*` prefs name — those stay untouched per the data-compat constraints.
Once set, `runIfNeeded()` short-circuits on every later launch.

### `outputDir` pref rewrite

If the persisted `ConfigManager` `AppConfig.outputDir` starts with `/storage/emulated/0` or
`/sdcard` and contains an `Overdrive` path segment, that segment is rewritten to `Wheelstop`
(segment-exact replace via path-split/join, not a raw substring replace, so it can't corrupt an
unrelated path that merely contains the substring "Overdrive") and written back via
`ConfigManager.updateAppConfig(config.copy(outputDir = ...))`. This uses `ConfigManager`'s own
`overdrive_config` prefs file/API as intended — only the *value* is rewritten, not the prefs
file name, which is a preserved literal and was not touched.

### Wiring — why it must run first, and why volumes aren't read via `StorageManager`

`WheelstopApplication.onCreate()` (`app/src/main/java/app/wheelstop/android/WheelstopApplication.kt`)
now calls `LegacyMediaMigration.runIfNeeded(this)` as the very first statement, before
`applyPersistedLocale()`, logging setup, `PreferencesManager.init()`, and
`DaemonKeepaliveService.start()`.

This ordering is load-bearing, not cosmetic: `StorageManager`'s private constructor
(`app/src/main/java/app/wheelstop/android/storage/StorageManager.java:573`) eagerly calls
`initDirectories()`, which creates `INTERNAL_BASE_DIR` (`/storage/emulated/0/Wheelstop`) and,
via `initSdCardDirectories()`/`initUsbDirectories()` → `initVolumeDirectories()` (line ~2116),
creates `<volumePath>/Wheelstop` on any mounted SD/USB volume — all inside the constructor, the
first time anything calls `StorageManager.getInstance()`. If the migration ran after that (or
reused `StorageManager` for volume discovery, which would call `getInstance()` and trigger the
exact same side effect), the "Wheelstop dir doesn't exist yet" guard would always see it already
created and skip every rename, permanently orphaning the legacy files. That's why the migration
walks `/storage/` directly with `File` I/O instead of asking `StorageManager` for the volume
list, and why it's the first line of `onCreate()`.

## Fixed: misleading comment in `RecordingsIndex.java`

`RecordingsIndex.java:1268-1274` (query-time storage-type classifier for NULL-`storage` legacy
rows, inside `applyFilters`). The comment previously claimed the broad `/storage/emulated/%`
LIKE clause "already covers both … so no migration scan is lost" — conflating a SQL filter used
only when a caller filters `queryRecordings` by `storage=INTERNAL` with the on-disk directory
scan `reconcile()`/`scanDirNames()` actually performs. The two are unrelated: this clause never
protects a row from being dropped by `reconcile()`, which purges by directory-scan membership
regardless of the `storage` column. Replaced the comment with an accurate description (it's a
query-time classifier only) and a pointer to `LegacyMediaMigration` as the actual fix for the
purge problem.

## Bonus: cosmetic filename renames

As specified, `overdrive-backup-*.json` → `wheelstop-backup-*.json` and
`overdrive-automations.json` → `wheelstop-automations.json`:

- `app/src/main/assets/web/shared/backup-flow.js:184` (`buildFilename()`)
- `app/src/main/assets/web/shared/automations.js:135` (`a.download = ...`)

Extended beyond the two named files to the native/Kotlin equivalents in
`app/src/main/java/app/wheelstop/android/ui/fragment/settings/SettingsAboutFragment.kt`, which
generate the *same* filenames on the actual write path used on the BYD head unit (no
DocumentsUI ⇒ SAF unavailable ⇒ direct file I/O, per the file's own doc comments):
- `writeBundleToPublicDir()` fallback `"overdrive-backup.json"` → `"wheelstop-backup.json"` (line ~520)
- `suggestedBackupName()` prefix `"overdrive-backup"` → `"wheelstop-backup"` (line ~534)

Rationale for the extension: the `settings_backup_default_filename` string resource
(`app/src/main/res/values/strings.xml:913` and all locale variants) was *already*
`wheelstop-backup.json` from an earlier de-Overdrive pass — these two inline literals in the
same file were the only leftovers, and leaving them un-renamed would have produced
`wheelstop-backup.json` from the string resource but `overdrive-backup-<model>-<ver>.json` from
the two code paths that actually build the name. Confirmed via grep that
`BUNDLE_FORMAT = "overdrive-config-bundle"` (`ConfigBackupService.kt:37`) — the value restore
actually validates — was not touched; filenames are cosmetic only, as the task noted.

Verified no other `overdrive-backup`/`overdrive-automations` source hits remain
(`app/build/**` stale artifacts from a prior build don't count — they're regenerated):
```
$ grep -rn "overdrive-backup\|overdrive-automations" app/src
(no output)
```

## Data-compat literals: confirmed untouched

Spot-checked none of the preserved literals were touched by this change:
`/data/local/tmp/overdrive_config.json`, `.overdrive*` paths, `overdrive-byd-cred-v1`,
`overdrive-config-bundle`, prefs names `overdrive_config`/`*_prefs`, HA `overdrive_` entity
prefix, `overdrive://`, `X-Overdrive-*`, `com.overdrive.app`. The new migration guard prefs file
is `wheelstop_migrations` (new name, not reusing any `overdrive_*` prefs file).

## Files touched

- `app/src/main/java/app/wheelstop/android/storage/LegacyMediaMigration.kt` (new)
- `app/src/main/java/app/wheelstop/android/WheelstopApplication.kt` (wire-in, first line of `onCreate()`)
- `app/src/main/java/app/wheelstop/android/server/RecordingsIndex.java` (comment fix only, ~line 1268)
- `app/src/main/assets/web/shared/backup-flow.js` (bonus rename)
- `app/src/main/assets/web/shared/automations.js` (bonus rename)
- `app/src/main/java/app/wheelstop/android/ui/fragment/settings/SettingsAboutFragment.kt` (bonus rename, extended scope)

## Build

```
docker run --rm -v "$PWD:/src" -v and-sdk:/sdk -v and-gradle:/gradle eclipse-temurin:17-jdk bash -c \
  'export ANDROID_HOME=/sdk GRADLE_USER_HOME=/gradle; cd /src && \
   ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain 2>&1 | tail -80'
```

Result: `BUILD SUCCESSFUL in 24s` (51 actionable tasks: 17 executed, 34 up-to-date). Unit tests
and `assembleDebug` both ran clean; only pre-existing, unrelated deprecation warnings
(`startActivityForResult`) in `SettingsAboutFragment.kt`. Ownership of Docker-created build
output was restored with the follow-up `chown` container run per instructions.

## Concerns / judgment calls

- **One-shot guard is set after a single attempt regardless of outcome.** If
  `MANAGE_EXTERNAL_STORAGE` hasn't been granted yet on the very first post-upgrade launch (the
  app requests/grants it via `StorageSetup`/ADB app-ops elsewhere, asynchronously, later in
  startup), `File.renameTo()` on the legacy dirs can fail silently, and the guard still flips to
  "done" — there's no retry on a later launch once permission lands. This matches the task's
  literal spec ("guarded by a one-shot ... flag", "best-effort... never crash startup") rather
  than a retry-until-success design, but it's worth an on-car smoke test after a real
  upgrade-with-existing-recordings to confirm permission is already available by the time
  `Application.onCreate()` runs (BYD's ADB app-ops grant flow suggests it usually is, by the time
  the app process restarts post-update, but this wasn't verified against a live device in this
  task).
- Did not attempt an on-car verification (out of scope per the task — build-only ask). The fix
  is code-reviewed and compiles, but the actual `renameTo()` behavior under BYD's SELinux/FUSE
  storage stack is unverified beyond this build.
- Extended the bonus filename rename to `SettingsAboutFragment.kt` beyond the two files
  literally named in the task, for consistency with the already-renamed string resource — see
  rationale above. Flagging in case that scope creep is unwanted.
