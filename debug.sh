#!/usr/bin/env bash

set -euo pipefail

case "$#:${1:-}" in
    0:|1:--clear) ;;
    *) echo "Usage: $0 [--clear]" >&2; exit 2 ;;
esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "Building debug APK..."
"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :app:assembleDebug

bash "$ROOT_DIR/scripts/install-apk.sh" "$APK_PATH" "com.mascit.openmarkdown" "$@"
