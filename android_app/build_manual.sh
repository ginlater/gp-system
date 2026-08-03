#!/usr/bin/env bash
set -euo pipefail

SDK_DIR="${ANDROID_HOME:-/usr/lib/android-sdk}"
ANDROID_JAR="$SDK_DIR/platforms/android-23/android.jar"
DX="$SDK_DIR/build-tools/debian/dx"

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT_DIR/app"
OUT_DIR="$ROOT_DIR/manual-build"
GEN_DIR="$OUT_DIR/gen"
CLASS_DIR="$OUT_DIR/classes"

rm -rf "$OUT_DIR"
mkdir -p "$GEN_DIR" "$CLASS_DIR"

aapt package -f -m \
  -J "$GEN_DIR" \
  -S "$APP_DIR/src/main/res" \
  -M "$APP_DIR/src/main/AndroidManifest.xml" \
  -I "$ANDROID_JAR"

javac -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$CLASS_DIR" \
  "$GEN_DIR/com/aibeautyfulwomen/gongpai/R.java" \
  "$APP_DIR/src/main/java/com/aibeautyfulwomen/gongpai/MainActivity.java" \
  "$APP_DIR/src/main/java/com/aibeautyfulwomen/gongpai/RecordingService.java"

"$DX" --dex --output="$OUT_DIR/classes.dex" "$CLASS_DIR"

aapt package -f \
  -S "$APP_DIR/src/main/res" \
  -M "$APP_DIR/src/main/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  -F "$OUT_DIR/app-unsigned.apk"

(cd "$OUT_DIR" && zip -q app-unsigned.apk classes.dex)

zipalign -f 4 "$OUT_DIR/app-unsigned.apk" "$OUT_DIR/app-aligned.apk"

if [[ ! -f "$ROOT_DIR/release.keystore" ]]; then
  if [[ ! -f "$ROOT_DIR/release.keystore.password" ]]; then
    openssl rand -base64 24 > "$ROOT_DIR/release.keystore.password"
  fi
  PASS="$(cat "$ROOT_DIR/release.keystore.password")"
  keytool -genkeypair \
    -keystore "$ROOT_DIR/release.keystore" \
    -storepass "$PASS" \
    -keypass "$PASS" \
    -alias gongpai \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Gongpai, OU=Gongpai, O=Aibeautyfulwomen, L=Chengdu, ST=Sichuan, C=CN"
fi

PASS="$(cat "$ROOT_DIR/release.keystore.password")"
apksigner sign \
  --ks "$ROOT_DIR/release.keystore" \
  --ks-key-alias gongpai \
  --ks-pass "pass:$PASS" \
  --key-pass "pass:$PASS" \
  --out "$ROOT_DIR/gongpai-release.apk" \
  "$OUT_DIR/app-aligned.apk"

apksigner verify --verbose "$ROOT_DIR/gongpai-release.apk"
ls -lh "$ROOT_DIR/gongpai-release.apk"
