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

# --with-backend: 发完 APK 再把后端也上线(git push + 生产 git pull + restart gongpai)，
# 这样 APK 和后端版本常量真正一条命令同步上线，不留"版本号已抬、包/常量没跟上"的窗口。
WITH_BACKEND=0
for a in "$@"; do
  case "$a" in
    --with-backend) WITH_BACKEND=1 ;;
    -h|--help) echo "用法: tools/deploy_app.sh [--with-backend]"; echo "  --with-backend  发完 APK 再 git push + 生产 git pull + restart gongpai"; exit 0 ;;
    *) die "未知参数: $a (用 --help)" ;;
  esac
done

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

echo "==> 5 校验生产 APK == 本地"
LOCAL_SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
PROD_SHA=$(ssh "$HOST" "sha256sum '$REMOTE_APK' | cut -d' ' -f1")
[ "$LOCAL_SHA" = "$PROD_SHA" ] || die "生产 sha 不一致 local=$LOCAL_SHA prod=$PROD_SHA"
echo "    sha256=$PROD_SHA"

if [ "$WITH_BACKEND" = 1 ]; then
  echo "==> 5.5 后端上线 (git push + 生产 git pull + restart gongpai)"
  BR=$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)
  [ -z "$(git -C "$ROOT" status --porcelain -- webapp.py)" ] || die "webapp.py 有未提交改动，先 git commit 再 --with-backend(否则后端不会更新)"
  git -C "$ROOT" push origin "$BR"
  ssh "$HOST" "cd '$REMOTE_DIR' && git pull origin $BR && sudo systemctl restart gongpai && sleep 3 && systemctl is-active gongpai" \
    || die "后端部署失败，去服务器手查 (git pull / systemctl status gongpai)"
fi

echo "==> 6 校验后端版本常量是否已生效 (live /api/app/version)"
LIVE_VC=$(ssh "$HOST" "curl -s http://127.0.0.1:5058/api/app/version" | grep -oE '"latestVersionCode":[0-9]+' | grep -oE '[0-9]+' | head -1)
if [ "$LIVE_VC" = "$APK_VC" ]; then
  grn "✅ APK v$APK_VC + 后端 v$LIVE_VC 已发布并校验通过。"
else
  red  "⚠ APK 已发(v$APK_VC)，但线上 /api/app/version 还是 v$LIVE_VC：后端常量没部署。"
  if [ "$WITH_BACKEND" = 1 ]; then
    echo "   --with-backend 跑了仍不一致：可能 webapp.py 的 APP_LATEST_VERSION_CODE 没改到 $APK_VC。"
  else
    echo "   补救: 重跑加 --with-backend，或手动 git push + 生产 git pull + restart gongpai。"
  fi
fi
