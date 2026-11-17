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

echo "== 抓取线上前端（打包进 APK）=="
# 壳不再用 server.url 指线上，改成加载 APK 内的 dist。这份 dist 必须**就是线上那份**：
# 抓不全脚本会非零退出，这一步失败比出一个「某个页面 404」的包强得多。
#
# 顺手把「包内是哪一版前端」记下来，写进 release 说明。
# 为什么值这一行：包里是旧前端时，build 号、版本号、界面全都正常，
# 只有刚修的那处不生效 —— 在 release 页面上一眼对得上，比翻 Actions 日志快。
# 注意必须在这里取：下一行 build.sh 会用 `tee build.log`（不带 -a）把日志覆盖掉。
node mobile/scripts/fetch-web.mjs dist 2>&1 | tee -a build.log
WEB_APP="$(grep -o 'app 指纹: app\.[0-9a-f]*\.js' build.log | tail -1 | sed 's/.*: //' || true)"
WEB_VER="$(grep -o "版本: [^ ]*" build.log | tail -1 | sed 's/版本: //' || true)"
# cloud 单独记一笔：跨域、CORS 头、云存储这类问题全长在这个文件里。
# 包内是旧 cloud.js 时，症状和「网络故障」一模一样 —— 先看版本能省掉一整轮排查。
WEB_CLOUD="$(grep -oE 'cloud\.[0-9a-f]{8}\.js' dist/index.html | head -1 || true)"
echo "包内前端：${WEB_APP:-未取到} / cloud ${WEB_CLOUD:-未取到} / 版本 ${WEB_VER:-未取到}"

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

# tag 用「上一个 release 的编号 +1」，**不用** GITHUB_RUN_NUMBER：
# run number 是这个 workflow 的累计跑数，失败一次就永久跳号（该出 build-35 的那次
# 会变成 build-36），而人是在按编号比对版本的 —— 跳号看起来像漏发了一版。
# 从 Releases 推导就永远连续，重跑也不会重号（同名 tag 走 upload --clobber）。
LAST_TAG="$(gh release list --limit 1 --exclude-drafts 2>/dev/null | awk '{print $1}' | head -1)"
LAST_NO="${LAST_TAG#build-}"
case "$LAST_NO" in ''|*[!0-9]*) LAST_NO=0 ;; esac
TAG="build-$((LAST_NO + 1))"
echo "本次 tag：$TAG（上一个：${LAST_TAG:-无}）"

{
  echo "### 悦读星球 Android 壳 $TAG"
  echo ""
  echo "- APK 大小：$SIZE"
  echo "- commit：\`${GITHUB_SHA:-local}\`"
  echo ""
  echo "下载后直接安装（debug 签名，需允许「安装未知来源应用」）。"
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

# 发到 Releases：每次构建一个 tag，手机端从 Releases 页直接下载
gh release create "$TAG" moread-debug.apk \
  --title "悦读星球 Android 壳 $TAG" \
  --notes "自动构建的 debug 内测包，下载后直接安装。

- commit: \`${GITHUB_SHA:-local}\`
- 包内前端：\`${WEB_APP:-未取到}\` / \`${WEB_CLOUD:-未取到}\` / 版本 \`${WEB_VER:-未取到}\`
- 前端是构建时从线上抓的，已打进包里（不再远程加载页面）
- 因此**每次改前端都要重出一次包**；只装了旧包的设备看不到新界面
- 判断包里是不是最新前端：把上面这行与线上首页 index.html 引用的 app.*.js 对一下" \
  || gh release upload "$TAG" moread-debug.apk --clobber

echo "== 完成：$TAG =="
