#!/bin/bash
# Build Muse Drive (Android Auto media app) from source.
# Toolchain: metamuse-widget tools/sdk (build-tools 35.0.0, platform
# android-35), used READ-ONLY for compilation, + Temurin JDK 21.
# No emulator is touched by this build.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/workspace/metamuse-widget/tools/sdk}"
export JAVA_HOME="$HOME/workspace/custom-skills/local-rea/tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
KS="keystore/musedrive.keystore"
KS_PASS_FILE="keystore/keystore-password.txt"
VERSION_CODE=2
VERSION_NAME="2.0.0"

rm -rf build
mkdir -p build/gen build/classes build/dex keystore res/raw

if [ ! -f "$KS" ]; then
  PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
  echo "$PASS" > "$KS_PASS_FILE"
  chmod 600 "$KS_PASS_FILE"
  keytool -genkeypair -keystore "$KS" -alias musedrive -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Muse Drive, O=JonathanCruzate, C=US" 2>/dev/null
  echo "keystore created"
fi
PASS="$(cat "$KS_PASS_FILE")"

echo "== silent loop (5s, 8kHz mono PCM) =="
python3 - << 'PY'
import struct
sr = 8000
frames = b"\x00\x00" * sr * 5
with open("res/raw/silence.wav", "wb") as f:
    f.write(b"RIFF" + struct.pack("<I", 36 + len(frames)) + b"WAVE")
    f.write(b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, sr, sr * 2, 2, 16))
    f.write(b"data" + struct.pack("<I", len(frames)) + frames)
print("silence.wav written", 44 + len(frames), "bytes")
PY

echo "== aapt2 compile =="
find res -type f | sort > build/res-files.txt
"$BT/aapt2" compile --dir res -o build/res.zip

echo "== aapt2 link =="
"$BT/aapt2" link -o build/unsigned.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 29 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  build/res.zip

echo "== javac =="
find src build/gen -name "*.java" > build/sources.txt
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d build/classes @build/sources.txt

echo "== d8 =="
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name "*.class")

echo "== package dex =="
(cd build/dex && zip -q ../unsigned.apk classes.dex)

echo "== zipalign + sign =="
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "Muse-Drive-$VERSION_NAME.apk" build/aligned.apk
"$BT/apksigner" verify --print-certs "Muse-Drive-$VERSION_NAME.apk" | head -3
ls -la "Muse-Drive-$VERSION_NAME.apk"
