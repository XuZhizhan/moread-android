#!/usr/bin/env node
/**
 * 纯 JS 的 SVG 路径光栅化 + PNG 编码（零依赖）。
 *
 * 为什么要有这个：图标源文件是「转曲后的矢量路径」（UI/yuedu-xingqiu-f4.svg），
 * 而 Android 桌面图标要 PNG（mipmap-*）+ 自适应图标的前景位图。
 * 构建机上不一定有 rsvg-convert / inkscape / ImageMagick，也装不动（CI 时间 + 网络），
 * 所以这里自己实现最小的一套：只吃 M/L/H/V/Q/T/C/S/Z，足够覆盖字体转曲的产物。
 *
 * 遇到 A（圆弧）会直接报错 —— 与其画错，不如让人看见。
 *
 * 用法：node scripts/svg-vector.mjs <svg> <输出 png> <边长> [--inset 比例] [--bg 颜色|none]
 */
import fs from 'node:fs';
import zlib from 'node:zlib';
import process from 'node:process';

/* ---------------- 路径解析 ---------------- */

/** 把 d 解析成若干闭合多边形（三次/二次曲线按像素尺度自适应展平）。 */
export function parsePath(d, scale = 1) {
  const tok = /([MmLlHhVvQqTtCcSsZzAa])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)/g;
  const tokens = [];
  let m;
  while ((m = tok.exec(d))) tokens.push(m[1] ? m[1] : parseFloat(m[2]));

  const polys = [];
  let poly = null;
  let cur = [0, 0];
  let start = [0, 0];
  let cmd = null;
  let i = 0;

  const stepsFor = (len) => Math.max(4, Math.min(32, Math.ceil((len * scale) / 3)));
  const quad = (p0, c, p1) => {
    const len = Math.hypot(c[0] - p0[0], c[1] - p0[1]) + Math.hypot(p1[0] - c[0], p1[1] - c[1]);
    const n = stepsFor(len);
    for (let k = 1; k <= n; k++) {
      const t = k / n, mt = 1 - t;
      poly.push([mt * mt * p0[0] + 2 * mt * t * c[0] + t * t * p1[0],
        mt * mt * p0[1] + 2 * mt * t * c[1] + t * t * p1[1]]);
    }
  };
  const cubic = (p0, c1, c2, p1) => {
    const len = Math.hypot(c1[0] - p0[0], c1[1] - p0[1]) + Math.hypot(c2[0] - c1[0], c2[1] - c1[1]) +
      Math.hypot(p1[0] - c2[0], p1[1] - c2[1]);
    const n = stepsFor(len);
    for (let k = 1; k <= n; k++) {
      const t = k / n, mt = 1 - t;
      poly.push([
        mt * mt * mt * p0[0] + 3 * mt * mt * t * c1[0] + 3 * mt * t * t * c2[0] + t * t * t * p1[0],
        mt * mt * mt * p0[1] + 3 * mt * mt * t * c1[1] + 3 * mt * t * t * c2[1] + t * t * t * p1[1]
      ]);
    }
  };

  while (i < tokens.length) {
    if (typeof tokens[i] === 'string') cmd = tokens[i++];
    const rel = cmd === cmd.toLowerCase();
    const up = cmd.toUpperCase();
    if (up === 'A') throw new Error('不支持圆弧命令 A：这个 SVG 不是「转曲后的字体轮廓」形态，需要换工具处理');
    const num = () => tokens[i++];
    const abs = (x, y) => (rel ? [cur[0] + x, cur[1] + y] : [x, y]);

    if (up === 'M') {
      const p = abs(num(), num());
      cur = p; start = p.slice();
      poly = [p.slice()];
      polys.push(poly);
      cmd = rel ? 'l' : 'L';           // 后续同组坐标按 lineto 处理（SVG 规范）
    } else if (up === 'L') {
      cur = abs(num(), num());
      poly.push(cur.slice());
    } else if (up === 'H') {
      const x = num();
      cur = rel ? [cur[0] + x, cur[1]] : [x, cur[1]];
      poly.push(cur.slice());
    } else if (up === 'V') {
      const y = num();
      cur = rel ? [cur[0], cur[1] + y] : [cur[0], y];
      poly.push(cur.slice());
    } else if (up === 'Q') {
      const c = abs(num(), num());
      const e = abs(num(), num());
      quad(cur, c, e);
      cur = e;
    } else if (up === 'T') {
      // 平滑二次：控制点是上一个控制点的镜像。字体轮廓里极少见，留个保守实现
      const e = abs(num(), num());
      quad(cur, cur, e);
      cur = e;
    } else if (up === 'C') {
      const c1 = abs(num(), num());
      const c2 = abs(num(), num());
      const e = abs(num(), num());
      cubic(cur, c1, c2, e);
      cur = e;
    } else if (up === 'S') {
      const c2 = abs(num(), num());
      const e = abs(num(), num());
      cubic(cur, cur, c2, e);
      cur = e;
    } else if (up === 'Z') {
      cur = start.slice();
      poly = null;                     // 下一个点若没有 M 就非法；正常字体轮廓都会带 M
    }
  }
  return polys.filter((p) => p && p.length > 1);
}

/* ---------------- 光栅化 ---------------- */

const hex = (s) => {
  const t = s.replace('#', '');
  const v = t.length === 3 ? t.split('').map((c) => c + c).join('') : t;
  return [parseInt(v.slice(0, 2), 16), parseInt(v.slice(2, 4), 16), parseInt(v.slice(4, 6), 16)];
};

/**
 * 扫描线填充（nonzero 环绕），竖向 4× 采样、横向解析式覆盖 —— 小尺寸下边缘也不锯齿。
 *
 * @param {Array} polys  parsePath 的产物
 * @param {{size:number, viewBox?:number, inset?:number, bg?:string|null, fg?:string, ox?:number, oy?:number}} o
 *        inset: 图形整体缩放（1 = 铺满画布）；ox/oy: 归一化偏移（0.5 = 居中）
 */
export function rasterize(polys, o) {
  const size = o.size;
  const vb = o.viewBox || 200;
  const inset = o.inset == null ? 1 : o.inset;
  const scale = (size / vb) * inset;
  const ox = (o.ox == null ? 0.5 : o.ox) * size;
  const oy = (o.oy == null ? 0.5 : o.oy) * size;
  const bg = o.bg === null ? null : hex(o.bg || '#000000');
  const fg = hex(o.fg || '#ffffff');

  // 变换到输出像素坐标
  const tx = (x) => ox + (x - vb / 2) * scale;
  const ty = (y) => oy + (y - vb / 2) * scale;

  // 边表：只保留 y 方向有跨度的边
  const edges = [];
  for (const poly of polys) {
    for (let k = 0; k < poly.length; k++) {
      const a = poly[k];
      const b = poly[(k + 1) % poly.length];
      const y0 = ty(a[1]), y1 = ty(b[1]);
      if (y0 === y1) continue;
      edges.push({ x0: tx(a[0]), y0, x1: tx(b[0]), y1, dir: y1 > y0 ? 1 : -1 });
    }
  }

  const SS = 4;                                  // 竖向每像素采样数
  const cov = new Float32Array(size * size);     // 覆盖度累加
  const cross = [];

  for (let row = 0; row < size * SS; row++) {
    const y = (row + 0.5) / SS;
    cross.length = 0;
    for (let e = 0; e < edges.length; e++) {
      const ed = edges[e];
      const lo = Math.min(ed.y0, ed.y1), hi = Math.max(ed.y0, ed.y1);
      if (y < lo || y >= hi) continue;
      const t = (y - ed.y0) / (ed.y1 - ed.y0);
      cross.push({ x: ed.x0 + t * (ed.x1 - ed.x0), dir: ed.dir });
    }
    if (!cross.length) continue;
    cross.sort((a, b) => a.x - b.x);

    let wind = 0;
    for (let k = 0; k < cross.length - 1; k++) {
      wind += cross[k].dir;
      if (wind === 0) continue;
      let xa = cross[k].x, xb = cross[k + 1].x;
      if (xb <= 0 || xa >= size) continue;
      if (xa < 0) xa = 0;
      if (xb > size) xb = size;
      if (xb <= xa) continue;
      const c0 = Math.floor(xa), c1 = Math.ceil(xb);
      const base = ((row / SS) | 0) * size;
      for (let c = c0; c < c1 && c < size; c++) {
        const l = Math.max(xa, c), r = Math.min(xb, c + 1);
        if (r > l) cov[base + c] += r - l;
      }
    }
  }

  const rgba = new Uint8Array(size * size * 4);
  for (let p = 0; p < size * size; p++) {
    const a = Math.max(0, Math.min(1, cov[p] / SS));
    const q = p * 4;
    if (bg) {
      rgba[q] = Math.round(bg[0] * (1 - a) + fg[0] * a);
      rgba[q + 1] = Math.round(bg[1] * (1 - a) + fg[1] * a);
      rgba[q + 2] = Math.round(bg[2] * (1 - a) + fg[2] * a);
      rgba[q + 3] = 255;
    } else {
      rgba[q] = fg[0];
      rgba[q + 1] = fg[1];
      rgba[q + 2] = fg[2];
      rgba[q + 3] = Math.round(a * 255);
    }
  }
  return { width: size, height: size, rgba };
}

/* ---------------- PNG 编码 ---------------- */

let CRC_TABLE = null;
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'latin1'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

export function encodePNG(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;      // bit depth
  ihdr[9] = 6;      // RGBA
  ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;

  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;      // filter: none
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
}

/* ---------------- 从 SVG 文本取路径 ---------------- */

export function readSvg(text) {
  const vb = /viewBox="([^"]+)"/.exec(text);
  const box = vb ? vb[1].trim().split(/[\s,]+/).map(Number) : [0, 0, 200, 200];
  const paths = [];
  const re = /<path\b([^>]*)\/?>/g;
  let m;
  while ((m = re.exec(text))) {
    const attrs = m[1];
    const d = /\bd="([^"]+)"/.exec(attrs);
    if (!d) continue;
    const fill = /\bfill="([^"]+)"/.exec(attrs);
    paths.push({ d: d[1], fill: fill ? fill[1] : '#000000' });
  }
  const rect = /<rect\b([^>]*)\/?>/.exec(text);
  const bgFill = rect && /\bfill="([^"]+)"/.exec(rect[1]);
  return { viewBox: box, paths, background: bgFill ? bgFill[1] : null };
}

/* ---------------- CLI ---------------- */

const isMain = process.argv[1] && /svg-vector\.mjs$/.test(process.argv[1]);
if (isMain) {
  const [src, out, sizeArg] = process.argv.slice(2);
  if (!src || !out || !sizeArg) {
    console.error('用法：node scripts/svg-vector.mjs <svg> <输出.png> <边长> [--inset 1] [--bg #2E9BFF|none] [--fg #fff]');
    process.exit(2);
  }
  const arg = (name, dflt) => {
    const k = process.argv.indexOf('--' + name);
    return k > 0 ? process.argv[k + 1] : dflt;
  };
  const text = fs.readFileSync(src, 'utf8');
  const { viewBox, paths, background } = readSvg(text);
  const size = parseInt(sizeArg, 10);
  const scale = (size / (viewBox[2] || 200));
  const polys = [];
  for (const p of paths) polys.push(...parsePath(p.d, scale));
  const bgArg = arg('bg', background || '#000000');
  const r = rasterize(polys, {
    size,
    viewBox: viewBox[2] || 200,
    inset: parseFloat(arg('inset', '1')),
    bg: bgArg === 'none' ? null : bgArg,
    fg: arg('fg', paths[0] ? paths[0].fill : '#ffffff')
  });
  fs.writeFileSync(out, encodePNG(r.width, r.height, r.rgba));
  console.log(out, size + '×' + size, fs.statSync(out).size + ' 字节');
}
