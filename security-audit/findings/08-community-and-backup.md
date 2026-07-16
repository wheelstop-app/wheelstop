# 08 — Community Automations & Config Backup

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

Two features let *external content* enter the trust boundary: the "Community Automations" catalog (browse/share/import automations others built) and config backup/restore. Both are places where an attacker's data becomes the car's configuration or behaviour.

---

<a name="f10"></a>
## F10 — 🟠 High: unsigned config restore deep-merges attacker JSON into control sections

A backup bundle has **no signature, no HMAC, and no origin binding** — validation only checks a format string and that each section is a JSON object. `applyBundle` then deep-merges every non-excluded section from the supplied file into live config:

- Merge routine: [`ConfigBackupService.kt#L106`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/ConfigBackupService.kt#L106) (`deepMergeInto`)
- Excluded sections are only housekeeping keys: [`ConfigBackupService.kt#L64`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/ConfigBackupService.kt#L64) (`updates/lastModified/version/configSeq`)
- Credential sections are only `bydCloud/navMap/telegram`: [`ConfigBackupService.kt#L71`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/ConfigBackupService.kt#L71)

> **Correction (per PR review):** an earlier draft named the **MQTT broker** as a restore-merge target. That is wrong and has been removed. The bundle only carries `settings.unified` (the `overdrive_config.json` sections); **MQTT connections are stored in a *separate* file** `/data/local/tmp/mqtt_connections.json` by `MqttConnectionStore` ([`MqttConnectionStore.java#L28`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttConnectionStore.java#L28)), which `UnifiedConfigManager` never touches — the `buildBundle` docstring's "what it contains" list confirms MQTT is not included. So a crafted backup **cannot** set the MQTT broker/credentials. Thanks to the reviewer for catching this.

The still-valid restore-merge targets are the **unified-config sections that are neither excluded nor credential-guarded**, which an attacker bundle can set wholesale:

- **`telegram`** — via the credential-skip bypass (F10b below): points the car's command bot at the attacker (this *is* a vehicle-command channel, per [doc 07](07-telegram-bot.md)'s command surface).
- **`community.workerUrl`** — repoints the automation catalog origin to an attacker server (F-community below).
- **`cloudflared`** — the tunnel token (a unified section).

The proxy endpoint is **not** a target (it is hard-coded in `SingboxLauncher`, not config-driven), so restore cannot repoint it.

### F10b — the Telegram "credential-skip" guard is bypassable with plaintext

`telegram` is a credential section, but it is only skipped when the bundle's `ENC:` blobs fail to decrypt — and the decrypt-check **returns true when there are no `ENC:` blobs at all**. An attacker supplies a **plaintext** `telegram.botToken` + their own `chatId` (no `ENC:` prefix); the guard passes and the section merges. The victim's car then polls the attacker's bot and treats the attacker's chat as the authorised controller.

**Exploit path:** craft a bundle with a valid `manifest.format`, socially-engineer the owner into "Restore settings" (restore is presented as a normal feature and needs only JWT + `confirm=true`). No integrity check rejects the foreign file.

**Impact:** takeover of the car's Telegram command channel (and repointing of the community/tunnel config) — **but only after** the attacker both obtains a valid session (JWT) *and* induces the owner to perform the restore with `confirm=true`. The backup file alone is not sufficient; it is the payload, not the whole exploit. Given those prerequisites, the consequence is an attacker-controlled Telegram bot able to issue vehicle commands.

---

<a name="f11"></a>
## F11 — 🟠 High: the backup bundle ships the ciphertext *and* the key that decrypts it

The bundle contains all credential sections as `ENC:` blobs **and** the device-id file that the credential cipher derives its key from:

- [`ConfigBackupService.kt#L173`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/ConfigBackupService.kt#L173):
  ```kotlin
  // Snapshot the DID so the ENC: secrets remain decryptable after a factory reset.
  readDid()?.let { bundle.put("did", it) }
  ```

Since both halves travel together, an intercepted or leaked backup yields **plaintext** BYD-cloud login + password, navMap routing API key, and the Telegram bot token. Export is JWT-gated but explicitly permitted over the tunnel/public mode, so a relayed/forged JWT (see [doc 01](01-network-exposure-and-auth.md)) or a phished export pulls it remotely. The only defence is a UI "keep it private" warning.

**Impact:** one exfiltrated backup = total credential compromise ("keys to the kingdom").

---

<a name="f15"></a>
## F15 — 🟠 High: imported/tested community automations reach the full vehicle-control catalog

A shared automation may invoke the curated action catalog, which includes vehicle unlock, surveillance disable, recording-off, open windows/trunk/sunroof, and app launch:

- `unlock` → `POST /api/vehicle/unlock`: [`Actions.java#L37`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/automation/action/Actions.java#L37)
- surveillance action → `/api/surveillance/${action}` (incl. disable): [`Actions.java#L423`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/automation/action/Actions.java#L423)
- launch app → `/api/apps/launch`: [`Actions.java#L456`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/automation/action/Actions.java#L456)

Import guards exist but are thin: shell actions are refused on **both** the share and import paths (the share-side `403` is at [`CommunityApiHandler.kt#L142`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/community/CommunityApiHandler.kt#L142); the no-shell-on-both-publish-and-import policy is documented at [`#L35`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/community/CommunityApiHandler.kt#L35)), and imports are forced `disabled=true`. But there is **no review gate and no capability limit** beyond shell, and two one-action paths turn a shared automation live:

- the user **enabling** it (it then fires on its own trigger — e.g. a "door opens" trigger that unlocks + disables surveillance), or
- `POST /api/automations/test/{id}`, which runs the action chain immediately, **ignoring conditions and the disabled flag**: [`AutomationApiHandler.java#L51`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AutomationApiHandler.java#L51) → `triggerActions(id, false)` at [`#L219`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AutomationApiHandler.java#L219).

**Exploit path:** publish an innocuously-named automation ("Test your horn") whose actions are `unlock` + `surveillance disable`; the victim taps **Test** or enables it after a cursory glance.

**Positive bounding (verified):** an imported automation **cannot** run shell and **cannot** hit arbitrary endpoints — a path allowlist blocks `/api/backup`, `/api/update`, `/api/debug`, `/api/telegram`, `/api/keymap`, and action parameters are type-validated (enum allowlists, int clamping, app-id regex). So its power is bounded to the curated catalog, and **no location-exfil action exists**. The risk is vehicle control contingent on one deliberate-but-easily-induced user tap, not silent auto-exec or data theft.

---

## F-community — 🟡 Medium: single hard-coded shared backend, no content signing, no pinning

All installs pool into one Cloudflare Worker; content is not signed, and the client uses a plain OkHttp client with **no certificate pinning**:

- [`CommunityConfig.kt#L47`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/community/config/CommunityConfig.kt#L47) → `DEFAULT_WORKER_URL = "https://community-edge.yash321sri.workers.dev"`

The worker URL is also restore-overridable (F10), so the same restore vector can repoint the catalog origin to an attacker server. Because the endpoint is a `*.workers.dev` subdomain, the relevant control-plane risk is **compromise of that single Cloudflare account** — not independent DNS takeover (DNS is Cloudflare's; there is no separate delegated zone to hijack). Either way, whoever controls that account can serve the whole fleet's catalog. See [doc 03](03-proxy-mitm-and-infrastructure.md) for the infrastructure note (DNS resolves it to Cloudflare anycast on the `workers.dev` namespace).

---

## Recommendations (this document)

1. **Sign backup bundles** and restrict which sections restore may write — exclude/allowlist MQTT/telegram/proxy/community/tunnel from the merge (F10/F10b).
2. Stop bundling the device-id alongside the ciphertext it decrypts (F11).
3. Require an explicit review/confirmation before a community automation can be **enabled or Tested**, and treat the capability probe as blocking, not advisory (F15).
4. Sign community content and/or pin the backend certificate (F-community).
