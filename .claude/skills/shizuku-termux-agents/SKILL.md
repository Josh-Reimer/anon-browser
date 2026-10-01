---
name: shizuku-termux-agents
description: This skill should be used when a coding agent running inside Termux (optionally nested in proot-distro) needs to drive the real Android OS — install/verify APKs, run pm/am/dumpsys/uiautomator/input, or receive tasks from an app via Termux's RUN_COMMAND plugin — using Shizuku for privileged access without full root. Triggers on "shizuku", "rish", "pm install termux", "uiautomator agent", "RUN_COMMAND termux", "drive android app from termux", "adb shell from termux without root".
---

# Shizuku + Termux for agents driving a real device

An agent whose own shell runs inside Termux (or a nested proot-distro
container) can't reach most of the real Android OS directly — `pm`, `am`,
`dumpsys`, `uiautomator`, `input`, and friends live outside that sandbox.
Shizuku bridges that gap: paired once via wireless debugging (no root
required), it exposes ADB-shell-level privileges to a local client. This
skill covers the two directions that combine into a working agent loop:
the agent reaching *out* to the OS (Shizuku/`rish`), and an app reaching
*in* to hand the agent work (Termux's `RUN_COMMAND` plugin).

## Reaching Shizuku: the vanilla `rish` script is often broken here

Shizuku ships a shell client — the `rish` script plus `rish_shizuku.dex` —
but running it as-is from inside a sandboxed/nested shell commonly breaks
two independent ways:

1. Its shebang execs `/system/bin/sh` directly, which a sandboxed/seccomp
   -restricted shell may not be permitted to exec at all.
2. Even if that worked, the script needs its own executable bit, which (per
   the `android-termux-build` skill) is a silent no-op on FUSE-backed shared
   storage — so it can't be made executable there either.

**Working bypass** — call `app_process64` directly, bypassing both
problems, reproducing the script's real args by hand:

```bash
RISH_APPLICATION_ID=<package Shizuku is paired/authorized for> \
  /system/bin/app_process64 \
  -Djava.class.path=/path/to/rish_shizuku.dex \
  /system/bin --nice-name=rish rikka.shizuku.shell.ShizukuShellLoader \
  -c '<command>'
```

`RISH_APPLICATION_ID` must match whatever package Shizuku actually
authorized on the device — not an arbitrary value. Wrap this in a one-line
helper script (taking the command as `$1`) and keep it outside any repo
that gets committed/published, since it's environment-specific plumbing,
not project code.

## Know the real privilege level: `uid=2000(shell)`, not root

A `rish` connection authenticates as the real ADB shell identity — groups
like `sdcard_rw`, `inet`, `adb` — **not root**. Don't assume a privileged
operation will succeed just because it's reachable through Shizuku. Two
concrete consequences:

- **Shell (uid 2000) does not have broad access to other apps' private
  storage.** Reading another app's own data directory directly through
  `rish` gives `Permission denied`, even if some *other* identity available
  to the agent's own session can read that exact file through a different
  access path (e.g. a bind-mounted view of the filesystem). Don't conflate
  the two — test the actual path you intend to use, not an adjacent one
  that happens to work.
- **`run-as <package>` through `rish` only works for debuggable builds** —
  confirmed useful for reading a debug build's private app storage (control
  port auth cookies, internal databases, etc.), but fails with "package not
  debuggable" against a release/non-debuggable target.

## `pm install` from a FUSE-backed path fails — stage through `/data/local/tmp`

Installing an APK whose path resolves through `/sdcard/...` (even via
`rish`, which itself can read the file fine) fails at the `system_server`
level: `system_server has no access to read file context
u:object_r:fuse:s0`. This is specifically about `system_server`'s own
ability to read through the FUSE layer for install purposes — unrelated to
whether the invoking shell can read the file.

```bash
cp app-debug.apk /data/local/tmp/
pm install -r /data/local/tmp/app-debug.apk
```

## Always re-verify an install actually landed

`pm install -r`'s own stdout doesn't always make a failure here obvious.
After every install (not just the first one in a session), confirm it
actually took:

```bash
dumpsys package <applicationId> | grep lastUpdateTime
date
```

`lastUpdateTime` should be within seconds of the current device time. Skip
this check even once and it's easy to burn real debugging time chasing
behavior that "should" reflect a recent rebuild while the device is
silently still running an old install.

## UI automation through `rish`

`uiautomator dump` + `input tap/text/swipe/keyevent` can drive a real app's
UI end-to-end through `rish`, with caveats:

- **The screen locks between commands.** `input keyevent KEYCODE_WAKEUP`
  handles a simple sleep/dim, but a *secure* lockscreen needs the user to
  physically unlock the device — there's no way around that from an agent
  shell.
- **Foreground focus can get stolen back mid-sequence** — e.g. by whatever
  is rendering the agent's own visible terminal session, if that's on the
  same device. Batch a multi-step interaction (tap a field → type → tap a
  button) into a single `rish` invocation where possible, rather than
  issuing each step as a separate call, to shrink the window where focus
  can flap away.
- **`uiautomator dump`'s text/content-desc extraction is unreliable for
  custom-drawn UI** (e.g. Compose text inside a scrollable container) — it
  can come back empty even though the text is visibly on screen.
  `screencap -p <path>` followed by reading the resulting image is the
  trustworthy fallback for confirming actual on-screen state when a dump
  looks suspiciously empty.

## Shizuku's own service can go stale

After inactivity or a device reboot, Shizuku's service can start timing out
("Request timeout... may be blocked by battery optimization"). This isn't
fixable from the agent's side — it requires the user to open the Shizuku
app and confirm/restart the service. Recognize this failure signature and
surface it as "ask the user to reopen Shizuku," not as a bug in the
invocation.

## The other direction: an app handing work to the agent via `RUN_COMMAND`

Termux's `RUN_COMMAND` broadcast/intent API lets an Android app launch a
command inside Termux unattended — the complementary half of this pattern,
used to let an in-app feature hand a free-form task to an agent running in
the Termux/proot-distro environment this skill is about.

- Requires `com.termux.permission.RUN_COMMAND` in the calling app's
  manifest, plus `allow-external-apps=true` in the target Termux's own
  `~/.termux/termux.properties` (a one-time manual step on the device —
  nothing the app or agent can set from its own side).
- The command path and each argument arrive as literal Intent extras (a
  path string plus a string array), never shell-parsed — so free-form text
  from the app (e.g. a user's typed request) needs no quoting/escaping
  handling to reach the agent safely.
- Results come back to the app via a `BroadcastReceiver`; for anything that
  might outlive the calling process (a build, an agent invocation), persist
  state the UI can resume collecting from rather than holding it only in
  memory.
- The invoked script lands in the same FUSE-storage-with-no-exec-bit and
  non-login-shell-PATH situation covered in the `android-termux-build`
  skill — invoke it as `bash script.sh` rather than relying on its shebang,
  and have it set any `PATH`/env it needs explicitly rather than assuming
  dotfiles were sourced.
