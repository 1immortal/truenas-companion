# TrueNAS Companion (Android)

A free, open-source, native Android app for keeping an eye on — and managing — your **TrueNAS SCALE** server from your phone.
No ads, no analytics, no tracking, no paid features. The app talks only to the servers you add.

> **Status:** v0.3.0, early release. Unit-tested against sample API payloads (including the TrueNAS 25.10 `reporting.realtime`
> and `alert.list` formats) and a fake JSON-RPC server. The dashboard and password + 2FA sign-in are confirmed working on a real
> TrueNAS SCALE 25.10.3 server. Phone alerts (new in 0.3) have not been verified on a real device yet.
> See [Known limitations](#known-limitations).

## Features

**Design**
- Material 3 with a royal-blue / cyan brand theme: soft blue-tinted light mode and a deep-navy dark mode, gradient gauges and subtle glows
- Light/dark/system theme. Wallpaper-based dynamic color (Android 12+) is optional and **off by default** (0.2.1 switches it off once for existing installs)
- Card layout with plenty of spacing and rounded corners. It shows the essentials first, and you tap to expand for details
- Color-coded status chips (healthy / warning / critical), animated capacity bars and gauges, live sparklines
- Shimmer skeletons while loading, friendly empty and error states, pull-to-refresh everywhere

**Customizable dashboard**
- Built from blocks: *System, CPU, Memory, Temperature, Network, Storage pools, Apps, Alerts*
- **Edit dashboard** mode (tune icon): long-press and drag to **reorder**, tap the eye to **show/hide** a block, and use the arrows to make it **half or full width** in a 2-column grid
- The layout is saved **per server** (DataStore). **Reset to default layout** is in edit mode
- Live CPU % (with per-thread bars in the full-width card), CPU temperature, and network throughput (auto units, e.g. `7.2 KB/s`) over the WebSocket API (`reporting.realtime`)
- Memory is split like the TrueNAS web UI: **Services / ZFS cache (ARC) / Free**. ARC is not counted as "used", because ZFS gives it back when services need RAM
- CPU note: TrueNAS reports the aggregate CPU usage as a whole percent, so a nearly idle machine reads 0. The app then shows the per-thread average instead, and `<1%` when it is below 1
- Tap the Pools, Apps, Alerts or Temperature blocks to open the matching tab

**Storage:** pools with status, health, capacity bar, and scrub/resilver info (tap to expand). Disks show model, size, pool and temperature. Datasets show usage and encryption/lock state, and system datasets are hidden by default.

**Apps:** installed apps with their state, **start / stop / restart / redeploy**, and an "open web UI" portal link.
- **App upgrades (new in 0.2):** apps with a newer catalog version show `current → latest` and an **Upgrade** button. The confirmation dialog shows the target version and release notes (`app.upgrade_summary`) and has an optional *Snapshot host paths first* switch. The upgrade runs as a TrueNAS job (`app.upgrade`) with a live progress bar on the app card.
- **Upgrade all** appears when two or more apps have updates. It starts one `app.upgrade` job per app, and you confirm the list first.
- **Newer image build (new in 0.3.1):** sometimes TrueNAS reports a newer build of an app's container image while the app version stays the same (the web UI doesn't show this as an update). The app card then shows a calm *Newer image build · same version* note. Tapping it explains what that means and offers **Redeploy**, which pulls the newer image and restarts the app (`app.pull_images` with `redeploy: true`; `chart.release.pull_container_images` on 24.04). The plain **Redeploy** menu action only recreates the containers from the images already on the NAS. It does not download anything.
- **Check for updates** (refresh icon in the top bar) runs `catalog.sync` so new versions appear, then reports how many updates are available.
- **Catalog and install (new in 0.4):** the ⊕ button opens the app catalog (`app.available`). You can search it, filter by category, and see which apps are already installed (✓). An app's page shows its version, description, readme and sources (`catalog.get_app_details`). **Install** opens a form generated from the app's own questions schema, with collapsible groups, text/number/password/choice/switch fields, lists (add/remove items), nested sections, `show_if` conditions and inline validation. The name is checked with the same rule TrueNAS uses. A field type the form can't draw keeps its default and is listed at the top. **JSON** mode lets you edit the raw values instead. Installing runs `app.create` as a job, with progress on the Apps tab.
- **App details (new in 0.4):** tap an app card for live **CPU / memory / network / disk** (`app.stats`, streamed only while the screen is open), its containers, notes, **Open web UI**, **Edit**, **Logs**, **Roll back** and **Delete**.
  - **Edit** reloads the current configuration with the schema (`app.query` with `include_app_schema` / `retrieve_config`) and saves it with `app.update`. Custom (compose) apps are edited as JSON.
  - **Roll back** lists earlier versions (`app.rollback_versions`) and can take a snapshot first (`app.rollback`).
  - **Delete** (`app.delete`) removes the app and its containers. Deleting the app's ixVolume data is a separate, unchecked option. Your own host-path datasets are never touched.
  - **Logs** follows a container's log (`app.container_log_follow`), with 500 lines of history and at most 3,000 lines kept. It has a container picker, a filter that highlights matches, error and warning colouring, **Follow** (auto-scroll; scrolling up turns it off) and **Pause** (freezes the view and counts new lines). The stream stops when you leave the screen.
  - The web UI link uses the address the app is connected to right now, keeping the app's port and path, so it also works on the local address at home.

**VMs and containers (new in 0.4.1):** the Apps tab has an **Apps / VMs / Containers** switch, which keeps the bottom bar at five tabs.
- **VMs** (`vm.*`, TrueNAS 25.04.2+ / 25.10): a list with state, vCPUs and memory, plus **Start**, **Shut down** (a clean ACPI shutdown, run as a job), **Restart** and **Power off** (asks first, and needs your fingerprint when the app lock guards dangerous actions).
  - Tap a VM for its resources and devices (disks, CD-ROM, NICs, display, PCI/USB), **Edit resources** (vCPUs / cores / threads, memory, autostart, description; `vm.update`), **Open display** (the SPICE web display from `vm.get_display_web_uri`, built for the address you're connected through), and **Delete** (only when stopped; deleting the zvols is a separate, unchecked option).
  - **New VM** creates the VM (`vm.create`) and then its devices (`vm.device.create`): a new VirtIO zvol in a dataset you pick, an installer ISO chosen with a small file browser (`filesystem.listdir` under /mnt), a VirtIO NIC on an interface from `vm.device.nic_attach_choices`, and an optional SPICE web display (TrueNAS requires a display password). If a device fails, the app says the VM was created and which device to fix.
- **Containers** (`virt.instance.*`, Incus/LXC on 25.04+): a list with status and image. Tap a card for its CPU/memory limits, autostart, pool and IP addresses. **Start / Stop / Restart** and **Delete** (only when stopped, and asks first) run as jobs. If containers aren't set up (no pool chosen), the app says so instead of showing an empty list. There's no in-app shell (that needs a terminal emulator); use the TrueNAS web UI for that.
- Lists load when you open them and refresh after each action. Pull to refresh. Job progress is followed only while the screen is visible, with no background polling.

**Tasks (new in 0.2):** a live list of TrueNAS jobs (`core.get_jobs` plus `core.subscribe("core.get_jobs")`) such as app upgrades, scrubs, catalog syncs, system updates and replication, with progress bars, errors, and **Abort** for abortable jobs (`core.job_abort`). Open it from the Apps top bar (a badge shows running jobs) or from the System tab. Jobs started in the web UI show up too.

**Alerts:** severity-colored list, relative time, **dismiss**, and an option to show dismissed alerts.

**Phone alerts (new in 0.3):** notifications for new TrueNAS alerts, with **no push service, no Firebase and no server of ours**. The phone checks your NAS directly.
- **How it works:** a WorkManager job runs every **15, 30 or 60 minutes** (your choice) while the phone has a network connection. It signs in with the saved session token (or API key), reads `alert.list`, and compares it with the alert IDs it saw last time. You are notified only about **new, non-dismissed** alerts at or above your **minimum severity** (default *Warning*; the levels are Info, Notice, Warning, Error, Critical, Alert, Emergency). The first check after you turn alerts on only records what is already there, so you don't get a burst of old alerts.
- Each check signs in with the session token and requests a fresh one, so **background checks also keep your session alive**. If the session does expire (or a 2FA code is needed), you get a single *Sign in to keep receiving alerts* notification that opens the sign-in dialog.
- **Instant alerts** (optional, off by default): a foreground service keeps a WebSocket open and subscribes to the `alert.list` event (`core.subscribe`), so alerts arrive within seconds. It reconnects with backoff (5 s up to 5 min, and sooner when the network comes back) and shows a silent ongoing notification with a *Turn off* button. It uses more battery than periodic checks.
- **Notifications:** separate channels for *Critical & errors*, *Warnings*, *Info & notices* and *Cleared alerts*, so you can tune sound and vibration in Android settings. The title is the alert type, the text is the TrueNAS message, and the server name is shown as subtext. Several alerts are grouped together. Tapping one opens the Alerts tab for that server. **Dismiss** dismisses the alert on the NAS in the background, and **Open** opens the app.
- Optional: *Notify when an alert clears*, and *Quiet hours* (only Critical and more severe alerts come through), plus a *Send test notification* button.
- Turn it on in **System › Phone alerts**, or from the card on the Alerts tab. On Android 13+ the app explains why before asking for the notification permission. A hint links to Android's *allow background activity* setting if battery optimization is on; this is optional and never forced.
- **Battery:** periodic checks are cheap. There is one short connection per interval, and Android batches them with other apps' work, so a 15-minute check may run a few minutes late, especially in Doze. Instant mode keeps one TLS WebSocket open with a 45 s ping, which costs noticeably more battery on mobile data. Use it if you need alerts within seconds. The app screens and periodic checks reuse that same connection instead of opening their own.
- **Battery design (0.3.1):** the app's own connection closes 30 s after you leave the app. Live dashboard stats and job lists only stream while their screen is visible, and live stats stop completely if you hide every card that uses them. Dropped streams reconnect with backoff (2 s up to 60 s). Long TrueNAS jobs are polled every 1 s at first, then gradually less often, up to every 5 s. Periodic checks only renew the session token when less than half of its lifetime is left. They look up alert titles only when there is something new to notify.

**App lock (new in 0.4):** in **System › Security**, lock the app with your **fingerprint, face or screen lock** (AndroidX Biometric: class-2 biometrics or device PIN/pattern/password).
- Relock right away, or after 1, 5 or 15 minutes in the background. The app always locks after a restart.
- While locked, nothing behind the lock screen is composed, so no NAS data is drawn.
- **Hide content in recents** blanks the app in the app switcher (`setRecentsScreenshotEnabled(false)` on Android 13+, `FLAG_SECURE` on older versions and while locked).
- **Confirm dangerous actions** asks for your fingerprint again before shutdown, reboot, stopping services, deleting or rolling back an app, and *Upgrade all*.
- Turning the lock on or off also needs a fingerprint. If the phone has no screen lock, the app says so and offers a shortcut to set one up. If you remove the phone's screen lock later, the app turns its lock off so you aren't locked out.
- Notification actions (Dismiss) keep working while the app is locked.

**Home network (new in 0.4):** a server can have a **local address** as well as its main one, for example `https://192.168.1.10` at home and `https://nas.example.com` through your reverse proxy elsewhere.
- **Auto-detect:** type the NAS's IP or hostname and the app probes, in parallel with short timeouts, https on 443 / 444 / 8443 / 9443, then http on 80 / 81 / 8080 / 8000. This is useful when Nginx Proxy Manager runs as a TrueNAS app and the TrueNAS UI has moved off 80/443. A port counts only if it answers like TrueNAS: `/api/versions` returns TrueNAS API versions, or the login page is TrueNAS rather than NPM. No credentials are sent while probing. HTTPS is preferred. You confirm the address it found, and a self-signed certificate goes through the usual *Trust this certificate* fingerprint step.
- **http only?** The app warns that your password would cross the LAN unencrypted and suggests turning on HTTPS in TrueNAS (**System › General › GUI**). It still lets you use the address with **password sign-in**. It **never sends an API key over http**, because TrueNAS revokes it.
- **Check** confirms the local address works and is the **same NAS** as the main address (it compares the unauthenticated `/api/boot_id`, which returns `system.boot_id`).
- **Which address to use:** *Auto* (default), *Local* or *Remote*. In Auto the app tries the local address once per network (Wi-Fi, Ethernet or VPN, with a 1.5 s probe; never on mobile data) and remembers the result until the network changes. If the local address stops answering, it falls back to the main one. A small **Local / Remote** chip next to the server name on the dashboard shows which one is in use. Phone alerts and instant alerts follow the same choice and reconnect when you switch networks.
- *Only on my home Wi-Fi (SSID)* is deliberately not offered. Reading the Wi-Fi name needs precise and background location permission, and the reachability probe plus the certificate pin plus the same-NAS check is a stronger signal anyway.

**System:** services list with start/stop switches (stopping asks you to confirm), **reboot / shutdown** with confirmation dialogs, appearance settings, and a server switcher.

**Connections**
- Save **multiple servers** and switch between them
- Choose how to sign in **per server**: **API key** or **Username & password** (with two-factor codes). See [Sign-in methods](#sign-in-methods)
- API keys, session tokens and (optional) saved passwords are **encrypted** with an AES-256-GCM key kept in the Android Keystore
- **Trust this server** for self-signed HTTPS certificates. On the first connection the app shows the certificate's subject, validity and SHA-256 fingerprint. If you accept, it **pins that exact certificate** for that server. It never trusts all certificates
- **Test sign-in** button with clear error messages: host not found, connection refused, timeout, untrusted certificate, TLS failure, rejected/revoked API key (including the reverse-proxy cause), wrong password, missing permission

## Sign-in methods

### Username & password (with 2FA)
Recommended if your API key keeps getting rejected, for example behind a reverse proxy (see below). Needs TrueNAS 25.04 or newer (WebSocket API).

1. In **Add/Edit server**, pick **Username & password**, then enter your TrueNAS username and password.
2. Tap **Test sign-in**. If two-factor authentication is on for your account, a dialog asks for the **6-digit code** from your authenticator app. It submits automatically once all 6 digits are typed. If a code is wrong you can try again. After too many wrong codes TrueNAS ends the attempt, and you start over with your password.
3. Tap **Save**. The session from the test is kept, so you don't need a second code.

**Staying signed in.** After a successful password (+ code) sign-in, the app asks TrueNAS for a **session token**
(`auth.generate_token` with `single_use=false`, `match_origin=false`, and a TTL of **1, 7 or 30 days** that you choose as *Stay signed in for*).
The token is stored encrypted and used for later connections and app launches (`auth.login_ex` with `TOKEN_PLAIN`). **No password or 2FA code is needed while it is valid.**
Every successful connection gets a fresh token, so the period is counted from when you **last** used the app (sliding expiry).
When the token expires or TrueNAS rejects it, the app shows the sign-in dialog again:
- **Remember password off (default):** you enter your password and, if enabled, a 2FA code.
- **Remember password on:** the password is stored encrypted on the device, so only the **2FA code** is asked (nothing at all if 2FA is off).

The protocol follows the official docs: `auth.login_ex` (`PASSWORD_PLAIN` → `OTP_REQUIRED` → `auth.login_ex_continue` with `OTP_TOKEN`),
and handles the `SUCCESS`, `OTP_REQUIRED`, `AUTH_ERR`, `EXPIRED` (password expired: change it in the web UI) and `REDIRECT` responses.

> Tokens are kept in the TrueNAS middleware. They are probably **lost when the NAS reboots or the middleware restarts** (not verified). In that case you simply get the sign-in prompt again.

### API key
See [Create a TrueNAS API key](#create-a-truenas-api-key). Simple, with no prompts. **But TrueNAS revokes a key that ever arrives over plain HTTP.**

### Behind a reverse proxy (Nginx Proxy Manager, Traefik, Caddy …)
If you reach TrueNAS through a proxy at `https://nas.example.org`, but the proxy forwards to TrueNAS over **http://** (NPM's default *Scheme: http*),
TrueNAS sees an insecure connection and **rejects and revokes your API key**, even though your phone uses HTTPS. Fix it in one of two ways:
- In the proxy, set the upstream to **https** and TrueNAS's HTTPS port (NPM: *Scheme `https`*, *Forward Port `443`*, *Websockets Support* on). Then reset the key in TrueNAS (**My API Keys › Edit › Reset**) and paste the new one. **Or:**
- Switch the server to **Username & password** in the app.

Either way, make sure the proxy has **WebSockets support** enabled (the app uses `wss://HOST/api/current`). The app pings every 30 s (45 s for instant alerts). That keeps the connection under nginx's default 60 s idle timeout, so NPM needs no extra timeout settings.

## Supported TrueNAS versions / APIs

| TrueNAS version | API used | Notes |
|---|---|---|
| **25.04 "Fangtooth", 25.10 "Goldeye"** | JSON-RPC 2.0 WebSocket at `wss://HOST/api/current`, `auth.login_with_api_key` | Main target. Live stats included |
| **26 / 27 (preview)** | Same endpoint. The app falls back to `auth.login_ex` with `API_KEY_PLAIN` when `auth.login_with_api_key` is gone | Set the key owner's username under *Advanced options* (default `truenas_admin`) |
| 24.10 "Electric Eel" and older SCALE | Legacy REST API v2.0 (`/api/v2.0`, `Authorization: Bearer <key>`) | Used automatically if `/api/current` doesn't exist, or you can force it under *Advanced options*. No live stats. Pre-24.10 Kubernetes apps go through `chart.release.*` |

Methods used on the WebSocket API: `auth.login_ex` / `auth.login_ex_continue` / `auth.generate_token` (password sign-in), `system.info`, `core.subscribe("reporting.realtime")`, `pool.query`, `disk.query`, `disk.temperatures`,
`pool.dataset.query`, `app.query` / `app.start` / `app.stop` / `app.redeploy` (jobs, tracked with `core.get_jobs`), `alert.list` / `alert.dismiss`,
`service.query`, `service.control` (falls back to `service.start`/`service.stop`), `system.reboot` / `system.shutdown`,
`app.upgrade_summary` / `app.upgrade` / `app.pull_images` / `catalog.sync`, `core.get_jobs` / `core.job_abort`,
`app.available` / `app.categories` / `catalog.get_app_details` / `app.create` / `app.update` / `app.delete` / `app.rollback_versions` / `app.rollback` (0.4, jobs),
`vm.query` / `vm.start` / `vm.stop` / `vm.poweroff` / `vm.restart` / `vm.update` / `vm.delete` / `vm.create` / `vm.device.create` / `vm.device.nic_attach_choices` / `vm.get_display_web_uri`, `filesystem.listdir`,
`virt.global.config` / `virt.instance.query` / `virt.instance.start` / `virt.instance.stop` / `virt.instance.restart` / `virt.instance.delete` (0.4.1),
`core.subscribe("app.stats:{interval}")`, `core.subscribe("app.container_log_follow:{app_name, container_id, tail_lines}")`, `GET /api/versions` (auto-detect) and `GET /api/boot_id` (same-NAS check).
Method names and payloads for 0.4 / 0.4.1 were checked against the middleware source of TrueNAS 25.10.3.
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
- **Releases:** download `truenas-companion-vX.Y.Z-debug.apk` from the latest GitHub Release. Release APKs are all signed with the same key, so a new release **installs as an update** over the previous one and keeps your servers and settings. **Or:**
- **Actions:** open the latest successful *Android CI* run and download the `truenas-companion-debug-apk` artifact (a zip containing the APK).

To install it, open the APK on your phone. Android asks you to allow **Install unknown apps** for your browser or file manager. Allow it, then tap Install.
With a computer you can run `adb install -r truenas-companion-vX.Y.Z-debug.apk` instead.

> CI artifacts are signed with the CI runner's own debug key, which is different from the release key. Android refuses to install one over the other ("App not installed"). Stick to one source, or uninstall first.

### Option B: build it yourself
Requirements: JDK 17+ (21 recommended) and the Android SDK with platform 37 (Android Studio installs these for you).

```bash
git clone https://github.com/<you>/truenas-companion.git
cd truenas-companion
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'   # render UI previews (Robolectric + Roborazzi) to ./screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or open the folder in **Android Studio** and press *Run*.

Debug builds use the application id `app.truenascompanion.debug` and are signed with the standard Android debug key. A release build needs your own signing config.

## Architecture

```
app/src/main/java/app/truenascompanion/
├── data/
│   ├── api/          TrueNasApi interface, JSON-RPC WebSocket client + implementation, WebSocketAuth (password/2FA/token), REST v2.0 implementation,
│   │                 tolerant JSON parsers, error mapping, connector (WebSocket first, REST fallback)
│   ├── net/          OkHttp client factory, certificate pinning trust manager ("trust this server"), RouteResolver (local/remote),
│   │                 LocalDetector (auto-detect), LocalCheck (same-NAS check)
│   ├── security/     Android Keystore AES-GCM secret cipher, AppLock state machine
│   ├── store/        DataStore: servers, encrypted keys, per-server dashboard layout, appearance
│   ├── repository/   TrueNasRepository: active connection, lazy reconnect, live stats with retry
│   └── model/        Domain models and the dashboard layout model
└── ui/               Compose screens + ViewModels (dashboard, storage, apps, jobs, alerts, system, servers, auth dialogs), theme, components
```
Stack: Kotlin, Jetpack Compose, Material 3, Navigation Compose, Coroutines/Flow, OkHttp (WebSocket + HTTP), kotlinx.serialization,
DataStore, AndroidX Biometric, [Coil](https://coil-kt.github.io/coil/) for catalog icons, [Reorderable](https://github.com/Calvin-LL/Reorderable) for drag-and-drop. minSdk 26, target/compile SDK 37.

## Security notes
- API keys, session tokens and saved passwords are stored only on the device, encrypted with a non-exportable Android Keystore key. Saving the password is off by default. App backup and device-transfer are disabled, so keys are never included in cloud backups.
- Self-signed certificates are accepted only if their SHA-256 fingerprint matches one you pinned for that server. Everything else goes through normal system CA validation. If the server's certificate changes (for example after it is regenerated), the connection fails until you trust the new one.
- Cleartext HTTP is allowed because many home NAS boxes are reached that way on the LAN. Remember that TrueNAS revokes API keys sent over HTTP, and that password sign-in over HTTP sends your password unencrypted. The app warns you in both cases.
- Session tokens are created with `match_origin=false` so that they keep working when your phone's IP changes (Wi-Fi ↔ mobile data). Anyone who pulls the token out of the encrypted store could use it until it expires. Pick a shorter *Stay signed in* period if that worries you.
- No analytics, crash reporting or ads. Phone alerts are fetched directly from your NAS; nothing goes through a push service. The only requests that don't go to your NAS are **app icons** (0.4). The phone downloads these from the URLs in the TrueNAS catalog (TrueNAS's own CDN) and caches them. No credentials or NAS data are sent with them.

## Known limitations
- **Not yet verified against a live TrueNAS server.** Payload formats were taken from the official API docs (v25.04–v27). The parsers are deliberately tolerant, but some fields may differ in practice, especially `reporting.realtime` on older releases, `disk.temperatures` output, and REST-mode actions.
- Live stats (CPU, memory, network, CPU temperature) need the WebSocket API (25.04+). REST mode shows system info, storage, apps, alerts and services without live charts.
- Password sign-in, 2FA, session tokens, app upgrades and the task list were built against the documented API (v25.10) and a fake test server. **They have not been verified on a live NAS.** In particular it is unverified whether a `TOKEN_PLAIN` token sign-in ever asks for 2FA again (the docs don't say it does), and whether tokens survive a NAS reboot.
- Password sign-in, app upgrades and the task list need the WebSocket API (25.04+). They are not available in legacy REST mode.
- App install/edit forms, logs, stats, rollback and delete (0.4) were built from the 25.10.3 middleware source and tested with sample schemas. **They have not been tried against real catalog apps on a live NAS.** Some field types (for example certificate or GPU pickers) are shown as "kept at default" and can be changed in JSON mode.
- Auto-detect, local/remote switching and the app lock were tested with unit tests and a fake server only. Real-network behaviour (VPN apps, captive portals, OEM biometric prompts) is unverified.
- VM and container actions (0.4.1) were built from the 25.10.3 middleware source and tested against a fake server. **They are unverified on a real NAS.** In particular, whether the SPICE web display opens without first signing in to the TrueNAS web UI in the same browser is unverified. VM creation covers the common case (one disk, ISO, NIC, display); passthrough devices, CPU pinning and similar options are left to the web UI.
- Snapshots, shares, and replication/cloud-sync/S.M.A.R.T. tasks are not in v0.2 yet.
- The legacy DDP WebSocket (`/websocket`) of pre-25.04 releases is not used. REST is used instead.
- No home-screen widgets yet.
- Phone alerts depend on Android letting the app run in the background. Aggressive OEM battery savers (some Xiaomi, Huawei and Samsung settings) can delay or stop checks unless the app is allowed to run in the background. Instant mode only reconnects after a reboot if Android lets it start a foreground service at boot.

## App icon
The adaptive launcher icon (foreground, background and monochrome layers for themed icons) is generated from `tools/icon/gen.py`.
It writes the vector drawables to `app/src/main/res/drawable/ic_launcher_*.xml` (a preview needs `cairosvg` and `pillow`).

## License
MIT. See [LICENSE](LICENSE).
