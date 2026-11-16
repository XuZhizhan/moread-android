#!/usr/bin/env bash
# 悦读星球 Android 壳：生成 Capacitor 工程 → 图标 → 开屏 → 注入插件 → sync。
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

step "应用图标（源：android-patches/moread_icon.svg）"
# 一份矢量出全部档位：五档 mipmap + 自适应图标的前景/背景 + 主题图标（v33 monochrome）。
# 这样改图只改一个文件，不会出现「换了 192 忘了 48」；仓库里也不会有二进制图标。
# 摆放规则见 scripts/make-icons.mjs 文件头：自适应那层必须收进安全区（108dp 画布里只承诺 72dp），
# 老的 mipmap PNG 那档没有蒙版，保持铺满。
node scripts/make-icons.mjs android/app/src/main/res android-patches/moread_icon.svg

step "应用名（只改显示名，不动身份）"
# appName 只在 `cap add android` 那一次被写进 values/strings.xml。本地如果已经有 android/
# 目录，上面那行会跳过 cap add，名字就还是旧的 —— 所以这里按配置再覆盖一遍。
# **只动 app_name 与 title_activity_main**：package_name / custom_url_scheme 是身份，
# 改一个字就是另一个应用（老用户的数据、商店里的自己全部对不上）。
# 找不到键时只警告不中断：名字错了在桌面上一眼就看见，不该为它挡住整条构建。
node -e '
  const fs = require("fs");
  const name = JSON.parse(fs.readFileSync("capacitor.config.json", "utf8")).appName;
  const p = process.argv[1];
  let s = fs.readFileSync(p, "utf8");
  let hit = 0;
  for (const key of ["app_name", "title_activity_main"]) {
    const re = new RegExp("(<string name=\"" + key + "\">)[^<]*(</string>)");
    if (!re.test(s)) { console.log("⚠ strings.xml 里没有 " + key + "，跳过（cap add 生成时应已写好）"); continue; }
    s = s.replace(re, "$1" + name + "$2");
    hit++;
  }
  fs.writeFileSync(p, s);
  console.log("应用名 = " + name + "（覆盖 " + hit + " 处）");
' android/app/src/main/res/values/strings.xml

step "去掉开屏画面（只留纸色底）"
# 模板的 AppTheme.NoActionBarLaunch 挂的是 @drawable/splash —— Capacitor 标志那 11 张图。
# 冷启动时这张图要一直挂到 WebView 画出第一帧，壳加载的是线上页面，网一慢就成了「开屏很久」。
# 换成纯色之后没有 logo 可看，观感是「页面在加载」，而不是「卡在启动图上」。
#
# 这里只处理开屏；图标由上面「应用图标」一步生成（docs §8.1）。
# Android 12+ 的启动屏会画应用图标，现在 moread_blank 是把它压掉的那张 ——
# 要不要改成展示图标，跟「启动窗口挂到 Web 首帧」一起谈（docs §8.2）。
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

# 放到 sync 之后：cap sync 会把 android 工程里它管的文件重写一遍，接线放前面有被覆盖的风险。
# 这一步只碰 app/build.gradle 的一处 injection，跟 sync 的唯一交集就是「谁后跑」。
step "固定 debug 签名（后续包可覆盖升级，不必卸载）"
# CI 每次都是全新机器，AGP 在 ~/.android/debug.keystore 找不到钥匙就临时生成一把 ——
# 于是每个包的签名都不一样，手机上只能「先卸载再装」，本地书架 / 笔记 / 阅读进度
# 跟着一起被清掉。仓库里带一份固定 keystore（scripts/moread-debug.p12），构建时复用它：
# 从 build-16 起所有包同一个签名，直接覆盖安装。
# 口令就是公开的 android（Android debug 钥匙的惯例），只用于内测；上架要换正式签名（文档 §6.3）。
KS="scripts/moread-debug.p12"
if [ -f "$KS" ] && keytool -list -keystore "$KS" -storetype PKCS12 -storepass android >/dev/null 2>&1; then
  cp "$KS" android/app/moread-debug.p12
  node scripts/patch-signing.mjs android/app/build.gradle
  # 指纹打进日志：以后要判断「这两个包能不能互相覆盖」，看这一行对不对得上就够了。
  # 末尾兜一个 || ：上面已经证明钥匙能读，这里再失败也不该把整条构建打断（set -o pipefail）。
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
    # 悄悄退回临时签名比构建失败更糟：CI 是绿的、Release 里有包，手机上却装不上，
    # 真正的原因还得再翻一遍日志才发现。宁可这个包出不来。
    echo "✗ $WHY —— CI 里不许退回 AGP 临时签名，先把这把钥匙修好" >&2
    exit 1
  fi
  echo "⚠ $WHY，本次沿用 AGP 临时签名：这个包仍需卸载才能装"
fi

echo "== setup 完成 =="
