#!/bin/bash
# 一键:重生成工程(必须,XcodeGen 显式列文件,新增文件不 regenerate 不进 target)→ 编译 → 装 → 启动 →(可选)截图。
# 用法: ./run.sh            构建+启动到 iPhone 17 模拟器
#        ./run.sh shot      额外截图到 /tmp/meili.png
#        SIM_UDID=xxx ./run.sh   指定模拟器
set -e
cd "$(dirname "$0")"
UDID="${SIM_UDID:-E1676741-D79B-4F59-A8BF-7A2FEA7F4961}"   # iPhone 17
BUNDLE="com.aibeautyfulwomen.gongpai.ios"
APP="build/Build/Products/Debug-iphonesimulator/MeiliCompanion.app"

echo "▸ xcodegen generate"
xcodegen generate >/dev/null

echo "▸ build"
xcodebuild -project MeiliCompanion.xcodeproj -scheme MeiliCompanion \
  -sdk iphonesimulator -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" \
  -derivedDataPath build \
  CODE_SIGNING_ALLOWED=YES CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual build 2>&1 \
  | grep -iE "error:|warning: .*Sendable|BUILD SUCCEEDED|BUILD FAILED" || true

echo "▸ boot + install + launch"
xcrun simctl bootstatus "$UDID" -b >/dev/null 2>&1 || true
open -a Simulator
xcrun simctl install "$UDID" "$APP"

# 调试自动登录(测试顾问账号,DEBUG-only hook 见 AppState.bootstrap)。LOGIN=0 ./run.sh 可跳过。
LOGIN_USER="${LOGIN_USER:-15882692524}"   # 测试顾问「何智莉」(有报告数据)
LOGIN_PASS="${LOGIN_PASS:-692524}"
ARGS=()
if [ "${LOGIN:-1}" = "1" ] && [ -n "$LOGIN_USER" ]; then
  ARGS=(-autoLoginUser "$LOGIN_USER" -autoLoginPass "$LOGIN_PASS")
fi
if [ -n "$TAB" ]; then ARGS+=(-startTab "$TAB"); fi
if [ -n "$REPORT" ]; then ARGS+=(-openReport "$REPORT"); fi
if [ -n "$CUST" ]; then ARGS+=(-openCustomer "$CUST"); fi
if [ -n "$PREVCUST" ]; then ARGS+=(-openPreviewCust "$PREVCUST" -openPreviewDate "${PREVDATE:-}"); fi
if [ -n "$BIND" ]; then ARGS+=(-openBind "$BIND"); fi
if [ -n "$REMIND" ]; then ARGS+=(-openReminders YES); fi
if [ -n "$SET" ]; then ARGS+=(-openSettings YES); fi
if [ -n "$SIMBIND" ]; then ARGS+=(-simBind "$SIMBIND"); fi
if [ -n "$SIMEXPIRE" ]; then ARGS+=(-simExpire YES); fi
xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
xcrun simctl launch "$UDID" "$BUNDLE" "${ARGS[@]}"

if [ "$1" = "shot" ]; then
  sleep 5
  xcrun simctl io "$UDID" screenshot /tmp/meili.png
  echo "▸ screenshot → /tmp/meili.png"
fi
