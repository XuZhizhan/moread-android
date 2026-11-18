#!/usr/bin/env node
/**
 * 把线上站点抓成本地 dist/，供 `cap sync` 打进 APK。
 *
 * 为什么抓而不在仓库里存 dist：仓库那份和线上那份迟早不一致，而 APK 用的是仓库里那份 ——
 * 结果就是「线上已经修好了，包里还是旧的」，版本号、界面全都正常，只有刚修的那处不生效。
 * 抓取让 APK 的输入只有一个来源：线上当前是什么，包里就是什么。抓不全就构建失败。
 *
 * 用法：node mobile/scripts/fetch-web.mjs [目标目录] [站点根]
 *   分享码用 MOREAD_SHARE 覆盖（默认值是公开的分享链接参数，写死没关系）
 *
 * 发布顺序是硬的：① 先发 Web（改完前端跑 build.mjs 再发布）② 再出包。
 * 判据：构建日志那行「app 指纹」必须与线上 index.html 引用的一致。
 * 详细约定见 mobile/gh/BUILD-NOTES.md。
 */
import fs from 'node:fs/promises';
import path from 'node:path';

const OUT = process.argv[2] || 'dist';
const BASE = (process.argv[3] || 'https://moread-66776.app.workbuddy.host').replace(/\/+$/, '');
const SHARE = process.env.MOREAD_SHARE || 'SRPBCZNFVG';

/* 站点对**所有**路径都要求 sharecode（不带就 403，连 CSS 都是）。
   资源名里已经带内容哈希，加查询串不影响缓存语义。 */
const urlFor = (rel) => {
  const clean = rel.replace(/^\.?\//, '');
  return BASE + '/' + clean + (clean.includes('?') ? '&' : '?') + 'sharecode=' + encodeURIComponent(SHARE);
};

async function get(rel) {
  const res = await fetch(urlFor(rel), { redirect: 'follow' });
  if (!res.ok) throw new Error('抓取失败 ' + rel + ' → HTTP ' + res.status);
  const buf = Buffer.from(await res.arrayBuffer());
  if (!buf.length) throw new Error('抓到空文件 ' + rel);
  return buf;
}

async function put(rel, buf) {
  const p = path.join(OUT, rel.replace(/^\.?\//, ''));
  await fs.mkdir(path.dirname(p), { recursive: true });
  await fs.writeFile(p, buf);
}

async function main() {
  await fs.rm(OUT, { recursive: true, force: true });
  await fs.mkdir(OUT, { recursive: true });

  /* 1) 清单两张表：PRECACHE（读者一定会用到）+ LAZY（管理端/诊断页，首次访问才取）。
        少抓 lazy 那份，App 里的「进入管理端」就是 404。 */
  const man = (await get('precache-manifest.js')).toString('utf8');
  const grab = (key) => {
    const m = man.match(new RegExp('self\\.__' + key + '__\\s*=\\s*(\\[[\\s\\S]*?\\]);'));
    if (!m) throw new Error('清单里找不到 __' + key + '__：线上版本格式变了？');
    return JSON.parse(m[1]);
  };
  const precache = grab('MOREAD_PRECACHE');
  const lazy = grab('MOREAD_LAZY');
  const version = (man.match(/__MOREAD_VERSION__\s*=\s*'([^']+)'/) || [])[1] || '?';

  /* 2) SW 本体与清单自己不在 precache 列表里（清单是 build 之后才生成的），单列 */
  const extras = ['sw.js', 'precache-manifest.js'];

  /* 2.5) 全量资产清单（build.mjs 生成）。没有它，**有意不进 precache 的资源**就抓不到：
         它们不在两份清单里，也不在 index.html 的 <img src> 里。开屏那五张 JPEG
         正是这一类（路径写在 CSS 的 --lg-photo 变量上，按 html 属性切换）。
         build-35 的教训：包里只剩两个 SVG 字标，照片版开屏全黑、蓝色版正常，
         看起来像渲染坏了，其实是文件根本不在包里。
         旧站点没有这个文件时退回原行为，下面的 CSS 引用自检会兜住。 */
  let assets = [];
  try {
    const parsed = JSON.parse((await get('asset-list.json')).toString('utf8'));
    if (!Array.isArray(parsed) || parsed.length < 30) throw new Error('只有 ' + parsed.length + ' 条');
    assets = parsed;
  } catch (e) {
    console.log('  ⚠ 线上没有可用的 asset-list.json（' + e.message + '）：退回按 precache 清单抓');
    console.log('      重新跑一次 build.mjs 并发布，就能带上它');
  }

  const all = [...new Set([...precache, ...lazy, ...assets, ...extras])];
  if (all.length < 30) throw new Error('清单条目只有 ' + all.length + ' 条：不像一份完整的站点');

  /* 3) 串行下载。站点是 python 单进程，并发压太狠只会互相排队等超时。 */
  let bytes = 0;
  for (const rel of all) {
    const buf = await get(rel);
    bytes += buf.length;
    await put(rel, buf);
  }

  /* 4) 自检 + 补抓：引用到的本地文件都必须在，缺失先补抓、抓不到才失败。
        补抓的不写进 precache（清单是 SW 的事），但会打进 APK，运行时按需落盘。
        仍然要打印警告：清单缺一条本身是构建配置问题，静默补上会让它一直没人修。 */
  const listFiles = async (dir, prefix) => {
    const out = [];
    for (const e of await fs.readdir(dir, { withFileTypes: true })) {
      const rel = prefix ? prefix + '/' + e.name : e.name;
      if (e.isDirectory()) out.push(...await listFiles(path.join(dir, e.name), rel));
      else out.push(rel);
    }
    return out;
  };

  const html = await fs.readFile(path.join(OUT, 'index.html'), 'utf8');
  const refs = [...html.matchAll(/\b(?:src|href)\s*=\s*["']([^"'#?]+)["']/g)]
    .map((m) => m[1])
    .filter((r) => !/^(https?:)?\/\//.test(r) && !r.startsWith('data:'));

  /* 4a) CSS 的 url(...) 也必须在。html 扫描看不见底图（那是 CSS 变量，不是 <img src>），
         build-35 的黑屏正是这么漏过去的：清单没登记、html 没引用，两处都查不到。 */
  for (const rel of await listFiles(OUT, '')) {
    if (!rel.endsWith('.css')) continue;
    const css = await fs.readFile(path.join(OUT, rel), 'utf8');
    const dir = path.posix.dirname(rel);
    for (const m of css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)) {
      const u = m[1].trim();
      if (!u || /^(https?:)?\/\//.test(u) || u.startsWith('data:') || u.startsWith('#')) continue;
      const clean = u.split('?')[0].replace(/^\.?\//, '');
      refs.push(u.startsWith('/') ? clean : path.posix.normalize(path.posix.join(dir === '.' ? '' : dir, clean)));
    }
  }
  const fetchedExtra = [];
  const reallyMissing = [];
  for (const r of new Set(refs)) {
    try {
      await fs.access(path.join(OUT, r));
    } catch (e) {
      try {
        const buf = await get(r);
        await put(r, buf);
        bytes += buf.length;
        fetchedExtra.push(r);
      } catch (e2) {
        reallyMissing.push(r);
      }
    }
  }
  if (fetchedExtra.length) {
    console.log('  ⚠ 清单外补抓 ' + fetchedExtra.length + ' 条（没进 precache，运行时按需取）');
    fetchedExtra.forEach((f) => console.log('      ' + f));
  }
  if (reallyMissing.length) {
    throw new Error('引用了线上也拿不到的文件：' + reallyMissing.join(', '));
  }

  const files = await listFiles(OUT, '');
  const appRef = (html.match(/app\.[0-9a-f]{8}\.js/) || ['(没找到)'])[0];
  console.log('抓取完成 → ' + OUT + '/');
  console.log('  版本: ' + version);
  /* 这一行是「包里到底是哪一版前端」的唯一凭据，必须与线上 index.html 一致 */
  console.log('  app 指纹: ' + appRef);
  console.log('  清单: precache ' + precache.length + ' + lazy ' + lazy.length + ' + 资产 '
    + assets.length + ' + 自身 ' + extras.length);
  console.log('  落地: ' + files.length + ' 个文件 / ' + Math.round(bytes / 1024) + 'KB');
}

main().catch((err) => {
  console.error('✗ ' + err.message);
  console.error('  前端抓不下来就不该出包：包里会是一个半截站点，装上才发现某个页面 404。');
  process.exit(1);
});
