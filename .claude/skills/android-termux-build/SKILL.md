---
name: android-termux-build
description: This skill should be used when building, signing, or installing an Android app from inside Termux or a Termux + proot-distro container — Gradle builds on FUSE-backed shared storage, aapt2 on a non-glibc host, JDK/toolchain pinning, keeping a release keystore out of a repo, native-lib alignment warnings, or getting a built APK installed on-device from that same environment. Triggers on "build android app in termux", "gradlew termux", "sh gradlew", "aapt2 termux", "proot-distro android build", "keystore out of repo", "pm install apk termux", "16KB page size android".
---

# Building Android apps from Termux / proot-distro

Termux (optionally layered with a `proot-distro` container for a fuller
toolchain) can build a real Gradle Android project end-to-end on-device. The
friction is specific and repeatable — this skill lists the concrete fixes.

## `chmod +x` is a silent no-op on FUSE-backed shared storage

If the project lives under `/sdcard/...` (or any bind mount backed by that,
e.g. a `proot-distro login -b /storage/emulated/0/coding:/root/coding`
mapping), `chmod +x` on files there **exits 0 but doesn't change anything** —
the underlying FUSE layer doesn't support the executable bit. This silently
breaks anything that relies on its own shebang/exec bit:

- `gradlew` must be invoked as `sh gradlew assembleDebug`, never `./gradlew`,
  everywhere in docs/scripts/CI-equivalents for the project.
- Any other script meant to be executed by something else (a RUN_COMMAND
  intent, a cron-like trigger, etc.) must be invoked the same explicit way —
  `bash /path/to/script.sh`, not relying on its own execute bit.

Don't spend time trying to "fix" the chmod — there's no fix from this side;
treat every script in the tree as non-executable by convention and always
invoke with an explicit interpreter.

## `aapt2` needs Termux's own build, not the Maven-published one

AGP's default `aapt2` artifact (pulled transitively via Maven) is built for
glibc Linux hosts and won't run on Termux (Bionic libc, not glibc). Override
it with Termux's own `aapt2` package:

```kotlin
// or via gradle.properties: android.aapt2FromMavenOverride=...
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

Install it first with `pkg install aapt2` (or equivalent) if it isn't already
on the device. Expect a one-time experimental-option warning from AGP when
this is set — that's expected, not a misconfiguration.

## Pin `JAVA_HOME` explicitly

Termux's package manager updates JDKs in place; a build script that assumes
whatever `java` happens to be on `PATH` can break silently across an
unrelated `pkg upgrade`. Pin and pass it explicitly on every Gradle
invocation instead of relying on an exported default:

```bash
JAVA_HOME=/root/toolchain/jdk-17.0.20.1+1 sh gradlew assembleDebug
```

## Using `proot-distro` for a fuller/more stable toolchain

When Termux's own rolling packages are too unstable for a pinned
Gradle/AGP/JDK combination, a `proot-distro` Debian (or similar) container
gives a normal apt-managed Linux userland instead, while still running on-
device.

**A bare `proot-distro login <name>` does NOT automatically bind your
project's shared-storage path into the container.** That has to be requested
explicitly:

```bash
proot-distro login debian -b /storage/emulated/0/coding:/root/coding -- <command>
```

Without `-b`, a path that very much exists on the device comes back as "No
such file or directory" inside a freshly-logged-in container — it's not
there yet, not a permissions issue.

**`proot-distro` refuses to run recursively** — `proot-distro login ...` from
inside a session that is *itself* already running under proot errors
immediately ("proot-distro should not be executed under PRoot"). You can't
test a fresh nested login from inside such a session; testing has to happen
from the outer Termux layer, or by reading proot-distro's own source instead
of running it.

### PATH/env gotcha: a command after `--` skips your dotfiles entirely

`proot-distro login <name> -- <command>` execs `<command>` **directly**,
not through a login shell — so `~/.bashrc`, `~/.profile`, and `/etc/profile`
never get sourced, and any `PATH`/`JAVA_HOME`/etc. customization that lives
only in those dotfiles silently doesn't apply. The `PATH` such a command
actually gets is proot-distro's own baked-in default plus (on a Termux host)
Termux's own `usr/bin` appended at the end — nothing more.

Concretely, this means **any script meant to be invoked this way must set
its own `PATH`/`JAVA_HOME`/etc. explicitly at the top**, rather than assuming
whatever an interactive login shell in that same container would have:

```bash
# Do this inside the script itself — don't rely on ~/.bashrc running.
export PATH="$HOME/.local/bin:$PATH"
JAVA_HOME=/root/toolchain/jdk-17.0.20.1+1 sh gradlew assembleDebug
```

This bites hardest when a tool installed inside the container (a CLI, a
toolchain binary) lives somewhere only added to `PATH` via `.bashrc` — any
non-interactive entry point (a RUN_COMMAND script, a cron job, an agent
invoking the container unattended) will fail to find it even though an
interactive `proot-distro login` into the exact same container works fine.

## Keeping the release keystore out of the repo

Load signing config from a path outside the repo, with an env-var override
and a sane default so the build still works for whoever has the keystore
without hardcoding a path:

```kotlin
val keystorePropertiesFile = file(
    providers.environmentVariable("ANON_BROWSER_KEYSTORE_PROPERTIES")
        .getOrElse("../../anon-browser-keys/keystore.properties")
)
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

signingConfigs {
    if (keystorePropertiesFile.exists()) {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
}

buildTypes {
    release {
        if (keystorePropertiesFile.exists()) {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

Guarding every reference behind `keystorePropertiesFile.exists()` means a
debug build (or anyone without the keystore) still builds cleanly instead of
failing on a missing file.

## 16KB native-library page alignment

Newer Android devices/SDK levels expect native libraries built 16KB-page
-aligned. A transitive dependency can silently ship a non-aligned `.so` that
gets flagged by the debug-build compatibility warning (or fails to load
outright on a 16KB-page device). Fix by pinning that specific dependency to
its first aligned version — comment *why*, since the pinned version will
otherwise look arbitrary next to whatever a BOM would have resolved:

```kotlin
// Pinned above what the Compose BOM pulls in transitively (1.0.1) — 1.1.0 is
// built 16KB-page-aligned, 1.0.1 isn't (flagged by Android's debug-build
// compatibility warning).
implementation("androidx.graphics:graphics-path:1.1.0")
```

A daemon binary shipped disguised as a native lib (see the
`android-tor-integration` skill) also needs
`packaging { jniLibs { useLegacyPackaging = true } }` so it isn't compressed
in a way that breaks executing it straight out of `nativeLibraryDir`.

## Installing the built APK on-device from this same environment

`pm install -r <path>` fails with `system_server has no access to read file
context u:object_r:fuse:s0` when the path is under the FUSE-backed
`/sdcard/...` layer — `system_server` can't read through that layer for
install purposes, regardless of your own shell's read access to the exact
same path. Fix: copy the APK to `/data/local/tmp/` first, then install from
there:

```bash
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/
pm install -r /data/local/tmp/app-debug.apk
```

**Always verify an install actually landed** before trusting anything
observed on-device afterward — this failure mode doesn't always show up
clearly in `pm install`'s own output:

```bash
dumpsys package <applicationId> | grep lastUpdateTime
date
```

Compare the two; `lastUpdateTime` should be within seconds of the current
time. Treating an install as successful without this check, across several
rebuild-and-reinstall cycles, can mean debugging behavior that "should"
reflect a recent code change while the device is silently still running a
much older build — re-check freshness after *every* install, not just the
first one in a session. See the `shizuku-termux-agents` skill for the same
gotcha when installing via `pm install` through Shizuku/`rish` specifically.
