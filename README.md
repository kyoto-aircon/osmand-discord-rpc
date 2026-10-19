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
- **`raincord-plugin/`** — a Kettu (Raincord) plugin that polls `http://127.0.0.1:8090/status`
  and sets a client-side Discord activity with `LOCAL_ACTIVITY_UPDATE`.

## Requirements

- Android 7.0+ (GrapheneOS is fine).
- OsmAnd (`net.osmand.plus` or `net.osmand`) with the AIDL V2 API — any current store build.
- Raincord (Kettu) for the presence display.
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

## Install the Raincord plugin

Kettu installs plugins from a URL pointing at a folder with `manifest.json` and `index.js`.
Host `raincord-plugin/` anywhere reachable from the phone (a public gist or static host works;
the files contain no secrets), then use Kettu's plugin installer with the manifest URL.
If your Kettu build supports local installs, point it at the folder directly — there is no build
step.

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
- **Client-side presence** exists only while Discord/Raincord is running, and is shown by the
  client (same mechanism as Vencord's CustomRPC).
- Distance is rounded to whole kilometres and speed to whole km/h to match the requested format.
- The loopback endpoint is readable by other apps on the device; it contains only trip stats.
