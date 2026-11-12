#!/usr/bin/env bash
# Adds the sherpa-onnx library (offline neural voice) to this project.
# Run once before building, or let the GitHub workflow run it. Needs curl, jq and tar with bzip2.
# If this step fails the app still builds; it just uses the phone's own voice.
set -u
cd "$(dirname "$0")/.."

# "latest" can be a models-only release, so pick the newest real version tag (vX.Y.Z).
TAG=$(curl -fsSL "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases?per_page=50" \
  | jq -r '[.[] | select(.tag_name | test("^v[0-9]+\\.[0-9]+\\.[0-9]+$"))][0].tag_name')
if [ -z "$TAG" ] || [ "$TAG" = "null" ]; then echo "Could not find the latest sherpa-onnx release."; exit 0; fi
echo "Using sherpa-onnx $TAG"

TMP=$(mktemp -d)
curl -fsSL -o "$TMP/libs.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/$TAG/sherpa-onnx-$TAG-android.tar.bz2" \
  || { echo "Could not download the native libraries."; exit 0; }
mkdir -p "$TMP/libs" && tar xjf "$TMP/libs.tar.bz2" -C "$TMP/libs"

JNI=app/src/main/jniLibs/arm64-v8a
mkdir -p "$JNI"
find "$TMP/libs" -path '*arm64-v8a*' -name '*.so' -exec cp {} "$JNI"/ \;
ls "$JNI"

# The Kotlin wrapper classes must come from the same release as the native libraries.
KT=app/src/main/java/com/k2fsa/sherpa/onnx
mkdir -p "$KT"
curl -fsSL "https://api.github.com/repos/k2-fsa/sherpa-onnx/contents/sherpa-onnx/kotlin-api?ref=$TAG" \
  | jq -r '.[] | select(.name | endswith(".kt")) | .download_url' \
  | while read -r url; do
      curl -fsSL -o "$KT/$(basename "$url")" "$url"
    done
ls "$KT"
rm -rf "$TMP"
