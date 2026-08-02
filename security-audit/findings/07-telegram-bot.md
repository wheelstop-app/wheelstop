# 07 — Telegram Bot Daemon

*Permalink base:* `https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

The Telegram bot is the **best-designed** remote surface in the app — it enforces a genuine single-owner allowlist, so a random Telegram user who merely finds the bot cannot command it. The findings here are about the weaknesses *around and beneath* that gate, not a wide-open door.

## The authorisation model (context)

- Long-polling (not webhook): the daemon calls `getUpdates`.
- Inbound messages are gated on `chatId == ownerChatId`; **an unknown chat id is silently dropped**: [`TelegramBotDaemon.java#L2063`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/TelegramBotDaemon.java#L2063).
- The owner is established via `/pair <6-digit PIN>`, handled at [`TelegramBotDaemon.java#L2158`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/TelegramBotDaemon.java#L2158).

This is fundamentally sound. No pre-auth command injection and no auth-bypass race were found. The gate's problem is that it is **single-factor** and that the factors are weak.

---

<a name="f13"></a>
## F13 — 🟠 High: unthrottled pairing PIN + locally-recoverable bot token

### The pairing PIN has no brute-force protection

`/pair` is processed *before* the owner check ([`TelegramBotDaemon.java#L2053`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/TelegramBotDaemon.java#L2053)), so any chat can attempt to pair before an owner is bound. The PIN is 6 digits (keyspace 900 000):

- [`PairingManager.java#L36`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/telegram/impl/PairingManager.java#L36) → `random.nextInt(900000) + 100000`

`handlePairCommand` validates it with **no attempt counter, no rate limit, no lockout, and no alert** to the owner on failed attempts. An attacker who knows the bot and is online during the user's ~5-minute pairing window can make `/pair NNNNNN` guesses **without any application-enforced attempt limit**; whoever pairs first becomes the sole owner and inherits the full command surface below.

*Mitigating factor:* the window is narrow (the PIN only exists while the user is actively pairing) and Telegram's inbound delivery rate makes exhausting 900 000 in 5 minutes impractical online. But there is no defence-in-depth and the owner is never told a brute-force is underway.

### The bot token is recoverable by a local/ADB actor

The token is **not** committed (good) — it is entered in the UI and stored encrypted. But it lives in the world-readable `/data/local/tmp/overdrive_config.json` (see [doc 09](09-secrets-and-crypto.md)), and the key that decrypts it is derived solely from a **world-readable device-id file** plus a **hardcoded salt compiled into the APK**. Any actor with ADB/shell access (the same channel the app is installed through) can read the ciphertext + device-id and reproduce the key.

With a stolen token an attacker can starve the real daemon of updates (Telegram delivers each update once to whoever polls), read the owner's inbound messages, and impersonate the bot to phish the owner. (Direct car commands still require appearing as `ownerChatId`, which Telegram will not let them spoof — so token theft is a bot-integrity/phishing compromise, serious but not by itself remote actuation.)

**Impact:** owner takeover during the pairing window; bot hijack / impersonation / update-starvation from a local foothold.

---

## The command surface behind the single-factor gate

Once an actor is "owner" (via the PIN weakness, or a stolen session that lets them drive the app), every command is available with no per-command confirmation or re-auth. Two are especially damaging:

- **`/backup` exfiltrates secrets and location.** It ships the full settings bundle — which, per its own warning text, contains credentials and the device key, plus (with `trips`) location history — straight to the requesting chat. See [doc 08](08-community-and-backup.md) F11.
- **`/download` / `/events` exfiltrate recorded footage on demand**, bypassing the `videoUploads` privacy toggle (that toggle only gates engine-pushed video, not the owner-command download path).

Others: `/daemon <name> start|stop` (camera, sentry, cloudflared, zrok, sing-box, tailscale), `/url` (reveal live tunnel URLs to the in-car web server), `/update install` (trigger OTA — see [doc 04](04-ota-integrity.md)), `/sendlog`. None is a *direct* door-unlock command, but `/daemon cloudflared start` + `/url` exposes the vehicle-control web server to the internet, and `/update install` can push an APK — so vehicle control and code change are reachable indirectly.

**Command injection:** checked and **not** found reachable — daemon names are matched against a fixed table, filenames are `File.getName()`-sanitised and pattern-matched, log targets are validated. The residual risk (F20 in [doc 02](02-local-rce-and-ipc.md)) is the pervasive string-concatenation shell pattern, which is safe today only because every interpolated value is constrained upstream.

---

## Recommendations (this document)

1. Rate-limit and lock out `/pair` attempts; alert the owner on repeated failures; consider a longer/one-time pairing secret (F13).
2. Do not store the bot token where a shell-context process can read both it and its key material; derive the key from something not world-readable (F13 + [doc 09](09-secrets-and-crypto.md)).
3. Require a second confirmation for high-impact commands (`/backup`, `/download`, `/update install`, tunnel start).
