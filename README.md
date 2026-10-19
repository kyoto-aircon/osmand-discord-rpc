# OsmAnd Trip RPC

Share your live OsmAnd trip recording in Discord as rich presence, formatted exactly like the
status bar:

```
15KM/H - 50KM - 01:45:23
```

Two pieces:

- **`app/`** — a small Android app that reads the *live* values of OsmAnd's in-progress trip
  recording through OsmAnd's official AIDL plugin API (`getGpxRecordingInfo()` and
  `getGpxPoints("")`). No own GPS, no location permission, no distance drift: the numbers come
  from the same counters OsmAnd shows. The app registers itself as a connected app, so it appears
  in OsmAnd's plugin screen, and it exposes the values on a loopback HTTP endpoint.
- **`kettu-plugin/`** — a Kettu (Vendetta/Bunny format) plugin that polls
  `http://127.0.0.1:8090/status` and sets a client-side Discord activity with
  `LOCAL_ACTIVITY_UPDATE`. Works on Kettu; **not on Raincord**, which only loads plugins compiled
  into the client and has no third-party plugin installer.

## Requirements

- Android 7.0+ (GrapheneOS is fine).
- OsmAnd (`net.osmand.plus` or `net.osmand`) with the AIDL V2 API — any current store build.
- [Kettu](https://github.com/C0C0B01/Kettu) for the presence display (Raincord cannot load
  third-party plugins).
- Trip recording must be running in OsmAnd while sharing.

## Build

The repo ships its own toolchain in `.tools/` (git-ignored):

```powershell
$env:JAVA_HOME  = "$PWD\.tools\jdk21-home"
$env:ANDROID_HOME = "$PWD\.tools\android-sdk"
.\gradlew.bat --no-daemon :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Install the app

1. Sideload the APK (`adb install -r app-debug.apk` or a file manager).
2. Open **OsmAnd Trip RPC** → **Grant OsmAnd access**.
3. In OsmAnd's "Allow OsmAnd Trip RPC to use OsmAnd?" dialog, enable **Trip recording** and
   **Tracks: view**, then allow. Both are required: `recording` reads distance/duration/state,
   `tracks_view` reads the recent track points used for speed.
4. Start a trip recording in OsmAnd, then tap **Start sharing** in the app.
5. The ongoing notification mirrors the status line; it has a **Stop** action.

The app also shows up under OsmAnd → **Plugins** as a connected app, where the permissions can be
changed later.

## Install the Kettu plugin

This repo is public, so the plugin installs straight from GitHub raw:

```
https://raw.githubusercontent.com/kyoto-aircon/osmand-discord-rpc/main/kettu-plugin/
```

1. Install Kettu (non-root: [KettuManager](https://github.com/C0C0B01/KettuManager/releases/latest);
   root: [KettuXposed](https://github.com/C0C0B01/KettuXposed/releases/latest)).
2. In Discord, open **Settings → General** and enable **Developer Settings**.
3. Open **Settings → Plugins** and tap the floating **+** (or **Install a plugin**).
4. Paste the URL above (a folder URL — the installer appends `manifest.json` and `index.js`).
5. Kettu warns that the source is unproxied; tap **Continue**.

Raincord is not supported: its plugin list is compiled into the client at build time and its
Plugins page has no install action. The plugin files themselves are portable — any Kettu/Vendetta
loader can install them from a URL.

Presence is set while `recording === true`, and cleared within ~2 seconds after the recording
stops or the app is stopped.

## HTTP API

`GET http://127.0.0.1:8090/status`

```json
{
  "enabled": true,
  "recording": true,
  "speedKmh": 15,
  "distanceKm": 50,
  "elapsedSeconds": 6323,
  "state": "15KM/H - 50KM - 01:45:23",
  "error": null,
  "updatedAt": 1750000000000
}
```

## Caveats

- **Manual stop.** OsmAnd's AIDL API does not expose whether sharing is wanted, so the service is
  started/stopped from the app (or its notification). It does, however, auto-report
  `recording:false` when the OsmAnd recording stops, and the presence clears.
- **Cleartext loopback.** Android 9+ blocks cleartext HTTP by default, and the fetch runs inside
  Discord's process, not ours. If the activity never appears, check Kettu's logs for
  `[OsmAndTripRPC] status fetch failed`. This is the one unproven link in the chain.
- **Client-side presence** exists only while Discord/Kettu is running, and is shown by the
  client (same mechanism as Vencord's CustomRPC).
- Distance is rounded to whole kilometres and speed to whole km/h to match the requested format.
- The loopback endpoint is readable by other apps on the device; it contains only trip stats.
