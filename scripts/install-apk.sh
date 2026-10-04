#!/usr/bin/env bash

set -euo pipefail

case "$#:${3:-}" in
    2:|3:--clear) ;;
    *) echo "Usage: $0 APK_PATH PACKAGE_NAME [--clear]" >&2; exit 2 ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$1"
PACKAGE_NAME="$2"
CLEAR_DATA=false
if [[ "${3:-}" == "--clear" ]]; then CLEAR_DATA=true; fi
case "$PACKAGE_NAME" in
    com.mascit.openmarkdown) ;;
    *) echo "error: unsupported OpenMarkdown package: $PACKAGE_NAME" >&2; exit 2 ;;
esac

if [[ ! -f "$APK_PATH" ]]; then
    echo "error: APK not found: $APK_PATH" >&2
    exit 1
fi
if ! ADB_BIN="$(bash "$SCRIPT_DIR/adb-device.sh" --find-adb)"; then
    echo "APK built; adb unavailable, skipping installation." >&2
    exit 0
fi
if ! DEVICE_SERIALS="$(ADB="$ADB_BIN" bash "$SCRIPT_DIR/adb-device.sh")"; then
    echo "APK built, but device discovery/authorization failed. Nothing was installed." >&2
    exit 1
fi

echo "Installation targets:"
printf '%s\n' "$DEVICE_SERIALS"
if [[ "$CLEAR_DATA" == true ]]; then
    echo "WARNING: --clear will erase $PACKAGE_NAME data for the current Android user on EVERY target above." >&2
    echo "Recent file history, cached documents, and settings will be lost. Keep external copies of your documents." >&2
fi

clear_app_data() {
    local serial="$1" user_id packages clear_output
    user_id="$("$ADB_BIN" -s "$serial" shell am get-current-user)" || return 1
    user_id="$(printf '%s' "$user_id" | tr -d '\r')"
    case "$user_id" in
        ""|*[!0-9]*) echo "error: could not identify the current Android user on $serial" >&2; return 1 ;;
    esac
    packages="$("$ADB_BIN" -s "$serial" shell pm list packages --user "$user_id" "$PACKAGE_NAME" 2>&1)" || {
        printf '%s\n' "$packages" >&2
        return 1
    }
    packages="$(printf '%s' "$packages" | tr -d '\r')"
    if [[ -n "$packages" ]] && printf '%s\n' "$packages" | grep -qv '^package:'; then
        printf 'error: could not inspect installed packages on %s: %s\n' "$serial" "$packages" >&2
        return 1
    fi
    # pm's filter is a substring search; require an exact package before clearing.
    if ! printf '%s\n' "$packages" | grep -Fxq "package:$PACKAGE_NAME"; then
        echo "$PACKAGE_NAME is not installed for Android user $user_id on $serial; no data to clear."
        return 0
    fi
    echo "Clearing $PACKAGE_NAME data on $serial (Android user $user_id)..."
    if ! clear_output="$("$ADB_BIN" -s "$serial" shell pm clear --user "$user_id" "$PACKAGE_NAME" 2>&1)"; then
        printf '%s\n' "$clear_output" >&2
        return 1
    fi
    if [[ "$(printf '%s' "$clear_output" | tr -d '\r')" != "Success" ]]; then
        printf 'error: data reset was not confirmed on %s: %s\n' "$serial" "$clear_output" >&2
        return 1
    fi
    echo "Data cleared for $PACKAGE_NAME on $serial."
}

install_and_launch() {
    local serial="$1" resolved component start_output
    if [[ "$CLEAR_DATA" == true ]] && ! clear_app_data "$serial"; then
        echo "error: data reset failed on $serial; skipping installation and launch" >&2
        return 1
    fi
    echo "Installing $PACKAGE_NAME on $serial..."
    if ! "$ADB_BIN" -s "$serial" install -r "$APK_PATH"; then
        echo "error: installation failed on $serial; no uninstall was attempted" >&2
        if [[ "$CLEAR_DATA" == true ]]; then echo "Any completed data reset cannot be undone; restore your backup manually." >&2; fi
        return 1
    fi
    resolved="$("$ADB_BIN" -s "$serial" shell cmd package resolve-activity --brief "$PACKAGE_NAME")" || return 1
    component="$(printf '%s\n' "$resolved" | tr -d '\r' | awk '/\// { component = $0 } END { print component }')"
    if [[ -z "$component" ]]; then
        echo "error: no launcher activity found for $PACKAGE_NAME on $serial" >&2
        return 1
    fi
    echo "Launching $PACKAGE_NAME on $serial..."
    "$ADB_BIN" -s "$serial" shell am force-stop "$PACKAGE_NAME" || return 1
    if ! start_output="$("$ADB_BIN" -s "$serial" shell am start -W -n "$component" 2>&1)"; then
        printf '%s\n' "$start_output" >&2
        return 1
    fi
    if [[ "$start_output" == *"Error:"* || "$start_output" != *"Status: ok"* ]]; then
        printf '%s\n' "$start_output" >&2
        return 1
    fi
    echo "$PACKAGE_NAME is running on $serial."
    echo "App logs: $ADB_BIN -s $serial logcat -s OpenMarkdown:D '*:S'"
}

SUCCEEDED=0
FAILED=0
while IFS= read -r DEVICE_SERIAL; do
    # adb shell must not consume the target list from this loop's stdin.
    if install_and_launch "$DEVICE_SERIAL" </dev/null; then
        SUCCEEDED=$((SUCCEEDED + 1))
    else
        FAILED=$((FAILED + 1))
        echo "error: deployment failed on $DEVICE_SERIAL; continuing with remaining devices" >&2
    fi
done <<< "$DEVICE_SERIALS"

echo "Deployment finished: $SUCCEEDED device(s) installed and launched; $FAILED failed."
[[ "$FAILED" == 0 ]]
