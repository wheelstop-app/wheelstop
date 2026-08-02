# 09 — Secrets & Cryptography

*Permalink base:* `https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

This document consolidates the cryptographic and secret-storage weaknesses that underpin findings elsewhere. The theme: the app stores sensitive material in a world-readable location and "protects" it with keys that are either committed to the repo or derived from world-readable inputs.

---

<a name="f6"></a>
## F6 — 🔴 Critical: the `Safe`/`Enc` AES key is committed to the repository

`Safe.s()` is described in-source as "SOTA string decryption" that splits an AES-256 key into four byte-arrays "to defeat static analysis":

- Key parts + IV: [`Safe.java#L32`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/proxy/Safe.java#L32)
- Reassembly: [`Safe.java#L58`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/proxy/Safe.java#L58)
- Encrypted constants: [`Enc.java`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/proxy/Enc.java)

The **identical AES-256 key and IV are committed** in a helper script in the repo root:

- [`generate_safe_enc.py#L21`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/generate_safe_enc.py#L21):
  ```python
  K1 = bytes([0x38, 0x39, ...])   # → ASCII "89384728..."
  ...
  KEY = K1 + K2 + K3 + K4         # "89384728374829301827384910293847"
  IV  = "1029384756102938"
  ```

Because the key **and** the encrypt/decrypt script ship in the same public repository, **every `Safe.s()` constant is reversible offline in seconds** (static key/IV, no per-device salt). What it nominally "protects" — the proxy server IP/UUID/keys and various command/path strings — is therefore not secret. And it is moot anyway: the *real* proxy endpoint is stored in **plaintext** elsewhere ([`SingboxLauncher.kt#L172`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt#L172), see [doc 03](03-proxy-mitm-and-infrastructure.md)).

This is best read as a **maturity signal**: the codebase treats obfuscation as security. It is equivalent to committing credentials.

**Impact:** no secret in the proxy subsystem is actually secret.

---

## World-readable config is the common denominator

Nearly every other finding routes back to one file:

- `/data/local/tmp/overdrive_config.json`, created **mode 0666 (world read + write)**: [`UnifiedConfigManager.kt#L41`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L41), 0666 intent at [`#L1233`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L1233)

It holds, in one place readable by any **shell-domain** process (the DAC mode is 0666, but SELinux `shell_data_file` context still applies — see the scoping note in [doc 01](01-network-exposure-and-auth.md#f5)):

| Material | Consequence if read | Consequence if written |
|---|---|---|
| Device secret (JWT signing key) | Forge any session → full API/vehicle control (F5) | Rotate/lock out the owner |
| PIN PBKDF2 hash + salt | Offline-crack a 4-digit PIN despite strong KDF | — |
| Telegram bot token (ENC:) | Bot hijack (F13) | Repoint owner (F10) |
| Community worker URL | Learn catalog origin | Repoint catalog origin (F-community) |
| cloudflared tunnel token | Learn tunnel identity | Repoint/hijack tunnel |

> **Correction (per PR review):** the MQTT broker URL + credentials are **not** in this file. They live in a *sibling* file, `/data/local/tmp/mqtt_connections.json` ([`MqttConnectionStore.java#L28`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttConnectionStore.java#L28)), also under `/data/local/tmp` and therefore under the same shell-domain exposure — but they are **not** reachable via the config-restore merge ([doc 08](08-community-and-backup.md) F10). A shell-domain reader can still **observe or redirect** the MQTT control channel by reading/overwriting that separate file directly.

The reason `overdrive_config.json` is 0666 is architectural: the daemon (UID 2000) and the app (UID 10xxx) are different UIDs that need to share state, and `/data/local/tmp` is the cross-UID-accessible location. That is a real engineering constraint — but solving it with world-RW DAC permissions means the app's entire security state is exposed to anything in the shell domain (and, subject to the unit's SELinux policy, potentially other apps), and *writable* by them.

---

## Credential cipher: key derived from world-readable inputs

The credential cipher that wraps the `ENC:` secrets derives its key from:

1. `/data/local/tmp/.byd_device_id` — written **world-readable by design** (so the different-UID daemon can derive the same key): [`ConfigBackupService.kt#L618`](https://github.com/wheelstop-app/wheelstop/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/config/ConfigBackupService.kt#L618) (`setReadable(true, false)`), plus
2. a hardcoded salt compiled into the APK, deliberately excluding `Build.FINGERPRINT` for stability.

Both inputs are available to any shell-context actor (the DID from a world-readable file, the salt from the shipped binary), so the "encryption" on the stored credentials adds little against a local attacker — it mainly protects against a casual copy of the config file to another device. This is why [doc 08](08-community-and-backup.md) F11 (the backup bundling the DID with the ciphertext) is so damaging: it hands over both halves at once.

---

## Recommendations (this document)

1. Delete the committed key / obfuscation approach; do not rely on `Safe`/`Enc` for confidentiality (F6).
2. Replace the 0666 shared-file model with an authenticated IPC broker between the app and daemon UIDs, or use the Android Keystore for key material, so secrets are never world-readable/writable.
3. Derive credential-cipher keys from something not world-readable (e.g. Keystore-backed key), and stop exporting the DID in backups.
4. **Revoke and rotate every already-exposed secret** — the fixes above change *storage*, but material that has already shipped or been exported is permanently compromised and must be re-issued: the committed `Safe`/`Enc` AES key, every device JWT signing secret, the Telegram bot token (reissue via BotFather), MQTT broker credentials, any proxy credentials, and any previously exported backup bundles (which carry the DID + `ENC:` blobs). Treat all of them as burned and generate replacements.
