# 04 — OTA Update Integrity

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

The updater downloads an APK and installs it with elevated privilege. On a device that controls a car, the update channel is a code-execution channel — its trust chain matters as much as the vehicle API itself.

---

<a name="f3"></a>
## F3 — 🟠 High: no *app-side* integrity verification or anti-rollback on the update package

> **Scope correction (per PR review):** an earlier draft implied this permits "arbitrary APK replacement / RCE via the proxy." That is inaccurate and has been corrected. `pm install -r -d` still requires the replacement APK to be **signed by the same certificate** as the installed app — Android enforces that independently of this app. So this finding is *not* arbitrary code execution on its own. The real, still-valid gaps are: (a) **no app-side SHA-256/signature/pinning** on the download, and (b) **no anti-rollback**, which together allow a **silent downgrade to a validly-signed older (vulnerable) build**, and would allow worse only if the developer signing key is leaked or the download endpoint is unvalidated. Severity adjusted Critical → High accordingly.

### The download path

The APK URL comes from the GitHub releases API (`browser_download_url`) and is fetched over HTTPS — but that fetch **transits the hard-coded proxy** from [doc 03](03-proxy-mitm-and-infrastructure.md) (`ProxyHelper.getHttpProxy()`), and the shell download builder uses `wget -q` / `curl -sL` (both validate certificates by default) with **no certificate *pinning*** on top. TLS validation means the forwarding proxy cannot silently swap the APK for a differently-signed one; the gap is the absence of an *app-level* integrity check layered on top, which pinning or a signed digest would provide:

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

1. **Rollback / downgrade is possible without breaking any signature.** Because `-d` permits downgrades and there is no anti-rollback version floor, an *older, validly-signed* build with known vulnerabilities can be installed. The realistic vectors are **not** a network MITM (the GitHub download is HTTPS-validated — see the F2 correction in [doc 03](03-proxy-mitm-and-infrastructure.md)), but rather: a **local actor who stages an APK** into the shell-writable `/data/local/tmp` staging path ([doc 02](02-local-rce-and-ipc.md) F16), the Telegram **`/update install [tag]`** command letting an owner-position attacker pick an older tag ([doc 07](07-telegram-bot.md)), or any download endpoint that is ever plaintext/unvalidated.
2. **If the developer signing key is ever weak, leaked, or the build is unsigned/debuggable, there is no second line of defence** — a malicious APK installs silently at elevated privilege and gains the full BYD HAL permission set on next launch. This is the only path to *arbitrary* (non-same-signer) code, and it depends on the signing key, not on this app's (absent) checks.

**Impact:** silent downgrade to a validly-signed vulnerable build via a local or owner-position actor; arbitrary code execution only if the signing key is compromised. The app itself adds no integrity defence-in-depth beyond Android's same-signer enforcement.

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
