# OsmAnd Trip RPC — Kettu plugin

Shows the live OsmAnd trip recording (`15KM/H - 50KM - 01:45:23`) as Discord rich presence.

The plugin polls the companion Android app on `http://127.0.0.1:8090/status` every 2 seconds,
and sets/clears the client-side activity with `FluxDispatcher` (`LOCAL_ACTIVITY_UPDATE`),
the same mechanism Vencord's CustomRPC uses.

## Install

This repo is public, so Kettu can install straight from GitHub raw:

```
https://raw.githubusercontent.com/kyoto-aircon/osmand-discord-rpc/main/kettu-plugin/
```

1. Install Kettu (KettuManager for non-root, KettuXposed for root).
2. Settings → General → enable **Developer Settings**.
3. Settings → Plugins → floating **+** (or **Install a plugin**).
4. Paste the URL above — a folder URL; the installer appends `manifest.json` and `index.js`.
5. Accept the unproxied-source warning.

No build step: `index.js` is a hand-written Kettu bundle (`manifest.json` hash matches it).

**Raincord is not supported.** Raincord compiles its plugin list into the client and has no
third-party plugin installer; use Kettu. The files are portable to any Kettu/Vendetta-family
loader.

## Requirements

- The companion APK installed, OsmAnd access granted, and sharing started.
- Kettu must allow cleartext HTTP to loopback. If the activity never appears, check Kettu's
  logs for `[OsmAndTripRPC] status fetch failed` — a cleartext-blocked fetch is the most likely
  cause on Android 9+ (this is a client-app network policy, not a plugin bug).

## Notes

- The activity is client-side and only visible while Discord/Kettu is running.
- `state` is the exact string served by the app; `details` is "OsmAnd trip recording".
- `name` is the same trip string, which is what Discord shows on the compact presence row.
- Presence is cleared 2 seconds after the recording stops or the app goes away.
