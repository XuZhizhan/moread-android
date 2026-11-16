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
 *   drawable/ic_launcher_foreground.xml                             前景：白字矢量，收进 72%
 *   drawable-{mdpi..xxxhdpi}/ic_stat_moread.png                     通知栏 24dp 单色剪影（单字「读」）
 *   drawable-v24/ic_launcher_foreground.xml                         同上（API 24+ 优先取这一份）
 *   drawable/ic_launcher_background.xml                             背景：纯色 #2E9BFF
 *
 * 两条摆放规则，各有理由：
 *   · 自适应图标必须收进安全区：108dp 画布里系统只承诺中间 72dp，蒙版圆半径 36dp，
 *     而这幅图墨迹占 64.3%×65.4%（69.4×70.6dp），铺满时圆形蒙版会把四个角咬掉。
 *     缩到 72% 后墨迹 50.0×50.9dp，占 72dp 蒙版的 71% —— 四个字不再顶到圆边。
 *     再大（85.6%，即最初那版）字会占满蒙版 84%，视觉上像「塞满了」；
 *     再小（66%）字在桌面 48dp 档只剩 20dp，笔画开始糊。72% 是这两头的平衡点。
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

const INSET = 0.72;                       // 收进安全区的比例（见文件头：蒙版内留 29% 呼吸）
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

/* ---------- 0. 先清掉模板的前景残留 ----------
 * Capacitor 模板把自适应图标的前景放在 drawable-v24/ic_launcher_foreground.xml。
 * Android 的资源匹配里「-v24」比「无后缀」更具体 —— API 24+ 一律优先取 v24 那份，
 * 所以我们写到 drawable/ 的矢量永远轮不上（真机表现：桌面还是模板那个 logo）。
 * 处理办法不是挑一个目录写，而是先把所有 drawable- 与 mipmap- 目录下的同名文件清掉，
 * 再往 drawable/ 与 drawable-v24/ 各写一份 —— 任何限定符组合都只会命中我们的。
 */
const purged = [];
if (fs.existsSync(RES)) {
  for (const d of fs.readdirSync(RES, { withFileTypes: true })) {
    if (!d.isDirectory()) continue;
    const dir = path.join(RES, d.name);
    if (/^drawable/.test(d.name)) {
      for (const f of fs.readdirSync(dir)) {
        // $ic_launcher_foreground__0.xml 是 aapt 对模板矢量的中间产物，源文件删了它也不该留
        if (/^\$?ic_launcher_foreground(__0)?\.xml$/.test(f)) {
          fs.rmSync(path.join(dir, f), { force: true });
          purged.push(d.name + '/' + f);
        }
      }
    }
    if (/^mipmap/.test(d.name)) {
      for (const f of fs.readdirSync(dir)) {
        if (/^ic_launcher_foreground.*\.(png|webp)$/.test(f)) {
          fs.rmSync(path.join(dir, f), { force: true });
          purged.push(d.name + '/' + f);
        }
      }
    }
  }
}

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

const FG_XML = `<?xml version="1.0" encoding="utf-8"?>
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
`;

// 两份内容完全相同：drawable/ 兜底，drawable-v24/ 赢过模板的限定符
put('drawable/ic_launcher_foreground.xml', FG_XML);
put('drawable-v24/ic_launcher_foreground.xml', FG_XML);

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

/* ---------- 3.5 通知栏小图标：24dp 单色剪影 ----------
 * 通知的 SmallIcon 系统只读 alpha 通道，颜色一律转白，内容必须是实心简单形。
 * 桌面图标是「蓝底 + 四个白字」，直接拿来用会变成一个白色实心方块 ——
 * 四个字挤进 22dp 会并成一条白带，等于什么都没画（现在用着系统自带的小铃铛）。
 *
 * 所以单独出一档：从源路径里挑出「读」字那一组子路径。
 * 布局是两行四字「悦读 / 星球」，取右上象限就是「读」。判据用子路径包围盒中心，
 * 靠整体 bbox 的中线分开 —— 实测四组各自不跨中线，所以这个分法不含糊。
 * 内容按 22/24 撑满并居中：系统只保证中间 22dp 可见，四周 1dp 是硬性留白。
 */
const boxes = polys.map((P) => {
  let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const [x, y] of P) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  return { P, x0, x1, y0, y1, cx: (x0 + x1) / 2, cy: (y0 + y1) / 2 };
});
const allX0 = Math.min(...boxes.map((b) => b.x0)), allX1 = Math.max(...boxes.map((b) => b.x1));
const allY0 = Math.min(...boxes.map((b) => b.y0)), allY1 = Math.max(...boxes.map((b) => b.y1));
const midX = (allX0 + allX1) / 2, midY = (allY0 + allY1) / 2;
const statCell = boxes.filter((b) => b.cx > midX && b.cy < midY);
if (statCell.length < 6) {
  console.error('✗ 通知剪影取「读」失败：右上象限只有 ' + statCell.length + ' 条子路径，字的结构变了？');
  process.exit(1);
}
const statPolys = statCell.map((b) => b.P);
const stX0 = Math.min(...statCell.map((b) => b.x0)), stX1 = Math.max(...statCell.map((b) => b.x1));
const stY0 = Math.min(...statCell.map((b) => b.y0)), stY1 = Math.max(...statCell.map((b) => b.y1));
const stW = stX1 - stX0, stH = stY1 - stY0;

const STAT_DENS = [['mdpi', 24], ['hdpi', 36], ['xhdpi', 48], ['xxhdpi', 72], ['xxxhdpi', 96]];
const statRaster = [];
for (const [name, px] of STAT_DENS) {
  const content = px * (22 / 24);                       // 系统只保证中间 22/24 可见
  const inset = (content / px) * (VB / Math.max(stW, stH));
  // ox/oy 是「归一化中心位移」：tx(x)=ox*px + (x-vb/2)*scale，scale/px = inset/vb
  const ox = 0.5 - ((stX0 + stW / 2) - VB / 2) * inset / VB;
  const oy = 0.5 - ((stY0 + stH / 2) - VB / 2) * inset / VB;
  const r = rasterize(statPolys, { size: px, viewBox: VB, inset, bg: null, fg: '#ffffff', ox, oy });
  /* 24dp 下宋体的横画只有半像素宽，覆盖率低的笔画会淡到看不见（字会「掉笔画」）。
     对 alpha 提一点 gamma：0.30 → 0.38，形状不变，只是不再糊掉。
     只在前两档做 —— 48px 以上本来就清楚，改了反而显胖。 */
  if (px <= 48) {
    for (let i = 3; i < r.rgba.length; i += 4) {
      if (r.rgba[i] > 0) r.rgba[i] = Math.min(255, Math.round(255 * Math.pow(r.rgba[i] / 255, 0.8)));
    }
  }
  statRaster.push({ name, px, rgba: r.rgba });
  put(`drawable-${name}/ic_stat_moread.png`, encodePNG(px, px, r.rgba));
}

/* ---------- 4. 自检：写完回头验，别让「生成了但不对」溜过去 ---------- */
const problems = [];
for (const [name, px] of DENSITIES) {
  const buf = fs.readFileSync(path.join(RES, `mipmap-${name}/ic_launcher.png`));
  if (buf.readUInt32BE(16) !== px || buf.readUInt32BE(20) !== px) {
    problems.push(`mipmap-${name}/ic_launcher.png 尺寸不是 ${px}×${px}`);
  }
}
/* 两个路径都要有：只有 drawable/ 时，API 24+ 会拿模板那份顶上 */
for (const rel of ['drawable/ic_launcher_foreground.xml', 'drawable-v24/ic_launcher_foreground.xml']) {
  const p2 = path.join(RES, rel);
  if (!fs.existsSync(p2)) { problems.push(rel + ' 不存在：API 24+ 会退回别的限定符'); continue; }
  const s2 = fs.readFileSync(p2, 'utf8');
  if (!new RegExp('android:scaleX="' + String(INSET) + '"').test(s2)) {
    problems.push(rel + ' 没缩到 ' + INSET + '，要么字顶到蒙版边，要么写错了值');
  }
  if (!/android:pathData="M/.test(s2)) problems.push(rel + ' 里没有路径数据');
}
/* 清完之后不该再有任何模板的前景残留 */
for (const d of fs.readdirSync(RES, { withFileTypes: true })) {
  if (!d.isDirectory()) continue;
  const dir = path.join(RES, d.name);
  if (/^mipmap/.test(d.name) && fs.readdirSync(dir).some((f) => /^ic_launcher_foreground/.test(f))) {
    problems.push(d.name + ' 里还留着模板的 ic_launcher_foreground 位图');
  }
}
if (!new RegExp(BRAND, 'i').test(fs.readFileSync(path.join(RES, 'drawable/ic_launcher_background.xml'), 'utf8'))) {
  problems.push('背景色不是 ' + BRAND);
}
/* 通知剪影：必须透明底（有底色就是一整块白方块），且墨迹撑满中间 22/24 */
for (const { name, px, rgba } of statRaster) {
  const a = (x, y) => rgba[(y * px + x) * 4 + 3];
  if (a(0, 0) !== 0 || a(px - 1, 0) !== 0 || a(0, px - 1) !== 0 || a(px - 1, px - 1) !== 0) {
    problems.push('ic_stat_moread(' + name + ') 四角不透明：bg 没关，通知栏会显示成一个实心方块');
  }
  let x0 = px, x1 = -1, y0 = px, y1 = -1;
  for (let y = 0; y < px; y++) {
    for (let x = 0; x < px; x++) {
      if (a(x, y) > 32) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    }
  }
  if (x1 < 0) { problems.push('ic_stat_moread(' + name + ') 是空图：一条墨迹都没有'); continue; }
  const pad = Math.round(px * (1 - 22 / 24) / 2);
  if (x0 < pad - 1 || y0 < pad - 1 || px - 1 - x1 < pad - 1 || px - 1 - y1 < pad - 1) {
    problems.push('ic_stat_moread(' + name + ') 出血：墨迹 ' + x0 + '→' + x1 + ' / ' + y0 + '→' + y1 + '，应四周留 ' + pad + 'px');
  }
}
if (problems.length) {
  console.error('✗ 图标生成后自检不通过：\n  - ' + problems.join('\n  - '));
  process.exit(1);
}

console.log(`图标：${written.length} 个文件，底色 ${BRAND}，前景收进 ${(INSET * 100).toFixed(1)}%`);
