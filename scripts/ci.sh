#!/usr/bin/env bash
# CI 入口：在 GitHub Actions 的 ubuntu-latest 上把 debug APK 打出来。
#
# 为什么逻辑住在这里、而不是写在 workflow 里：
# 这个仓库的 `.github/workflows/android.yml` 只能由人在网页上手工新建
# （`.github` 是隐藏目录，拖拽上传带不上去），所以那份 YAML 必须短到
# 「贴一次不会贴错」。真正的步骤、环境准备、产物发布，全部在这个文件里。
#
# 本地也能跑（JDK/SDK 齐的机器上）：bash mobile/scripts/ci.sh
set -euo pipefail

cd "$(dirname "$0")/../.."   # 回到仓库根

echo "== JDK 21 =="
JDK="$(ls -d /usr/lib/jvm/temurin-21-jdk* 2>/dev/null | head -1 || true)"
if [ -z "$JDK" ]; then JDK="$(ls -d /usr/lib/jvm/java-21-openjdk* 2>/dev/null | head -1 || true)"; fi
if [ -n "$JDK" ]; then export JAVA_HOME="$JDK"; export PATH="$JAVA_HOME/bin:$PATH"; fi
java -version

echo "== Android SDK =="
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKM="$(command -v sdkmanager || true)"
if [ -z "$SDKM" ]; then SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"; fi
yes | "$SDKM" --licenses > /dev/null 2>&1 || true
"$SDKM" --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" > /dev/null
echo "sdk 就绪"

# 失败时把日志推回 ci-log 分支 —— 这边没有 Actions 日志接口，只能靠这一手看报错
on_fail() {
  echo "== 构建失败，回传日志到 ci-log 分支 =="
  git config user.name "moread-ci"
  git config user.email "ci@users.noreply.github.com"
  git checkout -B ci-log
  git add -f build.log || true
  git commit -m "ci: build log #${GITHUB_RUN_NUMBER:-0}" || true
  git push -f origin ci-log || true
}
trap on_fail ERR

echo "== 生成 android 工程并注入插件 =="
bash mobile/scripts/build.sh setup 2>&1 | tee build.log

# cap add 拷出来的 gradlew 不一定是可执行的，补一下再跑
chmod +x mobile/android/gradlew

echo "== 打 debug APK =="
(cd mobile/android && ./gradlew assembleDebug --no-daemon --stacktrace) 2>&1 | tee -a build.log

APK="mobile/android/app/build/outputs/apk/debug/app-debug.apk"
cp "$APK" moread-debug.apk
ls -lh moread-debug.apk
SIZE="$(du -h moread-debug.apk | cut -f1)"

{
  echo "### 墨读 Android 壳 build ${GITHUB_RUN_NUMBER:-local}"
  echo ""
  echo "- APK 大小：$SIZE"
  echo "- commit：\`${GITHUB_SHA:-local}\`"
  echo ""
  echo "下载后直接安装（debug 签名，需允许「安装未知来源应用」）。"
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

# 发到 Releases：每次构建一个 tag，手机端从 Releases 页直接下载
TAG="build-${GITHUB_RUN_NUMBER:-local}"
gh release create "$TAG" moread-debug.apk \
  --title "墨读 Android 壳 build ${GITHUB_RUN_NUMBER:-local}" \
  --notes "自动构建的 debug 内测包，下载后直接安装。

- commit: \`${GITHUB_SHA:-local}\`
- 壳只加载线上 Web：以后改界面 / 改阅读器都不用重新装包" \
  || gh release upload "$TAG" moread-debug.apk --clobber

echo "== 完成：$TAG =="
