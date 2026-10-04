#!/usr/bin/env bash

set -euo pipefail

case "$#:${1:-}" in
    0:|1:--clear) ;;
    *) echo "Usage: $0 [--clear]" >&2; exit 2 ;;
esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILT_APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/OpenMarkdown.apk"
PACKAGE_NAME="com.mascit.openmarkdown"

echo "Building signed release APK..."
"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :app:assembleRelease

if [[ ! -f "$BUILT_APK_PATH" ]]; then
    echo "error: expected APK was not produced at $BUILT_APK_PATH" >&2
    exit 1
fi

cp "$BUILT_APK_PATH" "$APK_PATH"
echo "Signed release APK: $APK_PATH"

bash "$ROOT_DIR/scripts/install-apk.sh" "$APK_PATH" "$PACKAGE_NAME" "$@"
