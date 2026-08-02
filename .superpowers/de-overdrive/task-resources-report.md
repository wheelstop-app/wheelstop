# Task: rename Overdrive-branded Android resource names to Wheelstop

Scope: `app/src/main` (res/ + java/kotlin + AndroidManifest.xml). `app/src/main/assets/**` was
left untouched (JS/web pass, explicitly out of scope).

## What was renamed

### Explicitly requested (all three families)

1. **`Theme.Overdrive*` → `Theme.Wheelstop*`** (dot form in XML/manifest, `Theme_Overdrive*` →
   `Theme_Wheelstop*` underscore form in `R.style.*` code refs). Covers `Theme.Overdrive.M3`,
   `Theme.Overdrive.M3.BottomSheet`, `Theme.Overdrive.M3.Dialog`.
2. **`Widget.Overdrive*` → `Widget.Wheelstop*`**. Covers the full `Widget.Overdrive.M3.*` family
   (CardView, CardView.Outlined, Button, Button.Outlined, Button.Tonal, Button.Text, PinKey,
   PinKey.Action, Toolbar, Slider, BottomSheet, NavigationRail, NavigationRail.ActiveIndicator,
   SegmentedButton, Button.Dialog.Primary, Button.Dialog.Neutral).
3. **`overdrive_status_*` → `wheelstop_status_*`** — both the 8 colour resources in
   `colors_m3.xml` (values + values-night: `success/warning/danger/info` × `light/dark`) and the
   `overdrive_status_summary_title` / `overdrive_status_summary_text` **string** resources across
   all ~34 locale `strings.xml` files (the task's own example used the string pair as a "colour
   resource" example — treated as a naming error, renamed both types under the shared prefix).
4. **File renames**: `git mv` both `themes_overdrive.xml` files to `themes_wheelstop.xml`
   (values/ and values-night/). Comments inside referencing the old filename were updated.

### Judgment calls beyond the literal bullet list (flagging for review)

The task's own VERIFY grep (`Widget\.Overdrive` with no `.M3` anchor) turned out to also match
two style families the RENAME bullets didn't explicitly name:
- `Widget.Overdrive.NavRow.LeadingIcon[.Route]` and `Widget.Overdrive.HazardPill`
  (defined in `res/values/themes.xml`, used by the RoadSense nav-row/route-option layouts) →
  renamed to `Widget.Wheelstop.*`. These were required to satisfy the exact VERIFY command given.

Additionally, in the same theme files I found four more Overdrive-branded **style** resource
families that are not literally `Theme.Overdrive` / `Widget.Overdrive` substrings, so the given
VERIFY grep would NOT have caught them, but they are unambiguously "Overdrive-branded Android
resource names" per the task's opening sentence, and leaving them half-renamed inside files I was
already editing seemed wrong:
- `TextAppearance.Overdrive.*` (DisplaySmall, HeadlineLarge/Medium/Small, TitleLarge/Medium,
  LabelLarge/Medium, plus `TextAppearance.Overdrive.NavRow.Title`/`.Subtitle`)
- `ShapeAppearance.Overdrive.*` (SmallComponent, LargeComponent, RouteSheet)
- `ThemeOverlay.Overdrive.M3.MaterialAlertDialog`
- `MaterialAlertDialog.Overdrive.*` (Title, Body, TitleIcon)

All four renamed to their `Wheelstop` equivalents, with every reference (all in `res/`, no code
refs to these specific ones) updated in the same pass. **Flagging this explicitly** since it's an
interpretation of "every Overdrive-branded resource" beyond the literal 3-bullet list — easy to
revert if a separate pass was meant to own these.

One more judgment call: `DaemonKeepaliveService.kt` has
`private const val SUMMARY_CHANNEL_ID = "overdrive_status_summary"` — a plain Kotlin string
literal used as a runtime Android `NotificationChannel` ID, not an `R.*`-checked resource.
Renaming it is a minor behavioural change (existing installs will get a fresh notification
channel, losing any per-channel mute state the user had set). It was renamed anyway because the
task's VERIFY command (`rg 'overdrive_status'`) is written broadly enough to catch this literal
and require 0 hits. Left untouched (correctly out of scope, not resource names, not matched by
the case-sensitive VERIFY grep): the hardcoded channel *display name* `"Overdrive Status"`, the
wakelock tag `"Overdrive:DaemonKeepalive"`, the `OverdriveApplication` class name, and all prose
comments mentioning "Overdrive" (capital O, not `overdrive_status`).

## Files touched (60 content edits + 2 renames)

- `app/src/main/AndroidManifest.xml` (5× `android:theme="@style/Theme.Overdrive.M3"` → `Theme.Wheelstop.M3`)
- `app/src/main/res/values/themes_overdrive.xml` → `values/themes_wheelstop.xml` (git mv + content)
- `app/src/main/res/values-night/themes_overdrive.xml` → `values-night/themes_wheelstop.xml` (git mv + content)
- `app/src/main/res/values/themes.xml`, `values-night/themes.xml` (comments + `ShapeAppearance`/`TextAppearance`/`Widget.*NavRow*`/`HazardPill` defs)
- `app/src/main/res/values/colors_m3.xml`, `values-night/colors_m3.xml` (8 colour defs)
- `app/src/main/res/values/strings.xml` + all 33 locale `values-*/strings.xml` (2 string defs each)
- Layouts: `activity_pin_lock.xml` (+ `layout-land`), `activity_roadsense_map.xml`,
  `dialog_language_picker.xml`, `item_nav_route_option.xml`, `item_nav_search_result.xml`,
  `item_nav_stop.xml`, `sheet_roadsense_hazard.xml`
- Drawable: `res/drawable/dialog_m3_background.xml` (comment only)
- Kotlin/Java: `CloudflaredPaidConfig.kt`, `RoadSenseMapActivity.kt`, `SetupGuideDialog.java`,
  `DaemonKeepaliveService.kt`, `MainActivity.kt`, `LanguagePickerDialog.kt`, `DaemonsFragment.kt`,
  `DashboardFragment.kt`, `RecordingLibraryFragment.kt`, `UpdateDialog.java`
  (all `R.style.Theme_Overdrive_M3_{BottomSheet,Dialog}` → `Theme_Wheelstop_M3_*`)

## Verify output

```
$ rg -n 'Theme\.Overdrive|Theme_Overdrive|Widget\.Overdrive|overdrive_status' app/src/main
(no output — 0 hits)

$ rg -c 'wheelstop_status|Theme\.Wheelstop|Widget\.Wheelstop' app/src/main
169 total hits across 50 files

$ find app/src/main -iname "themes_wheelstop.xml"
app/src/main/res/values/themes_wheelstop.xml
app/src/main/res/values-night/themes_wheelstop.xml

$ find app/src/main -iname "themes_overdrive.xml"
(no output)
```

Also ran a definition/reference cross-check for every renamed style family (`Theme`, `Widget`,
`TextAppearance`, `ShapeAppearance`, `ThemeOverlay`, `MaterialAlertDialog` × `.Wheelstop`): every
reference resolves to a matching definition, no orphans introduced. A few style defs
(`Widget.Wheelstop.M3.Button.Text`, `.CardView.Outlined`, `.NavigationRail` (bare),
`.SegmentedButton`) are unreferenced by any layout/code — this was true before the rename too
(pre-existing unused styles), not a regression.

## DO-NOT-TOUCH verification

Confirmed untouched: `overdrive_config`/`overdrive_config.json` (55 hits, all intact),
`overdrive_prefs`, `overdrive-byd-cred-v1`, `overdrive-config-bundle`, `.overdrive_device_id`,
`.overdrive_pin_reset`, `.overdrive/audio`, `/data/local/tmp/*overdrive*` paths, zrok
`uniqueName`/`UNIQUE_NAME_PREFIX = "overdrive"` (`ZrokLauncher.kt`). No `security-audit/`
directory exists under `app/src/main`. `dimens_overdrive.xml` and `OverdriveApplication.kt`
left untouched (filename/class name, not resource identifiers, not in the RENAME list, not
matched by the VERIFY grep).

## Anything I was unsure about

- The two "judgment calls" sections above (the extra style families, and the notification
  channel ID literal) — both were necessary to fully satisfy the letter of "every reference must
  be updated" and the given VERIFY grep, but go slightly beyond the literal 3-bullet RENAME list.
  Worth a quick look if a separate task was meant to own the `TextAppearance`/`ShapeAppearance`/
  `MaterialAlertDialog`/`ThemeOverlay` families or the notification channel ID.
- Did not touch the stale doc comment in `themes_wheelstop.xml` claiming a "legacy `Theme.Wheelstop`
  alias" exists for bare (non-`.M3`) manifest entries — this alias doesn't actually appear to
  exist in the current codebase (the manifest only ever used `.M3`), so the comment was already
  stale pre-rename. Left as-is (content unrelated to the rename itself, sed just substituted the
  brand word inside the pre-existing stale text).
