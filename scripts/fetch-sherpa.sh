#!/usr/bin/env bash
# Adds the sherpa-onnx library (offline neural voice) to this project.
# Run once before building, or let the GitHub workflow run it. Needs curl, git and tar with bzip2.
# Only 1.x versions are used: 2.x removed the built-in espeak phonemizer that Piper voices (Zani) need.
# It never calls the rate-limited GitHub API: versions come from `git ls-remote`, and a version is only used
# when its Android archive is really uploaded (a fresh tag can exist for a while before its files do).
set -u
cd "$(dirname "$0")/.."

fail() { echo "::error::$1"; exit 1; }

TAGS=$(git ls-remote --tags --refs https://github.com/k2-fsa/sherpa-onnx 2>/dev/null \
  | sed 's|.*refs/tags/||' | grep -E '^v1\.[0-9]+\.[0-9]+$' | sort -Vr | head -8)
[ -n "$TAGS" ] || fail "Could not list sherpa-onnx versions."

TMP=$(mktemp -d)
TAG=""
for t in $TAGS; do
  url="https://github.com/k2-fsa/sherpa-onnx/releases/download/$t/sherpa-onnx-$t-android.tar.bz2"
  echo "Trying sherpa-onnx $t"
  if curl -fsSL --retry 3 -o "$TMP/libs.tar.bz2" "$url" && tar tjf "$TMP/libs.tar.bz2" >/dev/null 2>&1; then TAG=$t; break; fi
done
[ -n "$TAG" ] || fail "No sherpa-onnx version had a usable Android archive."
echo "Using sherpa-onnx $TAG"

mkdir -p "$TMP/libs" && tar xjf "$TMP/libs.tar.bz2" -C "$TMP/libs"
JNI=app/src/main/jniLibs/arm64-v8a
mkdir -p "$JNI"
find "$TMP/libs" -path '*arm64-v8a*' -name '*.so' -exec cp {} "$JNI"/ \;
ls "$JNI"
ls "$JNI"/*.so >/dev/null 2>&1 || fail "The Android archive had no arm64 libraries."

# The Kotlin wrapper classes must come from the same release as the native libraries.
KT=app/src/main/java/com/k2fsa/sherpa/onnx
mkdir -p "$KT"
git clone -q --depth 1 --branch "$TAG" --filter=blob:none --sparse https://github.com/k2-fsa/sherpa-onnx "$TMP/src" \
  || fail "Could not fetch the sherpa-onnx Kotlin sources."
git -C "$TMP/src" sparse-checkout set sherpa-onnx/kotlin-api >/dev/null
cp "$TMP"/src/sherpa-onnx/kotlin-api/*.kt "$KT"/
ls "$KT"
[ -f "$KT/Tts.kt" ] || fail "Tts.kt is missing from the Kotlin sources."
rm -rf "$TMP"
echo "sherpa-onnx $TAG installed."
