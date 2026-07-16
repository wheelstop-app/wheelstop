# OverDrive — Security Audit

A defensive security review of the **OverDrive** vehicle-control / surveillance application for BYD head units, performed to answer a single question:

> *Can an external actor — someone who is not the owner and is not authorised — observe, monitor, or control the vehicle through this application?*

**Short answer: yes, through several independent paths.** This document set records the findings, the evidence, and the reasoning behind that conclusion.

---

## ⚠️ Read this first

- This is a **read-only audit**. No application code was modified. The only files added are the documents under `security-audit/`.
- Findings are backed by **permalinks to the exact upstream source** at the commit audited (below). Line numbers are pinned to that commit and will not drift.
- Severity uses a standard **Critical / High / Medium / Low** scale, judged by *what an unauthorised actor gains* and *how much access they need to get it*.
- Some claims requiring live external infrastructure (WHOIS / geolocation of the hard-coded proxy IP) **could not be fully completed inside the audit sandbox** because outbound WHOIS (TCP/43) and the geolocation HTTP APIs were blocked by the environment's egress proxy. Those gaps are called out explicitly in [`findings/03`](findings/03-proxy-mitm-and-infrastructure.md) with the exact commands for you to reproduce them yourself. Nothing is fabricated.

## Audited revision

| | |
|---|---|
| Repository | `shauneccles/Overdrive-release` |
| Commit (pinned) | [`a6ecca5324a4c5d9b7676b4a9a120b03baceab19`](https://github.com/shauneccles/Overdrive-release/commit/a6ecca5324a4c5d9b7676b4a9a120b03baceab19) |
| Default branch | `main` (HEAD == audited commit) |
| Scope | `app/src/main` — ~192 Kotlin + ~413 Java source files |

All permalinks in these documents use the form:
`https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/<path>#L<line>`

---

## Bottom line

OverDrive is not a hardened consumer remote-control product. It is a power-user tool that **bootstraps system-level privilege** on the head unit (daemons run as UID 2000 / shell, some as UID 1000 / system, holding the full `BYDAUTO_*` vehicle HAL permission set — door locks, drivetrain, ADAS, charging) and then exposes that privilege through channels that are, in multiple cases, **unauthenticated or trivially bypassed**.

The design contains some genuinely sound elements (see [What is done well](#what-is-done-reasonably-well)), but the aggregate posture means a motivated attacker in any of several positions — a co-resident app, a device on the same Wi-Fi, the operator of the bundled proxy, or a party on a shared MQTT broker — can observe the car's camera and location and/or actuate it.

---

## Severity summary

| # | Finding | Sev | Attacker position | Doc |
|---|---------|-----|-------------------|-----|
| F1 | Unauthenticated localhost `shell` command → arbitrary code execution as the daemon | 🔴 Critical | Any app on the head unit | [02](findings/02-local-rce-and-ipc.md#f1) |
| F2 | All app egress (incl. OTA download, BYD-cloud creds) funnelled through one hard-coded, non-owner proxy | 🔴 Critical | Proxy operator / whoever compromises it | [03](findings/03-proxy-mitm-and-infrastructure.md#f2) |
| F3 | OTA update has no signature/hash verification; `pm install -r -d` allows silent downgrade | 🔴 Critical | Proxy/MITM on the download | [04](findings/04-ota-integrity.md#f3) |
| F4 | Live H.264 camera stream on `0.0.0.0:8887` with zero authentication | 🔴 Critical | Same Wi-Fi / LAN, or any local app | [05](findings/05-surveillance-and-camera.md#f4) |
| F5 | JWT signing secret is 8 chars (~41 bits) **and** stored in a world-read/write file | 🔴 Critical | LAN sniff → offline crack; any local process | [01](findings/01-network-exposure-and-auth.md#f5) |
| F6 | "Encryption" (`Safe`/`Enc`) uses an AES key committed to the repo | 🔴 Critical | Anyone with the public repo | [09](findings/09-secrets-and-crypto.md#f6) |
| F7 | MQTT accepts vehicle commands from any publisher on a guessable topic; plaintext/public broker default | 🔴 Critical | Anyone on the broker | [06](findings/06-mqtt.md#f7) |
| F8 | Loopback "safety-net" auth bypass for tunnels without proxy headers | 🟠 High | Generic tunnel / SSRF-to-localhost | [01](findings/01-network-exposure-and-auth.md#f8) |
| F9 | Unauthenticated surveillance IPC socket exposes GPS, config, control | 🟠 High | Any local app | [05](findings/05-surveillance-and-camera.md#f9) |
| F10 | Config restore deep-merges unsigned attacker JSON into control sections (MQTT broker, Telegram owner) | 🟠 High | Phished restore file | [08](findings/08-community-and-backup.md#f10) |
| F11 | Config backup ships credential ciphertext **and** the key that decrypts it | 🟠 High | Any leaked/exfiltrated backup | [08](findings/08-community-and-backup.md#f11) |
| F12 | Recordings/snapshots/GPS tracks written world-readable to shared storage | 🟠 High | Any app with storage access | [05](findings/05-surveillance-and-camera.md#f12) |
| F13 | Telegram pairing PIN (6 digits) has no rate-limit/lockout; bot token locally recoverable | 🟠 High | Attacker online during pairing / local | [07](findings/07-telegram-bot.md#f13) |
| F14 | Control plane binds `0.0.0.0:8080` in cleartext (no TLS); 1-year non-revocable JWTs | 🟠 High | Same Wi-Fi / LAN | [01](findings/01-network-exposure-and-auth.md#f14) |
| F15 | Imported/tested community automations reach the full vehicle-control catalog; no sandbox | 🟠 High | Malicious published automation + 1 tap | [08](findings/08-community-and-backup.md#f15) |
| F16 | Daemons run from world-writable `/data/local/tmp`; binaries/scripts are plantable | 🟠 High | Local shell / second app | [02](findings/02-local-rce-and-ipc.md#f16) |
| F17 | `deviceId` disclosed unauthenticated via `/auth/status` (enables F5 offline crack) | 🟡 Medium | Anyone who can reach `:8080` | [01](findings/01-network-exposure-and-auth.md#f17) |
| F18 | `trustAllCerts` / trust-all TLS socket factories disable certificate validation | 🟡 Medium | On-path attacker | [06](findings/06-mqtt.md#f18) |
| F19 | Enabling a Zrok/Cloudflare tunnel publishes the control plane to the internet with no tunnel-layer auth | 🟡 Medium→High | Internet | [03](findings/03-proxy-mitm-and-infrastructure.md#f19) |
| F20 | Shell commands built by string concatenation across launchers (latent injection) | 🟡 Medium | Chained with F2/F3 | [02](findings/02-local-rce-and-ipc.md#f20) |

---

## How to read this audit

| Document | Covers |
|---|---|
| [01 — Network exposure & authentication](findings/01-network-exposure-and-auth.md) | Server bindings, JWT design, loopback bypass, PIN, session lifetime |
| [02 — Local RCE & IPC](findings/02-local-rce-and-ipc.md) | The `shell` TCP command, IPC sockets, `/data/local/tmp` daemon model |
| [03 — Proxy, tunnels & infrastructure](findings/03-proxy-mitm-and-infrastructure.md) | Bundled sing-box VLESS proxy, the hard-coded endpoint, OSINT on the IP, tunnels |
| [04 — OTA update integrity](findings/04-ota-integrity.md) | Download path, verification (or absence of it), install privileges |
| [05 — Surveillance, camera & streaming](findings/05-surveillance-and-camera.md) | Live stream auth, IPC, world-readable footage, location leakage |
| [06 — MQTT](findings/06-mqtt.md) | Command topics, broker defaults, telemetry/location exposure, TLS |
| [07 — Telegram bot](findings/07-telegram-bot.md) | Owner model, pairing PIN, token storage, command surface |
| [08 — Community automations & config backup](findings/08-community-and-backup.md) | Restore merge, credential export, community trust, automation capability |
| [09 — Secrets & cryptography](findings/09-secrets-and-crypto.md) | `Safe`/`Enc` key, world-readable config, credential cipher derivation |

---

## What is done reasonably well

For calibrated trust, the audit also records the controls that *do* hold:

- **JWT validation is not vulnerable to `alg:none` / algorithm confusion** — it always recomputes HS256 and compares ([`AuthManager.java#L454`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/auth/AuthManager.java#L454)).
- **The local PIN uses PBKDF2-HMAC-SHA256, 120 000 rounds, 32-byte salt**, with escalating lockout — a reasonable KDF choice ([`PinManager.java`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/auth/PinManager.java)). Its weakness is only that the hash lives in the world-readable config and that it gates the app UI, not the network APIs.
- **The Telegram bot enforces a real single-owner allowlist** — an unknown chat id is dropped ([`TelegramBotDaemon.java#L2063`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/TelegramBotDaemon.java#L2063)).
- **Community automations cannot carry `shell` actions and cannot hit arbitrary endpoints** — a path allowlist bounds them to a curated catalog ([`CommunityApiHandler.kt#L142`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/community/CommunityApiHandler.kt#L142)).
- **3D model assets are SHA-256 verified** before use ([`ModelsApiHandler.java`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/ModelsApiHandler.java)), and automation action parameters are type-validated (enum allowlists, int clamping) — no deserialization-gadget RCE was found.

These are noted so the reader does not over-correct: the problem is specific and fixable, not that every line is wrong.

---

## Disclosure

The project's own [`SECURITY.md`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/SECURITY.md) asks that vulnerabilities be reported privately (Discord/Telegram `@irshsay`) rather than via public issues, and requests time to remediate before public disclosure. If any part of this audit is taken upstream, please follow that process. This document set is intended for the vehicle owner evaluating whether to run the software.
