# Task: rename Overdrive-branded JS identifiers to Wheelstop (web dashboard)

Scope: `app/src/main/assets/web` (*.js, *.html, *.css) only. Branch `cleanup/de-overdrive`.

## What was renamed

1. **`OverdriveAppShell` → `WheelstopAppShell`** — global `window.OverdriveAppShell`.
   32 occurrences across 8 files (definition + every read/comment mention):
   - **Defined** in `app/src/main/assets/web/shared/app-shell.js:982`
     (`window.OverdriveAppShell = window.OverdriveAppShell || {};`), with methods attached at
     lines 983 (`refreshVehicle`), 990 (`mountVehicleCanvas`), 1144 (`setSoc`), 1164
     (`setCharging`), 1184 (`rewireNavGroups`). Also 2 doc-comment mentions in the same file
     (lines 170, 980).
   - **Used** (reads `window.OverdriveAppShell`) in: `shared/vehicle-control.js` (2),
     `shared/core.js` (4), `shared/update-flow.js` (2), `shared/trips.js` (3, incl. 1 comment),
     `shared/map.js` (2, incl. 1 comment), `local/index.html` (2, incl. 1 comment),
     `local/live-view.html` (4, incl. 2 comments).
   - Per-file counts (`rg -o 'OverdriveAppShell' <file> | wc -l` before rename): index.html=2,
     live-view.html=4, app-shell.js=9, map.js=2, core.js=6, trips.js=3, vehicle-control.js=3,
     update-flow.js=3 → sums to 32, matching the task's expected count.

2. **`OverdrivePush` → `WheelstopPush`** — global `window.OverdrivePush`. 7 occurrences:
   - **Defined** in `app/src/main/assets/web/shared/pwa-init.js:311`
     (`window.OverdrivePush = { async requestAndSubscribe() {...}, ... }`).
   - **Used** in `app/src/main/assets/web/local/notifications.html` — 6 occurrences across
     `removeDevice()`, the enable-button handler, and the disable-button handler (lines 1940,
     1946, 1988, 1993, 2029×2).

3. **Custom event `'overdrive:vehicle-changed'` → `'wheelstop:vehicle-changed'`**.
   - Dispatch sites in `shared/app-shell.js`: line 756 (`new CustomEvent(...)`, modern path) and
     line 764 (`ev.initCustomEvent(...)`, legacy/IE fallback path) — both in the same function,
     both updated.
   - Doc-comment mention in `local/index.html:62`.
   - Searched the whole scope for any other `overdrive:<name>` custom-event prefix — these two
     dispatch sites (same event name, two code paths) were the only ones found; there were no
     `addEventListener('overdrive:...')` listener sites anywhere in scope (the event doesn't
     appear to have any registered listeners in this codebase snapshot).

4. **`overdrive.`-prefixed storage keys → `wheelstop.`-prefixed**:
   - `'overdrive.dashboard.cardOrder'` → `'wheelstop.dashboard.cardOrder'` in
     `local/index.html:984` (localStorage key for dashboard card ordering).
   - `'overdrive.navGroup.' + slug` → `'wheelstop.navGroup.' + slug` in `shared/app-shell.js`,
     both the getter (`readState`, line 894) and setter (`writeState`, line 898).

## Files touched (10 files, 37 line changes: 37 insertions / 37 deletions)

- `app/src/main/assets/web/local/index.html`
- `app/src/main/assets/web/local/live-view.html`
- `app/src/main/assets/web/local/notifications.html`
- `app/src/main/assets/web/shared/app-shell.js`
- `app/src/main/assets/web/shared/core.js`
- `app/src/main/assets/web/shared/map.js`
- `app/src/main/assets/web/shared/pwa-init.js`
- `app/src/main/assets/web/shared/trips.js`
- `app/src/main/assets/web/shared/update-flow.js`
- `app/src/main/assets/web/shared/vehicle-control.js`

No `.css` file needed changes (none of the renamed identifiers/keys/events appear in CSS).

## Verify output

```
$ rg -n 'OverdriveAppShell|OverdrivePush' app/src/main/assets/web
(no output — 0 hits)

$ rg -n "overdrive:[a-z-]+|['\"]overdrive\." app/src/main/assets/web
(no output — 0 hits)

$ rg -n 'WheelstopAppShell|WheelstopPush' app/src/main/assets/web
32 WheelstopAppShell hits across index.html, live-view.html, app-shell.js, map.js, core.js,
trips.js, vehicle-control.js, update-flow.js
7 WheelstopPush hits across notifications.html (6) and pwa-init.js (1, the definition)
```

Cross-checked every reference site resolves to the definition site (`window.WheelstopAppShell =
window.WheelstopAppShell || {}` in app-shell.js; `window.WheelstopPush = {...}` in pwa-init.js) —
no orphaned reads introduced.

Also confirmed the event rename landed on both dispatch sites and the wheelstop-prefixed keys
landed correctly:

```
$ rg -n "wheelstop:vehicle-changed|['\"]wheelstop\." app/src/main/assets/web
local/index.html:62    (comment)
local/index.html:984   var STORAGE_KEY = 'wheelstop.dashboard.cardOrder';
shared/app-shell.js:756  ev = new CustomEvent('wheelstop:vehicle-changed', {
shared/app-shell.js:764  ev.initCustomEvent('wheelstop:vehicle-changed', true, true, {
shared/app-shell.js:894  localStorage.getItem('wheelstop.navGroup.' + slug) === '1'
shared/app-shell.js:898  localStorage.setItem('wheelstop.navGroup.' + slug, ...)
```

## DO-NOT-TOUCH verification

Confirmed untouched (spot-checked with `rg`):
- `overdrive_locale` (localStorage key, `core.js:30` and `lang-picker.js:187`) — underscore form,
  not the `overdrive.` dotted-prefix pattern named in scope, left as-is.
- `/data/local/tmp/overdrive/models/` path comments in `vehicle-control.js` (lines 505, 1118).
- `overdrive_config` — not present in this scope's files (checked, 0 hits; the string lives
  elsewhere in the repo per the task note, out of scope here).
- `OverdriveEvCard3D` / `OverdriveEvSpriteCache` / `OverdriveDisableEvCard3D` /
  `data-overdrive-ev-card-3d` / `data-overdrive-collapse` — a *different* family of globals/DOM
  markers (not named in the RENAME list) defined in `ev-card-3d.js`, `ev-card-sprite-cache.js`,
  and referenced throughout `app-shell.js`/`map.js`/`core.js`. Left fully untouched — these look
  like they belong to a separate rename pass, not this one.
- Prose brand words ("OverDrive PWA bootstrap", "OverDrive - Trip Analytics Module v2", "inside
  the Overdrive shell", "Overdrive Android shell") in header/doc comments — left as-is.
- No `security-audit/` directory exists under `app/src/main/assets/web`.

## Follow-up pass: remaining Overdrive* JS family + data-overdrive-* attributes

The coordinator asked for the second `Overdrive*` global family (flagged as a concern above) to be
renamed too, as part of the same full de-Overdrive sweep. Renamed:

- **`OverdriveEvCard3D` → `WheelstopEvCard3D`** — defined in
  `app/src/main/assets/web/shared/ev-card-3d.js:304` (constructor function), with
  `root.WheelstopEvCard3D = WheelstopEvCard3D` at line 787 exposing it globally, plus all
  `.prototype.*` method definitions in the same file. Used/read via `window.WheelstopEvCard3D` in
  `shared/app-shell.js` (8 sites: guard checks, instantiation, doc comments) and mentioned in
  comments in `shared/map.js` (2) and `shared/core.js` (1).
- **`OverdriveEvSpriteCache` → `WheelstopEvSpriteCache`** — defined in
  `shared/ev-card-sprite-cache.js` (guard at line 37, `root.WheelstopEvSpriteCache = {...}` at
  line 347). Used in `shared/app-shell.js` (4 sites, all `window.WheelstopEvSpriteCache` reads).
- **`OverdriveDisableEvCard3D` → `WheelstopDisableEvCard3D`** — an opt-out flag only referenced
  inside `shared/app-shell.js` (comment + `if (window.WheelstopDisableEvCard3D) return;`); no page
  in the repo actually sets it, so no other file needed changes.
- **`__overdriveEvCard3dVendorPromise` → `__wheelstopEvCard3dVendorPromise`** — an internal
  `root.*`-scoped (i.e. `window`-scoped) vendor-load cache in `ev-card-3d.js`, not explicitly
  named in the coordinator's bullet list (it's lowercase-prefixed, not `Overdrive<Word>`), but
  it's unambiguously the same brand word embedded in a global identifier, so renamed for
  consistency with the rest of the file (6 occurrences, all within `ev-card-3d.js`).
- **`data-overdrive-ev-card-3d` → `data-wheelstop-ev-card-3d`** and
  **`data-overdrive-collapse` → `data-wheelstop-collapse`** — both DOM data-attribute markers are
  set (`setAttribute`) and read (`querySelector('script[data-...]')`) entirely within
  `shared/app-shell.js` itself (self-referential script-injection idempotency guards); confirmed
  via repo-wide grep that no HTML template or other JS file references either attribute, so no
  `dataset.*` reads or CSS `[data-overdrive-...]` selectors existed to update.

Files touched in this pass: `shared/ev-card-3d.js`, `shared/ev-card-sprite-cache.js`,
`shared/app-shell.js`, `shared/map.js`, `shared/core.js` (51 insertions / 51 deletions).

### Verify (coordinator's exact command)

```
$ rg -n 'Overdrive|data-overdrive' app/src/main/assets/web | rg -iv \
    'overdrive_config|/tmp/.*overdrive|overdrive/models|DCIM/Overdrive|saved to Overdrive|the Overdrive (shell|Android)|// .*Overdrive'
```

Remaining output is exclusively the two allowed categories:
- i18n `storage_path_default` / `events_saved_to_default` strings in the *non-English* locale
  JSON files (`i18n/it.json`, `de.json`, `ja.json`, ... — ~30 files) containing translated forms
  of "saved to Overdrive/trips" etc. These are the same DCIM/Overdrive-folder display-text family
  as the English string the filter already excludes; the filter regex only matched the literal
  English phrase "saved to Overdrive", so the translated equivalents surface in the diff output,
  but they're the identical out-of-scope category (recording-folder display text, another pass).
- `/* ... */`-style header prose comments ("`* Overdrive — EV-card sprite cache.`",
  "`* Overdrive — App-shell visual identity layer.`", "`The Overdrive accent stripe`", etc.) across
  ~9 files. These use a leading `*` inside a block comment, not `//`, so the filter's
  `// .*Overdrive` clause doesn't strip them — same "prose brand word in a comment" category the
  coordinator already scoped out, just a block-comment style the given regex didn't happen to
  anchor on.

No JS identifiers, `data-overdrive-*` attributes, or `window.Overdrive*` globals remain anywhere
in `app/src/main/assets/web` after this pass.

```
$ rg -c 'OverdriveEvCard3D|OverdriveEvSpriteCache|OverdriveDisableEvCard3D|data-overdrive-|__overdriveEvCard3dVendorPromise' app/src/main/assets/web
(no output — 0 hits)

$ rg -n 'WheelstopEvCard3D|WheelstopEvSpriteCache|WheelstopDisableEvCard3D|data-wheelstop-|__wheelstopEvCard3dVendorPromise' app/src/main/assets/web | wc -l
51
```

## Concerns / judgment calls

- The task's occurrence counts ("~32", "~7") matched exactly (32 and 7) once comment mentions of
  the identifier names were included in the rename. I renamed those comment mentions too (e.g.
  `app-shell.js:170`, `:980`, `map.js:116`, `trips.js` comment, `live-view.html` comments,
  `index.html:62`) since they literally spell out the JS identifier/event name being renamed
  (stale references would be misleading documentation), and doing so was required to hit the
  exact expected counts. This is consistent with "definition AND everywhere it's read" — comments
  citing the identifier are effectively part of "everywhere it's referenced" for documentation
  purposes.
- Found and deliberately left alone a second, unrelated `Overdrive*` global family
  (`OverdriveEvCard3D`, `OverdriveEvSpriteCache`, `OverdriveDisableEvCard3D`, plus two
  `data-overdrive-*` DOM attribute markers) that is NOT in the RENAME list. Flagging this in case
  it was meant to be covered by this task or a sibling one — as given, the instructions named only
  `OverdriveAppShell` and `OverdrivePush`, so I left this family untouched.
- No `addEventListener` site for `overdrive:vehicle-changed` (or any other `overdrive:` event) was
  found anywhere in scope — only the two dispatch sites in `app-shell.js`. Nothing to break at
  runtime from missing a listener rename; there simply isn't one in this codebase snapshot.
- No build was run per instructions (JS is not compile-checked anyway).
