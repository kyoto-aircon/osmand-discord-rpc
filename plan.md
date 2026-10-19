This plan was AI-generated with minimal to no oversight.
# OsmAnd → Discord RPC Trip Sharing

## 1. Scope contract

- **Goal**: live OsmAnd trip stats as Discord rich presence, `15KM/H - 50KM - 01:45:23`.
- **Architecture (final, revised during build)**: companion Android app reading OsmAnd's live
  recording via AIDL (`getGpxRecordingInfo` + `getGpxPoints("")`) — *no own GPS*; loopback HTTP
  server; Kettu/Raincord plugin polling it and setting `LOCAL_ACTIVITY_UPDATE`.
- **In scope**: Android app (zero runtime deps beyond the vendored OsmAnd AIDL jar), permission
  request flow through OsmAnd's own dialog, foreground service + notification, `/status` JSON,
  Raincord plugin, private GitHub release with APK.
- **Out of scope**: own GPS (rejected after discovering exact AIDL stats), OsmAnd fork, cloud
  polling, desktop Vencord relay, auto-start.
- **Done**: app appears in OsmAnd plugins, requests `recording` + `tracks_view`, serves live
  status, Raincord shows the activity line, build+tests pass, private release published.
- **Open risk**: cleartext HTTP to 127.0.0.1 from Discord's process (unproven; documented).

## 2. Findings

- OsmAnd AIDL V2 exposes `getGpxRecordingInfo()` (isRecording, distance metres, duration ms)
  and `getGpxPoints("")` → live in-progress recording points with per-point speed. Exact parity
  with the status bar, no location permission.
- Permission groups `recording` + `tracks_view` are requested via the exported activity
  `net.osmand.aidl.REQUEST_PERMISSIONS` (extras `groups` / result `granted_groups`), and the app
  appears in OsmAnd's Plugins screen as a connected app.
- Kettu plugin contract: `manifest.json` + IIFE `index.js` receiving the `vendetta` global;
  presence via `FluxDispatcher.dispatch({type:"LOCAL_ACTIVITY_UPDATE", activity, socketId})`.

## 3. Steps (as built)

1. Toolchain vendored under `.tools/` (JDK 21, Android SDK 36, Gradle 8.11.1 wrapper).
2. Gradle project + vendored `app/libs/osmand-aidl-lib.jar`.
3. `TripStats` (pure formatting/geometry) + unit tests.
4. `OsmandAidlClient` (dual package bind, rebind), `LocalHttpServer`, `TripSharingService`,
   `MainActivity` (grant + toggle).
5. Raincord plugin (`index.js`, `manifest.json`, README).
6. Build + unit tests green; APK produced.
7. Tester + review subagents, fixes, commit, private GitHub repo + release.

## 4. Files

See repo tree: `app/`, `raincord-plugin/`, `README.md`, `plan.md`, `.gitignore`.
