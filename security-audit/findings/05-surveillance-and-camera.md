# 05 — Surveillance, Camera & Streaming

*Permalink base:* `https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/`

This is the "observe" half of the threat model: can an unauthorised party watch the car's cameras, pull recorded footage, or read its location. There are three independent paths.

---

<a name="f4"></a>
## F4 — 🔴 Critical: live H.264 camera stream on `0.0.0.0:8887` with zero authentication

The live-video WebSocket server binds all interfaces and **authenticates nothing** — `onOpen` adds any connecting socket to the broadcast set and immediately sends the cached SPS/PPS, after which live frames flow:

- Port + wildcard bind: [`WebSocketStreamServer.java#L24`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java#L24), [`#L60`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java#L60) (`super(new InetSocketAddress(PORT))` — no host arg ⇒ `0.0.0.0`)
- No-auth accept: [`WebSocketStreamServer.java#L107`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java#L107):
  ```java
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
      clients.add(conn);                 // no token, no JWT, no loopback check
      ...
      if (cachedSpsPps != null) { conn.send(cachedSpsPps); }
  }
  ```

There is no examination of `handshake` for a token, no JWT, no loopback restriction. While the stream is active, **any device on the same Wi-Fi/hotspot/LAN** connects `ws://<device-ip>:8887` and receives the live camera feed; any local app connects over loopback. The HTTP API even advertises the `wsUrl` to callers.

**Impact:** real-time camera surveillance of the vehicle interior/surroundings by an unauthenticated network attacker.

---

<a name="f9"></a>
## F9 — 🟠 High: unauthenticated surveillance IPC socket (GPS, config, control)

`SurveillanceIpcServer` listens on `127.0.0.1:19877` and dispatches commands with **no peer-credential or token check** (it is a plain TCP loopback socket, not a UNIX socket, so it cannot even check the peer UID):

- Listener: [`SurveillanceIpcServer.java#L18`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L18)
- `GET_SAFE_LOCATIONS` → returns home/work coordinates: [`SurveillanceIpcServer.java#L494`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L494)
- `EXPORT_CONFIG` (optionally `includeTrips`) → exfiltrates config bundle incl. location history + credentials: [`SurveillanceIpcServer.java#L393`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L393)
- `REPLACE_CONFIG` → write/replace live config: [`SurveillanceIpcServer.java#L412`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L412)

A co-resident app connects and sends `{"command":"GET_SAFE_LOCATIONS"}` for the car's home coordinates, or `{"command":"EXPORT_CONFIG","includeTrips":true}` for trip/location history plus stored credentials; `REPLACE_CONFIG` and the START/STOP/DISABLE commands give it surveillance control.

**Impact:** unauthenticated local disclosure of vehicle location and credentials, plus surveillance-config takeover.

---

<a name="f12"></a>
## F12 — 🟠 High: recordings, snapshots and GPS tracks written world-readable to shared storage

Footage is written to `/sdcard/DCIM/BYDCam` (MediaStore-indexed shared storage) and explicitly marked world-readable so a different-UID daemon can decode it:

- Output dir constant: [`CameraDaemon.java`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java) (`PATH_CAMERA_OUTPUT_DIR = /sdcard/DCIM/BYDCam`)
- World-readable event dirs / thumbnails / SRT GPS sidecars via `setReadable(true, /*ownerOnly=*/false)` across the surveillance engine and recorder (e.g. `SurveillanceEngineGpu`, `ThumbnailBuffer`, `SrtWriter`, `SafeLocationManager`).

Because the files carry world-read bits on shared storage, any app with media/storage access reads the raw recordings, event thumbnails, and **GPS subtitle tracks** directly off disk — bypassing the HTTP server and its auth entirely.

**Impact:** all recorded footage, snapshots, and embedded GPS tracks are readable by other apps on the device.

---

## The loopback bypass makes the *authenticated* camera routes reachable too

Even the routes that *do* go through `AuthMiddleware` — `/snapshot/{id}`, `/api/surveillance/snapshot/*`, `/video/{file}`, `/thumb/{file}`, `/api/recordings`, `/api/surveillance/safe-locations` — are reachable **without a session** by any local app, via the loopback safety-net (F8 in [doc 01](01-network-exposure-and-auth.md#f8)). A local app can also call `/api/stream/enable` to *turn on* the 8887 stream that F4 then serves unauthenticated.

The signed-thumb-token path ([`AuthMiddleware.java#L107`](https://github.com/shauneccles/Overdrive-release/blob/a6ecca5324a4c5d9b7676b4a9a120b03baceab19/app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L107)) is more narrowly scoped (bound to a filename + device secret, traversal-blocked) but still grants auth-free fetch of an individual event thumbnail to anyone who obtains the notification URL, which is placed in OS notification banners / FCM image fetches.

---

## Recommendations (this document)

1. Require a token on the 8887 WebSocket handshake and bind it to loopback (or authenticate + TLS) — never broadcast live video to anonymous sockets (F4).
2. Convert the surveillance IPC to a UNIX socket with `SO_PEERCRED` UID checks and a token (F9).
3. Store recordings in app-private storage; if cross-UID access is needed, mediate it through an authenticated interface rather than world-read bits on `/sdcard` (F12).
