# Anon Browser

An Android browser that runs a bundled Tor daemon and forces all page traffic
through it before anything loads.

## How it works

- **Rendering**: Android `WebView`, which is Chromium under the hood — there's
  no supported way to embed a from-scratch build of upstream Chromium in an
  Android app short of shipping your own multi-gigabyte fork, so this uses the
  OS's Chromium-based WebView component.
- **Tor**: the `tor` binary (via `info.guardianproject:tor-android`) runs as a
  child process inside a foreground `Service` (`TorService`), so Android
  doesn't freeze/kill it when the app is backgrounded. `TorManager` launches
  it, parses `Bootstrapped NN%` from its log, and speaks a small hand-rolled
  control-port client (cookie auth + `SIGNAL NEWNYM`) for the "new identity"
  button.
- **Proxying**: `androidx.webkit.ProxyController` overrides the WebView's
  network stack to route everything through Tor's local SOCKS5 port
  (127.0.0.1:9150). This is only wired up *after* Tor reports bootstrap
  complete — the UI blocks page loads until then, so nothing goes out
  unproxied. Chromium's SOCKS5 client resolves DNS through the proxy itself
  (no local DNS leak).
- **Bridges**: tap the settings icon in the toolbar to paste bridge lines
  from bridges.torproject.org (`Bridges.kt`, `BridgesSheet` in
  `MainActivity.kt`). Plain (unlisted) bridges and `obfs4`/`webtunnel`/
  `meek_lite` bridges work — the pluggable-transport ones run through
  `com.netzarchitekten:IPtProxy` (the same library Orbot uses), which starts
  a local SOCKS listener per transport that `TorManager` points torrc's
  `ClientTransportPlugin` at. Snowflake and dnstt bridges are recognized but
  not started (they need extra broker/ICE or resolver config this app
  doesn't set up yet) — pasting one shows a warning and that line is
  skipped rather than silently failing. Saving restarts Tor with the new
  config.
- **Circuit viewer**: the layers icon in the toolbar opens a sheet showing the
  current circuit — This device → Guard → Middle → Exit → Destination — as a
  nested-ring "onion layer" path. Backed by `TorManager.getCurrentCircuit()`,
  which asks the control port for `GETINFO circuit-status` (the most
  recently built `PURPOSE=GENERAL` circuit) and `GETINFO ns/id/<fingerprint>`
  per hop for its IP. Since this app doesn't do Tor Browser's per-site
  circuit isolation, this shows *a* current circuit, not necessarily the one
  that carried whatever page is currently on screen.

## Known limitations — read before relying on this for real anonymity

This is **not** Tor Browser. Tor Browser is Firefox-based specifically
because of years of fingerprinting-resistance patches (canvas/WebGL
randomization, letterboxing, font/timezone normalization, first-party
isolation, etc.) that don't exist in stock Chromium/WebView. Concretely,
compared to Tor Browser:

- **Fingerprinting**: sites can still fingerprint you via canvas, WebGL,
  installed fonts, screen metrics, etc. Nothing here randomizes or spoofs
  those.
- **WebRTC**: camera/mic permission requests are denied by default (see
  `onPermissionRequest` in `MainActivity`), which blocks the main leak vector,
  but this isn't as thorough as Tor Browser's WebRTC hardening.
- **Per-site circuit isolation**: Tor Browser gives each site its own Tor
  circuit automatically. This app shares one circuit across all sites until
  you tap "new identity" (the shuffle icon), which is coarser.
- **Everyone on this device shares the process' trust boundary**: the SOCKS
  (9150) and control (9151) ports are bound to loopback only, but any other
  app on the same device could still reach them.

Treat this as "Tor-routed Chromium browsing," not "anonymous browsing" in the
Tor-Browser sense. Don't use it for anything where deanonymization has real
consequences without understanding the gaps above.

## Building

This was built and tested in a Termux/proot-distro (Debian) environment with:

- JDK 17 at `/root/toolchain/jdk-17.0.20.1+1`
- Android SDK at `/root/coding/android-sdk` (`local.properties` points at it)
- `gradle.properties` overrides `android.aapt2FromMavenOverride` to Termux's
  native aarch64 `aapt2`, since AGP's Maven-resolved `aapt2` is x86_64-only
  and won't run in this aarch64 proot sandbox. On a normal dev machine or CI,
  delete that line so AGP uses its own `aapt2`.

```sh
export JAVA_HOME=/root/toolchain/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/root/coding/android-sdk
sh gradlew assembleDebug --console=plain
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

**Installing**: this proot's `/root/coding` is bind-mounted straight to the
phone's shared storage at `/storage/emulated/0/coding`, so the simplest path
is to open a file manager on the phone, browse to
`Internal storage/coding/anon-browser/app/build/outputs/apk/debug/`, and tap
`app-debug.apk` to sideload it directly (grant "install unknown apps" to the
file manager if prompted) — no adb required.

If you'd rather use `adb install`, run it from Termux itself (not from inside
this Debian proot) — both the SDK's bundled `platform-tools/adb` (x86_64-only
ELF, won't run on this aarch64 sandbox, same issue as `aapt2`) and Termux's
own `adb` (fails to dynamically link when invoked from inside this proot)
don't work from in here.
