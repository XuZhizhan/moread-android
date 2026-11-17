#!/usr/bin/env node
/**
 * 把线上站点抓成本地 dist/，供 `cap sync` 打进 APK。
 *
 * 为什么需要这个脚本：
 *   APK 原来用 `server.url` 指到线上（每次冷启动完整下一遍网页，705KB / 19 个请求）。
 *   改成打包进 APK 之后，构建机必须有一份**和线上一致**的前端 —— 仓库里只放了一份
 *   1080 字节的占位页（那是为了让 cap sync 不报错的），不是真东西。
 *
 * 为什么不在仓库里存 dist：
 *   · 每次发版要重提 60+ 个文件（含 PNG 二进制），仓库会一直变大；
 *   · 更麻烦的是「仓库里那份」和「线上那份」迟早不一致，而 APK 用的是仓库里那份 ——
 *     结果就是「线上已经修好了，包里还是旧的」，这种问题没人能从版本号上看出来。
 *   抓取方案让 APK 的输入只有一个来源：线上当前是什么，包里就是什么。抓不全就构建失败。
 *
 * 用法：node mobile/scripts/fetch-web.mjs [目标目录] [站点根]
 *   站点根默认 https://moread-66776.app.workbuddy.host
 *   分享码用环境变量 MOREAD_SHARE 覆盖（默认值写死没关系：它是公开的分享链接参数）
 *
 * 发布顺序（2026-11-17 起，这条是硬的）：
 *   ① 先发 Web —— 改完前端跑 build.mjs 再发布；
 *   ② 再出包 —— 这个脚本把线上那份抓进来。
 *   反过来做会打出一个「用旧前端」的包，而它的版本号、build 号、界面全都正常，
 *   只有你刚修的那处没生效。上线时最常见的误判就是这个。
 *   判据：构建日志最后那行「app 指纹」必须和线上首页 index.html 里引用的一致。
 */
import fs from 'node:fs/promises';
import path from 'node:path';

const OUT = process.argv[2] || 'dist';
const BASE = (process.argv[3] || 'https://moread-66776.app.workbuddy.host').replace(/\/+$/, '');
const SHARE = process.env.MOREAD_SHARE || 'SRPBCZNFVG';

/* 站点对**所有**路径都要求 sharecode（不带就 403，连 CSS 都是）。
   资源名里已经带内容哈希，加查询串不会影响缓存语义。 */
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

  /* 1) 先拿清单。precache-manifest.js 是构建产物，里面有两张表：
        PRECACHE（读者一定会用到）+ LAZY（管理端/诊断页，首次访问才取）。
        两张都要抓 —— 少抓 lazy 那份，App 里的「进入管理端」就是 404。 */
  const man = (await get('precache-manifest.js')).toString('utf8');
  const grab = (key) => {
    const m = man.match(new RegExp('self\\.__' + key + '__\\s*=\\s*(\\[[\\s\\S]*?\\]);'));
    if (!m) throw new Error('清单里找不到 __' + key + '__：线上版本格式变了？');
    return JSON.parse(m[1]);
  };
  const precache = grab('MOREAD_PRECACHE');
  const lazy = grab('MOREAD_LAZY');
  const version = (man.match(/__MOREAD_VERSION__\s*=\s*'([^']+)'/) || [])[1] || '?';

  /* 2) 清单之外还必须有的：SW 本体与清单自己（它们不在 precache 列表里，
        因为清单是 build 之后才生成的）。少了 sw.js，App 就没有离线能力。 */
  const extras = ['sw.js', 'precache-manifest.js'];

  const all = [...new Set([...precache, ...lazy, ...extras])];
  if (all.length < 30) throw new Error('清单条目只有 ' + all.length + ' 条：不像一份完整的站点');

  /* 3) 逐条下载。串行而不是并发 20：构建机带宽给足也没用，
        站点是 python 单进程，压太狠只会互相排队等超时。 */
  let bytes = 0;
  for (const rel of all) {
    const buf = await get(rel);
    bytes += buf.length;
    await put(rel, buf);
  }

  /* 4) 自检：index.html 里引用到的每个本地文件都必须在。
        这一步挡的是「清单漏了一条」——那种包能装上、能打开，但某个功能静默 404。 */
  const html = await fs.readFile(path.join(OUT, 'index.html'), 'utf8');
  const refs = [...html.matchAll(/\b(?:src|href)\s*=\s*["']([^"'#?]+)["']/g)]
    .map((m) => m[1])
    .filter((r) => !/^(https?:)?\/\//.test(r) && !r.startsWith('data:'));
  const missing = [];
  for (const r of new Set(refs)) {
    try { await fs.access(path.join(OUT, r)); } catch (e) { missing.push(r); }
  }
  if (missing.length) {
    throw new Error('index.html 引用了清单里没有的文件：' + missing.join(', '));
  }

  const files = [];
  const walk = async (dir) => {
    for (const e of await fs.readdir(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) await walk(p);
      else files.push(p);
    }
  };
  await walk(OUT);

  console.log('抓取完成 → ' + OUT + '/');
  console.log('  版本: ' + version);
  /* 这一行是「包里到底是哪一版前端」的唯一凭据。它必须与线上首页 index.html
     里引用的那个 app.*.js 一致 —— 不一致就说明这次抓的是旧页面（缓存、或者
     发布还没生效就出了包），包能装上、界面也正常，只有你刚改的那处不生效。 */
  const appRef = (html.match(/app\.[0-9a-f]{8}\.js/) || ['(没找到)'])[0];
  console.log('  app 指纹: ' + appRef);
  console.log('  清单: precache ' + precache.length + ' + lazy ' + lazy.length + ' + 自身 ' + extras.length);
  console.log('  落地: ' + files.length + ' 个文件 / ' + Math.round(bytes / 1024) + 'KB');
}

main().catch((err) => {
  console.error('✗ ' + err.message);
  console.error('  前端抓不下来就不该出包：包里会是一个半截站点，装上才发现某个页面 404。');
  process.exit(1);
});
