<h1 align="center">Wheelstop</h1>
<p align="center">Advanced sentry mode &amp; dashcam for BYD vehicles — an independent, open fork of Overdrive.</p>

---

## What this is

**Wheelstop** is a free, open-source dashcam / sentry-mode app for BYD vehicles with DiLink v3.
All data stays on the head unit — no cloud, no accounts, no subscriptions.

It is an **independent fork of [Overdrive](https://github.com/yash-srivastava/Overdrive-release) by
Yash Srivastava** (MIT). The app itself — cameras, sentry, surveillance, Home Assistant integration —
is upstream's work, and full credit for it belongs there. Wheelstop exists to carry that work forward
as a genuinely open, reproducible, independently-maintained project.

> Wheelstop installs as its own app (`app.wheelstop.android`), so it sits **alongside** Overdrive on the
> head unit rather than replacing it. Only one should own the cameras/daemons at a time — Wheelstop
> detects a running Overdrive on startup and offers to stop or disable it.

## Why a fork

Wheelstop diverges from upstream on the things that make a project trustworthy to build on:

- **Open blind-spot coefficients.** Upstream ships `libod.so`, a compiled blob whose source is withheld
  and which gates the blind-spot feature to the maintainer's signing key. Wheelstop reimplements that
  math in open Kotlin — no blob, no key gate.
- **Reproducible, cert-gated signed releases.** Every release is built in CI, signature-pinned, and
  published with a `SHA256SUMS`. The in-app updater verifies the download and installs in place
  (same-cert `pm install -r`).
- **Honest provenance.** Vendored tunnel binaries are fetched-and-verified with recorded provenance and
  third-party notices, not committed as opaque blobs. CI watches upstream releases for source
  completeness.
- **Standard OSS hygiene.** Public issues, release-please changelogs, Renovate, zizmor-checked workflows.

See the [design spec](docs/superpowers/specs/2026-08-02-wheelstop-rebrand-and-org.md) for the full story.

## Install

Grab the signed APK from [Releases](https://github.com/wheelstop-app/wheelstop/releases) and sideload it
onto the head unit (`adb install -r <apk>`). After the first install, Wheelstop updates itself in place.

## Build

No host JDK needed — the build runs in Docker (fetches opencv-mobile + OpenH264 by pinned digest, builds
the native surveillance lib):

```bash
docker run --rm -v "$PWD:/src" -v and-sdk:/sdk -v and-gradle:/gradle \
  eclipse-temurin:17-jdk bash -c '
    export ANDROID_HOME=/sdk ANDROID_SDK_ROOT=/sdk GRADLE_USER_HOME=/gradle
    cd /src && ./gradlew :app:assembleDebug'
```

Release signing and the local rapid-iteration workflow are documented in
[`docs/release-signing-runbook.md`](docs/release-signing-runbook.md).

## Credits

Wheelstop is built on **[Overdrive](https://github.com/yash-srivastava/Overdrive-release)** by Yash
Srivastava. The upstream project is the origin of essentially all of the app's functionality; this fork
adds the openness/reproducibility/provenance layer described above. Both are MIT licensed — see
[`LICENSE`](LICENSE). Upstream's original README is preserved as
[`Readme.upstream.md`](Readme.upstream.md).
