<p align="center">
  <img src="docs/images/icon.png" width="112" alt="TrueNAS Companion icon">
</p>

<h1 align="center">TrueNAS Companion</h1>

<p align="center">
  A free, ad-free Android app to manage your <b>TrueNAS SCALE</b> server from your phone.
</p>

<p align="center">
  <a href="https://github.com/1immortal/truenas-companion/releases/latest"><b>Download the latest APK</b></a>
</p>

<table>
  <tr>
    <td><img src="docs/images/dashboard-dark.png" width="200" alt="Dashboard"></td>
    <td><img src="docs/images/catalog-dark.png" width="200" alt="Apps catalog"></td>
    <td><img src="docs/images/vms-dark.png" width="200" alt="Virtual machines"></td>
    <td><img src="docs/images/notif-mock-dark.png" width="200" alt="Phone alerts"></td>
  </tr>
</table>
<sub>Screenshots use made-up example data.</sub>

## What you can do

- **See how your NAS is doing at a glance.** Live CPU, memory, network and temperatures, plus pool health and free space, on a dashboard you can rearrange.
- **Get alerts on your phone.** A notification when a pool degrades, a disk runs hot or space runs low, straight from your NAS with no cloud service in between.
- **Manage your apps.** Browse the catalog, install, update, edit, roll back, read logs and see live resource use.
- **Control VMs and containers.** Start, stop, restart, edit resources or create a simple VM.
- **Keep an eye on storage.** Pools, disks with temperatures, and datasets.
- **Handle everyday chores.** Start or stop services, follow running tasks, dismiss alerts, reboot or shut down.
- **Works at home and away.** Add a local address and the app uses it on your home Wi-Fi, then switches to your remote address everywhere else.
- **Stays secure.** Sign in with your TrueNAS username, password and two-factor code (or an API key), lock the app with your fingerprint, and keep all credentials encrypted on the device.
- **Looks good.** Material You design with light and dark themes.

## Get the app

1. On your phone, open the [latest release](https://github.com/1immortal/truenas-companion/releases/latest) and download the `.apk` file.
2. Open the downloaded file. Android asks you to allow **Install unknown apps** for your browser or file manager. Allow it, go back, then tap **Install**.
3. New versions install over the old one and keep your settings.

Needs Android 8.0 or newer and TrueNAS SCALE (25.04 or newer is recommended for live stats and password sign-in).

## Getting started

1. **Add your server.** Tap *Add server* and enter the address you use for the TrueNAS web UI, for example `https://truenas.local` or `https://nas.example.com`. If your NAS uses its own self-signed certificate, the app shows its fingerprint and asks you to trust it once.
2. **Sign in.** Use your TrueNAS username and password, and enter your two-factor code if you have 2FA on. The app then stays signed in for the period you choose (1, 7 or 30 days).
3. **Optional: add a local address.** If you reach your NAS through a reverse proxy or domain from outside, add its home address too (the app can find it for you). At home the app connects directly for extra speed.
4. **Optional: turn on phone alerts** in the app's settings, and pick which alerts matter to you.

## Privacy

The app talks only to the servers you add. There are no ads, analytics, tracking or accounts. Your passwords, keys and sessions stay encrypted on your phone. The only other requests the app makes are for app icons, which come from TrueNAS's own catalog servers, with no personal data attached.

## More

- [Technical details](docs/TECHNICAL.md): how every feature works, sign-in and sessions, supported TrueNAS versions, security notes and known limitations.
- [Building from source](docs/BUILDING.md)
- Found a bug or have an idea? [Open an issue](https://github.com/1immortal/truenas-companion/issues).

TrueNAS Companion is an independent project and is not affiliated with or endorsed by iXsystems. TrueNAS is a trademark of iXsystems, Inc.

## License

MIT. See [LICENSE](LICENSE).
