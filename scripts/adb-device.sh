#!/usr/bin/env bash

set -euo pipefail

# Prints one authorized transport per physical device; diagnostics go to stderr.
# Emulators are used only when no authorized physical devices are available.
find_adb() {
    if [[ -n "${ADB:-}" && -x "$ADB" ]]; then
        printf '%s\n' "$ADB"
    elif command -v adb >/dev/null 2>&1; then
        command -v adb
    elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
        printf '%s\n' "$ANDROID_SDK_ROOT/platform-tools/adb"
    elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
        printf '%s\n' "$ANDROID_HOME/platform-tools/adb"
    elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
        printf '%s\n' "$HOME/Library/Android/sdk/platform-tools/adb"
    else
        echo "error: adb was not found; install Android platform-tools or set ADB" >&2
        exit 1
    fi
}

ADB_BIN="$(find_adb)"
if [[ "${1:-}" == "--find-adb" ]]; then
    printf '%s\n' "$ADB_BIN"
    exit 0
fi

connect_wireless() {
    local address="$1" attempt
    echo "Connecting to wireless device $address..." >&2
    # adb connect can exit successfully even when the connection fails.
    "$ADB_BIN" connect "$address" >&2 || true
    for attempt in 1 2 3; do
        if [[ "$("$ADB_BIN" -s "$address" get-state 2>/dev/null || true)" == "device" ]]; then
            return 0
        fi
        if [[ "$attempt" != 3 ]]; then sleep 1; fi
    done
    return 1
}

discover_wireless() {
    local service="$1" host="${2:-}" services addresses attempt
    # A freshly started adb server may need a moment to discover mDNS services.
    for attempt in 1 2 3; do
        if ! services="$("$ADB_BIN" mdns services)"; then
            echo "warning: wireless discovery failed; connected transports will still be used" >&2
            return 1
        fi
        # adb mDNS instance names can contain spaces (e.g. "phone (2)"); columns are tab-separated.
        addresses="$(printf '%s\n' "$services" | awk -F '\t' -v service="$service" -v host="$host" '
            ($2 == service || $2 == service ".") {
                addressHost = $3
                sub(/:[^:]*$/, "", addressHost)
                if (host == "" || addressHost == host) print $3
            }' | sort -u)"
        if [[ -n "$addresses" ]]; then
            printf '%s\n' "$addresses"
            return 0
        fi
        if [[ "$attempt" != 3 ]]; then sleep 1; fi
    done
    return 1
}

connect_discovered() {
    local addresses="$1" address services connected instance
    services="$("$ADB_BIN" mdns services 2>/dev/null || true)"
    connected="$("$ADB_BIN" devices | awk -F '\t' 'NR > 1 && $2 == "device" {
        name = $1
        sub(/\._adb-tls-connect\._tcp$/, "", name)
        sub(/ \([0-9]+\)$/, "", name)
        print name
    }')"
    while IFS= read -r address; do
        [[ -n "$address" ]] || continue
        instance="$(printf '%s\n' "$services" | awk -F '\t' -v address="$address" '
            ($2 == "_adb-tls-connect._tcp" || $2 == "_adb-tls-connect._tcp.") && $3 == address {
                name = $1
                sub(/ \([0-9]+\)$/, "", name)
                print name
                exit
            }')"
        # mDNS can keep advertising an old port after the same phone connects on a new one.
        if [[ -n "$instance" ]] && printf '%s\n' "$connected" | grep -Fxq -- "$instance"; then
            continue
        fi
        if ! connect_wireless "$address" </dev/null; then
            echo "warning: $address is unavailable; skipping this endpoint" >&2
        fi
    done <<< "$addresses"
}

pair_wireless() {
    local pairing_addresses pairing_address addresses reply
    if [[ ! -t 0 ]]; then
        echo "error: no authorized device; connect a phone or run interactively to pair" >&2
        return 1
    fi
    echo "On one phone, open Developer options > Wireless debugging > Pair device with pairing code." >&2
    read -r -p "Press Enter when the pairing code is visible... " reply
    pairing_addresses="$(discover_wireless _adb-tls-pairing._tcp)" || {
        echo "error: no pairing service found; keep the pairing screen open on the same Wi-Fi network" >&2
        return 1
    }
    if [[ "$pairing_addresses" == *$'\n'* ]]; then
        echo "error: multiple pairing screens are open; leave only one open and rerun" >&2
        return 1
    fi
    pairing_address="$pairing_addresses"
    echo "Pairing with $pairing_address. Enter the code shown on your phone when prompted." >&2
    # Let adb read the code directly; never store it or pass it as a command-line argument.
    "$ADB_BIN" pair "$pairing_address" >&2 || return 1
    addresses="$(discover_wireless _adb-tls-connect._tcp "${pairing_address%:*}")" || {
        echo "error: no connection service found after pairing; leave Wireless debugging enabled" >&2
        return 1
    }
    connect_discovered "$addresses"
}

unique_physical_devices() {
    local serial identity
    while IFS= read -r serial; do
        [[ -n "$serial" ]] || continue
        identity="$("$ADB_BIN" -s "$serial" shell getprop ro.serialno </dev/null 2>/dev/null | tr -d '\r' || true)"
        # Unknown identities must remain separate; never guess that two phones are the same.
        case "$identity" in
            ""|unknown|UNKNOWN|0) identity="transport:$serial" ;;
            *) identity="device:$identity" ;;
        esac
        printf '%s\t%s\n' "$identity" "$serial"
    done | awk -F '\t' '!seen[$1]++ { print $2 }'
}

authorized_targets() {
    local devices physical emulators
    devices="$("$ADB_BIN" devices)" || return 1
    # adb devices also uses tabs; splitting on whitespace truncates mDNS serials containing spaces.
    printf '%s\n' "$devices" | awk -F '\t' 'NR > 1 && NF >= 2 && $2 != "device" {
        print "warning: skipping " $1 " (" $2 ")"
    }' >&2
    physical="$(printf '%s\n' "$devices" | awk -F '\t' 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1 }')"
    if [[ -n "$physical" ]]; then
        printf '%s\n' "$physical" | unique_physical_devices
    else
        emulators="$(printf '%s\n' "$devices" | awk -F '\t' 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1 }')"
        if [[ -n "$emulators" ]]; then printf '%s\n' "$emulators"; fi
    fi
}

# Discover all wireless phones even when another phone is already connected.
"$ADB_BIN" start-server >&2
if ADDRESSES="$(discover_wireless _adb-tls-connect._tcp)"; then
    connect_discovered "$ADDRESSES"
fi
TARGETS="$(authorized_targets)"
if [[ -z "$TARGETS" ]]; then
    pair_wireless
    TARGETS="$(authorized_targets)"
fi
if [[ -z "$TARGETS" ]]; then
    echo "error: no authorized devices are available" >&2
    exit 1
fi
printf '%s\n' "$TARGETS"
