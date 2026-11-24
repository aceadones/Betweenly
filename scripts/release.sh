#!/usr/bin/env bash
# Builds the release APK, publishes it to the relay KV, and cuts a GitHub release.
# Optional argument: a notes file. Otherwise the notes are "Loviedowpie <versionName>".
# This script is for the Mac toolchain in the repo docs. It does not run itself.
set -euo pipefail

echo "step 1: locate the repo"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
echo "repo: $ROOT"

echo "step 2: set JAVA_HOME, ANDROID_HOME, and the NDK"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "ANDROID_NDK_HOME=$ANDROID_NDK_HOME"

echo "step 3: read versionName and versionCode"
GRADLE_FILE="$ROOT/android/app/build.gradle.kts"
VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName *= *"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | sed -n '1p')"
VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode *= *\([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | sed -n '1p')"
if [[ -z "${VERSION_NAME}" || -z "${VERSION_CODE}" ]]; then
  echo "could not read versionName or versionCode from $GRADLE_FILE" >&2
  exit 1
fi
echo "versionName=$VERSION_NAME"
echo "versionCode=$VERSION_CODE"

echo "step 4: release notes"
mkdir -p "$ROOT/dist"
NOTES_FILE="$ROOT/dist/release-notes.txt"
if [[ $# -ge 1 ]]; then
  if [[ ! -f "$1" ]]; then
    echo "notes file not found: $1" >&2
    exit 1
  fi
  cp "$1" "$NOTES_FILE"
  echo "notes from $1"
else
  printf '%s\n' "Loviedowpie ${VERSION_NAME}" > "$NOTES_FILE"
  echo "notes: Loviedowpie ${VERSION_NAME}"
fi

echo "step 5: assemble the release APK"
cd "$ROOT/android"
./gradlew :app:assembleRelease
cd "$ROOT"

echo "step 6: copy the APK into dist/"
SIGNED="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
UNSIGNED="$ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk"
if [[ -f "$SIGNED" ]]; then
  APK_SRC="$SIGNED"
elif [[ -f "$UNSIGNED" ]]; then
  APK_SRC="$UNSIGNED"
  echo "warning: the APK is unsigned. Set signing.keystore in android/local.properties and run again before shipping."
else
  echo "release APK was not produced" >&2
  exit 1
fi
DEST="$ROOT/dist/Loviedowpie-v${VERSION_NAME}.apk"
cp "$APK_SRC" "$DEST"
echo "copied $APK_SRC"
echo "to $DEST"

echo "step 7: sha256 and size"
SHA256="$(shasum -a 256 "$DEST" | awk '{print $1}')"
SIZE="$(stat -f%z "$DEST")"
echo "sha256=$SHA256"
echo "size=$SIZE"
if [[ ! "$SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "sha256 was not 64 hex characters" >&2
  exit 1
fi

echo "step 8: write dist/latest.json"
export ROOT VERSION_CODE VERSION_NAME SHA256 SIZE NOTES_FILE
python3 - <<'PY'
import json, os
root = os.environ["ROOT"]
with open(os.environ["NOTES_FILE"], encoding="utf-8") as handle:
    notes = handle.read()
doc = {
    "versionCode": int(os.environ["VERSION_CODE"]),
    "versionName": os.environ["VERSION_NAME"],
    "sha256": os.environ["SHA256"],
    "size": int(os.environ["SIZE"]),
    "notes": notes,
}
path = os.path.join(root, "dist", "latest.json")
with open(path, "w", encoding="utf-8") as handle:
    json.dump(doc, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
print(path)
PY

echo "step 9: upload latest.json and the APK to the relay KV"
cd "$ROOT/relay"
npx wrangler kv key put upd:json --binding STORE --remote --path "$ROOT/dist/latest.json"
npx wrangler kv key put upd:apk --binding STORE --remote --path "$DEST"
cd "$ROOT"
echo "uploaded upd:json and upd:apk"

echo "step 10: create the GitHub release"
gh release create "v${VERSION_NAME}" "$DEST" --title "v${VERSION_NAME}" --notes-file "$NOTES_FILE"
echo "done: v${VERSION_NAME}"
