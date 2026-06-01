#!/usr/bin/env bash
#
# build_manual.sh —— 工牌接诊 App 命令行构建脚本
# ============================================================
# 这是“文档化”的命令行构建路径，核心其实只有一行：./gradlew assembleDebug
#
# 【更省事的做法】直接用 Android Studio 打开本目录(android_app)，
#   点 Build → Build APK(s) 即可，Studio 会自动处理 SDK / JDK / local.properties，
#   完全不用跑这个脚本。详见 README.md。
#
# 命令行构建需要两个前提（Android Studio 会自动帮你搞定）：
#   1) JAVA_HOME 指向 JDK 17（AGP 8.x 要求 JDK 17）
#   2) 让 Gradle 知道 Android SDK 在哪，二选一：
#        - 环境变量 ANDROID_HOME=/path/to/Android/sdk，或
#        - 本目录下的 local.properties 里写 sdk.dir=/path/to/Android/sdk
#
# 别忘了构建前先确认 START_URL（见 README.md「第一件事」）。
#
# 本脚本只做检查 + 友好提示，缺工具时给指引而不是直接崩溃。
# 用法：
#   ./build_manual.sh            # 默认构建 debug APK
#   ./build_manual.sh release    # 构建 release（注意：未配 signingConfig 时产出未签名包）
# ============================================================

# 注意：故意不开 set -e，以便我们能自己打印友好提示后再决定退出码。
set -u

# 切到脚本所在目录（即 android_app/），保证相对路径正确
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || { echo "无法进入脚本目录 $SCRIPT_DIR"; exit 1; }

VARIANT="${1:-debug}"
case "$VARIANT" in
  debug)   GRADLE_TASK="assembleDebug" ;;
  release) GRADLE_TASK="assembleRelease" ;;
  *)
    echo "未知参数：$VARIANT"
    echo "用法：$0 [debug|release]"
    exit 1
    ;;
esac

echo "==> 工牌接诊 App 构建脚本（目标：$VARIANT）"
echo "==> 工作目录：$SCRIPT_DIR"
echo

MISSING=0

# ---- 1) 检查 JDK ----
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
  echo "提示：未设置 JAVA_HOME，将使用 PATH 中的 java（$JAVA_BIN）。"
  echo "      建议显式设置：export JAVA_HOME=/path/to/jdk-17"
else
  JAVA_BIN=""
  echo "✗ 没找到 Java（JDK）。"
  echo "    AGP 8.13 需要 JDK 17。请安装 JDK 17 并设置 JAVA_HOME，例如："
  echo "      export JAVA_HOME=/path/to/jdk-17"
  MISSING=1
fi

if [ -n "$JAVA_BIN" ]; then
  JAVA_VER_LINE="$("$JAVA_BIN" -version 2>&1 | head -n 1)"
  echo "✓ Java：$JAVA_VER_LINE"
  case "$JAVA_VER_LINE" in
    *\"17.*|*\"1.17*) : ;;  # 期望 JDK 17
    *)
      echo "  ⚠ 看起来不是 JDK 17。AGP 8.13 要求 JDK 17，其它版本可能构建失败。"
      ;;
  esac
fi

# ---- 2) 检查 Android SDK 位置 ----
SDK_OK=0
if [ -n "${ANDROID_HOME:-}" ]; then
  echo "✓ ANDROID_HOME=$ANDROID_HOME"
  SDK_OK=1
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  echo "✓ ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
  SDK_OK=1
elif [ -f "local.properties" ] && grep -q '^sdk.dir=' local.properties; then
  echo "✓ local.properties 已配置 sdk.dir：$(grep '^sdk.dir=' local.properties)"
  SDK_OK=1
fi

if [ "$SDK_OK" -ne 1 ]; then
  echo "✗ 没找到 Android SDK 位置。"
  echo "    二选一："
  echo "      export ANDROID_HOME=/path/to/Android/sdk"
  echo "    或在本目录($SCRIPT_DIR)新建 local.properties，写："
  echo "      sdk.dir=/path/to/Android/sdk"
  echo "    （用 Android Studio 打开本工程时会自动生成 local.properties。）"
  MISSING=1
fi

# ---- 3) 检查 gradlew ----
if [ ! -f "./gradlew" ]; then
  echo "✗ 当前目录没有 gradlew，确认你在 android_app/ 目录里。"
  MISSING=1
else
  chmod +x ./gradlew 2>/dev/null || true
fi

# ---- 提醒 START_URL ----
echo
echo "提醒：构建前请确认启动地址 START_URL 是否正确（见 README.md「第一件事」）："
echo "      app/src/main/java/com/airec/bledemo/ConsultantActivity.java"
echo

# ---- 缺工具则不构建，仅给指引 ----
if [ "$MISSING" -ne 0 ]; then
  echo "============================================================"
  echo "环境尚未就绪，未执行构建。请按上面 ✗ 的提示补齐后重试。"
  echo "（最省事的办法：直接用 Android Studio 打开 android_app 目录构建。）"
  echo "============================================================"
  exit 1
fi

# ---- 执行构建 ----
echo "==> 执行：./gradlew $GRADLE_TASK"
echo
./gradlew "$GRADLE_TASK"
STATUS=$?

echo
if [ "$STATUS" -eq 0 ]; then
  if [ "$VARIANT" = "debug" ]; then
    echo "✓ 构建成功。APK 位置："
    echo "    app/build/outputs/apk/debug/app-debug.apk"
    echo "  安装到手机（先开 USB 调试）："
    echo "    adb install -r app/build/outputs/apk/debug/app-debug.apk"
  else
    echo "✓ 构建成功。release APK 位置："
    echo "    app/build/outputs/apk/release/"
    echo "  ⚠ 若 app/build.gradle 未配置 signingConfig，产出为未签名包"
    echo "    （app-release-unsigned.apk），不能直接装机。签名方法见 README.md。"
  fi
else
  echo "✗ 构建失败（退出码 $STATUS）。常见原因：JDK 不是 17、SDK 路径不对、首次构建需联网下依赖。"
  echo "  排查不顺时，建议改用 Android Studio 打开 android_app 目录构建，报错更直观。"
fi

exit "$STATUS"
