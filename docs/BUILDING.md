# Building YTN

Requirements: JDK 17+ (21 recommended) and the Android SDK with platform 37 (Android Studio installs these for you).

```bash
git clone https://github.com/1immortal/ytn.git
cd ytn
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'   # render UI previews (Robolectric + Roborazzi) to ./screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or open the folder in **Android Studio** and press *Run*.

Debug builds use the application id `app.truenascompanion.debug` (launcher label "YTN Preview") and are signed with the standard Android debug key. A release build needs your own signing config. The package ids stay `app.truenascompanion[.debug]` after the rename to YTN, so updates install in place.

## Continuous integration

Every push to `main` runs `.github/workflows/android.yml`: it validates the Gradle wrapper, then runs `./gradlew assembleDebug testDebugUnitTest lintDebug` and uploads the debug APK and the test/lint reports as artifacts. CI builds are signed with the runner's own debug key, so they can't be installed over release APKs.

Supply-chain hygiene (1.8.0): every GitHub Action is pinned to a full commit SHA (the tag is kept as a comment), checkout doesn't keep the token (`persist-credentials: false`), the workflow only has `contents: read`, `gradle-wrapper.properties` carries `distributionSha256Sum` so the wrapper refuses a tampered Gradle download, and Dependabot (`.github/dependabot.yml`) proposes weekly updates for the actions and the Gradle dependencies.

## UI previews

Screenshot tests (Robolectric + Roborazzi) only run with `-Pscreenshots`. They render every screen with made-up example data, in light and dark themes and at 1.3× font scale, into `./screenshots/`.

## Testing the WireGuard tunnel

`app/src/androidTest/.../TunnelE2ETest.kt` checks the built-in tunnel on an emulator or phone against a real WireGuard peer: the tunnel comes up and handshakes, only the NAS address goes through it (split tunnel), holders are reference counted, and a dead endpoint falls back to the remote address. A small HTTP server on the peer side answers `/whoami` with the caller's source address.

Example with a Linux host running the emulator (`10.0.2.2` is the host as seen from the emulator):

```bash
# Host: WireGuard peer + two fake "NAS" addresses + the whoami server
ip link add wge2e type wireguard && wg set wge2e listen-port 51820 private-key srv.key peer $(cat cli.pub) allowed-ips 10.99.0.2/32
ip addr add 10.99.0.1/24 dev wge2e && ip link set wge2e up
ip addr add 192.168.77.10/32 dev lo && ip addr add 192.168.77.11/32 dev lo
python3 -c 'import http.server as h
class H(h.BaseHTTPRequestHandler):
    def do_GET(s): s.send_response(200); s.end_headers(); s.wfile.write(s.client_address[0].encode())
h.ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()' &
# cli.conf: [Interface] PrivateKey/Address 10.99.0.2/24, [Peer] host public key, AllowedIPs 0.0.0.0/0, Endpoint 10.0.2.2:51820
# bad.conf: the same with an endpoint port nothing listens on

./gradlew assembleDebug assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set app.truenascompanion.debug ACTIVATE_VPN allow   # pre-grant the VPN consent
adb shell am instrument -w -e wgConf $(base64 -w0 cli.conf) -e badConf $(base64 -w0 bad.conf) \
  -e nasIp 192.168.77.10 -e otherIp 192.168.77.11 -e port 8080 -e tunnelIp 10.99.0.2 \
  app.truenascompanion.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Make sure the emulator has a working network first (`adb shell ping 10.0.2.2`; `adb shell svc data enable` if not).

## App icon

The YTN "Bay-Y" icon is an adaptive icon made of three vector layers: `ic_launcher_background.xml` (navy), `ic_launcher_foreground.xml` (the Y and drive bays) and `ic_launcher_monochrome.xml` (Android 13+ themed icons), wired up in `mipmap-anydpi-v26/`. The source SVGs are in `tools/icon/ytn/`. The launch (splash) screen uses the same foreground on the theme navy, so a cold start doesn't flash grey.

Debug builds add a small badge through a debug-only layer (`app/src/debug/res/drawable/ic_launcher_foreground_debug.xml` and `mipmap-anydpi-v26/`), so the release resources stay untouched.

`V180IconTest` renders the real icon from the built resources (round, squircle and themed shapes, a home-screen mock-up, the 512 px README icon `docs/images/ytn-icon-512.png` / `icon.png`) and the widget preview image, with `-Pscreenshots`.

## Release signing

Release builds look for `/home/box/secure/keystore.properties` (or pass `-PkeystoreProperties=/path/to/keystore.properties`) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. The keystore itself must never be committed. Without that file, `assembleRelease` is unsigned; `assembleDebug` always uses the Android debug key (`applicationId` suffix `.debug`).
