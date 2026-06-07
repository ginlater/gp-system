#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# 一键发版：构建 release APK → 校验 → 发到生产，带强校验，防"版本号抬了但 APK 没发上去
# → 强制更新死循环"。APK 不在 git 里(.gitignore)，git pull 不会更新它，必须 scp。
#
# 用法:  tools/deploy_app.sh
# 前提:  本机有 Android SDK build-tools；能 ssh 到生产；build.gradle 与 webapp.py 版本号已改好。
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HOST="ubuntu@110.40.170.227"
REMOTE_DIR="/opt/gp-system"
REMOTE_APK="$REMOTE_DIR/app-release.apk"
# 固定签名证书：换 keystore 会签名不符、用户装不上(得先卸载)。发版必须是这张证书。
EXPECT_CERT="b2783752aad88e5e92507c3a3af9cd683b9ee01d559e35409b9f6eb385d9c77c"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android_app"
APK="$APP/app/build/outputs/apk/release/app-release.apk"
BT="$(ls -d "$HOME/Library/Android/sdk/build-tools/"* 2>/dev/null | sort -V | tail -1)"
AAPT="$BT/aapt2"; APKSIGNER="$BT/apksigner"

red(){ printf '\033[31m%s\033[0m\n' "$*"; }; grn(){ printf '\033[32m%s\033[0m\n' "$*"; }
die(){ red "✗ $*"; exit 1; }

echo "==> 1/6 校验版本号同步 (build.gradle ↔ webapp.py)"
GRADLE_VC=$(grep -oE 'versionCode[[:space:]]+[0-9]+' "$APP/app/build.gradle" | grep -oE '[0-9]+' | head -1)
WEBAPP_VC=$(grep -oE 'APP_LATEST_VERSION_CODE[[:space:]]*=[[:space:]]*[0-9]+' "$ROOT/webapp.py" | grep -oE '[0-9]+' | head -1)
echo "    build.gradle=$GRADLE_VC  webapp.APP_LATEST=$WEBAPP_VC"
[ -n "$GRADLE_VC" ] && [ "$GRADLE_VC" = "$WEBAPP_VC" ] || die "版本号不一致/缺失，先把两处改成同一个 versionCode 再发"

echo "==> 2/6 构建 release APK"
( cd "$APP" && ./gradlew :app:assembleRelease -q --console=plain )
[ -f "$APK" ] || die "构建产物不存在: $APK"

echo "==> 3/6 校验 APK versionCode + 签名证书"
APK_VC=$("$AAPT" dump badging "$APK" | grep -oE "versionCode='[0-9]+'" | grep -oE '[0-9]+' | head -1)
[ "$APK_VC" = "$GRADLE_VC" ] || die "APK versionCode=$APK_VC ≠ build.gradle $GRADLE_VC"
CERT=$("$APKSIGNER" verify --print-certs "$APK" | grep -i 'SHA-256 digest' | grep -oE '[0-9a-f]{64}' | head -1)
[ "$CERT" = "$EXPECT_CERT" ] || die "签名证书不符($CERT)：keystore 错了，装上去会签名冲突"
echo "    versionCode=$APK_VC  cert=ok"

echo "==> 4/6 备份旧包 + 发到生产"
ssh "$HOST" "cp -n '$REMOTE_APK' '$REMOTE_APK.bak.\$(date +%Y%m%d_%H%M%S)' 2>/dev/null || true"
scp "$APK" "$HOST:$REMOTE_APK"

echo "==> 5/6 校验生产 APK == 本地"
LOCAL_SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
PROD_SHA=$(ssh "$HOST" "sha256sum '$REMOTE_APK' | cut -d' ' -f1")
[ "$LOCAL_SHA" = "$PROD_SHA" ] || die "生产 sha 不一致 local=$LOCAL_SHA prod=$PROD_SHA"
echo "    sha256=$PROD_SHA"

echo "==> 6/6 校验后端版本常量是否已部署 (live /api/app/version)"
LIVE_VC=$(ssh "$HOST" "curl -s http://127.0.0.1:5058/api/app/version" | grep -oE '"latestVersionCode":[0-9]+' | grep -oE '[0-9]+' | head -1)
if [ "$LIVE_VC" = "$APK_VC" ]; then
  grn "✅ APK v$APK_VC 已发布并校验通过，后端版本常量也已生效。"
else
  red  "⚠ APK 已发(v$APK_VC)，但线上 /api/app/version 还是 v$LIVE_VC：后端常量没部署。"
  echo "   去执行: git push + 生产 git pull + sudo systemctl restart gongpai，否则强制更新会指向错版本。"
fi
