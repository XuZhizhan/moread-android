#!/usr/bin/env bash
# 墨读 Android 壳一键构建脚本
#
# 用法（在 mobile/ 目录下，或任意位置都行，脚本会自己 cd）：
#   bash scripts/build.sh setup          # 生成 android 平台 + 注入插件源码
#   bash scripts/build.sh debug          # 打 debug APK（真机内测用）
#   bash scripts/build.sh release        # 打 release APK（需先配 .signing.env）
#
# 依赖：Node 22+、JDK 17、Android Studio Otter（含 SDK 34、Gradle 由 wrapper 拉取）
# 首次 build 会联网拉 Gradle 与依赖，需几分钟。
set -euo pipefail

cd "$(dirname "$0")/.."   # 回到 mobile/

JAVA_SRC="android/app/src/main/java/com/moread/reader"
MANIFEST="android/app/src/main/AndroidManifest.xml"

step() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }

setup() {
  step "安装依赖"
  npm install

  step "生成 android 平台（Capacitor 8.5.1+）"
  if [ ! -d android ]; then npx cap add android; else echo "android/ 已存在，跳过 cap add"; fi

  step "注入插件源码"
  mkdir -p "$JAVA_SRC"
  cp android-patches/MoreadNativePlugin.java "$JAVA_SRC/MoreadNativePlugin.java"
  cp android-patches/ReminderReceiver.java "$JAVA_SRC/ReminderReceiver.java"
  cp android-patches/MainActivity.java "$JAVA_SRC/MainActivity.java"

  step "补 AndroidManifest（通知权限 + 提醒接收者）"
  node -e '
    const fs = require("fs");
    const p = process.argv[1];
    let s = fs.readFileSync(p, "utf8");
    if (!/POST_NOTIFICATIONS/.test(s)) {
      s = s.replace(/<application/, "<uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" />\n    <application");
    }
    if (!/ReminderReceiver/.test(s)) {
      s = s.replace(/<\/application>/, "        <receiver android:name=\".ReminderReceiver\" android:exported=\"false\" />\n    </application>");
    }
    fs.writeFileSync(p, s);
    console.log("manifest patched");
  ' "$MANIFEST"

  step "sync（把 dist 与插件写进 android 工程）"
  npx cap sync android
}

build_debug() {
  setup
  step "构建 debug APK"
  (cd android && ./gradlew assembleDebug)
  printf '\nAPK: android/app/build/outputs/apk/debug/app-debug.apk\n'
}

build_release() {
  setup
  if [ ! -f .signing.env ]; then
    echo "缺少 .signing.env（KEYSTORE/KEY_ALIAS/STORE_PASS/KEY_PASS），见 docs/android-shell-build.md §4" >&2
    exit 1
  fi
  set -a; . ./.signing.env; set +a
  step "构建 release APK（签名）"
  (cd android && ./gradlew assembleRelease)
  printf '\nAPK: android/app/build/outputs/apk/release/app-release.apk\n'
}

case "${1:-setup}" in
  setup) setup ;;
  debug) build_debug ;;
  release) build_release ;;
  *) echo "未知命令：$1（可选 setup|debug|release）" >&2; exit 1 ;;
esac
