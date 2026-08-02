# Task D: final cosmetic de-Overdrive pass (prose, i18n folder text, HA origin, UA)

Branch `cleanup/de-overdrive`. Scope: all leftover Overdrive BRAND wording (comments,
docstrings, user-visible labels/text) across `app/src/main`, plus the recording-folder
display text in the web i18n JSON + strings.xml, without touching functional/data-compat
literals.

150 files changed, 304 insertions(+), 303 deletions(-) (the 1-line imbalance is a
pre-existing unrelated uncommitted edit to this same progress ledger from an earlier
task — see below, not part of this pass's substitutions, which are all 1-for-1 line
replacements).

## What was changed, by category

1. **Recording-folder display text → `Wheelstop/`**
   - `app/src/main/assets/web/i18n/*.json` — all 33 locale files, 99 lines (the
     `storage_path_default` ×2 and `events_saved_to_default` ×1 keys per locale).
     Non-slash phrasings (Turkish, German, Arabic) were caught too since the
     substitution was a plain brand-word swap, not a literal-string match.
   - `app/src/main/res/values*/strings.xml` — 34 files (base + 33 locales), 66 lines:
     two repeated XML comments ("...collapses Overdrive's three FGS...", "The lock
     guards the OverDrive UI only..."). No `Overdrive/`-style folder text actually
     lived in strings.xml; that was a red herring in the task's own scope note. The
     `.overdrive_pin_reset` preserved-token line (base + `values-ar`) was explicitly
     skipped via a line-exclusion guard in the sed pass.
   - `app/src/main/assets/server-i18n/en.json` + `pt-BR.json` — 19 Telegram-bot
     message strings (`/help` text, update-status messages, backup messages). Found
     via broader scan; not literally named in the task's item-1 path list but
     squarely "user-visible text" under item 4.
   - Inline HTML fallback text mirroring the same i18n keys (shown before JS
     substitutes the real translation): `trips.html`, `recording.html`,
     `surveillance.html` — 1 line each.

2. **HA discovery origin name** — `HomeAssistantDiscovery.java`: `origin.put("name",
   "OverDrive")` → `"Wheelstop"` (exactly as asked), plus the `deviceName()` fallback
   strings (`"OverDrive (" + model + ")"`, `"OverDrive Vehicle"`, etc. — 3 lines) and
   one doc-comment line, since those are also plain display text, not identifiers.
   Left the `nodeId()` `"overdrive_"` prefix, the `"overdrive_vin_" + vin` id, and
   `origin.put("url", "https://www.overdrive.qd.je")` untouched — those are HA
   node/entity identifiers; renaming them would orphan every existing user's HA
   device/entity registration, and only `origin.name` was in the task's ask.

3. **Geocoding User-Agent** — `GeocodingResolver.java`: `"OverDrive/%s (...)"` →
   `"Wheelstop/%s (...)"`, `%s` and URL untouched, exactly as asked. Did **not**
   extend this to the other three UA strings found elsewhere (`MapNetworking.kt`,
   `OverpassPoiClient.kt`, `WeatherTemperature.java`, `ModelsApiHandler.java` all
   still send `"OverDrive/1.0"` / `"Overdrive/1.0"`) — the task named only
   `GeocodingResolver.java` for this category, and a UA string is arguably a
   functional wire-protocol value rather than prose/display text, so I left the
   other four as a judgment call. Flagging in case a follow-up pass should sweep them
   too.

4. **Brand prose in comments/docstrings/user-visible text → Wheelstop** — roughly
   120 individual line edits across ~55 files (Java/Kotlin doc comments, `//` and
   `/* */` comments, XML `<!-- -->` comments, JS/CSS header comments, log messages
   surfaced in the app's own in-app log viewer, notification channel display names/
   descriptions, an on-screen rendered "OVERDRIVE" wordmark in the anti-theft
   `ScreenDeterrent`, and the `"name"` title field in the two bundled MapLibre style
   JSON files). Representative examples: `StorageManager.java`, `StorageSetup.kt`,
   `RoadSenseMapActivity.kt`, `DaemonKeepaliveService.kt` (channel display name +
   description), `MediaPlaybackService.java` (notification content text),
   `ConfigBackupService.kt`/`ConfigBackupApiHandler.java`/`SurveillanceIpcServer.java`
   (backup-validation and update-notification message text — several of these were
   *stale* comments/messages that already didn't match reality: e.g.
   `SettingsFragment.kt`'s doc comment claimed the footer reads "OverDrive vX.Y" but
   `R.string.settings_footer_format` already said "Wheelstop %1$s · %2$s"; same
   pattern for `DashboardInsight.kt`'s uptime-insight doc comment vs. the already-
   renamed `R.plurals.dashboard_insight_uptime_*` resources — updated the comments to
   match reality), and the full `web/shared/*.js` / `*.css` file-header comment block
   (18 files, "Overdrive — <Module>." → "Wheelstop — <Module>.").

## Judgment calls / things deliberately left untouched

- **The legacy-app detection feature** (`ExclusivityPreflight.kt`,
  `ExclusivityBlockerActivity.kt`, the "legacy Overdrive install" checks in
  `DaemonStartupManager.kt`, `AndroidManifest.xml:338`, and `RecordingsIndex.java:1270`)
  — entirely untouched. In this codebase "Overdrive" here does **not** mean "this
  product" — it's a genuinely separate, still-installable legacy APK
  (`com.overdrive.app`, `ExclusivityPreflight.OLD_PKG`) that this app detects and
  offers to stop/disable/uninstall so the two can't fight over the shared camera/
  tunnel/MQTT-client-id. Button text like "Uninstall Overdrive" refers to that other
  app by its real name as it appears in Android's own app list; renaming it to
  "Wheelstop" would make the UI describe uninstalling itself. This is the one
  category where "Overdrive" is a correct, load-bearing proper noun, not leftover
  branding.
- **Two apparent functional gaps found (not fixed — out of scope for a prose-only
  pass, flagging for a follow-up)**:
  - `StorageManager.java:2117` `initVolumeDirectories()` still does
    `new File(volumePath, "Overdrive")` for **SD card and USB** volumes — only the
    *internal* base dir (`INTERNAL_BASE_DIR`) was migrated to `/storage/emulated/0/
    Wheelstop` by the earlier recording-folder task. `ExternalStorageCleaner.java:256`
    (`name.equals("Overdrive")`, a CDR-folder skip-check) is the same gap. Left both
    the code and their surrounding doc comments untouched together, since renaming
    just the comments would make them describe behavior the code doesn't actually
    have.
  - `SettingsAboutFragment.kt` (12 lines) — the in-app settings-backup import/export
    feature reads/writes `File(getExternalStorageDirectory(), "Overdrive")`
    (`/sdcard/Overdrive/backups/…`), a separate folder from the one Task C migrated.
    Left the function, its doc comments, and the `shareOverdrive()` method name
    entirely alone (same reasoning as above).
  - `file_paths.xml:28`'s FileProvider comment ("...internal /storage/emulated/0/
    Overdrive") is now stale (internal is actually already Wheelstop-named) but the
    SD/USB part of the same sentence is still accurate given the gap above — left the
    whole comment alone rather than making it internally inconsistent.
- **HTTP/URI protocol identifiers** left untouched as functional, not prose:
  `X-Overdrive-Daemon` / `X-Overdrive-Version` (`LogUploader.java`),
  `X-Overdrive-Fail-Reason` (`WebViewFragment.kt`), the `overdrive://revival/`
  custom URI scheme (`ProcessRevivalReceiver.kt`).
- **zrok / Tailscale hostname generation** (`ZrokLauncher.kt`, `ZrokController.kt`,
  `TailscaleLauncher.kt`) — all comments and code describing/enforcing that generated
  hostnames must literally start with lowercase `"overdrive"` were left untouched;
  this is the same functional DNS-naming convention the task's preserve list already
  calls out for zrok's `uniqueName`/`UNIQUE_NAME_PREFIX`, just phrased slightly
  differently in a few spots the literal grep token didn't anchor on.
- **PREFS_NAME / CHANNEL_ID / DB-name / cache-key / storage-key constants** —
  `overdrive_setup`, `overdrive_onboarding`, `overdrive_dashboard_insights`,
  `overdrive_media_playback`, `overdrive-tts`, `overdrive_locale`, `overdrive_theme`,
  `overdrive-ev-sprites`, `overdrive-3d-v3` (sw.js cache version), `overdrive-backup*`
  generated filenames — all left as code identifiers per the task's explicit "do not
  rename identifiers" instruction. Comments that merely *quote* one of these unchanged
  literals (e.g. `DaemonKeepaliveService.kt`'s two comments quoting the
  `"Overdrive:DaemonKeepalive"` wakelock tag, `OnboardingState.kt`'s comment quoting
  `"overdrive_setup"`) were left matching the unchanged literal rather than rewritten
  into a false statement.
- **`OverdriveApplication.kt`/`OverdriveApplication` class name** — left everywhere
  (declaration, `Log.w("OverdriveApplication", …)` tags matching the class name,
  and every comment in other files that correctly references this still-unchanged
  class name: `ScreenOffReceiver.kt`, `AppAudioCaptureController.java`,
  `RoadSenseOverlayService.kt`, `KeymapApiHandler.java`). Only the one doc-comment
  line describing the class in prose ("Application class for Overdrive.") was
  renamed, since the class itself is a code identifier out of scope for this pass.
- `PanoramicCameraGpu.java:2896` "(Ported from Overdrive-release PR #97.)" — left as
  a factual citation of the real upstream GitHub repo name, not brand prose.
- `SurveillanceApiHandler.java:1205`'s "`.overdrive` directory" comment — matches the
  preserved `/data/local/tmp/.overdrive` path (`SCREEN_DETERRENT_DIR`), left as-is.
- `overdrive:source` / `overdrive:license` / `overdrive:note` metadata **key names**
  in `dark_style.json` / `liberty_style.json` — left as a custom namespace prefix
  (MapLibre-style-spec convention for vendor metadata, not consumed by any code in
  this app); only the `"name"` title field value ("Overdrive Dark (night)" →
  "Wheelstop Dark (night)", etc.) was renamed since that's unambiguously display text.

## Verify

Preserved tokens, before and after — unchanged at 35 (confirms nothing in the
explicit preserve list was touched):

```
$ rg -c 'overdrive_config|overdrive-byd-cred|overdrive-config-bundle|overdrive_prefs|\.overdrive_device_id|\.overdrive_pin_reset|\.overdrive/audio|overdrive/models' app/src/main | wc -l
35        (same before and after this pass)
```

Full sweep, coordinator's exact filter:

```
$ rg -ni 'overdrive' app/src/main | rg -iv 'overdrive_config|overdrive-byd-cred|overdrive-config-bundle|overdrive_prefs|\.overdrive_device_id|\.overdrive_pin_reset|\.overdrive/audio|overdrive/models|/data/local/tmp/.*overdrive|uniqueName|UNIQUE_NAME_PREFIX' | wc -l
103
```

All 103 remaining hits were reviewed line-by-line and fall into the deliberate-leave
categories above:

- 2 map-style JSON files with only `overdrive:source` / `overdrive:license` /
  `overdrive:note` metadata keys left (values already renamed).
- Legacy-app-detection feature: `ExclusivityPreflight.kt` (5), `ExclusivityBlocker
  Activity.kt` (7), `DaemonStartupManager.kt` (3), `AndroidManifest.xml:338`,
  `RecordingsIndex.java:1270` = 17 lines.
- `com.overdrive.app` OLD_PKG literal = counted above.
- `OverdriveApplication` class name + matching log tags + comments referencing it
  correctly (unchanged class) across `OverdriveApplication.kt` (4),
  `AndroidManifest.xml:237`, `ScreenOffReceiver.kt`, `AppAudioCaptureController.java`
  (2), `RoadSenseOverlayService.kt`, `KeymapApiHandler.java` = 10 lines.
- PREFS_NAME/CHANNEL_ID/DB-name/storage-key/cache-key functional identifiers and
  comments quoting them unchanged: `DashboardInsight.kt`, `OnboardingState.kt` (2),
  `SetupGuideDialog.java`, `MediaPlaybackService.java` (2), `DaemonKeepaliveService.kt`
  (3), `core.js`, `theme.js`, `lang-picker.js`, `automations.js`, `backup-flow.js`,
  `ev-card-sprite-cache.js` (2), `NotificationStore.java` (2), `sw.js` (4),
  `vehicle-control-3d-test.html` = ~20 lines.
- zrok/Tailscale hostname-generation functional code+comments: `ZrokLauncher.kt` (4),
  `ZrokController.kt` (2), `TailscaleLauncher.kt` (1) = 7 lines.
- HTTP/URI protocol identifiers: `LogUploader.java` (2), `WebViewFragment.kt:840`,
  `ProcessRevivalReceiver.kt:110`, `MqttConnectionConfig.java` (2, historical
  migration-note comment quoting the old literal topic root) = 6 lines.
- Out-of-scope UA strings (not requested): `MapNetworking.kt`, `OverpassPoiClient.kt`,
  `WeatherTemperature.java`, `ModelsApiHandler.java` = 4 lines.
- HA entity/node identifiers (functional, not requested beyond `origin.name`):
  `HomeAssistantDiscovery.java` (3: `nodeId` prefix, vin id, origin url) = 3 lines.
- The two functional-gap clusters flagged above: `StorageManager.java` (3: probe
  file, doc comment tied to unrenamed code, the code itself), `ExternalStorageCleaner
  .java:256`, `SettingsAboutFragment.kt` (12), `file_paths.xml` (2),
  `UpdateLifecycle.java:197` (preserved `/data/local/tmp/overdrive_install.log`
  reference the top-level grep exclusion didn't anchor on because of the `case
  "$lf" in *overdrive_install.log)` glob syntax), `MainActivity.kt:909` and
  `BootReceiver.kt:130` (both abbreviated in-comment references to the preserved
  `/data/local/tmp/overdrive_update_progress.json` / `overdrive_update_in_progress`
  paths — same reasoning) = ~22 lines.
- `SurveillanceEngineGpu.java` thread names (3), `PanoramicCameraGpu.java:2896` repo
  citation, `SurveillanceApiHandler.java:1205` (preserved `.overdrive` dir comment) =
  5 lines.

Nothing in the remaining 103 lines is leftover Wheelstop-brand prose that should have
been renamed under this task's scope.

## Note on `.superpowers/de-overdrive/progress.md`

This file had a pre-existing uncommitted one-line edit (Task C's ledger entry,
matching real commit `58f9e8b9`) sitting in the working tree before this task started
— not something this pass produced. Appended this task's own ledger line alongside
it and committed both together as routine ledger bookkeeping; the commit's actual
code diff is unaffected by that.

## Build

Not run, per instructions.
