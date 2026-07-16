# 04 — OTA Update Integrity

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

The updater downloads an APK and installs it with elevated privilege. On a device that controls a car, the update channel is a code-execution channel — its trust chain matters as much as the vehicle API itself.

---

<a name="f3"></a>
## F3 — 🔴 Critical: no cryptographic verification of the update package

### The download path is attacker-influenceable

The APK URL comes from the GitHub releases API (`browser_download_url`) and is fetched over HTTP(S) — but that fetch **transits the hard-coded proxy** from [doc 03](03-proxy-mitm-and-infrastructure.md) (`ProxyHelper.getHttpProxy()`), and the shell download builder uses `wget`/`curl` with **no certificate pinning**:

- Shell install/download one-liner (URL interpolated): [`AppUpdater.java#L1304`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/updater/AppUpdater.java#L1304)

### "Verification" is a file-size check

The only check applied to the downloaded APK before install is that it is larger than 1 MB:

- [`AppUpdater.java#L844`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/updater/AppUpdater.java#L844):
  ```java
  if (fileSize < 1_000_000) {
      // treat as failed download
  }
  ```

There is **no SHA-256 comparison, no signature verification, no pinned certificate, and no comparison against a release-signed digest**. Contrast this with the 3D-model download path, which *does* verify SHA-256 ([`ModelsApiHandler.java`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/ModelsApiHandler.java)) — so the primitive exists in the codebase but is not applied to the far more sensitive APK.

### Install is silent, elevated, and allows downgrade

- [`AppUpdater.java#L932`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/updater/AppUpdater.java#L932) and [`#L1304`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/updater/AppUpdater.java#L1304):
  ```
  pm install -r -d <apk>
  ```
  `-r` reinstalls keeping data; **`-d` explicitly allows downgrades**. Installation runs through the ADB/UID-2000 shell, followed by an `am start` auto-relaunch — no user interaction.

### What actually stands between an attacker and RCE

Android's `PackageManager` enforces that a `-r` replacement APK is **signed by the same certificate** as the installed app. That signer check is the *only* remaining barrier — the app contributes nothing. Two consequences:

1. **Rollback attack is fully viable today.** Because `-d` permits downgrades and there is no anti-rollback version floor, an attacker who controls the download (via the proxy in F2, or any MITM on the un-pinned fetch) can push an **older, validly-signed OverDrive build with known vulnerabilities**, then exploit those.
2. **If the developer signing key is ever weak, leaked, or the build is unsigned/debuggable, there is no second line of defence** — the malicious APK installs silently at elevated privilege and gains the full BYD HAL permission set on next launch.

**Impact:** a MITM on the update path (which the bundled proxy operator inherently has) can silently downgrade the app, and — absent the OS signer check — could achieve arbitrary code execution on a device that actuates a car.

---

## Remote trigger surface

The install/channel endpoints are reachable over the tunnel and are gated only by a soft "public mode" flag rather than a per-request privilege check:

- Install/channel gating on `CameraDaemon.isPublicMode()` (a mutable string compare), not authentication: [`UpdateApiHandler.java`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/UpdateApiHandler.java)

So a remote actor who passes JWT auth over a tunnel (see F19) can trigger an OTA install whenever public mode happens to be off — and the Telegram `/update install` command can do the same for an owner-position attacker (see [doc 07](07-telegram-bot.md)).

---

## Recommendations (this document)

1. Verify a **release-signed SHA-256 (or detached signature)** over the APK before install; fail closed on mismatch (F3).
2. **Pin the certificate** for the GitHub download and route the update fetch *outside* the bundled proxy (F3 + F2).
3. Enforce an **anti-rollback version floor**; drop `-d` unless a downgrade is explicitly, locally authorised (F3).
4. Gate install behind a real privilege check, not a mutable mode flag.
