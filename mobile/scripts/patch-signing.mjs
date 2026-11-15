#!/usr/bin/env node
/**
 * 把固定 debug keystore 接进 android/app/build.gradle。
 *
 * 为什么要有这一步：CI 每次都是全新机器，AGP 在 ~/.android/debug.keystore 找不到钥匙就
 * 临时生成一把 —— 于是每个构建产物的签名都不一样。Android 只允许**同签名**的包覆盖安装，
 * 换了签名就只能先卸载；卸载会连同 App 数据（本地书架、笔记、阅读进度、离线书）一起清掉。
 * 从 build-16 起改用仓库里那把固定的钥匙，之后每个包都能直接覆盖安装。
 *
 * 注入的就是 AGP 文档里「用自己的 debug 钥匙」那段：
 *
 *     signingConfigs { debug { storeFile ... keyAlias ... } }
 *
 * AGP 的 debug 构建类型默认就吃 signingConfigs.debug，所以 buildTypes 不用动 —— 这也正是
 * 注入点必须在 android {} 里、buildTypes 之前的原因（先声明再引用）。
 *
 * 用法：node scripts/patch-signing.mjs android/app/build.gradle
 * import { patch } 也可以直接调（tests/run.mjs 就是拿一份 Capacitor 骨架跑真注入）。
 */
import fs from 'node:fs';
import process from 'node:process';

/* 口令 storepass / keypass 都是 android —— 这是 Android debug 钥匙的公开惯例，
   不是疏忽。真正的秘密是**正式签名**，那把永远不进仓库。 */
export const BLOCK = [
  '    signingConfigs {',
  '        debug {',
  '            storeFile file("moread-debug.p12")',
  '            storePassword "android"',
  '            keyAlias "moread"',
  '            keyPassword "android"',
  '            storeType "PKCS12"',
  '        }',
  '    }'
].join('\n');

/**
 * 纯函数：给一段 build.gradle 文本，回注入后的文本。
 *
 * @param {string} text
 * @returns {{ text: string, status: 'injected' | 'already' }}
 * @throws {Error} 找不到注入点 —— 这时**必须**炸，不许静悄悄跳过：
 *         静默跳过 = 这个包又换回临时签名，用户装不上还以为是包坏了。
 */
export function patch(text) {
  /* 已经有 debug 段就别再插一份（重复注入 Gradle 直接报错）。
     判断要连段名一起看：只有 signingConfigs 而没有 debug，说明模板变了，不能猜。 */
  if (/signingConfigs\s*\{[\s\S]{0,800}?\bdebug\s*\{/.test(text)) {
    return { text, status: 'already' };
  }
  if (/signingConfigs\s*\{/.test(text)) {
    throw new Error('build.gradle 里已有 signingConfigs 但没有 debug 段，不敢猜怎么合并：请人工把 debug 段补进去');
  }
  /* Capacitor 8 生成的模板里 `android {` 独占一行（顶格）。`android{`、行尾带注释都认，
     但要求从行首开始 —— 认不出来就让人来看一眼，别猜一个位置塞进去。 */
  const m = /^android[ \t]*\{[^\n]*$/m.exec(text);
  if (!m) {
    throw new Error('build.gradle 里找不到顶格的 `android {`：模板变了，注入点要重新确认');
  }
  const at = m.index + m[0].length;
  return { text: text.slice(0, at) + '\n' + BLOCK + text.slice(at), status: 'injected' };
}

const isMain = process.argv[1] && /patch-signing\.mjs$/.test(process.argv[1]);
if (isMain) {
  const p = process.argv[2];
  if (!p) {
    console.error('用法：node scripts/patch-signing.mjs <build.gradle 路径>');
    process.exit(2);
  }
  let r;
  try {
    r = patch(fs.readFileSync(p, 'utf8'));
  } catch (e) {
    console.error('✗ ' + e.message);
    process.exit(1);
  }
  if (r.status === 'already') {
    console.log('signingConfigs.debug 已存在，跳过注入');
  } else {
    fs.writeFileSync(p, r.text);
    /* 写完再读一遍：这里没有 grep（沙箱里那个大概是替身），而且「写了但没落盘」
       这种事一旦发生，表现就是「构建成功、签名却没换」——最难查的那种。 */
    const back = fs.readFileSync(p, 'utf8');
    if (!back.includes('signingConfigs {')) {
      console.error('✗ 写回后读不到 signingConfigs：' + p);
      process.exit(1);
    }
    console.log('签名配置已注入 ' + p);
  }
}
