#!/usr/bin/env bash
# 墨读 Android 壳：生成 Capacitor 工程 → 换品牌资源 → 注入插件 → sync。
# CI 里由 scripts/ci.sh 调用；本地手跑也是这一句。
#
# 依赖：Node 22+、JDK 21、Android SDK（compileSdk 36）
set -euo pipefail

cd "$(dirname "$0")/.."   # 回到 mobile/

JAVA_SRC="android/app/src/main/java/com/moread/reader"
MANIFEST="android/app/src/main/AndroidManifest.xml"
RES="android/app/src/main/res"
# 站点上那张 maskable 图标（512×512，满幅 #2f6b5c 底）。必须带 sharecode：不带参数直接 403。
ICON_URL="https://moread-66776.app.workbuddy.host/icon-maskable-512.png?sharecode=SRPBCZNFVG"

# 品牌色：绿取自图标四角像素，纸色取自 App 正文底色。
BRAND_GREEN="#2F6B5C"
BRAND_PAPER="#F6F1E7"

step() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }

# 本机沙箱里 rm 被 shim 包了一层（shebang 指向不存在的 /bin/bash），
# 在 set -e 下会把整条构建打断。统一走 del()：rm 能用就用，用不了退回 node。
del() {
  if rm -f -- "$@" 2>/dev/null; then return 0; fi
  node -e 'for (const p of process.argv.slice(1)) { try { require("fs").rmSync(p, { force: true }); } catch (e) {} }' -- "$@"
}

step "安装依赖"
npm install

step "生成 android 平台"
[ -d android ] || npx cap add android

step "品牌资源（图标 / 启动图）"
mkdir -p "$RES/drawable" "$RES/drawable-xxxhdpi" "$RES/mipmap-xxxhdpi"
curl -fsSL "$ICON_URL" -o "$RES/drawable-xxxhdpi/moread_icon.png"

# 图标只留 xxxhdpi 一档（512px 落在 4x 档约 128dp），缺的密度系统按比例缩。
# 模板每个密度都有 ic_launcher，不删的话低密度机会优先取到 Capacitor 那张。
cp "$RES/drawable-xxxhdpi/moread_icon.png" "$RES/mipmap-xxxhdpi/ic_launcher.png"
cp "$RES/drawable-xxxhdpi/moread_icon.png" "$RES/mipmap-xxxhdpi/ic_launcher_round.png"
cp "$RES/drawable-xxxhdpi/moread_icon.png" "$RES/mipmap-xxxhdpi/ic_launcher_foreground.png"
for d in mdpi hdpi xhdpi xxhdpi; do del "$RES/mipmap-$d"/ic_launcher*.png; done

# 自适应图标（Android 8+ 走 mipmap-anydpi-v26）的底色跟图标底色对齐，
# 否则进程启动瞬间和视差缩放的边缘会透出模板的白色。
cat > "$RES/values/ic_launcher_background.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">$BRAND_GREEN</color>
    <color name="moread_paper">$BRAND_PAPER</color>
</resources>
XML

# 启动图：模板那 11 张（drawable + drawable-port-* + drawable-land-*）是 Capacitor 标志，
# 必须全部删掉——只要还剩一张，对应方向就会盖掉我们新写的 splash.xml。
for f in "$RES"/drawable*/splash.png; do del "$f"; done
# bitmap 只认位图、不能引用自适应图标，所以引 drawable-xxxhdpi 里那张 png。
cat > "$RES/drawable/splash.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="rectangle"><solid android:color="$BRAND_PAPER" /></shape>
    </item>
    <item>
        <bitmap android:gravity="center" android:src="@drawable/moread_icon" />
    </item>
</layer-list>
XML

# Android 12+ 的启动屏由系统画，不读 android:background，只读 windowSplashScreen*。
# 这两个属性来自 androidx.core:core-splashscreen（模板 build.gradle 里已经引了），
# 只在 v31+ 生效，低版本系统忽略。找不到那个 style 就只降级、不至于把构建打断。
step "启动屏（Android 12+）"
node -e '
  const fs = require("fs");
  const p = process.argv[1];
  let s = fs.readFileSync(p, "utf8");
  if (/windowSplashScreenBackground/.test(s)) {
    console.log("styles 已打过补丁，跳过");
    process.exit(0);
  }
  const re = /(<style name="AppTheme\.NoActionBarLaunch"[^>]*>)([\s\S]*?)(<\/style>)/;
  if (!re.test(s)) {
    console.warn("!! 模板里没有 AppTheme.NoActionBarLaunch，Android 12+ 的启动屏维持系统默认");
    process.exit(0);
  }
  s = s.replace(re, (m, open, body, close) =>
    open + body +
    "        <item name=\"windowSplashScreenBackground\">@color/moread_paper</item>\n" +
    "        <item name=\"windowSplashScreenAnimatedIcon\">@drawable/moread_icon</item>\n" +
    "    " + close);
  fs.writeFileSync(p, s);
  console.log("styles patched");
' "$RES/values/styles.xml"

step "校验品牌资源"
# 站点挂掉时 curl 可能存下错误页，aapt 报的错会很难读，这里先自己拦一道。
node -e '
  const b = require("fs").readFileSync(process.argv[1]);
  if (b.length < 1024 || b.subarray(1, 4).toString() !== "PNG") {
    console.error("图标不是有效 PNG（" + b.length + " 字节），检查 ICON_URL 的 sharecode");
    process.exit(1);
  }
  console.log("icon ok:", b.length, "bytes,", b.readUInt32BE(16) + "x" + b.readUInt32BE(20));
' "$RES/drawable-xxxhdpi/moread_icon.png"
node -e '
  const fs = require("fs"), path = require("path"), dir = process.argv[1];
  const left = fs.readdirSync(dir, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name.startsWith("drawable"))
    .flatMap((d) => fs.readdirSync(path.join(dir, d.name))
      .filter((f) => /^splash.*\.png$/.test(f))
      .map((f) => d.name + "/" + f));
  if (left.length) { console.error("模板启动图没删干净：" + left.join(", ")); process.exit(1); }
  console.log("splash: 模板图已清空");
' "$RES"

step "注入插件源码"
mkdir -p "$JAVA_SRC"
cp android-patches/MoreadNativePlugin.java "$JAVA_SRC/"
cp android-patches/ReminderReceiver.java "$JAVA_SRC/"
cp android-patches/MainActivity.java "$JAVA_SRC/"

step "补 AndroidManifest（通知权限 + 提醒接收者 + 接管返回键）"
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
  // Android 15 起这个开关默认 true、系统不再调 onBackPressed()；显式写死，
  // 免得 targetSdk 再往上抬时行为随系统默认值漂移（MainActivity 挂的是 dispatcher，两条路都通）。
  if (!/enableOnBackInvokedCallback/.test(s)) {
    s = s.replace(/<application\b/, "<application\n        android:enableOnBackInvokedCallback=\"true\"");
  }
  fs.writeFileSync(p, s);
  console.log("manifest patched");
' "$MANIFEST"

step "补 gradle 依赖（androidx.activity）"
# MainActivity 用 OnBackPressedDispatcher 接管返回键，需要 androidx.activity。
# Capacitor 那边把它声明成 implementation，不向 app 模块传递；模板里 appcompat→fragment→activity
# 一般会带进来，但那是隐式的，gradle 升级就可能断，所以显式写一行（版本取 variables.gradle）。
# 注意：这一整段被单引号包着，脚本里不能出现半角单引号，否则 bash 会提前断串（上一版就栽在这）。
node -e '
  const fs = require("fs");
  const p = process.argv[1];
  let s = fs.readFileSync(p, "utf8");
  if (/androidx\.activity:activity/.test(s)) {
    console.log("已经声明过，跳过");
    process.exit(0);
  }
  const re = /implementation project$[^)]*capacitor-android[^)]*$/;
  if (!re.test(s)) {
    console.warn("!! 模板 gradle 结构变了，没找到 capacitor-android 依赖行，记得手动确认 androidx.activity 在不在 classpath");
    process.exit(0);
  }
  s = s.replace(re, (m) => m + "\n    implementation \"androidx.activity:activity:$androidxActivityVersion\"");
  fs.writeFileSync(p, s);
  console.log("build.gradle patched");
' android/app/build.gradle

step "sync（把 dist 与插件写进 android 工程）"
npx cap sync android

echo "== setup 完成 =="
