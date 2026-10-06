# TrueNAS Companion: technical details

The in-depth companion to the [README](../README.md): how each feature works, sign-in and sessions, supported APIs, security and known limitations. For building from source see [BUILDING.md](BUILDING.md).

## Features in detail

**Design**
- Material 3 with a royal-blue / cyan brand theme: soft blue-tinted light mode and a deep-navy dark mode, gradient gauges and subtle glows
- Light/dark/system theme. Wallpaper-based dynamic color (Android 12+) is optional and **off by default** (0.2.1 switches it off once for existing installs)
- Card layout with plenty of spacing and rounded corners. It shows the essentials first, and you tap to expand for details
- Color-coded status chips (healthy / warning / critical), animated capacity bars and gauges, live sparklines
- Shimmer skeletons while loading, friendly empty and error states, pull-to-refresh everywhere

**Customizable dashboard**
- Built from blocks: *System, CPU, Memory, Temperature, Network, Storage pools, Apps, Alerts, Data protection, Reports*
- **Edit dashboard** mode (tune icon): long-press and drag to **reorder**, tap the eye to **show/hide** a block, and use the arrows to make it **half or full width** in a 2-column grid
- The layout is saved **per server** (DataStore). **Reset to default layout** is in edit mode
- Live CPU % (with per-thread bars in the full-width card), CPU temperature, and network throughput (auto units, e.g. `7.2 KB/s`) over the WebSocket API (`reporting.realtime`)
- Memory is split like the TrueNAS web UI: **Services / ZFS cache (ARC) / Free**. ARC is not counted as "used", because ZFS gives it back when services need RAM
- CPU note: TrueNAS reports the aggregate CPU usage as a whole percent, so a nearly idle machine reads 0. The app then shows the per-thread average instead, and `<1%` when it is below 1
- Tap the Pools, Apps, Alerts or Temperature blocks to open the matching tab; CPU, Memory, Network and Reports open the Reports screen (1.1.0)

**Users and groups (new in 1.1):** System › Users & groups.
- Lists local accounts from `user.query` / `group.query` with the filter `[["local","=",true]]` (directory-service accounts can't be edited through TrueNAS). Built-in / immutable accounts (`builtin` or `immutable`) are hidden behind **Show built-in** and are read-only.
- **Create** (`user.create`): username, full name, email, password (or *Disable password*, which sends `password: null` like the web UI), primary group (`group_create: true` or an existing `group` id), additional `groups` (API ids, not GIDs), `home` + `home_create`, `shell` (choices from `user.shell_choices`), `sshpubkey`, `smb`, `ssh_password_enabled`, `locked`.
- **Edit** (`user.update(id, patch)`): only changed fields are sent. A new home directory is created first with a separate `{home_create: true, home}` call, as in the web UI's user form. **Lock/unlock** sends `{locked}`; **Reset password** sends `{password}` (the middleware checks password history/complexity). SMB users can't have password login disabled; the middleware also refuses to lock the last full admin. Its validation messages are shown as-is.
- **Delete** (`user.delete(id, {delete_group})`), **groups** (`group.create {name, gid?, smb, users}` → id, `group.update(id, {name, smb, users})`, `group.delete(id, {delete_users})`).
- Every write asks for confirmation and, when the app lock is on, your fingerprint (security action, independent of *Confirm dangerous actions*).

**Reports (new in 1.1):** System › Reports, or tap the CPU / Memory / Network / Reports dashboard cards.
- Uses the same calls as the web UI's Reports page on 25.10: `reporting.netdata_graphs` (available graphs + identifiers) and **one** `reporting.netdata_get_data([{name, identifier}…], {start, end, aggregate: true})` per refresh. Graph names: `cpu`, `cputemp`, `memory` (*available*, bytes), `arcsize`, `interface` (received/sent, kilobits/s), `disk` (read/write, KiB/s), `disktemp` (°C, one result per disk, merged into one chart), `load`.
- Ranges **1h / 1d / 1w / 1m** (`end = now`, `start = end - range`). Long ranges are averaged down to ≤360 points on the phone; gaps (`null`) stay gaps.
- Charts are drawn with Compose Canvas (no chart library): smooth cubic lines, gradient fill, glow, avg/max legend from the server's `aggregations`. **Pinch** to zoom around your fingers, **two-finger drag** to pan, **touch / slide** for a tooltip with every series' value, **double-tap** (or the reset button) to zoom out. Vertical swipes still scroll the page.
- Pick the network interface or disk with chips. Charts the NAS doesn't have (e.g. no CPU temperature sensor) are left out.

**Audit log (new in 1.1):** System › Audit log.
- `audit.query({services: [MIDDLEWARE|SMB|SUDO], "query-filters", "query-options": {limit: 50, offset, order_by: ["-message_timestamp"]}})`, plus `{"count": true}` for the total, like the web UI's System › Audit page. Infinite scroll loads 50 more at a time.
- Filters: service, time range (`message_timestamp >`), event (`=`), username and address (regex `~`, `*` is a wildcard). The search box works like the web UI's basic search: an event name searches by event, anything else by username.
- Quick chips: **Authentication** (`event = AUTHENTICATION`), **Failed** (`success = false`), **Method calls**, and **REST logins** (`event = AUTHENTICATION` + `service_data.protocol = LEGACY_REST`, exactly what TrueNAS's *Deprecated REST API usage* alert counts). Combine it with an address to find the script or device that still uses REST.
- Tap an entry for a sheet with the details and the full JSON record (copyable).
- **Export** builds a CSV on the phone from the current filter (up to 5,000 newest rows, 500 per request) and opens the share sheet. `audit.export` isn't used because it needs an HTTP download. Exports are written to the app cache and cleaned up after a day.

**Legacy REST fallback removed (1.1).** The app now only speaks the JSON-RPC WebSocket API.

**Certificates (new in 1.2):** System › Manage › Certificates. All calls follow the 25.10 middleware (`api/v25_10_0/certificate.py`, `plugins/crypto_`) and the web UI's Credentials › Certificates pages.
- **List:** `certificate.query` (certificates, CAs with `cert_type_CA`, CSRs with `cert_type_CSR`). `from` / `until` are UTC strings like `Tue Oct  6 10:00:00 2026` and are parsed as UTC. Days left are whole days rounded down (like the middleware's `(until - now).days`). The API has no issuer field, so the issuer CN (or O) and the self-signed flag are read on the phone from the certificate PEM. Badges: *N days left*, *expiring* (within the warning window), *expired*, *Web UI*, *ACME · auto-renew*, *Self-signed*. The web UI certificate comes from `system.general.config().ui_certificate.id`, the allowed choices from `system.general.ui_certificate_choices`.
- **Import:** `certificate.create` job with `{name, create_type: CERTIFICATE_CREATE_IMPORTED, certificate, privatekey, passphrase?, add_to_trusted_store}`. Paste the PEMs or pick a file; a file with both blocks fills both fields. Names must match the middleware's `^[a-z0-9_-]+$` (max 120).
- **ACME:** uses DNS authenticators that already exist (`acme.dns.authenticator.query`; create them in the web UI). Pick an existing CSR (its names come from `webui.crypto.get_certificate_domain_names`) or let the app create one first (`CERTIFICATE_CREATE_CSR`, RSA 2048, SHA256, common name + SANs, named `<name>_csr`). Then `certificate.create` with `{create_type: CERTIFICATE_CREATE_ACME, csr_id, tos: true, acme_directory_uri, dns_mapping: {domain: authenticator id}, renew_days}`; directories from `certificate.acme_server_choices` (Let's Encrypt production preselected). The job is awaited for up to 10 minutes.
- **Renewal:** TrueNAS renews ACME certificates itself (daily `certificate.renew_certs`, private), so the app only edits `renew_days` (1–30) via the `certificate.update` job. There is no public "renew now" call.
- **Web UI certificate:** strong warning + confirmation, with the fingerprint/face prompt whenever the app lock is on. Then `system.general.update({ui_certificate, ui_restart_delay: 3, rollback_timeout: 600})`: TrueNAS restarts nginx after 3 s and rolls back after 10 minutes unless `system.general.checkin` is called. The app clears every saved certificate pin of the active server, marks it *review required* and disconnects. With review required, `PinningTrustManager` rejects even CA-signed chains that aren't pinned, so the next connection shows the *Trust this certificate* step (the app opens the connection settings for it). Once the new certificate is trusted and the app reconnects, it calls `system.general.checkin`. If that doesn't happen within 10 minutes, TrueNAS reverts and the review step shows the old certificate again.
- **Expiry warnings:** optional, on by default, 7 / 14 / 30 days (default 14), in System › Phone alerts. They run inside the normal alert check on the same connection, at most every 12 hours (one `certificate.query`), so there are no extra wakeups. CSRs and unparsable certificates are skipped. ACME certificates only warn once their automatic renewal is overdue (`min(days, renew_days − 1)`, the same threshold as TrueNAS's own alert). Each certificate is notified once per state (*expiring*, then *expired*); expiring warnings wait for the end of quiet hours, expired ones use the Critical channel. Tapping opens Certificates with that certificate highlighted.

**Quick actions (new in 1.2):**
- **App-icon shortcuts:** static *Shell*, *Alerts*, *Restart app…* and *Scrub pool…* (`res/xml/shortcuts.xml`; the debug build has its own copy for the `.debug` package). After a restart or scrub, the target becomes a dynamic shortcut (*Restart nextcloud…*), at most two, removed when its server is deleted.
- **Quick Settings tiles:** *NAS status* shows the active server's pool health and open alert count from the widget data (no network access of its own; refreshed whenever the widget refresh writes new data) and opens the app. *TrueNAS action* opens the action you pick in System › Phone alerts › Quick Settings tile.
- Shortcuts and tiles only open the app. Nothing runs silently: the shell, restart and scrub each show a confirmation (restart/scrub with a picker, preselected from a dynamic shortcut), followed by the fingerprint/face prompt whenever the app lock is on. A locked device is unlocked first. Restart uses `app.restart` (as the Apps tab), scrub `pool.scrub.scrub(pool, "START")`.

**Better alerts (new in 1.2):**
- **Snooze:** every alert notification has *Snooze* (1 hour, 8 hours, 1 day, 1 week); the Alerts tab has the same menu and *Unsnooze*. Snoozes are stored on the phone per alert id (TrueNAS has no snooze), dropped when the alert goes away, and the alert is notified again by the first check after the snooze ends (respecting quiet hours).
- **Grouping:** repeated alerts of the same class (`klass`) share one notification with a count (*SMART test failed · 3*), an inbox list and *Dismiss all*. When an alert clears, the group shrinks or disappears. Alert data is kept in the notification extras, so this works across checks without extra storage.
- **Deep links:** tapping a notification, or the button on an alert card, opens the related screen when `klass`/`args` say what it's about: pools (`VolumeStatus`, `ZpoolCapacity*`, `Scrub*`, `PoolUSBDisks`, `PoolUpgraded`) → Storage › Pools; disks (`SMART*`, `DiskTemperature*`) → Storage › Disks; `AppUpdate` with one app → that app, other app alerts → Apps; `SnapshotCount` → the dataset's snapshots; `Quota*`, `EncryptedDataset`, `SnapshotTotalCount` → Storage › Datasets; `HasUpdate` → System (TrueNAS update); `Certificate*` → Certificates. Anything else opens Alerts. Severity channels and quiet hours are unchanged.

**Storage:** pools with status, health, capacity bar, and scrub/resilver info (tap to expand). Disks show model, size, pool and temperature. Datasets show usage, compression, comments and encryption/lock state (system datasets are hidden by default).

**Widgets and multi-server (new in 1.0):**
- **Home-screen widget:** pool health + open alert count (+ route chip) for the active server. Refreshed by WorkManager every 30 minutes with a network constraint, using the same session token / VPN acquire path as phone alerts.
- **All servers:** System › All servers probes each saved NAS in parallel (background connector) and shows online status, pool summary and alert counts; tap to switch and open the dashboard.

**Connection overlay (1.0.1–1.0.3):** when a saved server can’t be reached, a full-screen modal (blur + barrier + underlay pointer block from 1.0.2) covers the content. From **1.0.3** the bottom navigation tabs stay visible but are **grayed out and not clickable** while the overlay is up, so you cannot switch tabs or open System through the nav bar. Use **Check connection settings** / **Quit** / **Try again** on the overlay itself.

**Release signing (1.0):** release APKs are signed with a dedicated keystore kept only on the build machine (`/home/box/secure/…`, never in git). From **1.0.3** the updater’s **Update channel** (System › About) selects which APK asset and which expected signer fingerprint to use (Release vs Debug). Mixing channels or a different key than the installed app requires a one-time uninstall/reinstall.

**System updates and boot environments (new in 0.9):** System › TrueNAS update / Boot environments.
- **Updates:** `update.status` (25.10; `update.check_available` was removed). Shows current train/profile, available version, release notes / changelog from the manifest, and download progress when present. **Download & update** starts the `update.run` job with `{reboot: true}` (same as the web UI) and follows job progress. Confirm + fingerprint when *Confirm dangerous actions* is on.
- **Boot environments:** `boot.environment.query` list with active / next-boot / keep. Activate (`boot.environment.activate` `{id}`), clone (`{id,target}`), keep/unkeep (`{id,value}`), delete (`destroy` `{id}`). Activate and delete ask for confirmation + fingerprint when guarded.

**Datasets and shares (new in 0.8):** Storage › Datasets / Shares.
- **Datasets / ZVOLs:** tree list with used/available, compression and ratio. Create a child dataset or ZVOL (`pool.dataset.create` with `type` FILESYSTEM or VOLUME, optional `share_type`, `compression`, `volsize`/`sparse`). Rename (`pool.dataset.rename`) and delete (`pool.dataset.delete` with recursive/force). Delete and rename ask for confirmation, and for your fingerprint when *Confirm dangerous actions* is on.
- **SMB shares** (`sharing.smb.query` / `create` / `update` / `delete`): name, path under `/mnt`, purpose (`DEFAULT_SHARE`, `MULTIPROTOCOL_SHARE`, `TIMEMACHINE_SHARE`, …), enabled, comment, read-only, browsable. Linked to a dataset path.
- **NFS shares** (`sharing.nfs.query` / `create` / `update` / `delete`): path, networks, hosts, read-only (`ro`), enabled, comment.
- SMB/NFS **service** start/stop stays on System › Services (same toggles as before). Network interface settings stay view-only.

**Apps:** installed apps with their state, **start / stop / restart / redeploy**, and an "open web UI" portal link.
- **App upgrades (new in 0.2):** apps with a newer catalog version show `current → latest` and an **Upgrade** button. The confirmation dialog shows the target version and release notes (`app.upgrade_summary`) and has an optional *Snapshot host paths first* switch. The upgrade runs as a TrueNAS job (`app.upgrade`) with a live progress bar on the app card.
- **Upgrade all** appears when two or more apps have updates. It starts one `app.upgrade` job per app, and you confirm the list first.
- **Newer image build (new in 0.3.1):** sometimes TrueNAS reports a newer build of an app's container image while the app version stays the same (the web UI doesn't show this as an update). The app card then shows a calm *Newer image build · same version* note. Tapping it explains what that means and offers **Redeploy**, which pulls the newer image and restarts the app (`app.pull_images` with `redeploy: true`; `chart.release.pull_container_images` on 24.04). The plain **Redeploy** menu action only recreates the containers from the images already on the NAS. It does not download anything.
- **Check for updates** (refresh icon in the top bar) runs `catalog.sync` so new versions appear, then reports how many updates are available.
- **Catalog and install (new in 0.4):** the ⊕ button opens the app catalog (`app.available`). You can search it, filter by category, and see which apps are already installed (✓). An app's page shows its version, description, readme and sources (`catalog.get_app_details`). **Install** opens a form generated from the app's own questions schema, with collapsible groups, text/number/password/choice/switch fields, lists (add/remove items), nested sections, `show_if` conditions and inline validation. The name is checked with the same rule TrueNAS uses. A field type the form can't draw keeps its default and is listed at the top. **JSON** mode lets you edit the raw values instead. Installing runs `app.create` as a job, with progress on the Apps tab.
- **App details (new in 0.4):** tap an app card for live **CPU / memory / network / disk** (`app.stats`, streamed only while the screen is open), its containers, notes, **Open web UI**, **Edit**, **Logs**, **Shell** (0.7), **Roll back** and **Delete**.
  - **Edit** reloads the current configuration with the schema (`app.query` with `include_app_schema` / `retrieve_config`) and saves it with `app.update`. Custom (compose) apps are edited as JSON.
  - **Roll back** lists earlier versions (`app.rollback_versions`) and can take a snapshot first (`app.rollback`).
  - **Delete** (`app.delete`) removes the app and its containers. Deleting the app's ixVolume data is a separate, unchecked option. Your own host-path datasets are never touched.
  - **Logs** follows a container's log (`app.container_log_follow`), with 500 lines of history and at most 3,000 lines kept. It has a container picker, a filter that highlights matches, error and warning colouring, **Follow** (auto-scroll; scrolling up turns it off) and **Pause** (freezes the view and counts new lines). The stream stops when you leave the screen.
  - The web UI link uses the address the app is connected to right now, keeping the app's port and path, so it also works on the local address at home.

**VMs and containers (new in 0.4.1):** the Apps tab has an **Apps / VMs / Containers** switch, which keeps the bottom bar at five tabs.
- **VMs** (`vm.*`, TrueNAS 25.04.2+ / 25.10): a list with state, vCPUs and memory, plus **Start**, **Shut down** (a clean ACPI shutdown, run as a job), **Restart** and **Power off** (asks first, and needs your fingerprint when the app lock guards dangerous actions).
  - Tap a VM for its resources and devices (disks, CD-ROM, NICs, display, PCI/USB), **Edit resources** (vCPUs / cores / threads, memory, autostart, description; `vm.update`), **Open display** (the SPICE web display from `vm.get_display_web_uri`, built for the address you're connected through), and **Delete** (only when stopped; deleting the zvols is a separate, unchecked option).
  - **New VM** creates the VM (`vm.create`) and then its devices (`vm.device.create`): a new VirtIO zvol in a dataset you pick, an installer ISO chosen with a small file browser (`filesystem.listdir` under /mnt), a VirtIO NIC on an interface from `vm.device.nic_attach_choices`, and an optional SPICE web display (TrueNAS requires a display password). If a device fails, the app says the VM was created and which device to fix.
- **Containers** (`virt.instance.*`, Incus/LXC on 25.04+): a list with status and image. Tap a card for its CPU/memory limits, autostart, pool and IP addresses. **Start / Stop / Restart** and **Delete** (only when stopped, and asks first) run as jobs. If containers aren't set up (no pool chosen), the app says so instead of showing an empty list. **Shell** (0.7) on a running container opens a terminal in it (`incus exec`, or the instance's own shell). Incus VMs are not shelled here; their console stays in the TrueNAS web UI.
- Lists load when you open them and refresh after each action. Pull to refresh. Job progress is followed only while the screen is visible, with no background polling.

**Shell (new in 0.7):** a terminal on the NAS, in an app container, or in an Incus container. The screen is the same idea as System › Shell, the app shell, and the container shell in the TrueNAS web UI. Classic VMs and Incus VMs are not included.

- **Where:** System › Shell (the NAS itself, as your user), an app's **Shell** button (one running container; you pick which if there are several, and `/bin/sh`, `/bin/bash` or one custom program), or **Shell** on a running Incus container (its default shell, or a program you name).
- **Gate:** opening a shell asks for your fingerprint or device PIN whenever the app lock is on, even if *Confirm dangerous actions* is off. A short warning says that commands can change the NAS, and that they stop when the shell closes.
- **Protocol** (TrueNAS 25.10 middleware `apps/webshell_app.py`, same calls as the web UI):
  1. `auth.generate_token` with ttl **300** seconds, empty attrs, `match_origin` **true** and `single_use` **true**. The token is bound to the address of the API connection, so the shell uses that same address and its pinned certificate.
  2. A second WebSocket to `wss://<that host>/<subpath>/websocket/shell/`. The first frame is text: `{"token": "…", "options": {…}}`.
  3. Options: `{}` for the host; `{app_name, container_id, command}` for an app (`docker exec -it`); `{virt_instance_id, use_console: false}` plus an optional `command` for an Incus container (`incus exec`, not the VM console). The command is one program path with no arguments, because the NAS passes it as a single argument.
  4. The NAS answers `{"msg":"connected","id":"…"}` or `{"msg":"failed",…}` (bad or reused token, or no web-shell privilege). After that, every frame is raw terminal bytes, both ways. Bytes that arrive before "connected" (the sudo warning) are shown, not dropped.
  5. The size is `core.resize_shell(id, cols, rows)` on the normal API socket, not on the shell socket.
  6. The NAS closes the socket when the shell exits. The app closes it when you leave the screen, when you tap disconnect, and 30 seconds after the app goes to the background (same grace as the app's own connection). Anything still running in that shell is stopped; long jobs belong in `nohup` or `tmux` on the NAS.
- **Not logged:** the one-time token and every byte of terminal input and output stay out of the log. Copying from the terminal marks the clipboard entry as sensitive. A reverse proxy (for example Nginx Proxy Manager) has to have WebSockets turned on, or the shell connection never upgrades; the error screen says so when you're on the remote address.
- The terminal widget is the Termux emulator and view (v0.118.0, Apache 2.0), vendored under `third_party/termux-terminal` with the local process/JNI parts removed. It only talks to the NAS socket.

**Tasks (new in 0.2):** a live list of TrueNAS jobs (`core.get_jobs` plus `core.subscribe("core.get_jobs")`) such as app upgrades, scrubs, catalog syncs, system updates and replication, with progress bars, errors, and **Abort** for abortable jobs (`core.job_abort`). Open it from the Apps top bar (a badge shows running jobs) or from the System tab. Jobs started in the web UI show up too.

**Data protection (new in 0.5):** a **Protection** tab in Storage, a *Data protection* dashboard card (tap it to open the tab; existing dashboards get the card appended, and you can hide it) and a snapshots screen per dataset.
- **Summary:** four lines, one each for snapshots, scrubs, SMART and backups: *OK*, *Running*, *Not set up*, *Overdue* or *Failed*. A snapshot or backup task counts as overdue when its last successful run is older than twice its schedule interval plus an hour. A pool counts as overdue when its last scrub is older than the scrub threshold (default 35 days) plus 7 days, or when it has never been scrubbed. Manual-only backup tasks are never overdue.
- **Snapshots** (tap a dataset in *Datasets*): list with creation time, unique and referenced size and hold state (`pool.snapshot.query` with `extra.properties` and `holds`), search, sort (newest / oldest / largest) and long-press multi-select delete. **Take snapshot** (`pool.snapshot.create`, optional recursive). **Roll back** (`pool.snapshot.rollback`) explains that every change since then is lost. If newer snapshots exist, ZFS can only roll back by destroying them (`recursive: true`); the dialog names how many and needs a tick box, and it asks for your fingerprint when *Confirm dangerous actions* is on. **Clone** to a new dataset (`pool.snapshot.clone`), **Hold / Release** (`pool.snapshot.hold` / `release`) and **Delete**.
- **Periodic snapshot tasks** (`pool.snapshottask.*`): list with schedule, retention and last run state; enable switch, **Run now** (`pool.snapshottask.run`), edit, add and delete. The editor has dataset, *Include child datasets* with exclusions, a schedule (hourly / daily / weekly / monthly presets or a custom cron line, plus an *Only between* window), retention (amount + hour/day/week/month/year), naming schema (must contain `%Y %m %d %H %M`), *Take empty snapshots* and *Enabled*. Editing and deleting pass `fixate_removal_date: true`, so snapshots the task already took keep their current removal date, as in the web UI.
- **Scrubs:** per pool, last scrub time and errors, **Start**, and **Pause / Stop** with live progress while a scrub runs (`pool.scrub.scrub`; the tab polls `pool.query` every 5 s only while it is visible and something is running). The scrub schedule (`pool.scrub.*`: schedule, threshold in days, enabled) can be edited or added.
- **Backups:** replication, cloud sync and rsync tasks (`replication.query`, `cloudsync.query`, `rsynctask.query`) with direction, target, last run state and time, and live progress. **Run now** (`replication.run`, `cloudsync.sync`, `rsynctask.run`), an enable switch (pausing asks first) and the **last log** (`logs_excerpt` of the last job). Creating and fully editing these tasks involves credentials, SSH connections and many options, so that stays in the web UI.

**SMART on TrueNAS 25.10 (0.5), scoped honestly.** TrueNAS 25.10 removed S.M.A.R.T. from its web UI and public API: `smart.test.*`, the SMART test results and the per-disk SMART pages are gone (checked against the 25.10.3 middleware source). What is left:
- a private method `disk.smart_test(type, disks)` that starts self-tests on disks (its source comment says it exists only for the migration below; cron runs it as root through `midclt`),
- the migration (`drop_smart`) that turned existing SMART test schedules into **cron jobs** running `midclt call disk.smart_test TYPE '["*"]'` (or a list of disk identifiers) as root, with the description *S.M.A.R.T. Test*,
- SMART **alerts** (for example a failed self-test), which TrueNAS still raises.

So the app does what's possible and says so in the UI:
- **Schedules** are those cron jobs (`cronjob.query` filtered on the command). You can add (short / long / conveyance test, schedule, all disks or specific disks by `disk.query` identifier), edit, enable/disable, **Run now** (`cronjob.run`) and delete them. They also appear in the web UI under *System › Advanced › Cron Jobs*. Disk identifiers are validated before they go into the command, so shell quoting stays safe.
- **Run a SMART test now** creates a temporary, disabled cron job with the same command, runs it once with `cronjob.run(id, skip_disabled=false)` and deletes it again. Leftovers (for example if the app was killed half-way) are removed the next time the tab loads.
- **Results:** there is no API for test results or progress on 25.10, so the app can't show them. If a test fails, TrueNAS raises an alert, which the Protection summary shows as *SMART problem reported* and phone alerts deliver as a notification. On TrueNAS 25.04 and older, SMART tests are still managed by the old `smart.test.*` API, which the app doesn't use: schedules made there don't show up in the app, so manage them in the web UI (the Protection summary still reports SMART alerts).

**In-app updates (new in 0.5; channels in 1.0.3):** **System › About** shows the version, a **Check for updates** button, an **Update channel** control (Release / Debug), and a *Check for updates daily* switch (on by default).
- The check is one anonymous request to the public GitHub API: `GET https://api.github.com/repos/1immortal/truenas-companion/releases/latest`, with no token and no personal data. The repository is baked in at build time (`-PupdateRepo=owner/name` changes it for forks). A 404 (no public release or private repository) and GitHub's hourly rate limit for anonymous requests (60 per IP) are reported calmly as "can't check right now".
- **Update channel (1.0.3):** picks which APK asset to fetch and which signer fingerprint must match.
  - **Release** (default on release builds) → `truenas-companion-release.apk` + release key (`BuildConfig.RELEASE_SIGNER_SHA256`).
  - **Debug** (default on debug builds) → `truenas-companion-debug.apk` + debug key (`BuildConfig.DEBUG_SIGNER_SHA256`).
  - Asset names are **unified across every tag** (not versioned). Pre-1.0.3 versioned names (`truenas-companion-vX.Y.Z*.apk`) are still accepted as a fallback while older releases remain latest. Mixing channels usually requires uninstall (different package id and/or signing key).
- **Daily check:** a WorkManager job every 24 h (only with a network connection and when the battery isn't low; first run an hour after install). It posts one low-importance notification per new version on the *App updates* channel. Tapping it opens System. The worker uses the saved update channel.
- **Download & install:** the APK is downloaded to the app's cache and its **SHA-256 is checked** against the digest GitHub publishes for the release asset (or the `.sha256` file attached to the release as a fallback). A release without a checksum is refused. Then the app checks that the APK has the **same package name, a higher version code, the channel’s expected signing certificate, and the same signing certificate as the installed app**, and hands it to Android's package installer, where you confirm. The first time, Android asks you to allow *Install unknown apps* for TrueNAS Companion (the app explains this and opens the setting). The APK is shared with the installer through a `FileProvider`, and nothing is installed silently.
- **Battery:** one small HTTPS request a day, batched by WorkManager. Turn the switch off to stop it completely.

**Alerts:** severity-colored list, relative time, **dismiss**, and an option to show dismissed alerts.

**Phone alerts (new in 0.3):** notifications for new TrueNAS alerts, with **no push service, no Firebase and no server of ours**. The phone checks your NAS directly.
- **How it works:** a WorkManager job runs every **15, 30 or 60 minutes** (your choice) while the phone has a network connection. It signs in with the saved session token (or API key), reads `alert.list`, and compares it with the alert IDs it saw last time. You are notified only about **new, non-dismissed** alerts at or above your **minimum severity** (default *Warning*; the levels are Info, Notice, Warning, Error, Critical, Alert, Emergency). The first check after you turn alerts on only records what is already there, so you don't get a burst of old alerts.
- Each check signs in with the session token and immediately rotates it (see *Staying signed in*), so **background checks also keep your session alive**. If the session does expire (or a 2FA code is needed), you get a single *Sign in to keep receiving alerts* notification that opens the sign-in dialog.
- **Instant alerts** (optional, off by default): a foreground service keeps a WebSocket open and subscribes to the `alert.list` event (`core.subscribe`), so alerts arrive within seconds. It reconnects with backoff (5 s up to 5 min, and sooner when the network comes back) and shows a silent ongoing notification with a *Turn off* button. It uses more battery than periodic checks.
- **Notifications:** separate channels for *Critical & errors*, *Warnings*, *Info & notices* and *Cleared alerts*, so you can tune sound and vibration in Android settings. The title is the alert type, the text is the TrueNAS message, and the server name is shown as subtext. Several alerts are grouped together. Tapping one opens the Alerts tab for that server. **Dismiss** dismisses the alert on the NAS in the background, and **Open** opens the app.
- Optional: *Notify when an alert clears*, and *Quiet hours* (only Critical and more severe alerts come through), plus a *Send test notification* button.
- Turn it on in **System › Phone alerts**, or from the card on the Alerts tab. On Android 13+ the app explains why before asking for the notification permission. A hint links to Android's *allow background activity* setting if battery optimization is on; this is optional and never forced.
- **Battery:** periodic checks are cheap. There is one short connection per interval, and Android batches them with other apps' work, so a 15-minute check may run a few minutes late, especially in Doze. Instant mode keeps one TLS WebSocket open with a 45 s ping, which costs noticeably more battery on mobile data. Use it if you need alerts within seconds. The app screens and periodic checks reuse that same connection instead of opening their own.
- **Battery design (0.3.1):** the app's own connection closes 30 s after you leave the app. Live dashboard stats and job lists only stream while their screen is visible, and live stats stop completely if you hide every card that uses them. Dropped streams reconnect with backoff (2 s up to 60 s). Long TrueNAS jobs are polled every 1 s at first, then gradually less often, up to every 5 s. (0.4.2: periodic checks rotate the session token on every sign-in again, because TrueNAS spends a token when the connection that used it closes. The cost is one extra RPC per check.) They look up alert titles only when there is something new to notify.

**Pinned certificates (1.0.3):** after you trust a self-signed certificate, connection settings show the SHA-256 fingerprint **masked by default** (tap **Show** to reveal). **Forget** still clears the pin for the remote or local address.

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

**VPN (new in 0.6):** reach the NAS from anywhere without exposing TrueNAS to the internet. Open a saved server › **VPN**.
- **Route order (Auto):** local address → Tailscale (if you entered a Tailscale address and the Tailscale app's VPN is connected) → the app's built-in WireGuard tunnel → remote address. The local probe is skipped on mobile data. Each step is checked once per network and remembered until the network changes; a failed step is skipped for that network. The dashboard chip shows **Local / Tailscale / VPN / Remote**.
- **Same NAS, same certificate:** the WireGuard route connects to the **local address** (the NAS's LAN IP) through the tunnel, so the local certificate pin and the same-NAS check (`/api/boot_id`) apply unchanged. The Tailscale route uses its own pin (or the local pin if the certificate is the same, which it is when the TrueNAS UI answers on the NAS's 100.x address).

*Built-in WireGuard*
- Uses the official [wireguard-android](https://git.zx2c4.com/wireguard-android/) library (`com.wireguard.android:tunnel`, userspace Go backend, Android `VpnService`). No separate WireGuard app is needed.
- **Import** a config per server by scanning a QR code (CameraX + ZXing; the camera permission is requested only when you tap *Scan QR*), opening a `.conf` file, or pasting the text. The app validates it, shows endpoint, addresses and allowed IPs, and asks you to confirm. The config (with its private key) is stored encrypted with the same Keystore key as your other secrets.
- **Split tunnel:** only TrueNAS Companion's own connections to the NAS go through the tunnel. When the tunnel comes up the app rewrites the config: `IncludedApplications = <this app>`, `AllowedIPs = <NAS LAN IP>/32` (from the first peer that covers it), and the config's `DNS` is dropped. Everything else on the phone (other apps, browsing, even this app's GitHub update check) keeps using your normal connection, even if the config says `AllowedIPs = 0.0.0.0/0`. The local address therefore has to be an IP address (for example `https://192.168.1.10`), which the VPN screen checks.
- **Modes:** *Off*; *Auto* (default after setup): brought up only when the app needs the NAS and neither the local address nor Tailscale answers, and taken down with the connection, 30 s after you leave the app; *Always on while the app is open*: the tunnel is used first, even at home (at home this needs your router to support NAT loopback/hairpinning; use Auto if it doesn't).
- **Background:** periodic alert checks and the session keep-alive bring the tunnel up for the few seconds they need it, and only when the other routes don't work. **Instant alerts** keep the tunnel up while the instant service runs; WireGuard itself is quiet (no traffic without data, plus the WebSocket's 45 s ping), but it keeps the radio and a VPN running, so expect instant mode over the tunnel to cost somewhat more battery than over Wi-Fi.
- **Android 8–16, including 14+:** the tunnel is started by binding to the app's `VpnService` rather than calling `startService`, so background starts from WorkManager are allowed. While a VPN is established Android itself binds the service with foreground priority, so no extra foreground service or foreground-service type is needed. Instant alerts already run in their own foreground service.
- **VPN permission:** Android asks once to allow the app to set up a VPN (*Allow VPN* on the VPN screen, or automatically after setup). Background checks never show this prompt; until it's granted they skip the tunnel and use the remote address.
- **Another VPN is active** (a different VPN app): Android allows only one VPN at a time, so the app does not replace it. It says so and uses Tailscale (if that's the other VPN) or the remote address. **Always-on VPN set to another app:** Android refuses to start the tunnel; the app explains how to change it (Settings › Network › VPN) and falls back to the remote address.
- The tunnel counts as up only after a WireGuard **handshake** (up to 8 s). No handshake usually means the router port forward is missing or the endpoint name is wrong; the app reports that and falls back.
- **Test VPN** (VPN screen and setup): brings the tunnel up, waits for the handshake and checks that the NAS answers through it (same-NAS check). On Wi-Fi at home it also tells you to repeat the test on mobile data, because only that proves the port forward works.

*Tailscale*
- The app does not embed Tailscale. Enter the NAS's **Tailscale address** (`https://100.x.y.z` or its MagicDNS name, e.g. `https://truenas.tail1234.ts.net`). The app uses it when a VPN is active on the phone (`ConnectivityManager` VPN transport) and the address answers. If a Tailscale address is set but the Tailscale app isn't connected, the VPN screen offers **Open Tailscale** (or the Play Store if it's not installed).

*Guided setup on the NAS* (VPN › **Set up VPN**; do it at home on Wi-Fi)
- **WireGuard (recommended):** installs the **wg-easy** app from the TrueNAS catalog (`stable` train, wg-easy v15). You confirm the public address of your home (defaults to the remote address's host name, e.g. your dynamic DNS name) and the UDP port your router will forward (default 51820). The app generates a 20-character wg-easy admin password, shows it once with a copy button (the clipboard entry is marked sensitive), and never stores it. After the install job finishes the app waits for wg-easy's web UI on `http://<NAS LAN IP>:30058`, completes wg-easy's first-run setup through its HTTP API (`/api/setup/2` admin account, `/api/setup/4` host and port), creates a client named after the phone (`/api/client`), downloads its config and imports it, with Auto mode. The password is sent only to wg-easy on your LAN; it never goes into the TrueNAS app configuration (the `INIT_*` variables are deliberately not used). The wg-easy web UI runs over plain http on the LAN (the catalog's *insecure* option), because it has no certificate of its own.
- If wg-easy is already installed, you can sign in with its admin account instead (the app then only creates a client), or open wg-easy's web page and scan the QR code of a client you create there. If anything in the API route fails, the app falls back to that manual path.
- **The one step the app can't do:** forward **UDP** port 51820 (or the port you chose) on your home router to the NAS's LAN IP, port 51820. Nginx Proxy Manager can't do this: it proxies web (TCP) traffic only, and WireGuard uses UDP.
- **Tailscale:** installs the official **Tailscale** app from the TrueNAS catalog (`community` train) with your auth key (Tailscale admin console › Settings › Keys › Generate auth key; the app links there) and a machine name. Optionally it advertises your home subnet (e.g. `192.168.1.0/24`; approve it in the admin console › Machines). This app doesn't need the subnet route: it uses the NAS's own Tailscale address. Then install the Tailscale app on your phone, sign in, and check the prefilled MagicDNS address (or enter the 100.x address shown in the Tailscale admin console; the TrueNAS app doesn't report it).
- Every install is confirmed (with your fingerprint if *Confirm dangerous actions* is on). If the catalog train isn't enabled (Apps › Discover › Manage Catalog › Trains) or no apps pool is set, the error says so.
- **Security tip:** once a VPN route has worked, the VPN screen shows a one-time tip that TrueNAS doesn't need to be exposed publicly anymore and that you can keep the dynamic DNS address in the app as a backup. The app never changes that for you.

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

**Staying signed in.** After a successful password (+ code) sign-in, the app asks TrueNAS for two **session tokens**, a primary and a spare
(`auth.generate_token` with `single_use=false`, `match_origin=false`, and a TTL of **1, 7 or 30 days** that you choose as *Stay signed in for*).
They are stored encrypted and used for later connections, app launches, alert checks and the instant-alerts service (`auth.login_ex` with `TOKEN_PLAIN`). **No password or 2FA code is needed while they are valid.**

How the token chain works (0.4.2), based on the TrueNAS 25.10 middleware source:
- When a connection that signed in with a token closes, TrueNAS **destroys that token** (`TokenSessionManagerCredentials.logout()` → `token_manager.destroy`). A token is therefore good for one connection only.
- So after **every** token sign-in the app immediately mints a new primary token on that connection. New tokens belong to your original password + 2FA sign-in, not to the connection, so they survive it.
- The spare is never used while the primary works. It is the fallback if the primary is rejected, and it is refreshed once it is past half of its lifetime.
- The app, the periodic alert check, the instant-alerts service and the keep-alive job share **one token manager with one lock per server**, so they never race to use or rotate the same token.
- Tokens are discarded only when TrueNAS **explicitly rejects** them (after one retry on a fresh connection). No network, timeouts, a dropped socket or a 5xx from the proxy while the phone wakes up keep every token.
- A light **keep-alive job** (every ~12 h, only with network and when the battery isn't low) renews the tokens once the primary is past half of its lifetime. Most runs do no network I/O at all.

When TrueNAS still asks for the password / 2FA code:
- **30 days after the last password + 2FA sign-in.** TrueNAS caps a sign-in (and every token derived from it) at 30 days (`max_session_age` for AAL1). This cannot be extended from the app.
- After the **NAS reboots or the middleware restarts** (tokens live only in middleware memory), or if someone ends the session in the TrueNAS web UI.
- If the phone stays offline (or the app and its alerts don't run) for longer than the chosen period.
- **Remember password off (default):** you enter your password and, if enabled, a 2FA code.
- **Remember password on:** the password is stored encrypted on the device, so only the **2FA code** is asked (nothing at all if 2FA is off).

The app never stores your 2FA secret and never generates codes.

The protocol follows the official docs: `auth.login_ex` (`PASSWORD_PLAIN` → `OTP_REQUIRED` → `auth.login_ex_continue` with `OTP_TOKEN`),
and handles the `SUCCESS`, `OTP_REQUIRED`, `AUTH_ERR`, `EXPIRED` (password expired: change it in the web UI) and `REDIRECT` responses.

> Why no "create an API key for this device" option (API keys don't need 2FA)? TrueNAS revokes an API key the first time it arrives over an insecure transport. Behind a proxy that forwards to TrueNAS over plain http (for example the default Nginx Proxy Manager setup), that happens on the first remote connection. Session tokens have no such check.

### API key
See [Create a TrueNAS API key](#create-a-truenas-api-key). Simple, with no prompts. **But TrueNAS revokes a key that ever arrives over plain HTTP.**

### Behind a reverse proxy (Nginx Proxy Manager, Traefik, Caddy …)
If you reach TrueNAS through a proxy at `https://nas.example.org`, but the proxy forwards to TrueNAS over **http://** (NPM's default *Scheme: http*),
TrueNAS sees an insecure connection and **rejects and revokes your API key**, even though your phone uses HTTPS. Fix it in one of two ways:
- In the proxy, set the upstream to **https** and TrueNAS's HTTPS port (NPM: *Scheme `https`*, *Forward Port `443`*, *Websockets Support* on). Then reset the key in TrueNAS (**My API Keys › Edit › Reset**) and paste the new one. **Or:**
- Switch the server to **Username & password** in the app.

Either way, make sure the proxy has **WebSockets support** enabled (the app uses `wss://HOST/api/current`). The app pings every 30 s (45 s for instant alerts). That keeps the connection under nginx's default 60 s idle timeout, so NPM needs no extra timeout settings.

## Create a TrueNAS API key

1. Open the TrueNAS web UI.
2. Open the **account menu** (the person icon, top right) and choose **My API Keys**. You can also go to **Credentials › Users**, select a user, and click **View API Keys**.
3. Click **Add**, give the key a name, pick an administrative **Username**, and choose whether it expires. Then click **Save**.
4. **Copy the key right away.** TrueNAS shows it only once. If you lose it, edit the key and choose **Reset** to get a new one.
5. In the app, tap **Add server**, enter the address (for example `https://truenas.local` or `https://192.168.1.10`), paste the key, tap **Test**, then **Save**.

> ⚠️ **Use HTTPS.** TrueNAS (25.04+) **automatically revokes an API key that is sent over plain HTTP**. The app warns you when you enter an `http://` address.
> The default TrueNAS certificate is self-signed. That is fine: tap **Test** and accept the "Trust this server?" dialog after checking the fingerprint.

The key has the same permissions as the user it belongs to. For read-mostly use, think about a dedicated user with a limited role (for example `READONLY_ADMIN`). Actions the user isn't allowed to perform show a "no permission" error.

## Supported TrueNAS versions / APIs

| TrueNAS version | API used | Notes |
|---|---|---|
| **25.04 "Fangtooth", 25.10 "Goldeye"** | JSON-RPC 2.0 WebSocket at `wss://HOST/api/current`, `auth.login_with_api_key` | Main target. Live stats included |
| **26 / 27 (preview)** | Same endpoint. The app falls back to `auth.login_ex` with `API_KEY_PLAIN` when `auth.login_with_api_key` is gone | Set the key owner's username under *Advanced options* (default `truenas_admin`) |
| 24.10 "Electric Eel" and older SCALE | Not supported | The app shows "needs TrueNAS 25.04 or newer". Since 1.1.0 the app never uses the legacy REST API v2.0 (`/api/v2.0`): TrueNAS 25.04+ logs every credentialed REST request as a `LEGACY_REST` authentication (the "Deprecated REST API usage" alert) and 26.04 removes it |

Methods used on the WebSocket API: `auth.login_ex` / `auth.login_ex_continue` / `auth.generate_token` (password sign-in), `system.info`, `core.subscribe("reporting.realtime")`, `pool.query`, `disk.query`, `disk.temperatures`,
`pool.dataset.query`, `app.query` / `app.start` / `app.stop` / `app.redeploy` (jobs, tracked with `core.get_jobs`), `alert.list` / `alert.dismiss`,
`service.query`, `service.control` (falls back to `service.start`/`service.stop`), `system.reboot` / `system.shutdown`,
`app.upgrade_summary` / `app.upgrade` / `app.pull_images` / `catalog.sync`, `core.get_jobs` / `core.job_abort`,
`app.available` / `app.categories` / `catalog.get_app_details` / `app.create` / `app.update` / `app.delete` / `app.rollback_versions` / `app.rollback` (0.4, jobs),
`vm.query` / `vm.start` / `vm.stop` / `vm.poweroff` / `vm.restart` / `vm.update` / `vm.delete` / `vm.create` / `vm.device.create` / `vm.device.nic_attach_choices` / `vm.get_display_web_uri`, `filesystem.listdir`,
`virt.global.config` / `virt.instance.query` / `virt.instance.start` / `virt.instance.stop` / `virt.instance.restart` / `virt.instance.delete` (0.4.1),
`auth.generate_token` (shell: ttl 300, `{}`, match_origin, single use) / `core.resize_shell` / `app.container_console_choices` / WebSocket `/websocket/shell/` (0.7),
`pool.snapshot.query` / `create` / `delete` / `rollback` / `clone` / `hold` / `release`, `pool.snapshottask.query` / `create` / `update` / `delete` / `run`, `pool.scrub.query` / `create` / `update` / `scrub`, `cronjob.query` / `create` / `update` / `delete` / `run`, `replication.query` / `update` / `run`, `cloudsync.query` / `update` / `sync`, `rsynctask.query` / `update` / `run` (0.5),
`core.subscribe("app.stats:{interval}")`, `core.subscribe("app.container_log_follow:{app_name, container_id, tail_lines}")`, `GET /api/versions` (auto-detect) and `GET /api/boot_id` (same-NAS check).
Method names and payloads for 0.4 / 0.4.1 / 0.5 / 0.7 were checked against the middleware source of TrueNAS 25.10.3.
1.1 adds `user.query` / `create` / `update` / `delete` / `shell_choices`, `group.query` / `create` / `update` / `delete`, `reporting.netdata_graphs`, `reporting.netdata_get_data` and `audit.query`, checked against the `stable/goldeye` middleware (API `v25_10_2`) and the web UI source.
The method names come from the official docs at <https://api.truenas.com/>.

## Installing APKs: details

- **In the app (0.5+):** System › About › Check for updates downloads, verifies and installs new releases (see *In-app updates*).
- **Releases:** download `truenas-companion-release.apk` or `truenas-companion-debug.apk` from the latest GitHub Release (same names on every tag; older versioned filenames are deprecated). Release APKs are all signed with the same key, so a new release **installs as an update** over the previous one and keeps your servers and settings. **Or:**
- **Actions:** open the latest successful *Android CI* run and download the `truenas-companion-debug-apk` artifact (a zip containing the APK).

To install it, open the APK on your phone. Android asks you to allow **Install unknown apps** for your browser or file manager. Allow it, then tap Install.
With a computer you can run `adb install -r truenas-companion-debug.apk` (or the release APK) instead.

> CI artifacts are signed with the CI runner's own debug key, which is different from the release key. Android refuses to install one over the other ("App not installed"). Stick to one source, or uninstall first.

## Architecture

```
app/src/main/java/app/truenascompanion/
├── data/
│   ├── api/          TrueNasApi interface, JSON-RPC WebSocket client + implementation, WebSocketAuth (password/2FA/token),
│   │                 tolerant JSON parsers, error mapping, connector (WebSocket only, no REST)
│   ├── net/          OkHttp client factory, certificate pinning trust manager ("trust this server"), RouteResolver (local/Tailscale/VPN/remote),
│   │                 LocalDetector (auto-detect), LocalCheck (same-NAS check)
│   ├── security/     Android Keystore AES-GCM secret cipher, AppLock state machine
│   ├── store/        DataStore: servers, encrypted keys, per-server dashboard layout, appearance
│   ├── repository/   TrueNasRepository: active connection, lazy reconnect, live stats with retry
│   ├── protection/   Schedule presets/descriptions and the protection summary rules (0.5)
│   ├── update/       GitHub release check, verified download + install, daily worker (0.5)
│   ├── vpn/          WireGuard config parsing + split-tunnel rewrite, TunnelManager (GoBackend), wg-easy API client, VPN setup helpers (0.6)
│   ├── shell/        Web shell protocol and connection (0.7)
│   └── model/        Domain models and the dashboard layout model
└── ui/               Compose screens + ViewModels (dashboard, storage, apps, jobs, alerts, system, servers, shell, auth dialogs), theme, components
```
Stack: Kotlin, Jetpack Compose, Material 3, Navigation Compose, Coroutines/Flow, OkHttp (WebSocket + HTTP), kotlinx.serialization,
DataStore, AndroidX Biometric, [wireguard-android](https://git.zx2c4.com/wireguard-android/) tunnel library, CameraX + [ZXing](https://github.com/zxing/zxing) for QR codes, [Coil](https://coil-kt.github.io/coil/) for catalog icons, [Reorderable](https://github.com/Calvin-LL/Reorderable) for drag-and-drop, and the [Termux](https://github.com/termux/termux-app) terminal emulator (Apache 2.0, vendored) for the shell. minSdk 26, target/compile SDK 37.

## Security notes
- API keys, session tokens and saved passwords are stored only on the device, encrypted with a non-exportable Android Keystore key. Saving the password is off by default. App backup and device-transfer are disabled, so keys are never included in cloud backups.
- Self-signed certificates are accepted only if their SHA-256 fingerprint matches one you pinned for that server. Everything else goes through normal system CA validation. If the server's certificate changes (for example after it is regenerated), the connection fails until you trust the new one.
- Cleartext HTTP is allowed because many home NAS boxes are reached that way on the LAN. Remember that TrueNAS revokes API keys sent over HTTP, and that password sign-in over HTTP sends your password unencrypted. The app warns you in both cases.
- Session tokens are created with `match_origin=false` so that they keep working when your phone's IP changes (Wi-Fi ↔ mobile data). Anyone who pulls the token out of the encrypted store could use it until it expires. Pick a shorter *Stay signed in* period if that worries you.
- WireGuard configs (including their private keys) are stored encrypted like API keys. The tunnel only carries this app's traffic to the NAS's LAN IP. The wg-easy admin password generated during setup is shown once and never stored by the app.
- A shell token is single-use, lasts 5 minutes, and only works from the same address as the API connection. It is never written to the log, and neither is terminal input or output. The shell WebSocket is closed when you leave its screen and 30 seconds after the app is backgrounded.
- No analytics, crash reporting or ads. Phone alerts are fetched directly from your NAS; nothing goes through a push service. The only requests that don't go to your NAS are **app icons** (0.4) and the **update check** (0.5). The phone downloads icons from the URLs in the TrueNAS catalog (TrueNAS's own CDN) and caches them. The update check is an anonymous request to the GitHub API once a day (and when you tap *Check for updates*); downloaded updates are verified by SHA-256 and signing certificate before Android installs them. No credentials or NAS data are sent with either.

## Known limitations
- **Not yet verified against a live TrueNAS server.** Payload formats were taken from the official API docs (v25.04–v27). The parsers are deliberately tolerant, but some fields may differ in practice, especially `reporting.realtime` on older releases and `disk.temperatures` output.
- Password sign-in, 2FA, session tokens, app upgrades and the task list were built against the documented API (v25.10) and a fake test server. **They have not been verified on a live NAS.** The token lifecycle in 0.4.2 is modelled on the 25.10.3 middleware source (tokens are spent when their connection closes; 30-day cap from the password + 2FA sign-in; tokens are held in memory), but it has not been checked on a live NAS.
- App install/edit forms, logs, stats, rollback and delete (0.4) were built from the 25.10.3 middleware source and tested with sample schemas. **They have not been tried against real catalog apps on a live NAS.** Some field types (for example certificate or GPU pickers) are shown as "kept at default" and can be changed in JSON mode.
- Auto-detect, local/remote switching and the app lock were tested with unit tests and a fake server only. Real-network behaviour (VPN apps, captive portals, OEM biometric prompts) is unverified.
- VM and container actions (0.4.1) were built from the 25.10.3 middleware source and tested against a fake server. **They are unverified on a real NAS.** In particular, whether the SPICE web display opens without first signing in to the TrueNAS web UI in the same browser is unverified. VM creation covers the common case (one disk, ISO, NIC, display); passthrough devices, CPU pinning and similar options are left to the web UI.
- Data protection (0.5) was built from the 25.10.3 middleware source and tested with unit tests and a fake API. **It is unverified on a real NAS.** In particular: the one-off SMART test via a temporary cron job, toggling cloud sync tasks with a partial `cloudsync.update`, and rollback error messages when clones exist. Creating replication, cloud sync and rsync tasks is left to the web UI, and SMART test results can't be shown on 25.10 (see above).
- Users & groups, Reports and the Audit log (1.1) were built from the 25.10 middleware and web UI sources and tested with unit tests (exact request payloads) and screenshot previews with example data. **They are unverified on a real NAS.** In particular, netdata identifiers and legends vary by hardware, and very large audit exports are capped at 5,000 rows.
- Certificates, quick actions and the alert improvements (1.2) were built from the 25.10 middleware and web UI sources and tested with unit tests (exact payloads, expiry/snooze/grouping logic, deep-link mapping), Robolectric notification tests and screenshot previews with example data. **They are unverified on a real NAS and phone.** In particular: ACME issuing with real DNS authenticators, the web UI certificate switch with rollback/check-in, launcher shortcut and Quick Settings tile behaviour across launchers/OEMs, and alert `args` for classes not listed above (those open Alerts). Deep links open the right tab or screen but don't scroll to the specific pool, disk or dataset.
- The in-app installer (0.5) was tested with unit tests; the download → verify → system installer flow is **unverified on a real phone**.
- The shell (0.7) follows TrueNAS 25.10.3 `apps/webshell_app.py` and the web UI's `auth.generate_token` / `/websocket/shell/` calls. Handshake, resize, binary frames and a real pty were tested with unit tests and `tools/webshell_stub.py`. **It has not been tried on a live NAS.** A program path can't take arguments. Incus and classic VMs have no in-app console. If a reverse proxy doesn't forward WebSockets, the shell says so instead of connecting.
- The VPN features (0.6) were built from the wg-easy 15.4.0 and TrueNAS apps catalog sources and the wireguard-android library. The tunnel itself (bring-up, handshake, split tunnel, reference counting, fallback on a failed handshake) was tested on an Android 14 emulator against a real WireGuard peer (see [Building](BUILDING.md#testing-the-wireguard-tunnel)). **Unverified:** installing wg-easy and Tailscale on a real TrueNAS 25.10, wg-easy's setup API on a real install, the background tunnel for alert checks on a real Android 14+ phone, real Tailscale detection, and QR scanning on a real camera. If another VPN app takes over while the tunnel is up, the status may only update once a connection fails.
- Shares (SMB/NFS) can't be managed yet.
- The legacy DDP WebSocket (`/websocket`) of pre-25.04 releases is not used, and neither is the REST API (removed in 1.1.0), so those releases are not supported.
- No home-screen widgets yet.
- Phone alerts depend on Android letting the app run in the background. Aggressive OEM battery savers (some Xiaomi, Huawei and Samsung settings) can delay or stop checks unless the app is allowed to run in the background. Instant mode only reconnects after a reboot if Android lets it start a foreground service at boot.

