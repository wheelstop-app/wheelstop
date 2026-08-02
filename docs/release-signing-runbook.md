# Release signing — operator runbook

Wheelstop signs with **one dedicated release key**, shared by CI and local dev, so a fast local
`assembleDebug` and a CI `assembleRelease` carry the *same* certificate and install over each other on
the car (`pm install -r` requires a matching cert). The key is **not** the old `androiddebugkey`. Its
cert SHA-256 is pinned in CI via the `RELEASE_SIGNER_SHA256` variable.

## 0. ⚠️ Back up the key — the one rule that matters

`~/.wheelstop/wheelstop-release.jks` + its password. Lose them and you can **never** update the app in
place again — every user would have to uninstall/reinstall. Keep the keystore + password in a password
manager AND an encrypted offsite copy. Do this before anything else.

## 1. Generate the key (once — already done if the file exists)

    mkdir -p ~/.wheelstop && chmod 700 ~/.wheelstop
    keytool -genkeypair -v -keystore ~/.wheelstop/wheelstop-release.jks \
      -storetype PKCS12 -alias wheelstop -keyalg RSA -keysize 4096 -validity 10000
    # cert fingerprint (public — this is the pin):
    keytool -list -v -keystore ~/.wheelstop/wheelstop-release.jks -alias wheelstop | grep -A1 SHA256

PKCS12 uses one password for store and key (so `SIGNING_KEYSTORE_PASSWORD` == `SIGNING_KEY_PASSWORD`).

## 2. CI: org secrets + the cert-pin variable

Secrets live at the **`wheelstop-app` org** (usable by `wheelstop-app/wheelstop` once the repo is there):

    base64 -w0 ~/.wheelstop/wheelstop-release.jks | gh secret set SIGNING_KEYSTORE_B64 --org wheelstop-app
    gh secret set SIGNING_KEYSTORE_PASSWORD --org wheelstop-app   # (your key password)
    gh secret set SIGNING_KEY_PASSWORD      --org wheelstop-app   # (same value)
    gh secret set SIGNING_KEY_ALIAS         --org wheelstop-app --body wheelstop

Pin the cert (public fingerprint — a **variable**, not a secret; rotating keys is then a one-liner):

    gh variable set RELEASE_SIGNER_SHA256 --org wheelstop-app \
      --body <the 64-hex SHA-256 from step 1>

`release.yml` fails the run if the built APK's signer ≠ this variable, or if the variable is unset.

## 3. Local rapid iteration (same cert as CI)

Put the password in a git-ignored local env (e.g. `~/.wheelstop/env`, `chmod 600`) and source it for the
dockerized build, mounting the keystore. `build.gradle.kts` signs **debug** builds with the release key
whenever `KEYSTORE_FILE` is set (else it falls back to the default debug key), so `assembleDebug` (no R8,
fast) still installs over a CI release:

    # ~/.wheelstop/env  (never commit)
    export KEYSTORE_FILE=/root/.wheelstop/wheelstop-release.jks
    export KEYSTORE_PASSWORD=... KEY_PASSWORD=... KEY_ALIAS=wheelstop

    docker run --rm -v "$PWD:/src" -v and-sdk:/sdk -v and-gradle:/gradle \
      -v ~/.wheelstop:/root/.wheelstop eclipse-temurin:17-jdk bash -c '
        source /root/.wheelstop/env
        export ANDROID_HOME=/sdk ANDROID_SDK_ROOT=/sdk GRADLE_USER_HOME=/gradle
        cd /src && ./gradlew --no-daemon :app:assembleDebug'
    # containers write as root — afterwards: chown back before touching git.

    adb install -r -d app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

## 4. Before the first Wheelstop release: validate the R8 build on the car

The Wheelstop package (`app.wheelstop.android`) has never run on the car. Build `:app:assembleRelease`
with the signing env (as §3 but the `release` variant), adb-install it **fresh** (new package installs
alongside `com.overdrive.app`), then with the vehicle in READY confirm: app launches; **surveillance /
motion detection works** (validates the JNI rename); camera daemons start; blind-spot renders; MQTT
entities report under `wheelstop/vehicle/telemetry`; the updater screen shows `wheelstop-app/wheelstop`.
Only then rely on auto-update. If R8 strips something, add `-keep` rules and repeat.

## 5. Cutting a release

Once release-please is wired, merging its release PR tags a release and triggers `release.yml`
automatically. Manual fallback: `gh workflow run release.yml -f version=v<N.N>`. The run builds,
cert-gates against `RELEASE_SIGNER_SHA256`, and publishes the signed APK + `SHA256SUMS` to the rolling
`alpha` release the updater reads.

## Security model

- **Primary:** `pm install -r` rejects any APK not signed with the on-device cert. A compromised GitHub
  release without the release private key produces an APK the car refuses. The key never leaves your
  machine / the org secrets.
- **Secondary:** the app verifies the downloaded APK's SHA-256 against the release `SHA256SUMS` before
  install; a missing sums asset degrades to same-cert-only install.
- **CI:** the cert gate fails the run if the signer ≠ `RELEASE_SIGNER_SHA256`, so a mis-provisioned
  secret can never publish an un-installable release.
