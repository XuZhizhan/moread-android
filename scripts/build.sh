#!/usr/bin/env bash
# 墨读 Android 壳：生成 Capacitor 工程 → 去掉开屏画面 → 注入插件 → sync。
# CI 里由 scripts/ci.sh 调用；本地手跑也是这一句。
#
# 依赖：Node 22+、JDK 21、Android SDK（compileSdk 36）
set -euo pipefail

cd "$(dirname "$0")/.."   # 回到 mobile/

JAVA_SRC="android/app/src/main/java/com/moread/reader"
MANIFEST="android/app/src/main/AndroidManifest.xml"
RES="android/app/src/main/res"

# 纸色，取自 App 正文底色；启动窗口就用它
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

step "去掉开屏画面（只留纸色底）"
# 模板的 AppTheme.NoActionBarLaunch 挂的是 @drawable/splash —— Capacitor 标志那 11 张图。
# 冷启动时这张图要一直挂到 WebView 画出第一帧，壳加载的是线上页面，网一慢就成了「开屏很久」。
# 换成纯色之后没有 logo 可看，观感是「页面在加载」，而不是「卡在启动图上」。
#
# 图标这一版不动：保持模板自带那张。等自己设计的图标出来再换
# （换法见 docs/02-architecture/android-shell-build.md 第 8 节，那套流程验过一遍了）。
mkdir -p "$RES/drawable"
cat > "$RES/values/moread_colors.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="moread_paper">$BRAND_PAPER</color>
</resources>
XML
# Android 12+ 的系统启动屏只读 windowSplashScreen*，光改 android:background 不够；
# 图标塞一张全透明 drawable，让它什么都别画（不然会画应用图标）。
cat > "$RES/drawable/moread_blank.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#00000000" />
</shape>
XML

node -e '
  const fs = require("fs");
  const p = process.argv[1];
  let s = fs.readFileSync(p, "utf8");
  if (!/windowSplashScreenAnimatedIcon/.test(s)) {
    s = s.replace(
      /<item name="android:background">@drawable\/splash<\/item>/,
      "<item name=\"android:background\">@color/moread_paper</item>\n" +
      "        <item name=\"windowSplashScreenBackground\">@color/moread_paper</item>\n" +
      "        <item name=\"windowSplashScreenAnimatedIcon\">@drawable/moread_blank</item>"
    );
  }
  if (/@drawable\/splash/.test(s)) {
    console.error("styles 里还挂着 @drawable/splash，删图会把它断掉");
    process.exit(1);
  }
  fs.writeFileSync(p, s);
  console.log("styles patched：启动窗口 = 纸色底，无 logo");
' "$RES/values/styles.xml"

# 上面确认过已经没人引用，这 11 张图纯占体积（每个方向一张），删掉
for f in "$RES"/drawable*/splash.png; do del "$f"; done

step "校验（没有残留的启动图）"
node -e '
  const fs = require("fs"), path = require("path"), dir = process.argv[1];
  const left = fs.readdirSync(dir, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name.startsWith("drawable"))
    .flatMap((d) => fs.readdirSync(path.join(dir, d.name))
      .filter((f) => /^splash.*\.png$/.test(f))
      .map((f) => d.name + "/" + f));
  if (left.length) { console.error("模板启动图没删干净：" + left.join(", ")); process.exit(1); }
  const styles = fs.readFileSync(path.join(dir, "values/styles.xml"), "utf8");
  if (/@drawable\/splash/.test(styles)) { console.error("styles 还引用着 @drawable/splash"); process.exit(1); }
  if (!/moread_blank/.test(styles)) { console.error("启动屏图标没换成空白图，Android 12+ 会画应用图标"); process.exit(1); }
  console.log("开屏：模板图已清空，启动窗口为纯纸色");
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
  // 免得 targetSdk 再往上抬时行为随系统默认值漂移（MainActivity 挂的是平台回调，靠它生效）。
  if (!/enableOnBackInvokedCallback/.test(s)) {
    s = s.replace(/<application\b/, "<application\n        android:enableOnBackInvokedCallback=\"true\"");
  }
  fs.writeFileSync(p, s);
  console.log("manifest patched");
' "$MANIFEST"

step "sync（把 dist 与插件写进 android 工程）"
npx cap sync android

echo "== setup 完成 =="
