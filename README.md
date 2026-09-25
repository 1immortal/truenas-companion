# TrueNAS Companion (Android)

A free, open-source, native Android app for keeping an eye on — and managing — your **TrueNAS SCALE** server from your phone.
No ads, no analytics, no tracking, no paid features. The app talks only to the servers you add.

> **Status:** v0.1.0, early release. It builds, and it is unit-tested against sample API payloads taken from the official
> API docs. **It has not been tested against a live TrueNAS server yet.** See [Known limitations](#known-limitations).

## Features

**Design**
- Material 3 with dynamic color (Android 12+), light/dark/system theme, and a TrueNAS-blue fallback palette
- Card layout with plenty of spacing and rounded corners. It shows the essentials first, and you tap to expand for details
- Color-coded status chips (healthy / warning / critical), animated capacity bars and gauges, live sparklines
- Shimmer skeletons while loading, friendly empty and error states, pull-to-refresh everywhere

**Customizable dashboard**
- Built from blocks: *System, CPU, Memory, Temperature, Network, Storage pools, Apps, Alerts*
- **Edit dashboard** mode (tune icon): long-press and drag to **reorder**, tap the eye to **show/hide** a block, and use the arrows to make it **half or full width** in a 2-column grid
- The layout is saved **per server** (DataStore). **Reset to default layout** is in edit mode
- Live CPU %, CPU temperature, memory used/total (plus ZFS ARC), and network throughput over the WebSocket API (`reporting.realtime`)
- Tap the Pools, Apps, Alerts or Temperature blocks to open the matching tab

**Storage:** pools with status, health, capacity bar, and scrub/resilver info (tap to expand). Disks show model, size, pool and temperature. Datasets show usage and encryption/lock state, and system datasets are hidden by default.

**Apps:** installed apps with their state, **start / stop / restart / redeploy**, an update-available badge, and an "open web UI" portal link.

**Alerts:** severity-colored list, relative time, **dismiss**, and an option to show dismissed alerts.

**System:** services list with start/stop switches (stopping asks you to confirm), **reboot / shutdown** with confirmation dialogs, appearance settings, and a server switcher.

**Connections**
- Save **multiple servers** and switch between them
- The API key is **encrypted** with an AES-256-GCM key kept in the Android Keystore
- **Trust this server** for self-signed HTTPS certificates. On the first connection the app shows the certificate's subject, validity and SHA-256 fingerprint. If you accept, it **pins that exact certificate** for that server. It never trusts all certificates
- **Test connection** button with clear error messages: host not found, connection refused, timeout, untrusted certificate, TLS failure, rejected/expired API key, missing permission

## Supported TrueNAS versions / APIs

| TrueNAS version | API used | Notes |
|---|---|---|
| **25.04 "Fangtooth", 25.10 "Goldeye"** | JSON-RPC 2.0 WebSocket at `wss://HOST/api/current`, `auth.login_with_api_key` | Main target. Live stats included |
| **26 / 27 (preview)** | Same endpoint. The app falls back to `auth.login_ex` with `API_KEY_PLAIN` when `auth.login_with_api_key` is gone | Set the key owner's username under *Advanced options* (default `truenas_admin`) |
| 24.10 "Electric Eel" and older SCALE | Legacy REST API v2.0 (`/api/v2.0`, `Authorization: Bearer <key>`) | Used automatically if `/api/current` doesn't exist, or you can force it under *Advanced options*. No live stats. Pre-24.10 Kubernetes apps go through `chart.release.*` |

Methods used on the WebSocket API: `system.info`, `core.subscribe("reporting.realtime")`, `pool.query`, `disk.query`, `disk.temperatures`,
`pool.dataset.query`, `app.query` / `app.start` / `app.stop` / `app.redeploy` (jobs, tracked with `core.get_jobs`), `alert.list` / `alert.dismiss`,
`service.query`, `service.control` (falls back to `service.start`/`service.stop`), `system.reboot` / `system.shutdown`.
The method names come from the official docs at <https://api.truenas.com/>.

## Create a TrueNAS API key

1. Open the TrueNAS web UI.
2. Open the **account menu** (the person icon, top right) and choose **My API Keys**. You can also go to **Credentials › Users**, select a user, and click **View API Keys**.
3. Click **Add**, give the key a name, pick an administrative **Username**, and choose whether it expires. Then click **Save**.
4. **Copy the key right away.** TrueNAS shows it only once. If you lose it, edit the key and choose **Reset** to get a new one.
5. In the app, tap **Add server**, enter the address (for example `https://truenas.local` or `https://192.168.1.10`), paste the key, tap **Test**, then **Save**.

> ⚠️ **Use HTTPS.** TrueNAS (25.04+) **automatically revokes an API key that is sent over plain HTTP**. The app warns you when you enter an `http://` address.
> The default TrueNAS certificate is self-signed. That is fine: tap **Test** and accept the "Trust this server?" dialog after checking the fingerprint.

The key has the same permissions as the user it belongs to. For read-mostly use, think about a dedicated user with a limited role (for example `READONLY_ADMIN`). Actions the user isn't allowed to perform show a "no permission" error.

## Install

### Option A: download a prebuilt APK
- **Releases:** download `app-debug.apk` from the latest GitHub Release, **or**
- **Actions:** open the latest successful *Android CI* run and download the `truenas-companion-debug-apk` artifact (a zip containing the APK).

To install it, open the APK on your phone. Android asks you to allow **Install unknown apps** for your browser or file manager. Allow it, then tap Install.
With a computer you can run `adb install app-debug.apk` instead.

### Option B: build it yourself
Requirements: JDK 17+ (21 recommended) and the Android SDK with platform 37 (Android Studio installs these for you).

```bash
git clone https://github.com/<you>/truenas-companion.git
cd truenas-companion
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or open the folder in **Android Studio** and press *Run*.

Debug builds use the application id `app.truenascompanion.debug` and are signed with the standard Android debug key. A release build needs your own signing config.

## Architecture

```
app/src/main/java/app/truenascompanion/
├── data/
│   ├── api/          TrueNasApi interface, JSON-RPC WebSocket client + implementation, REST v2.0 implementation,
│   │                 tolerant JSON parsers, error mapping, connector (WebSocket first, REST fallback)
│   ├── net/          OkHttp client factory, certificate pinning trust manager ("trust this server")
│   ├── security/     Android Keystore AES-GCM secret cipher
│   ├── store/        DataStore: servers, encrypted keys, per-server dashboard layout, appearance
│   ├── repository/   TrueNasRepository: active connection, lazy reconnect, live stats with retry
│   └── model/        Domain models and the dashboard layout model
└── ui/               Compose screens + ViewModels (dashboard, storage, apps, alerts, system, servers), theme, components
```
Stack: Kotlin, Jetpack Compose, Material 3, Navigation Compose, Coroutines/Flow, OkHttp (WebSocket + HTTP), kotlinx.serialization,
DataStore, [Reorderable](https://github.com/Calvin-LL/Reorderable) for drag-and-drop. minSdk 26, target/compile SDK 37.

## Security notes
- API keys are stored only on the device, encrypted with a non-exportable Android Keystore key. App backup and device-transfer are disabled, so keys are never included in cloud backups.
- Self-signed certificates are accepted only if their SHA-256 fingerprint matches one you pinned for that server. Everything else goes through normal system CA validation. If the server's certificate changes (for example after it is regenerated), the connection fails until you trust the new one.
- Cleartext HTTP is allowed because many home NAS boxes are reached that way on the LAN. Remember that TrueNAS revokes API keys sent over HTTP.
- No analytics, crash reporting, ads or third-party network calls.

## Known limitations
- **Not yet verified against a live TrueNAS server.** Payload formats were taken from the official API docs (v25.04–v27). The parsers are deliberately tolerant, but some fields may differ in practice, especially `reporting.realtime` on older releases, `disk.temperatures` output, and REST-mode actions.
- Live stats (CPU, memory, network, CPU temperature) need the WebSocket API (25.04+). REST mode shows system info, storage, apps, alerts and services without live charts.
- App upgrades, VM management, snapshots, and replication/cloud-sync/S.M.A.R.T. tasks are not in v0.1.
- The legacy DDP WebSocket (`/websocket`) of pre-25.04 releases is not used. REST is used instead.
- No home-screen widgets or push notifications yet.

## License
MIT. See [LICENSE](LICENSE).
