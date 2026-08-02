# Release signing — operator runbook

The CI (`.github/workflows/release.yml`) signs with the **same** keystore already
on the car (cert `df8fc138…`), so releases install in place with no data wipe. Two
steps are manual because they need the private key and a physical car.

## 1. One-time: load the signing secrets

From a machine with the keystore (`~/.overdrive-build/android/debug.keystore`) and
`gh` authenticated against `shauneccles/Overdrive-release`:

    gh secret set SIGNING_KEYSTORE_B64      --repo shauneccles/Overdrive-release \
      --body "$(base64 -w0 ~/.overdrive-build/android/debug.keystore)"
    gh secret set SIGNING_KEYSTORE_PASSWORD --repo shauneccles/Overdrive-release --body "android"
    gh secret set SIGNING_KEY_PASSWORD      --repo shauneccles/Overdrive-release --body "android"
    gh secret set SIGNING_KEY_ALIAS         --repo shauneccles/Overdrive-release --body "androiddebugkey"

The keystore's cert SHA-256 must be
`df8fc138481d279c019ff92137d104993de4a37d966504a7b7f78bb274c9a84e`
(check: `keytool -list -v -keystore ~/.overdrive-build/android/debug.keystore -storepass android`).
CI refuses to publish anything signed with a different cert.

## 2. Before the first real release: validate the R8 build on the car

The car has only ever run **debug** builds. The release build is R8/minified —
stripping could remove something reflected-into at runtime. Validate once:

1. Build locally with the signing env (same as CI):

       KEYSTORE_FILE=~/.overdrive-build/android/debug.keystore \
       KEYSTORE_PASSWORD=android KEY_PASSWORD=android KEY_ALIAS=androiddebugkey \
       ./gradlew :app:assembleRelease

   (No host JDK? Run it in the container from
   `docs/llm-live-car-debugging.md` §4, adding the four `KEYSTORE_*` env vars to
   the inner `export` line.)

2. Install in place over the running debug build (same cert → no wipe):

       adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk

3. With the vehicle in READY, confirm: app launches; camera daemons start;
   blind-spot card renders; MQTT entities report to Home Assistant; the in-app
   updater screen loads and shows the fork as its source.

Only after this passes should you rely on auto-update. If R8 strips something,
add the needed `-keep` rules to the ProGuard config and repeat.

## 3. Cutting a release

`gh workflow run release.yml -f version=v<NN.N>` (or the Actions UI). The `version`
must sort **newer** than the label currently on the car, or the updater won't offer
it. The run builds, cert-gates, and publishes to the rolling `alpha` release; the
car picks it up on its next update check.

## What protects the car (security model)

- **Primary:** Android's `pm install -r` rejects any APK not signed with the
  on-device certificate. A compromised GitHub release lacking the `df8fc138`
  private key produces an APK the car refuses. The private key never leaves your
  machine / GH secrets.
- **Secondary:** the app verifies the downloaded APK's SHA-256 against the
  release's `SHA256SUMS` before install (catches tamper/corruption early). A
  missing sums asset degrades gracefully to same-cert-only install.
- **CI:** the cert gate fails the run if the built APK's signer isn't `df8fc138`,
  so a mis-provisioned secret can never publish an un-installable release.
