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
set -euo pipefail
exec proot-distro login debian -- bash /root/coding/anon-browser/tools/self_modify_inner.sh "$1"
