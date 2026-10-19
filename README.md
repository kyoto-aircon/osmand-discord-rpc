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

- Android 7.0+ (GrapheneOS is fine). On GrapheneOS, the app needs its **Network** permission
  toggle enabled (Settings → Apps → OsmAnd Trip RPC → Network); without it, even a loopback
  socket is refused (`socket failed: EPERM`).
- OsmAnd 5.5+ for native trip stats (distance/duration/state/speed from the recording). On any
  older OsmAnd release (including 5.4.x, the current stable), the app automatically falls back to
  this phone's GPS: speed, distance and elapsed time are computed locally. The OsmAnd trip AIDL
  API (`getGpxRecordingInfo` etc.) only exists from OsmAnd 5.5-dev, added October 2026.
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

If **Grant OsmAnd access** does nothing, the app now opens OsmAnd's own permission dialog without
pre-checking it, and logs the candidates it found. If OsmAnd still does not show a dialog, use the
manual route: tap **Open OsmAnd** → **☰ menu** → **Plugins** → **OsmAnd Trip RPC** → enable it and
allow **Trip recording** (and **Tracks: view**). The Plugins entry exists once the app has made its
first AIDL call: opening the app triggers a registration probe, and tapping **Start sharing** also
does it if needed.

## Logs

The app keeps an in-memory log of everything it and its service did since launch: AIDL
bind/connect/failure, permission requests and results, HTTP server start and every plugin request
(throttled), state transitions, and a one-line-per-minute heartbeat. Tap **View logs** on the main
screen to read it; **Copy** puts the whole log plus a header (app version, Android version, device,
installed OsmAnd version) on the clipboard, **Share** sends it through the system sheet. Lines are
capped at 500 characters and 800 lines; logs are never uploaded anywhere.

For "the presence does not show up", the key line is the heartbeat/HTTP line:

- `httpRequests=N` in the heartbeat and `GET /status -> 200 (requests=...)` lines mean Kettu is
  reaching the app; the problem is then on the Discord side (plugin not installed/enabled, or
  client-side activity hidden).
- `httpRequests=0` means nothing ever connected: the plugin is not polling, or Discord's process
  blocks cleartext loopback HTTP.

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

Presence is set while `recording === true`, and cleared within ~2–3 seconds after the recording
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

- **Manual stop.** The service is started/stopped from the app (or its notification). On OsmAnd
  5.5+ it auto-reports `recording:false` when the OsmAnd recording stops and the presence clears.
  In GPS fallback mode sharing runs until you stop it.
- **Cleartext loopback.** Android 9+ blocks cleartext HTTP by default, and the fetch runs inside
  Discord's process, not ours. If the activity never appears, check Kettu's logs for
  `[OsmAndTripRPC] status fetch failed`. This is the one unproven link in the chain.
- **Client-side presence** exists only while Discord/Kettu is running, and is shown by the
  client (same mechanism as Vencord's CustomRPC).
- **GPS fallback drift.** In fallback mode distance is accumulated from location fixes (accuracy
  and minimum-step filtering), so it can differ slightly from OsmAnd's track distance.
- The loopback endpoint is readable by other apps on the device; it contains only trip stats.
