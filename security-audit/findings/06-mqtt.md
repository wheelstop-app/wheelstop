# 06 — MQTT Telemetry & Control

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

OverDrive is a pure MQTT **client** — it has no broker of its own — so every security property here rests on the broker the user points it at, and the app does very little to harden that choice. The default steers users toward a public, plaintext, anonymous broker.

---

<a name="f7"></a>
## F7 — 🔴 Critical: unauthenticated remote vehicle control via guessable command topics

When control is enabled, the client subscribes with wildcards to a command topic and executes inbound messages against the vehicle **with no authentication of the sender**:

- Subscriptions: [`MqttPublisherService.java#L235`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttPublisherService.java#L235):
  ```java
  client.subscribe(config.topic + "/+/set", config.qos);
  client.subscribe(config.topic + "/+/+/set", config.qos);
  ```

MQTT carries no per-message sender identity, and the app adds none — no HMAC, nonce, shared-secret topic segment, or publisher allowlist. The "allow control" switch is a **local owner** toggle deciding whether the client listens at all, **not who may command it**. Authorisation therefore collapses entirely to broker ACLs.

The command surface reachable by publishing a single retained string includes physically- and safety-significant actions — tailgate/windows/sunroof **open**, seat controls, drive/regen/steering modes, charge cap, and disabling **ESP (electronic stability control)**, lane assist, and child-presence detection (registered in the vehicle-control catalog under `mqtt/`).

**Exploit path:** learn the base topic (default is a non-secret string — see F7-topic below, or just read it live off the broker), then:
```
PUBLISH overdrive/vehicle/telemetry/tailgate/set  OPEN
PUBLISH overdrive/vehicle/telemetry/esp_control/set  0
```
On any open or weakly-ACL'd broker (F7-broker) that is fully anonymous remote actuation.

**Impact:** remote physical access (tailgate/windows/sunroof open), disabling a stability safety system, and arbitrary comfort/drive-mode manipulation on a physical vehicle.

---

## F7-broker — 🔴 the promoted default is a public, plaintext, anonymous broker

- Default port is plaintext MQTT: [`MqttConnectionConfig.java#L69`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/MqttConnectionConfig.java#L69) (`DEFAULT_PORT = 1883`), and a bare hostname is coerced to `tcp://` (plaintext).
- The setup UI's own placeholder points at the **public shared broker**: [`mqtt.html#L236`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/assets/web/local/mqtt.html#L236) → `placeholder="tcp://broker.hivemq.com"`.

`broker.hivemq.com` has no ACLs and no auth — every subscriber sees every publisher's topics. A user who follows the placeholder publishes their car's live location and exposes its command topics to the entire internet, in cleartext. Nothing requires `ssl://`/`8883`, requires credentials, or warns when a plaintext/public broker is chosen.

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
