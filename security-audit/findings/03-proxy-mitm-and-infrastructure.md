# 03 — Proxy, Tunnels & Third-Party Infrastructure

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

This is the single most important document for the question *"could this app be used maliciously against me?"* — because it identifies the **third-party infrastructure the app routes your vehicle's traffic through by design**, none of which you own or control.

---

<a name="f2"></a>
## F2 — 🟠 High: all app egress is funnelled through one hard-coded, non-owner VLESS proxy

> **Severity note:** downgraded from Critical to High after PR review. The original Critical rating assumed the operator could read/rewrite all traffic including credentials and the OTA APK; that is only true for non-TLS-validated channels (see the correction below). The finding remains High because the proxy is a *mandatory, non-owner* trust point for a safety device.

The app bundles **sing-box** and, when the proxy is running, routes app HTTP/MQTT traffic through a local `127.0.0.1:8119` inbound that forwards to a remote **VLESS-Reality** server. The server endpoint is written into the sing-box config **in plaintext** and is **not user-configurable**:

- [`SingboxLauncher.kt#L172`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt#L172):
  ```
  "server": "80.225.224.92",
  "server_port": 443,
  "uuid": "ce8591be-9fa8-4361-90f3-427e9b5e8b85",
  ...
  "public_key": "fxNUGiLzVwAk89RgogDrMq2u4pzyWAe_wx8D2frOPAQ",
  "short_id": "3ca47a3f8fb71e13"
  ```
  (uuid [L174](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt#L174), public_key [L182](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt#L182), short_id [L183](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt#L183))

Every OkHttp / MQTT / Paho consumer auto-routes through the proxy whenever sing-box is up — this includes **ABRP, the Telegram API, the BYD-cloud calls (login + credentials), and the OTA APK download**:

- Proxy accessor consulted by all clients: [`ProxyHelper.java#L125`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java#L125) (`getHttpProxy()`), host constant [`ProxyHelper.java#L39`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/mqtt/ProxyHelper.java#L39)

The proxy is a **forwarding proxy** (local HTTP/SOCKS inbound → VLESS tunnel → the operator's exit node), not a TLS terminator for the sessions it carries. **Correction (per PR review):** an earlier draft claimed the operator could "read and rewrite all traffic including BYD-cloud credentials and the OTA APK." That overstates it. For an HTTPS endpoint that the client validates normally (GitHub, `api.telegram.org`, the BYD-cloud API), the tunnel carries **end-to-end TLS** — the operator sees ciphertext plus metadata (destination, SNI, sizes, timing) and cannot decrypt or forge content without a certificate the client already trusts. Lack of certificate *pinning* is not the same as disabling validation.

What a malicious/compromised proxy operator **can** still do — which is why this remains serious:

1. **Content MITM on any channel that does *not* validate TLS.** Concretely, the trust-all MQTT path ([doc 06](06-mqtt.md) F18) and any plaintext-HTTP endpoint are fully readable/rewritable through the tunnel.
2. **Metadata surveillance** — which off-board services the car talks to, when, and how much, is visible even when content is not.
3. **Selective disruption / denial of service** — the operator can drop or stall specific connections. For a *safety* device this includes blocking OTA security updates or suppressing telemetry, silently.
4. **Redirection** — connections can be steered to attacker infrastructure; TLS validation stops a *silent* content swap for a correctly-named HTTPS host, but any endpoint that is plaintext or trust-all is exposed.

The chain to a forged OTA APK ([doc 04](04-ota-integrity.md)) therefore requires the download to be plaintext or unvalidated — it is not; see doc 04 for the corrected OTA analysis.

**Protocol note.** VLESS-Reality with `"server_name"` fronting (the config uses SNI `google.com`) and a `short_id`/`public_key` is a **censorship-circumvention transport** designed to make the proxy connection indistinguishable from a normal TLS handshake to a big site. That is not inherently malicious — but it means the car's off-board traffic is deliberately routed through, and obfuscated toward, a single operator-controlled node, in a way that is hard to detect on the wire.

> **Mitigating nuance (verified):** the device-**global** HTTP proxy path — which would have routed *all* head-unit traffic, not just OverDrive's — is **disabled** in this build; `setupSystemProxy()` now only clears stale proxies rather than setting one. So F2 is *app-scoped* MITM, not literally every packet on the unit. It still covers everything OverDrive itself sends, including your BYD-cloud login and the update download.

**Impact:** a mandatory, non-owner routing point for all OverDrive off-board traffic — able to surveil metadata, selectively block updates/telemetry on a safety device, and fully MITM any non-validated channel (trust-all MQTT, plaintext). Not "reads everything," but a single point of trust you neither chose nor control.

---

## Infrastructure intelligence on `80.225.224.92`

You asked specifically *who owns the IP, and where it is.* Here is what could and could not be established from inside the audit environment. **I did not fabricate any registration data.**

### What is verifiable

| Attribute | Value | Basis |
|---|---|---|
| IPv4 | `80.225.224.92` | Hard-coded in [`SingboxLauncher.kt#L172`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt#L172) |
| Port / protocol | `443/tcp`, VLESS-Reality (Xray/sing-box) | Same config block |
| Reality UUID | `ce8591be-9fa8-4361-90f3-427e9b5e8b85` | Client credential; anyone with the APK has it |
| Fronting SNI | `google.com` | Config `server_name` |
| Responsible RIR | **RIPE NCC** (Europe / Middle East / Central Asia) | `80.0.0.0/8` was allocated by IANA to RIPE NCC in April 2001 — a stable, public fact about the `80/8` block |

The `80/8` membership tells you the block is administered out of the RIPE region (very likely a European hosting provider), but **not** the specific holder, netname, city, or ASN.

### What could NOT be completed here (and why)

Live enrichment was blocked by the audit sandbox's egress policy:

- WHOIS over **TCP/43** to `whois.ripe.net` — **timed out / blocked** (only HTTPS-via-proxy egress is permitted).
- HTTP WHOIS/geolocation APIs (`ipinfo.io`, `ip-api.com`, `stat.ripe.net`, `rdap.db.ripe.net`, `bgp.he.net`) — all returned **HTTP 403** through the environment's proxy.
- Reverse DNS (PTR) via the container resolver returned **no record**.

### How to complete the OSINT yourself (copy-paste)

Run these from any normal machine to fill in the exact holder, ASN, and geolocation:

```bash
# Authoritative registry data (holder, netname, country, abuse contact):
whois 80.225.224.92                       # or: whois -h whois.ripe.net 80.225.224.92
curl -s https://rdap.db.ripe.net/ip/80.225.224.92 | jq .

# ASN + announced prefix (who routes it):
curl -s "https://stat.ripe.net/data/network-info/data.json?resource=80.225.224.92" | jq .
curl -s https://api.bgpview.io/ip/80.225.224.92 | jq '.data.prefixes[].asn'

# Geolocation + hosting/VPN flags:
curl -s "http://ip-api.com/json/80.225.224.92?fields=66846719" | jq .
curl -s https://ipinfo.io/80.225.224.92/json

# Liveness / what is actually listening on 443:
curl -sv --max-time 8 https://80.225.224.92/ 2>&1 | head -40
```

The two questions worth answering with those results: **(1)** is it a consumer/VPS hosting provider (expected for a personal proxy) or something else, and **(2)** does the geolocation match where you would expect the maintainer to be? Either way, the security conclusion is unchanged: *your car's traffic is being routed through a host you do not control.*

---

## Other bundled third-party endpoints (indicators)

These are hard-coded destinations the app talks to. Recorded here as a reference/IOC list for your own review:

| Purpose | Endpoint | Source |
|---|---|---|
| VLESS proxy | `80.225.224.92:443` | [`SingboxLauncher.kt#L172`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt#L172) |
| Community backend | `https://community-edge.yash321sri.workers.dev` | [`CommunityConfig.kt#L47`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/community/config/CommunityConfig.kt#L47) |
| Telegram Bot API | `https://api.telegram.org/bot…` | [`Enc.java#L149`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/proxy/Enc.java#L149) |
| Proxy DNS | `tcp://8.8.8.8` | [`Enc.java#L165`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/proxy/Enc.java#L165) |

The **community backend** was confirmed (via DNS) to resolve to Cloudflare anycast addresses (`2606:4700::/32`) — i.e. it is a **Cloudflare-hosted `*.workers.dev` subdomain**. (What DNS proves is Cloudflare hosting on the free `workers.dev` namespace; it does *not* by itself establish the account tier or who operates it — stated per the no-fabrication rule.) All OverDrive installs pool into that one backend. Community content is **not signed** (see [doc 08](08-community-and-backup.md)), so trust rests on TLS-to-that-origin and on whoever controls that Cloudflare account. Because it is a `workers.dev` subdomain, the relevant control-plane risk is **compromise of that Cloudflare account**, not independent DNS takeover.

---

<a name="f19"></a>
## F19 — 🟠 High (situational — only when a tunnel is enabled): enabling a tunnel publishes the control plane to the internet

`zrok share public http://localhost:8080` (and the cloudflared equivalent) publishes the local `HttpServer` — the entire vehicle-control API — at a public URL:

- Zrok share of `localhost:8080`: [`ZrokLauncher.kt#L120`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L120)
- Reserved share names are `overdrive` + 6 chars — low entropy, enumerable: [`ZrokLauncher.kt#L261`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L261)

The tunnel layer adds **no authentication of its own** — anyone with the URL reaches the login page and API, gated only by the JWT/PIN. This is defensible *if* the JWT were strong, but combined with F5 (41-bit signing key) and F17 (`deviceId` leak) it means the internet-facing gate is weaker than it looks. To zrok's credit, the loopback bypass (F8) is correctly disabled for zrok because zrok injects `X-Forwarded-*` headers — but a tunnel that does not (see F8) would be worse.

**Impact:** internet-facing physical-vehicle actuation surface, gated by a crackable session.

---

## Recommendations (this document)

1. Make the proxy strictly opt-in, **owner-configured** (your own endpoint), and off by default; never ship a hard-coded operator-controlled proxy that carries credential traffic (F2).
2. Pin certificates on the BYD-cloud and update-download clients so a MITM proxy cannot rewrite them (F2 + [doc 04](04-ota-integrity.md)).
3. If remote access is offered, add an authentication layer at the tunnel edge and use high-entropy, non-enumerable share names (F19).
