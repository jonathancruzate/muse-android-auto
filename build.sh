#!/bin/bash
# Build Muse for Android Auto (Android Auto media app) from source.
# Toolchain: meta-muse-widget tools/sdk (build-tools 35.0.0, platform
# android-35), used READ-ONLY for compilation, + Temurin JDK 21.
# No emulator is touched by this build.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/workspace/meta-muse-widget/tools/sdk}"
export JAVA_HOME="$HOME/workspace/custom-skills/local-rea/tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
KS="keystore/musedrive.keystore"
KS_PASS_FILE="keystore/keystore-password.txt"
VERSION_CODE=12
VERSION_NAME="2.5.1"

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

echo "== androidx libs (v2.2.0: MediaBrowserServiceCompat) =="
# Pinned AndroidX artifacts, fetched from Google's Maven repo if absent.
# AARs are unzipped and their classes.jar used for javac + d8.
mkdir -p libs build/libsjar
fetch() { # $1 = maven path, $2 = local name
  if [ ! -f "libs/$2" ]; then
    curl -sL --max-time 60 -o "libs/$2" "https://dl.google.com/dl/android/maven2/$1"
  fi
}
fetch "androidx/media/media/1.7.0/media-1.7.0.aar" media-1.7.0.aar
fetch "androidx/core/core/1.13.1/core-1.13.1.aar" core-1.13.1.aar
fetch "androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.aar" versionedparcelable-1.1.1.aar
fetch "androidx/annotation/annotation/1.8.2/annotation-1.8.2.jar" annotation-1.8.2.jar
# v2.3.2 ROOT-CAUSE FIX: the versions below are pinned to the last
# pre-Kotlin-Multiplatform artifacts (and the exact versions media
# 1.7.0 / core 1.13.1 declare in their own POMs). The previously
# fetched collection-1.4.5.jar and lifecycle-common-2.8.4.jar are
# KMP *metadata* jars — they contain .knm files and ZERO .class
# files — so d8 silently dexed nothing from them, and the shipped
# APK referenced androidx.collection.ArrayMap without defining it.
# MediaBrowserServiceCompat.<init> creates an ArrayMap, so EVERY
# DriveMediaService instantiation died with NoClassDefFoundError
# before onCreate — the v2.2.0 "won't open" crash and the v2.3.x
# "crashing frequently" loop (proven by the on-device CrashLog
# trace captured 2026-11-01, Jonathan's screenshot 14:54 EST).
# A raw curl does NOT follow Gradle module-metadata redirects to
# the -android variants; never fetch a bare *.jar for an androidx
# artifact that has gone KMP. The fetch guard below fails the
# build if a runtime library ever arrives empty again.
fetch "androidx/collection/collection/1.2.0/collection-1.2.0.jar" collection-1.2.0.jar
fetch "androidx/lifecycle/lifecycle-common/2.6.2/lifecycle-common-2.6.2.jar" lifecycle-common-2.6.2.jar
fetch "androidx/lifecycle/lifecycle-runtime/2.6.2/lifecycle-runtime-2.6.2.aar" lifecycle-runtime-2.6.2.aar
fetch "androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0.aar" interpolator-1.0.0.aar
fetch "androidx/arch/core/core-common/2.2.0/core-common-2.2.0.jar" core-common-2.2.0.jar
fetch "androidx/arch/core/core-runtime/2.2.0/core-runtime-2.2.0.aar" core-runtime-2.2.0.aar
fetch "androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.jar" concurrent-futures-1.1.0.jar
# Fetch guard: every runtime library must contain real classes.
# (annotation-*.jar is compile-only and exempt: androidx
# annotations are CLASS-retention and never resolved at runtime.)
for jar in libs/collection-*.jar libs/lifecycle-common-*.jar libs/core-common-*.jar libs/concurrent-futures-*.jar; do
  n=$(unzip -l "$jar" | grep -c '\.class' || true)
  if [ "$n" -eq 0 ]; then echo "FATAL: $jar contains 0 .class files (KMP metadata jar?) — refusing to build"; exit 1; fi
done
for aar in media core versionedparcelable lifecycle-runtime interpolator core-runtime; do
  jar=$(ls libs/${aar}-*.aar | head -1)
  name=$(basename "$jar" .aar)
  if [ ! -f "build/libsjar/${name}-classes.jar" ]; then
    unzip -o -q "$jar" classes.jar -d "build/libsjar/${name}"
    mv "build/libsjar/${name}/classes.jar" "build/libsjar/${name}-classes.jar"
  fi
done
for jar in build/libsjar/*-classes.jar; do
  n=$(unzip -l "$jar" | grep -c '\.class' || true)
  if [ "$n" -eq 0 ]; then echo "FATAL: $jar contains 0 .class files — refusing to build"; exit 1; fi
done
CP_LIBS=$(ls build/libsjar/*-classes.jar libs/annotation-*.jar libs/collection-*.jar libs/lifecycle-common-*.jar libs/core-common-*.jar libs/concurrent-futures-*.jar | tr '\n' ':')
DEX_LIBS=$(ls build/libsjar/*-classes.jar libs/collection-*.jar libs/lifecycle-common-*.jar libs/core-common-*.jar libs/concurrent-futures-*.jar)

echo "== javac =="
find src build/gen -name "*.java" > build/sources.txt
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$CP_LIBS" -d build/classes @build/sources.txt

echo "== d8 =="
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name "*.class") $DEX_LIBS

echo "== dex completeness guard (v2.3.2) =="
# Fail the build if the dex REFERENCES any androidx class it does
# not DEFINE (androidx.annotation exempt — never resolved at
# runtime). This is the exact defect that shipped in v2.2.0–v2.3.1
# (ArrayMap referenced, never defined, NoClassDefFoundError in
# MediaBrowserServiceCompat.<init>); it must never ship again.
"$BT/dexdump" -f build/dex/classes.dex | grep "Class descriptor" | sed "s/.*: *//; s/'//g" | sort -u > build/defined.txt
# Exempt: androidx.annotation (never resolved at runtime) and
# library R classes (Landroidx/.../R$*;) — resource stubs for
# library-internal layouts this app never inflates (it uses no
# NotificationCompat / ComponentActivity code paths, and the raw
# build links no library resources, so those paths are unusable
# by construction, with or without the stub).
strings build/dex/classes.dex | grep -oE "Landroidx/[a-zA-Z0-9/\$_]+;" | grep -v "^Landroidx/annotation/" | grep -vE "/R\\\$[^;]*;|/R;" | sort -u > build/referenced.txt
missing=$(comm -23 build/referenced.txt build/defined.txt)
if [ -n "$missing" ]; then
  echo "FATAL: dex references androidx classes it does not define:"
  echo "$missing"
  exit 1
fi
echo "dex guard OK: all referenced androidx classes are defined"

echo "== package dex =="
(cd build/dex && zip -q ../unsigned.apk classes.dex)

echo "== zipalign + sign =="
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "Muse-Android-Auto-$VERSION_NAME.apk" build/aligned.apk
"$BT/apksigner" verify --print-certs "Muse-Android-Auto-$VERSION_NAME.apk" | head -3
ls -la "Muse-Android-Auto-$VERSION_NAME.apk"
