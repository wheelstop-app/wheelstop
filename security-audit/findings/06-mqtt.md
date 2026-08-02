# 06 — MQTT Telemetry & Control

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

OverDrive is a pure MQTT **client** — it has no broker of its own — so every security property here rests on the broker the user points it at, and the app does very little to harden that choice. This whole surface is **opt-in**: MQTT is disabled by default (`enabled=false`, `brokerUrl=""`) and control requires the user to actively enable it. The concern is that when a user *does* enable it, the app nudges toward a public, plaintext, anonymous broker and adds no application-level command authentication of its own.

> **Scope corrections (per PR review), applied throughout this doc:**
> - The critical exposure is **"no application-level sender authentication,"** not "universally unauthenticated." A broker with credentials + ACLs *can* authenticate and authorise publishers; the app just doesn't add its own layer. The critical impact therefore has an explicit **precondition: an anonymous or weakly-ACL'd broker.**
> - `broker.hivemq.com` is a **UI placeholder hint**, *not* a configured default. A fresh install has `brokerUrl=""` and `enabled=false` and connects to nothing until the owner supplies and enables a broker (and separately enables HA control).

---

<a name="f7"></a>
## F7 — 🔴 Critical (precondition: anonymous / weak-ACL broker + control **and Home Assistant discovery** enabled): no application-level authentication on command topics

When control is enabled, the client subscribes with wildcards to a command topic and executes inbound messages against the vehicle **with no *application-level* authentication of the sender**:

- Subscriptions: [`MqttPublisherService.java#L235`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttPublisherService.java#L235):
  ```java
  client.subscribe(config.topic + "/+/set", config.qos);
  client.subscribe(config.topic + "/+/+/set", config.qos);
  ```

> **Precondition correction (added in review, per Codex PR review — verified):** the command subscriptions require **both** `allowControl` **and** `homeAssistantDiscovery` to be on — not `allowControl` alone. `MqttConnectionConfig.isControlEnabled()` returns `allowControl && homeAssistantDiscovery`, and the `/+/set` subscribes sit inside the `if (config.isHomeAssistant())` → `if (config.isControlEnabled())` branch ([`MqttPublisherService.java`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttPublisherService.java#L224)). An owner who enables control against an anonymous broker but leaves Home Assistant discovery **off** subscribes to no command topics, so the actuation surface does not exist. The full precondition is therefore: **anonymous/weak-ACL broker + `allowControl` + `homeAssistantDiscovery`** (and HA discovery *also* retains-advertises the whole command vocabulary — see F7-topic — so the two are usually enabled together, but both are required).

MQTT carries no per-message sender identity, and the app adds none — no HMAC, nonce, shared-secret topic segment, or publisher allowlist. The "allow control" switch is a **local owner** toggle deciding whether the client listens at all, **not who may command it**. Authorisation therefore collapses entirely to **broker ACLs** — which is fine *if* the broker authenticates and restricts publishers, and a full anonymous-actuation exposure *only if* the broker is anonymous or weakly ACL'd (as the promoted public broker is).

The command surface reachable by publishing a single retained string includes physically- and safety-significant actions — tailgate/windows/sunroof **open**, seat controls, drive/regen/steering modes, charge cap, and disabling **ESP (electronic stability control)**, lane assist, and child-presence detection (registered in the vehicle-control catalog under `mqtt/`).

**Exploit path (given the precondition):** learn the base topic (default is a non-secret string — see F7-topic below, or just read it live off the broker), then:
```text
PUBLISH overdrive/vehicle/telemetry/tailgate/set  OPEN
PUBLISH overdrive/vehicle/telemetry/esp_control/set  0
```
On an open or weakly-ACL'd broker (F7-broker) that is fully anonymous inbound command injection.

> **Actuation-scope correction (added in review, per Codex PR review — verified):** whether those publishes produce *physical* actuation depends on the target unit, and on a stock **unsigned** OverDrive install they largely do **not**. `MqttCommandRouter` dispatches every inbound command through `VehicleCommandRouter.executeSdkOnly` — its class doc states it is *"SDK-only … the BYD cloud [path is not used]"* — so unlike the HTTP `/api/vehicle/*` path, MQTT commands **cannot** use the BYD-cloud credential leg that actually works. They hit the local SDK/HAL setters, which are gated on the signature-protected `BYDAUTO_*_SET` permissions that this APK is **not** granted (return `STATUS_FAILED` — see [doc 10, Part 3](../10-review-and-verification.md#part-3--direct-answer-to-the-owners-concern) and the F16 caveat in [doc 02](02-local-rce-and-ipc.md#f16)). So the anonymous-command *surface* is real and unconditional, but the **physical-actuation** outcome is conditional on the unit permitting local HAL writes (platform-signed / rooted / non-sigperm properties) — not proven on a stock install. The **telemetry/location exposure** below (F7-topic) is the concrete, unconditional impact, since reads work regardless of signing.

**Impact:** unauthenticated inbound command injection into the vehicle-control handler. On units that permit local HAL writes this is remote physical access (tailgate/windows/sunroof open), stability-system disable, and drive-mode manipulation; on a stock unsigned install the HAL writes are rejected, so the realized impact narrows to the attempted-command surface plus the (unconditional) telemetry/location exposure in F7-topic. Scope the physical-control claim to the write capability actually present on the target unit.

---

## F7-broker — 🟠 the UI nudges toward a public, plaintext, anonymous broker (not an out-of-box default)

- Default port is plaintext MQTT: [`MqttConnectionConfig.java#L69`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttConnectionConfig.java#L69) (`DEFAULT_PORT = 1883`), and a bare hostname is coerced to `tcp://` (plaintext).
- The setup UI's placeholder **suggests** the public shared broker: [`mqtt.html#L236`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/assets/web/local/mqtt.html#L236) → `placeholder="tcp://broker.hivemq.com"`. This is an HTML placeholder hint, **not** a configured value: a new `MqttConnectionConfig` has `brokerUrl=""` and `enabled=false` ([`MqttConnectionConfig.java#L92`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttConnectionConfig.java#L92)), so a fresh install connects to nothing until the owner types in and enables a broker.

`broker.hivemq.com` has no ACLs and no auth — every subscriber sees every publisher's topics. A user who *follows the suggestion and enables control* publishes their car's live location and exposes its command topics to the entire internet, in cleartext. Nothing requires `ssl://`/`8883`, requires credentials, or warns when a plaintext/public broker is chosen — so the finding is about a **dangerous nudge the app doesn't guard against**, not an out-of-box exposure.

---

## F7-topic — 🟠 High: location + VIN published retained, namespaced only by a non-secret string

Telemetry — including live **GPS lat/lon** and **VIN** — is published **retained** to `<topic>/<key>`, where `<topic>` defaults to `overdrive/vehicle/telemetry` with no secret component. Because the messages are retained, a subscriber that connects *after the fact* immediately receives the last-known **location and VIN**:

- Broker config store (plaintext creds on disk): [`MqttConnectionStore.java#L28`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttConnectionStore.java#L28) (`/data/local/tmp/mqtt_connections.json`)

Home Assistant discovery additionally **auto-advertises the full command surface** (retained) — every command topic, payload vocabulary, and the exact base topic — to anyone subscribed to the discovery prefix, removing even the weak obscurity.

**Exploit path:** `SUBSCRIBE overdrive/#` (or `#`) → instant last-known location + VIN, and continuous tracking thereafter.

**Impact:** persistent passive vehicle tracking and owner de-anonymisation for anyone with broker read access.

---

<a name="f18"></a>
## F18 — 🟡 Medium: `trustAllCerts` disables certificate validation; no hostname verification

A UI toggle (promoted "for Home Assistant") routes TLS through a trust-all `X509TrustManager` whose `checkServerTrusted` is empty:

- [`ProxyHelper.java#L189`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java#L189) (`getTrustAllSslFactory`), empty check at [`#L197`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java#L197)

When set, TLS provides encryption but **zero authentication** — any MITM cert is accepted — and even the non-trust-all path never enables Paho HTTPS hostname verification. An on-path attacker (rogue AP / upstream, or the bundled proxy in [doc 03](03-proxy-mitm-and-infrastructure.md)) terminates TLS with a self-signed cert, then observes telemetry and injects `…/set` commands (F7).

**Impact:** defeats the one protection (TLS) a user would assume they have on a "secure" broker.

---

## Recommendations (this document)

1. **Authenticate inbound commands** — signed payloads or a dedicated credentialed control principal — instead of accepting any publisher to `<topic>/+/set` (F7).
2. Require TLS with a validated cert; refuse plaintext and warn on public brokers; drop or heavily gate the trust-all toggle and always enable hostname verification (F7-broker, F18).
3. Namespace topics with a per-install secret so topic knowledge ≠ read/write (F7-topic).
4. Store broker credentials in app-private, non-world-readable storage.
