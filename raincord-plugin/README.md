# OsmAnd Trip RPC — Raincord plugin

Shows the live OsmAnd trip recording (`15KM/H - 50KM - 01:45:23`) as Discord rich presence.

The plugin polls the companion Android app on `http://127.0.0.1:8090/status` every 2 seconds,
and sets/clears the client-side activity with `FluxDispatcher` (`LOCAL_ACTIVITY_UPDATE`),
the same mechanism Vencord's CustomRPC uses.

## Install

Kettu installs plugins from a URL to a folder containing `manifest.json` and `index.js`.
Host this folder somewhere reachable from your phone (any static host, or a public gist),
then use Kettu's plugin installer with the manifest URL.

If your Kettu build supports installing from a local file, use `manifest.json` + `index.js`
directly — no build step is required.

## Requirements

- The companion APK installed, OsmAnd access granted, and sharing started.
- Kettu must allow cleartext HTTP to loopback. If the activity never appears, check Kettu's
  logs for `[OsmAndTripRPC] status fetch failed` — a cleartext-blocked fetch is the most likely
  cause on Android 9+ (this is a client-app network policy, not a plugin bug).

## Notes

- The activity is client-side and only visible while Discord/Kettu is running.
- `state` is the exact string served by the app; `details` is "Trip recording".
- Presence is cleared 2 seconds after the recording stops or the app goes away.
