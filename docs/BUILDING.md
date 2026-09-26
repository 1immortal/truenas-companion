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

## App icon
The adaptive launcher icon (foreground, background and monochrome layers for themed icons) is generated from `tools/icon/gen.py`.
It writes the vector drawables to `app/src/main/res/drawable/ic_launcher_*.xml` (a preview needs `cairosvg` and `pillow`).


The README icon is `docs/images/icon.png` (rendered from the same spec).
