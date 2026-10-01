#!/bin/bash
# Runs inside the proot-distro Debian container. Takes the user's change
# request as $1, asks Claude Code to make the edit unattended, then rebuilds
# the app with the same Gradle invocation used throughout development.
#
# Not `set -e`: on a failure we still need to reach the final `exit` with the
# right code, not abort mid-script.
set -uo pipefail

# Claude Code CLI refuses --dangerously-skip-permissions when running as root
# (proot-distro's default login) unless this is set.
export IS_SANDBOX=1

# self_modify.sh invokes this script as `proot-distro login ... -- bash ...`.
# Passing a command after `--` makes proot-distro exec it directly instead of
# through a login shell, so ~/.bashrc (which normally puts ~/.local/bin, this
# container's own working `claude` install, ahead of everything else) never
# runs. Without this, bare `claude` resolves via proot-distro's default PATH
# to the Termux-side bind-mounted `claude` instead (Termux's Node reports
# process.platform "android", which no published @anthropic-ai/claude-code
# platform package matches, so that install is permanently stuck as an
# unfetched-native-binary stub — "claude native binary not installed").
export PATH="$HOME/.local/bin:$PATH"

cd /root/coding/anon-browser

echo "=== claude edit pass ==="
claude -p "$1" --dangerously-skip-permissions --output-format text
claude_exit=$?
echo "(claude exited $claude_exit)"

# Don't rebuild an untouched tree and call it Success if Claude itself failed
# (auth error, refused edit, etc.) — gradle would happily exit 0 on a no-op.
if [ "$claude_exit" -ne 0 ]; then
    exit "$claude_exit"
fi

echo "=== gradle build ==="
JAVA_HOME=/root/toolchain/jdk-17.0.20.1+1 sh gradlew assembleDebug
gradle_exit=$?
echo "(gradle exited $gradle_exit)"

exit "$gradle_exit"
