#!/usr/bin/env bash
# 悦读星球 Android 壳：生成 Capacitor 工程 → 图标 → 开屏 → 注入插件 → sync。
# CI 由 scripts/ci.sh 调用；本地手跑也是这一句。依赖 Node 22+、JDK 21、Android SDK 36。
set -euo pipefail

cd "$(dirname "$0")/.."   # 回到 mobile/

JAVA_SRC="android/app/src/main/java/com/moread/reader"
MANIFEST="android/app/src/main/AndroidManifest.xml"
RES="android/app/src/main/res"

BRAND_PAPER="#F6F1E7"      # 正文纸色
# 开屏底色：必须与 launch-splash.css 里最暗那一版（#0B1116）一致。
# 系统启动窗口 → 原生覆盖层 → Web 开屏是三次交接，前两层若不是这个色，
# 交接那一下就是整屏由浅翻深 —— 看起来像「画面不同步」，其实是底色在跳。
BRAND_LAUNCH_BG="#0B1116"

step() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }

# 本机沙箱里 rm 被 shim 包了一层，set -e 下会把构建打断。能用 rm 就用，用不了退回 node。
del() {
  if rm -f -- "$@" 2>/dev/null; then return 0; fi
  node -e 'for (const p of process.argv.slice(1)) { try { require("fs").rmSync(p, { force: true }); } catch (e) {} }' -- "$@"
}

step "安装依赖"
npm install

step "生成 android 平台"
[ -d android ] || npx cap add android

step "应用图标（源：android-patches/moread_icon.svg）"
# 一份矢量出全部档位，改图只改一个文件，不会出现「换了 192 忘了 48」
node scripts/make-icons.mjs android/app/src/main/res android-patches/moread_icon.svg

step "应用名（只改显示名，不动身份）"
# appName 只在 cap add 那一次写进 strings.xml，本地已有 android/ 时会跳过，所以这里再覆盖一遍。
# 只动 app_name 与 title_activity_main：package_name / custom_url_scheme 是身份，
# 改一个字就是另一个应用（老用户的数据全部对不上）。
node -e '
  const fs = require("fs");
  const name = JSON.parse(fs.readFileSync("capacitor.config.json", "utf8")).appName;
  const p = process.argv[1];
  let s = fs.readFileSync(p, "utf8");
  let hit = 0;
  for (const key of ["app_name", "title_activity_main"]) {
    const re = new RegExp("(<string name=\"" + key + "\">)[^<]*(</string>)");
    if (!re.test(s)) { console.log("⚠ strings.xml 里没有 " + key + "，跳过"); continue; }
    s = s.replace(re, "$1" + name + "$2");
    hit++;
  }
  fs.writeFileSync(p, s);
  console.log("应用名 = " + name + "（覆盖 " + hit + " 处）");
' android/app/src/main/res/values/strings.xml

step "开屏：只留开屏底色"
# 模板的 @drawable/splash（Capacitor 标志 11 张）要一直挂到 WebView 画出第一帧，
# 观感是「卡在启动图上」。换成纯色后是「页面在加载」。
# 这里只处理「系统启动窗口」那一帧 —— 它是系统画的，应用只能给底色和图标；
# 从 Activity 起到 Web 首帧之间的空窗由 MainActivity 的开屏覆盖层接住。
# 系统那一帧只留纯色：它的图标尺寸是系统定的，和覆盖层里的 84dp 对不上，
# 硬放进去会「跳」一下。覆盖层里的内容是淡入的，两段之间才看不出接缝。
mkdir -p "$RES/drawable"
cat > "$RES/values/moread_colors.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="moread_paper">$BRAND_PAPER</color>
    <color name="moread_launch_bg">$BRAND_LAUNCH_BG</color>
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
      "<item name=\"android:background\">@color/moread_launch_bg</item>\n" +
      "        <item name=\"windowSplashScreenBackground\">@color/moread_launch_bg</item>\n" +
      "        <item name=\"windowSplashScreenAnimatedIcon\">@drawable/moread_blank</item>"
    );
  }
  if (/@drawable\/splash/.test(s)) {
    console.error("styles 里还挂着 @drawable/splash，删图会把它断掉");
    process.exit(1);
  }
  fs.writeFileSync(p, s);
  console.log("styles patched：启动窗口 = 开屏底色（深墨），无 logo");
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
  console.log("开屏：模板图已清空，启动窗口为开屏底色（深墨）");
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

# 放到 sync 之后：cap sync 会把 android 工程里它管的文件重写一遍，接线放前面有被覆盖的风险。
step "固定 debug 签名（后续包可覆盖升级，不必卸载）"
# CI 每次都是全新机器，AGP 在 ~/.android/debug.keystore 找不到钥匙就临时生成一把 ——
# 于是每个包签名都不一样，手机上只能「先卸载再装」，本地书架 / 笔记 / 进度一起被清掉。
# 仓库里带一份固定 keystore，从 build-16 起所有包同一签名。口令是公开的 android，仅内测用；
# 上架要换正式签名。
KS="scripts/moread-debug.p12"
if [ -f "$KS" ] && keytool -list -keystore "$KS" -storetype PKCS12 -storepass android >/dev/null 2>&1; then
  cp "$KS" android/app/moread-debug.p12
  node scripts/patch-signing.mjs android/app/build.gradle
  # 指纹打进日志：判断「两个包能不能互相覆盖」，看这一行对不对得上就够了
  keytool -list -v -keystore "$KS" -storetype PKCS12 -storepass android 2>/dev/null | node -e '
    let s = "";
    process.stdin.on("data", (d) => { s += d; });
    process.stdin.on("end", () => {
      const m = s.match(/SHA1:\s*([0-9A-F:]+)/i);
      console.log("debug 签名指纹 SHA1：" + (m ? m[1] : "(没取到，看上面 keytool 输出)"));
    });
  ' || echo "⚠ 指纹没取到（不影响构建）"
else
  WHY="scripts/moread-debug.p12 不可用（文件缺失，或 keytool 读不出来）"
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    # 悄悄退回临时签名比构建失败更糟：CI 是绿的、Release 里有包，手机上却装不上
    echo "✗ $WHY —— CI 里不许退回 AGP 临时签名，先把这把钥匙修好" >&2
    exit 1
  fi
  echo "⚠ $WHY，本次沿用 AGP 临时签名：这个包仍需卸载才能装"
fi

echo "== setup 完成 =="
