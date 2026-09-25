#!/usr/bin/env bash

# Run a test command inside android-emulator-runner's script input. A failed
# command is captured while the runner still owns the live emulator, then the
# original command status is returned unchanged.
#
# Usage:
#   run-emulator-command-with-failure-capture.sh [serial] [output-directory] -- command [args...]

set -uo pipefail

SERIAL="${1:-emulator-5554}"
OUTPUT_DIR="${2:-build/failure-time-evidence}"
if [ "$#" -lt 4 ] || [ "${3:-}" != "--" ]; then
    printf 'usage: %s [serial] [output-directory] -- command [args...]\n' "$0" >&2
    exit 2
fi
shift 3

CAPTURE_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/capture-emulator-failure-evidence.sh"

printf 'run emulator command: '
printf '%q ' "$@"
printf '\n'

COMMAND_STATUS=0
"$@" || COMMAND_STATUS=$?

if [ "$COMMAND_STATUS" -ne 0 ]; then
    printf 'emulator command failed (status=%s); capturing live emulator evidence\n' "$COMMAND_STATUS" >&2
    if command -v timeout >/dev/null 2>&1; then
        timeout --kill-after=30 300 bash "$CAPTURE_SCRIPT" "$SERIAL" "$OUTPUT_DIR"
        CAPTURE_STATUS=$?
    else
        bash "$CAPTURE_SCRIPT" "$SERIAL" "$OUTPUT_DIR"
        CAPTURE_STATUS=$?
    fi
    if [ "$CAPTURE_STATUS" -ne 0 ]; then
        printf 'failure evidence capture ended with status=%s; preserving command status=%s\n' \
            "$CAPTURE_STATUS" "$COMMAND_STATUS" >&2
    fi
fi

exit "$COMMAND_STATUS"
