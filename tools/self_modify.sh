#!/data/data/com.termux/files/usr/bin/bash
# Termux-side entry point for Anon Browser's in-app "Self-Modify" feature.
#
# Invoked via Termux's RUN_COMMAND intent API (see SelfModify.kt) with
# RUN_COMMAND_PATH set to bash itself and this script's path as the first
# argument — NOT run via this file's own execute bit, which the bind-mounted
# /sdcard storage this repo lives on can't set (chmod on it is a silent
# no-op; same reason gradlew needs `sh gradlew` instead of `./gradlew`
# everywhere else in this project). $1 here is the user's free-form change
# request, delivered as a single Intent extra array element — never
# shell-parsed, so no quoting/injection handling is needed for that text.
#
# This script itself only hops into the proot-distro Debian container where
# the actual toolchain (JDK, Android SDK, Gradle caches, and the `claude`
# CLI) lives; the real work happens in self_modify_inner.sh, invoked the
# same explicit-interpreter way for the same reason.
#
# A bare `proot-distro login` does NOT automatically bind /sdcard/coding to
# /root/coding inside the container the way this project's own dev sandbox
# does (that bind is specific to how this sandbox itself was launched) — it
# has to be requested explicitly with -b, or the container can't see this
# repo at all ("No such file or directory" for a path that very much exists,
# just not inside a freshly-logged-in container's view).
set -euo pipefail
exec proot-distro login debian -b /storage/emulated/0/coding:/root/coding -- \
    bash /root/coding/anon-browser/tools/self_modify_inner.sh "$1"
