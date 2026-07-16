# 01 — Network Exposure & Authentication

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

This document covers the HTTP control plane, the JWT session model, the loopback authentication bypass, the local PIN, and session lifetime. The vehicle-control API (`/api/vehicle/*`), surveillance, camera, GPS and recordings all sit behind the server described here.

## Server bindings at a glance

| Server | Bind | Auth | Source |
|--------|------|------|--------|
| HTTP control plane | `0.0.0.0:8080` (cleartext) | JWT + loopback net | [`HttpServer.java#L194`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/HttpServer.java#L194) |
| TCP command server | `127.0.0.1:19876` | **none** (see [doc 02](02-local-rce-and-ipc.md)) | [`TcpCommandServer.java#L40`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/TcpCommandServer.java#L40) |
| Live video WebSocket | `0.0.0.0:8887` | **none** (see [doc 05](05-surveillance-and-camera.md)) | [`WebSocketStreamServer.java#L60`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java#L60) |
| Surveillance IPC | `127.0.0.1:19877` | **none** (see [doc 05](05-surveillance-and-camera.md)) | [`SurveillanceIpcServer.java#L18`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L18) |

The HTTP server's own source comment states it "Listens on 0.0.0.0:8080 for tunnel access" ([`HttpServer.java#L37`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/HttpServer.java#L37)). Binding to `0.0.0.0` means every device on the same Wi-Fi/hotspot/LAN can reach the full API; the only gate is the authentication logic below.

---

<a name="f5"></a>
## F5 — 🔴 Critical: weak, world-readable JWT signing secret → forgeable sessions

The JWT that authenticates every web/API request is signed with HMAC-SHA256 using the **device secret**, which is generated as **8 characters from a 36-character alphabet**:

- Generation: [`AuthManager.java#L199`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/auth/AuthManager.java#L199) → `generateSecret(8)`
- Alphabet: [`AuthManager.java#L634`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/auth/AuthManager.java#L634) → `"abcdefghijklmnopqrstuvwxyz0123456789"`
- Used directly as the HMAC key: [`AuthManager.java#L643`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/auth/AuthManager.java#L643)

**Entropy:** 36⁸ ≈ 2.8 × 10¹² ≈ **41.4 bits**. That is brute-forceable **offline**: an attacker who captures a single valid JWT (see F14 — the transport is cleartext) recomputes HMACs against the known header/payload until the signature matches, recovering the secret on commodity GPUs. They can then mint a token valid for a year (F14).

**It gets worse — the secret is stored world-readable.** The unified config lives at `/data/local/tmp/overdrive_config.json` and is deliberately created **mode 0666 (world read *and* write)** so the app UID and the daemon UID can share it:

- Path: [`UnifiedConfigManager.kt#L41`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L41)
- 0666 intent, stated in-source: [`UnifiedConfigManager.kt#L1233`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L1233) — *"the config file is world-RW (0666)"*
- `setReadable(true, false)` / `setWritable(true, false)` (the `false` = not owner-only): [`UnifiedConfigManager.kt#L205`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L205)

Any local process that can read that file obtains the JWT signing key directly (no brute force) and can forge tokens; any process that can *write* it can inject a malicious MQTT broker, proxy endpoint, or Telegram owner (see [doc 08](08-community-and-backup.md)).

> **Note on SELinux:** on a stock modern Android, `/data/local/tmp` files carry the `shell_data_file` context, which limits third-party-app reads. On these BYD head units the daemons run as shell (UID 2000) via `app_process` and the app is installed/driven over ADB, so the file is squarely reachable by the shell domain, by ADB, and by anything running in that domain. Treat "world-readable" as *fully exposed to any shell-context code and to anyone with ADB*, and potentially to co-resident apps depending on the unit's policy.

**Impact:** full, durable takeover of the web/API control plane — lock/unlock-equivalent actions, surveillance, GPS — via a forged session.

---

<a name="f8"></a>
## F8 — 🟠 High: loopback "safety-net" grants unauthenticated access when tunnel headers are absent

`AuthMiddleware` has a Tier-2 rule: if the request appears to come from loopback **and** carries none of a fixed set of reverse-proxy fingerprint headers, it is trusted with no token:

- [`AuthMiddleware.java#L146`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L146):
  ```java
  if (!hasTunnelHeaders && clientAddress != null) {
      String addrStr = clientAddress.toString();
      boolean isLoopback = addrStr.contains("127.0.0.1") || addrStr.contains("/0:0:0:0:0:0:0:1");
      if (isLoopback) { return true; }   // authenticated, no credential
  }
  ```
- `hasTunnelHeaders` is a **blocklist** set only for `X-Forwarded-*`, `Cf-*`, `X-Real-Ip`, `Forwarded`: [`HttpServer.java#L319`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/HttpServer.java#L319)

The entire remote-access model therefore assumes any tunnel is HTTP-aware and injects one of those exact headers. It **fails open** for any transport that forwards to `localhost:8080` without them:

- `ssh -R 8080:localhost:8080`, `socat`, or a tunnel in **raw-TCP mode** — the request arrives from `127.0.0.1` with no `X-Forwarded`/`Cf-*` header → `isLoopback && !hasTunnelHeaders` → **every route authenticated with no token**.
- Any other **local service with an SSRF or open-proxy flaw** that can be made to fetch `http://127.0.0.1:8080/api/...` is auto-authenticated for the same reason.
- The attacker controls their own request headers, so through an HTTP proxy they simply *omit* the fingerprint headers; the daemon only ever *adds* trust when they are absent.

Also note the loopback test is a fragile substring match on `SocketAddress.toString()` rather than `InetAddress.isLoopbackAddress()` ([`AuthMiddleware.java#L148`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L148)).

**Impact:** unauthenticated `POST /api/vehicle/unlock`, window/trunk open, ESP-disable, plus live camera/GPS, for any of the above transports.

---

<a name="f14"></a>
## F14 — 🟠 High: cleartext control plane on the LAN + effectively immortal sessions

- **No TLS anywhere.** The server speaks plain HTTP on `0.0.0.0:8080` ([`HttpServer.java#L194`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/HttpServer.java#L194)). Anyone on the same Wi-Fi can passively sniff the `byd_session` JWT cookie / `Authorization: Bearer` token and every command, then replay it. The `/ws` H.264 camera stream is likewise cleartext.
- **1-year, non-revocable tokens.** `JWT_EXPIRY_MS = 365 days` — the source comment itself calls it *"1 year (effectively indefinite)"*: [`AuthManager.java#L72`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/auth/AuthManager.java#L72). There is no server-side session store or revocation list; the only way to invalidate a leaked token is manually pressing "Regenerate" (which rotates the secret for *all* sessions).
- **JWT accepted as a URL query parameter** for WebSocket (`/ws?token=`): [`HttpServer.java#L416`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/HttpServer.java#L416). Query-string tokens routinely leak into proxy/tunnel logs, browser history, and `Referer`; combined with the 1-year lifetime, any such leak is a durable full-control credential.

**Impact:** one passive sniff or one leaked URL yields a year of silent access.

---

<a name="f17"></a>
## F17 — 🟡 Medium: `deviceId` disclosed pre-authentication

`/auth/status` is listed in `PUBLIC_PATHS` ([`AuthMiddleware.java#L41`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L41)) and the handler returns `deviceId` ([`AuthApiHandler.java#L197`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AuthApiHandler.java#L197)). The JWT `sub` claim *is* this `deviceId`, so an attacker gets the known-plaintext needed to make the F5 offline crack cheaper, plus a per-vehicle fingerprint.

> **Documentation contradiction worth flagging:** the file-level comments claim `/auth/status` "requires auth" and is "Notably NOT public anymore" ([`AuthApiHandler.java#L17`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AuthApiHandler.java#L17)), yet the actual `PUBLIC_PATHS` set still contains it ([`AuthMiddleware.java#L41`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L41)). The code contradicts its own comment — the endpoint *is* public. This kind of drift is exactly where auth mistakes hide.

---

## PIN (local UI only) — reasonable KDF, wrong scope

The PIN implementation itself is sound — PBKDF2-HMAC-SHA256, 120 000 rounds, 32-byte salt, constant-time compare, escalating lockout ([`PinManager.java`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/auth/PinManager.java)). But:

1. It **gates only the app UI (`MainActivity`)**, not the HTTP/TCP/IPC/MQTT surfaces in this audit — so it provides no protection against any remote or local-app attack here.
2. Its hash+salt live in the same **world-readable 0666 config**, so a 4-digit numeric PIN (10⁴ candidates) is offline-crackable despite the strong KDF.

---

## Recommendations (this document)

1. Do not treat *absence* of headers as trust (F8). Authenticate positively; never let loopback imply "authenticated" for state-changing routes. Use `InetAddress.isLoopbackAddress()`.
2. Use a ≥256-bit random signing key, stored with per-UID protection — **not** in a 0666 file — and decouple it from the user-facing device token (F5).
3. Add TLS to the control plane; cut JWT lifetime and add real revocation (F14).
4. Stop returning `deviceId` unauthenticated (F17).
