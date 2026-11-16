#!/usr/bin/env node
/**
 * 从一份矢量图标（android-patches/moread_icon.svg）生成 Android 需要的全部图标资源。
 *
 * 为什么这样做，而不是往仓库塞一堆 PNG：
 *   · 源文件只有一份（10.8KB 的路径），改一次全档跟着变，不会出现「改了 192 忘了 48」；
 *   · 不需要构建机装 rsvg-convert / inkscape / ImageMagick（CI 上没有，装一次要几分钟还有网络风险）；
 *   · 仓库里不生二进制，diff 可读。
 *
 * 生成产物（res/ 下）：
 *   mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        48/72/96/144/192（铺满）
 *   mipmap-{...}/ic_launcher_round.png                              同上尺寸（收进安全区）
 *   mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml        自适应图标
 *   mipmap-anydpi-v33/ic_launcher.xml, ic_launcher_round.xml        同上 + monochrome（主题图标）
 *   drawable/ic_launcher_foreground.xml                             前景：白字矢量，收进 85.6%
 *   drawable/ic_launcher_background.xml                             背景：纯色 #2E9BFF
 *
 * 两条摆放规则，各有理由：
 *   · 自适应图标必须**收进安全区**：108dp 画布里系统只承诺中间 72dp，蒙版圆半径 36dp，
 *     而这幅图墨迹占 64.3%×65.4%（69.4×70.6dp），铺满时圆形蒙版会把四个角咬掉。
 *     缩到 85.6% 后墨迹 59.4×60.5dp，四种蒙版都不切（UI/icon-check 里有四张对照渲图）。
 *   · 老的 mipmap PNG（API<26 才会用到）保持铺满 —— 那一档没有蒙版，铺满才不显小。
 *     round 那张例外，它多半会被启动器裁成圆，所以也收进。
 *
 * 不碰 values/：背景色写成 drawable（shape），避免和模板里的 @color/ic_launcher_background 撞名。
 *
 * 用法：node scripts/make-icons.mjs <res 目录> [svg 路径]
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { readSvg, parsePath, rasterize, encodePNG } from './svg-vector.mjs';

const RES = process.argv[2];
const SVG = process.argv[3] || 'android-patches/moread_icon.svg';
if (!RES) {
  console.error('用法：node scripts/make-icons.mjs <res 目录> [svg 路径]');
  process.exit(2);
}

const INSET = 0.856;                      // 收进安全区的比例（见文件头）
const FALLBACK_BRAND = '#2E9BFF';
const DENSITIES = [['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]];

const svg = fs.readFileSync(SVG, 'utf8');
const { viewBox, paths, background } = readSvg(svg);
if (!paths.length) {
  console.error('✗ ' + SVG + ' 里没有 <path>：这份图标不是矢量轮廓');
  process.exit(1);
}
if (/<text\b/.test(svg) || /base64/i.test(svg)) {
  console.error('✗ ' + SVG + ' 里还有 <text> 或内嵌 base64 字体：请先转曲，否则渲染结果依赖字体');
  process.exit(1);
}
const BRAND = background || FALLBACK_BRAND;
const VB = viewBox[2] || 200;
const FG = paths[0].fill || '#ffffff';
const polys = [];
for (const p of paths) polys.push(...parsePath(p.d, 1));

const written = [];
const put = (rel, buf) => {
  const p = path.join(RES, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, buf);
  written.push(rel);
};

/* ---------- 1. 传统 mipmap PNG（API < 26 的兜底） ---------- */
for (const [name, px] of DENSITIES) {
  put(`mipmap-${name}/ic_launcher.png`,
    encodePNG(px, px, rasterize(polys, { size: px, viewBox: VB, inset: 1, bg: BRAND, fg: FG }).rgba));
  put(`mipmap-${name}/ic_launcher_round.png`,
    encodePNG(px, px, rasterize(polys, { size: px, viewBox: VB, inset: INSET, bg: BRAND, fg: FG }).rgba));
}

/* ---------- 2. 自适应图标的前景 / 背景 ---------- */
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
const t = ((1 - INSET) * VB) / 2;         // 缩放后居中所需的平移（viewBox 单位）

const fgPaths = paths.map((p) =>
  `        <path\n            android:fillColor="${esc(p.fill || FG)}"\n            android:pathData="${esc(p.d)}" />`
).join('\n');

put('drawable/ic_launcher_foreground.xml', `<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/make-icons.mjs 从 android-patches/moread_icon.svg 生成，不要手改。
     108dp 画布里只承诺中间 72dp，所以整幅缩到 ${(INSET * 100).toFixed(1)}% 居中。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="${VB}"
    android:viewportHeight="${VB}">
    <group
        android:translateX="${+t.toFixed(3)}"
        android:translateY="${+t.toFixed(3)}"
        android:scaleX="${INSET}"
        android:scaleY="${INSET}">
${fgPaths}
    </group>
</vector>
`);

put('drawable/ic_launcher_background.xml', `<?xml version="1.0" encoding="utf-8"?>
<!-- 图标底色。写成 drawable 而不是 @color，避免和模板 values/ 里的同名 color 冲突。 -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="${BRAND}" />
</shape>
`);

/* ---------- 3. 自适应图标的两份 XML ---------- */
const adaptive = (mono) => `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />${mono ? '\n    <monochrome android:drawable="@drawable/ic_launcher_foreground" />' : ''}
</adaptive-icon>
`;

for (const name of ['ic_launcher', 'ic_launcher_round']) {
  put(`mipmap-anydpi-v26/${name}.xml`, adaptive(false));
  put(`mipmap-anydpi-v33/${name}.xml`, adaptive(true));
}

/* ---------- 4. 自检：写完回头验，别让「生成了但不对」溜过去 ---------- */
const problems = [];
for (const [name, px] of DENSITIES) {
  const buf = fs.readFileSync(path.join(RES, `mipmap-${name}/ic_launcher.png`));
  if (buf.readUInt32BE(16) !== px || buf.readUInt32BE(20) !== px) {
    problems.push(`mipmap-${name}/ic_launcher.png 尺寸不是 ${px}×${px}`);
  }
}
const fgXml = fs.readFileSync(path.join(RES, 'drawable/ic_launcher_foreground.xml'), 'utf8');
if (!/android:scaleX="0\.856"/.test(fgXml)) problems.push('前景层没缩到 0.856，圆蒙版会咬四角');
if (!/android:pathData="M/.test(fgXml)) problems.push('前景层里没有路径数据');
if (!new RegExp(BRAND, 'i').test(fs.readFileSync(path.join(RES, 'drawable/ic_launcher_background.xml'), 'utf8'))) {
  problems.push('背景色不是 ' + BRAND);
}
if (problems.length) {
  console.error('✗ 图标生成后自检不通过：\n  - ' + problems.join('\n  - '));
  process.exit(1);
}

console.log(`图标：${written.length} 个文件，底色 ${BRAND}，前景收进 ${(INSET * 100).toFixed(1)}%`);
