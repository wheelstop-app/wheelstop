# 02 — Local RCE & IPC

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

This document covers code-execution and control primitives reachable by **another process on the head unit** — a sideloaded app, a second ADB session, or anything running in the shell domain. On an in-car Android head unit these are realistic: side-loaded apps, a passenger's connected device, or a malicious APK delivered through the OTA weakness in [doc 04](04-ota-integrity.md).

---

<a name="f1"></a>
## F1 — 🔴 Critical: unauthenticated `shell` command → arbitrary code execution as the daemon

`TcpCommandServer` binds `127.0.0.1:19876` and dispatches JSON commands **with no authentication whatsoever** — `handleClient` reads a line and hands it straight to `processCommand`:

- Bind: [`TcpCommandServer.java#L40`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/TcpCommandServer.java#L40)
- Dispatch with no token/UID check: [`TcpCommandServer.java#L83`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/TcpCommandServer.java#L83)

One of the commands is `shell`, which executes arbitrary input via `sh -c`:

- [`TcpCommandServer.java#L463`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/TcpCommandServer.java#L463):
  ```java
  case "shell":
      String shellCmd = cmd.optString("command", "");
      ...
      Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", shellCmd});   // L469
  ```

On Android, **any installed app can open a TCP socket to `127.0.0.1` with only the ordinary `INTERNET` permission** — no special privilege, no user prompt. Such an app sends:

```json
{"cmd":"shell","command":"id; cat /data/local/tmp/overdrive_config.json"}
```

…and receives command output back, executing as the **daemon UID (2000 / shell-class)** which holds the full vehicle HAL permission set (see F16). The same unauthenticated channel also exposes `shutdown`, `disableSurveillance`, `setStreamMode public`, `auth_invalidate`, and camera start/stop.

There is **no caller authentication of any kind** — no command allowlist and no token. (It is a `java.net.ServerSocket` loopback TCP server, so a kernel `SO_PEERCRED` peer-UID check isn't even available on it; that's *why* remediation needs either a Unix-domain/`LocalServerSocket` endpoint with peer-credential enforcement, or an application-layer auth token on the TCP channel.) Binding to loopback stops *remote* callers but not *co-resident* ones — which is precisely the untrusted party on a shared head unit.

**Impact:** local privilege of the daemon is fully owned by any co-resident process that can open the socket; through it, the entire vehicle-control surface and the secrets in the shell-readable config.

---

<a name="f16"></a>
## F16 — 🟠 High: system-privileged daemons launched from shell-writable `/data/local/tmp`

> **Wording correction (per PR review):** `/data/local/tmp` is `0771` owned by `shell` (not world-writable); the write boundary is **shell-domain code / ADB**, governed additionally by SELinux. The exploit chain below is unchanged — the daemons run *as* shell — but the accurate boundary is "shell-writable," not "world-writable."

The daemons are launched as UID 2000 (shell), and the sentry daemon optionally through a UID 1000 (system) path, via `app_process`:

- `app_process` launch line: [`DaemonLauncher.kt#L251`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt#L251)
- It **disables Android's phantom-process cap** — a deliberate removal of an OS guardrail: [`DaemonLauncher.kt#L218`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt#L218) (`device_config put activity_manager max_phantom_processes 2147483647`)

The privilege these daemons hold is extensive. `PermissionGranter` force-grants ~140 permissions with `pm grant`, including `WRITE_SECURE_SETTINGS` and the full `BYDAUTO_*` HAL set (door lock get/set, engine, gearbox, charging, ADAS, speed):

- [`PermissionGranter.java#L40`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/PermissionGranter.java#L40) (`WRITE_SECURE_SETTINGS` and the grant list)

The watchdog scripts, the `sing-box` binary, the `zrok` binary, the sing-box config, and the staged update APK all live under `/data/local/tmp/` (a directory writable in the shell domain). A process that can write there can **replace** a binary or script; on the next watchdog respawn, attacker code runs as UID 2000/1000 with that full permission set. This is also the mechanism that turns the OTA APK swap (F3) and the proxy-binary swap (F2) into code execution.

**Impact:** local privilege escalation to system-level vehicle control (door locks, lights, climate, drivetrain signalling).

---

<a name="f20"></a>
## F20 — 🟡 Medium: shell commands built by string concatenation (latent injection)

Across the launchers, shell command strings are assembled by direct interpolation and run via `sh -c` / the ADB shell:

- The OTA download URL is placed straight into a shell one-liner: [`AppUpdater.java#L1304`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/updater/AppUpdater.java#L1304) and the `wget`/`curl` builder around it.

Today the interpolated values are constrained upstream (allowlisted daemon names, `parseInt`-guarded numbers, `File.getName()`-sanitised filenames), so **no reachable injection was found**. In particular, the **OTA `browser_download_url`** — the one value that originates off-device — comes from a GitHub API response over **validated HTTPS**, which (per the F2/F3 correction in [doc 03](03-proxy-mitm-and-infrastructure.md) / [doc 04](04-ota-integrity.md)) a forwarding proxy **cannot** modify. So there is **no** present proxy→shell injection chain; an earlier draft that framed this as an active F2/F3 exploit was wrong and is corrected here.

**Impact (latent, not active):** the string-concatenation-into-`sh -c` pattern is fragile — any *future* change that forwards an unvalidated argument (or a download endpoint that drops TLS validation) would become an injection at daemon privilege. Flagged as a pattern to fix, not a current exploit.

---

## Other unauthenticated local IPC

The surveillance and audio-ingest IPC sockets are also loopback-bound with no caller authentication; they are covered in [doc 05](05-surveillance-and-camera.md) (F9) because their primary impact is footage/location disclosure and surveillance control:

- Surveillance IPC (`127.0.0.1:19877`): [`SurveillanceIpcServer.java#L18`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L18)

The common root cause across all of these: **loopback binding is treated as an authorisation boundary**, but on a multi-app device it is not — any co-resident process is "local".

---

## Recommendations (this document)

1. Remove the `shell` command entirely, or move the IPC to a Unix-domain/`LocalServerSocket` endpoint with a peer-credential (UID) check — or, if it stays a TCP socket, require a per-boot application-layer token that only the app process knows (F1).
2. Do not launch privileged daemons or their binaries from a shell-writable directory; stage them somewhere only the owning UID can write, and verify a hash before (re)exec (F16).
3. Never build shell strings from values that can originate off-device; pass arguments as an `argv` array and avoid `sh -c` for interpolated input (F20).
