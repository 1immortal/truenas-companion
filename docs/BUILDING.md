# Building TrueNAS Companion

Requirements: JDK 17+ (21 recommended) and the Android SDK with platform 37 (Android Studio installs these for you).

```bash
git clone https://github.com/1immortal/truenas-companion.git
cd truenas-companion
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'   # render UI previews (Robolectric + Roborazzi) to ./screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or open the folder in **Android Studio** and press *Run*.

Debug builds use the application id `app.truenascompanion.debug` and are signed with the standard Android debug key. A release build needs your own signing config.

## Continuous integration

Every push to `main` runs `.github/workflows/android.yml`: `./gradlew assembleDebug testDebugUnitTest lintDebug`, and uploads the debug APK and the test/lint reports as artifacts. CI builds are signed with the runner's own debug key, so they can't be installed over release APKs.

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
The adaptive launcher icon (foreground, background and monochrome layers for themed icons) is generated from `tools/icon/gen.py`.
It writes the vector drawables to `app/src/main/res/drawable/ic_launcher_*.xml` (a preview needs `cairosvg` and `pillow`).


The README icon is `docs/images/icon.png` (rendered from the same spec).
