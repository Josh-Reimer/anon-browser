---
name: android-tor-integration
description: This skill should be used when embedding a real Tor daemon into an Android app (not just pointing at Orbot) — running the tor binary as a child process, driving its control port, routing WebView/network traffic through it, adding pluggable-transport bridges, or showing circuit/relay info to the user. Triggers on "embed tor in android", "bundle tor daemon", "tor control port", "SOCKS proxy webview", "pluggable transports android", "obfs4 android", "onion circuit viewer", "hidden service android app", "libtor.so".
---

# Embedding Tor in an Android app

Bundling a real `tor` binary that runs as a child process inside your app — not
delegating to Orbot — gives you a self-contained, anonymity-focused app. This
skill documents the concrete pieces, grounded in a shipping implementation
(`TorManager.kt`, `TorService.kt`, `Bridges.kt` in this repo).

## Dependencies

```kotlin
// Bundled tor daemon binary (libtor.so per ABI, placed under nativeLibraryDir
// so it's executable under Android 10+'s W^X restrictions).
implementation("info.guardianproject:tor-android:0.4.8.16")

// Pluggable transports (obfs4/webtunnel/meek_lite) for Tor bridges — the same
// library Orbot uses. Snowflake/dnstt ship in the jar but take more wiring
// (separate rendezvous/broker setup) — budget for them separately if needed.
implementation("com.netzarchitekten:IPtProxy:5.5.1")

// Chromium WebView proxy control (routes WebView traffic through Tor's SOCKS5 port).
implementation("androidx.webkit:webkit:1.17.1")
```

`tor-android` ships the actual `tor` executable as `libtor.so` per ABI — a
real daemon binary disguised as a shared library so Android's packager treats
it as a native lib eligible to live under `nativeLibraryDir` (which, unlike
most of the APK, Android actually permits executing from on API 29+, where
W^X blocks running code copied out of other locations at runtime).

## Packaging gotcha: `useLegacyPackaging`

```kotlin
packaging {
    jniLibs {
        // tor-android ships libtor.so per-ABI; make sure it isn't stripped/
        // compressed in a way that breaks exec-from-nativeLibraryDir.
        useLegacyPackaging = true
    }
}
```

Without this, AGP's default native-lib compression can produce a `libtor.so`
that fails to exec correctly once extracted. Any daemon-binary-disguised-as-
`.so` dependency needs this.

## Launching the daemon

```kotlin
val binaryPath = File(context.applicationInfo.nativeLibraryDir, "libtor.so")
val builder = ProcessBuilder(binaryPath.absolutePath, "-f", torrcFile.absolutePath)
    .redirectErrorStream(true)
builder.environment()["HOME"] = context.filesDir.absolutePath
val proc = builder.start()
```

Parse `Bootstrapped (\d+)%` out of stdout to drive a progress UI; `100%` means
the daemon is ready to accept SOCKS connections.

## Minimal torrc

```
SocksPort 127.0.0.1:9150
ControlPort 127.0.0.1:9151
CookieAuthentication 1
CookieAuthFile <app-files-dir>/tor/control_auth_cookie
DataDirectory <app-files-dir>/tor
AvoidDiskWrites 1
ClientOnly 1
SocksPolicy accept 127.0.0.1
Log notice stdout
```

Pick non-default ports (e.g. `9150`/`9151`, Tor Browser's traditional pair)
rather than Orbot's `9050`/`9051` if there's any chance the device also has
Orbot installed — avoids a port clash entirely instead of detecting one.

`GeoIPFile <path>` is optional but needed if you want country lookups on
relays later (`GETINFO ip-to-country/...`); tor can't read straight out of
the APK, so extract the geoip asset to a real filesystem path once at startup.

## Control port protocol

Cookie auth, not a password — read the raw cookie bytes and hex-encode them:

```kotlin
val cookieHex = cookieFile.readBytes().joinToString("") { "%02X".format(it) }
out.write("AUTHENTICATE $cookieHex\r\n".toByteArray())
```

Useful commands once authenticated:
- `SIGNAL NEWNYM\r\n` — forces new circuits ("new identity").
- `GETINFO circuit-status\r\n` — multi-line reply (`250+circuit-status`,
  then lines, terminated by a lone `.`, then trailing `250 OK`).
- `GETINFO ns/id/<fingerprint>\r\n` — consensus lookup for a relay's IP
  (same `250+`/`.`/`250 OK` shape; the `r ` line's 7th space-separated
  field is the IP).
- `GETINFO ip-to-country/<ip>\r\n` — single-line reply pattern instead:
  `250-key=value` then a *separate* `250 OK` line on success, or a single
  `551 ...` line on failure (no GeoIP loaded). **Read the trailing `250 OK`
  whenever the first line started with `250-`** — skipping it desyncs the
  socket and the next command reads this leftover line instead of its own
  reply. This asymmetry (multi-line GETINFO always needs its own `250 OK`
  read; a `551` failure never has one) is the easiest bug to introduce here.

Circuit semantics worth knowing before you build a circuit viewer:
- Tor 0.4.8+ builds Conflux-linked circuit pairs (`PURPOSE=CONFLUX_LINKED`)
  for ordinary web traffic instead of a single `PURPOSE=GENERAL` circuit;
  keep `GENERAL` as a fallback for older tor builds.
- A circuit to a `.onion` address is a different purpose entirely —
  `PURPOSE=HS_CLIENT_REND` — and its three hops are Guard/Middle/**Rendezvous**,
  not Guard/Middle/**Exit**. Hidden-service circuits never have an exit
  relay: the destination is itself inside the Tor network, so traffic never
  needs to leave it.

## Routing WebView through Tor

```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
    val config = ProxyConfig.Builder()
        .addProxyRule("socks5://127.0.0.1:$socksPort")
        .build()
    ProxyController.getInstance().setProxyOverride(config, { it.run() }) { /* ready */ }
}
```

`ProxyController`'s override is **process-global** — it affects every
WebView in the process at once, so there's no per-tab opt-out. Only apply it
once Tor reports fully bootstrapped (not merely "started"), so no WebView
request can leave the device before the proxy is actually in place — wiring
this on process start instead of on bootstrap-complete is a real leak, not
just a race.

## Foreground service requirement

A background network daemon with no foreground service gets frozen/killed by
Android within minutes once the app backgrounds, on API 26+. Run the tor
process from a `LifecycleService` (or similar) and call `startForeground()`
immediately in `onCreate()`, with `foregroundServiceType="specialUse"` in the
manifest (there's no better-fitting official type for "runs a local proxy
daemon"). Tie the service's own lifecycle to the daemon: `onDestroy()` should
stop the tor process, not just the service's own bookkeeping.

## Bridges / pluggable transports

`IPtProxy.Controller` runs each needed transport (obfs4, webtunnel, meek_lite)
as a local SOCKS listener; wire the result into torrc:

```
UseBridges 1
ClientTransportPlugin <transport> socks5 127.0.0.1:<port-from-controller.port(transport)>
Bridge <bridge-line>
```

Start one controller per distinct transport actually needed (don't start
listeners for transports with no configured bridge line), and surface
unsupported transport names (anything not wired, e.g. Snowflake/dnstt) to the
user rather than silently dropping those bridge lines — a silently-ignored
bridge line that was supposed to be load-bearing is a connectivity dead end
the user can't diagnose.

## Native-lib page alignment

Newer Android versions/devices expect native libraries built 16KB-page
-aligned; a transitive dependency shipping an older non-aligned build gets
flagged (debug builds) or can outright fail to load (16KB-page devices).
Pin affected transitive deps explicitly to an aligned version rather than
letting your BOM/plugin resolve one transitively — e.g.:

```kotlin
// Pinned above what the Compose BOM pulls in transitively (1.0.1) — 1.1.0 is
// built 16KB-page-aligned, 1.0.1 isn't.
implementation("androidx.graphics:graphics-path:1.1.0")
```

Check any flagged lib's release notes for the first 16KB-aligned version
rather than guessing — these pins are usually a single version bump, not a
downgrade.

## Manifest permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

`usesCleartextTraffic="true"` is about the app's own loopback hop to Tor's
local SOCKS port (`127.0.0.1`), not permission to talk cleartext to the
internet — Tor itself is what encrypts/anonymizes everything past that hop.
