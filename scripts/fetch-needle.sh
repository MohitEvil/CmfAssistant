#!/usr/bin/env bash
# Adds Needle 3 (Cactus, on-device tool calling) to this project: the Android engine library and the weights.
# Optional. If anything fails the app still builds, it just won't have Needle (it then uses your AI provider for planning).
set -u
cd "$(dirname "$0")/.."

OUT=$(mktemp -d)
python3 -m pip install --quiet cactus-compute 2>&1 | tail -2
python3 - "$OUT" <<'PY' || { echo "Needle download failed; building without it."; exit 0; }
import sys, shutil, os
from cactus.agent import fetch
out = sys.argv[1]
files = fetch.download_platform("android-arm64", out)
lib = [f for f in files if f.endswith("libcactus.a")]
if not lib:
    raise SystemExit("no libcactus.a in the android-arm64 download: " + ", ".join(files))
os.makedirs("app/src/main/cpp/libs", exist_ok=True)
shutil.copy(lib[0], "app/src/main/cpp/libs/libcactus.a")
weights = fetch.fetch_weights()
os.makedirs("app/src/main/assets", exist_ok=True)
shutil.copy(weights, "app/src/main/assets/needle3.cact")
print("Needle 3 ready:", os.path.getsize("app/src/main/assets/needle3.cact") // 1024, "KB weights")
PY
rm -rf "$OUT"
