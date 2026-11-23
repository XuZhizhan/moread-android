#!/usr/bin/env node
/**
 * 壳锁竖屏：给 AndroidManifest.xml 里的 .MainActivity 加 android:screenOrientation="portrait"。
 *
 * 为什么必须写在 activity 上：Android 只认 activity（或 application 上的 property）
 * 里的 screenOrientation。写到 `<application>` 上不会报错、也完全不生效 —— 表现就是
 * 「改了、出了包、手机上照样横过去」，最难查的那一类。
 *
 * 为什么是 portrait 而不是 sensorPortrait：sensorPortrait 允许「倒过来」，手机平放时
 * 仍可能翻成横屏。阅读器的正文是按竖屏列宽排的，转过去不是「换个方向」而是版式崩掉，
 * 所以这里只留一个方向。
 *
 * 用法：node scripts/patch-manifest.mjs android/app/src/main/AndroidManifest.xml
 * import { lockPortrait } 也可以直接调 —— tests/run.mjs 拿 Capacitor 生成的骨架跑真注入。
 */
import fs from 'node:fs';
import process from 'node:process';

/* 开标签可能跨多行（Capacitor 模板就是每行一个属性），所以不能用 [^>\n] */
const ACTIVITY = /<activity\b[^>]*android:name="\.MainActivity"[^>]*>/;
const ATTR = /android:screenOrientation="([^"]*)"/;

export const PORTRAIT = 'android:screenOrientation="portrait"';

/**
 * 纯函数：给一段 AndroidManifest 文本，回锁好竖屏的文本。
 *
 * @param {string} text
 * @returns {{ text: string, status: 'injected' | 'already' | 'replaced' }}
 * @throws {Error} 找不到 .MainActivity —— 必须炸。静默跳过等于「以为锁了，其实没锁」，
 *         而这件事只有用户把手机转过去才会发现，那时包已经发出去了。
 */
export function lockPortrait(text) {
  const m = ACTIVITY.exec(text);
  if (!m) {
    throw new Error('AndroidManifest 里找不到 android:name=".MainActivity" 的 activity：模板变了，注入点要重新确认');
  }
  const tag = m[0];

  if (ATTR.test(tag)) {
    const value = ATTR.exec(tag)[1];
    if (value === 'portrait') return { text, status: 'already' };
    /* 模板或别的脚本已经写过一个别的值：改掉它，而不是叠第二个属性
       （同一个属性写两遍，aapt 会在合并阶段报错，或者按后写的算 —— 两种都不该赌）。 */
    const fixed = tag.replace(ATTR, PORTRAIT);
    return {
      text: text.slice(0, m.index) + fixed + text.slice(m.index + tag.length),
      status: 'replaced'
    };
  }

  const next = tag.replace(/<activity\b/, '<activity ' + PORTRAIT);
  return {
    text: text.slice(0, m.index) + next + text.slice(m.index + tag.length),
    status: 'injected'
  };
}

const isMain = process.argv[1] && /patch-manifest\.mjs$/.test(process.argv[1]);
if (isMain) {
  const p = process.argv[2];
  if (!p) {
    console.error('用法：node scripts/patch-manifest.mjs <AndroidManifest.xml 路径>');
    process.exit(2);
  }
  let r;
  try {
    r = lockPortrait(fs.readFileSync(p, 'utf8'));
  } catch (e) {
    console.error('✗ ' + e.message);
    process.exit(1);
  }
  if (r.status === 'already') {
    console.log('MainActivity 已经是 portrait，跳过');
  } else {
    fs.writeFileSync(p, r.text);
    /* 写完再读一遍：这个沙箱里没有可信的 grep，而且「写了但没落盘」的表现
       恰恰是「构建成功、手机还是能横屏」—— 只能靠回读。 */
    const back = fs.readFileSync(p, 'utf8');
    if (!/<activity\b[^>]*android:screenOrientation="portrait"[^>]*>/.test(back)) {
      console.error('✗ 写回后读不到 screenOrientation="portrait"：' + p);
      process.exit(1);
    }
    /* 写到 application 上等于没锁：这一条比上面那条更要紧，因为它不报错。 */
    const app = /<application\b[^>]*>/.exec(back);
    if (app && /android:screenOrientation=/.test(app[0])) {
      console.error('✗ screenOrientation 落到了 <application> 上：那样不生效，必须写在 MainActivity 上');
      process.exit(1);
    }
    console.log('竖屏锁已写入 ' + p + '（' + r.status + '）');
  }
}
