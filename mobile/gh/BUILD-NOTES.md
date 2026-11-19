# 出包约定（build-35 起）

## 一、开屏三层底色必须同一套

系统启动窗口 → 原生覆盖层 → Web 开屏是**三次交接**。

`build.sh` 生成两个颜色：`moread_paper`（正文纸色 `#F6F1E7`）与
`moread_launch_bg`（开屏深墨 `#0B1116`）。`styles.xml` 的
`android:background` / `windowSplashScreenBackground` 与 `MainActivity`
覆盖层底色，**三处都必须用 `moread_launch_bg`**。

用错会怎样：前两层浅、第三层深，交接那一下是整屏由浅翻深，
看起来像「画面不同步」，其实是底色在跳。`MainActivity` 的提示文字
`HINT` 也必须跟着是白色（`0x8AFFFFFF`）—— 黑 54% 在深底上等于隐形。

02/03 两版是品牌蓝，靠覆盖层 200ms 淡出过渡（变亮比变暗好接受）。

## 二、抓什么由 asset-list.json 决定，不是由「谁记得登记」决定

`build.mjs` 收尾时会遍历 `dist/` 写一份 `asset-list.json`（全量文件清单，排除 `.gz`），
`fetch-web.mjs` 读它并进抓取集合。**所以任何静态资源只要在 `dist/` 里，就一定会进包。**

这条是拿事故换来的（build-35）：开屏五张底图**有意不进 precache**（1.5MB，
不想把首装下载从 2.1MB 抬到 3.6MB），而它们又不在 `index.html` 里 ——
路径写在 `launch-splash.css` 的 `--lg-photo` 变量上，靠 `<html data-launch-variant>` 切换。
于是清单没登记、html 没引用，两处自检都看不见 → 5 张图一张没进包 →
照片版开屏全黑、蓝色版正常，看起来像渲染故障。

现在的两道防线：

- `fetch-web.mjs` 优先抓 `asset-list.json`（旧站点没有时退回清单 + 打印警告）
- 自检除了 `index.html` 的 `src`/`href`，还扫**所有 CSS 的 `url(...)`**

precache 仍只放「每次冷启动必用的小件」：

- 字标 `assets/launch/wordmark-*.svg`：**已进**（各 10KB）
- 底图 `assets/launch/splash-*.jpg`：**不进**（1.5MB，走 SW 运行时缓存），
  但它们在 `asset-list.json` 里，所以照样进包

改完 `build.mjs` 的清单逻辑，务必按第四节那样**真跑一遍** `fetch-web.mjs`：
落地文件数应当等于 `asset-list.json` 的条目数。

## 三、往这个仓库推大文件后，必须用 raw 逐字节校验

2026-11-18 出 build-35 时踩过：`build.sh`（10627B）推上去只剩 **4134B**，
`cat > moread_colors.xml <<XML` 之后的 120 行全丢。返回是 success，
坏在包里只有装上去才发现。

推完立刻：

```bash
curl -sL "https://raw.githubusercontent.com/<owner>/<repo>/<branch>/<path>" -o /tmp/x
diff -q <本地路径> /tmp/x && echo OK
```

不 OK 就把注释压短再推（那次 8.8KB 就完整通过了）。

## 四、tag 从「历史最大 build-N + 1」推导

`ci.sh` 会调 `gh release list --json tagName` 取全部 tag，筛出 1–4 位的 `build-N`，
按数字排序取最大，+1。不用 `GITHUB_RUN_NUMBER`（run number 是累计跑数，
失败一次就永久跳号，而人是在按编号比对版本的）。

**这里踩过两次，都是「拿不到 tag 列表」引起的**：

- `gh release list` 默认输出的第一列是**标题**不是 tag（列序 TITLE / TYPE / TAG NAME / PUBLISHED），
  用 `awk '{print $1}'` 取到的是「悦读星球」这种碎片 → 过滤后为空 → 退回 0 → 建出 `build-1`
- 为空时退回某个默认值是错的：名字错的包会被当成最新版发出去

所以：用 `--json tagName` 取结构化字段，编号限 1–4 位（历史里有 `build-11171924`
这类时间戳 tag，别让它把最大值带偏），**取不到就 `exit 1`，宁可不发包**。
重跑同名 tag 走 `gh release upload --clobber`，不会重号。

## 五、大改动走分支 + PR，不要直推 main

`android.yml` 是 `on: push: branches: [main]`，推 main 立刻烧一次 CI。
推到其他分支**不触发任何 workflow**，可以先在那里用 raw 校验内容完整，
再开 PR 合进 main。合进 main 那一下才真正触发构建。

## 六、只改前端也要一个 commit 才能出包

包内 dist 是构建时从线上抓的，所以**只改前端、不动 `mobile/`** 时，
仓库里没有任何东西需要改 —— 但 CI 只认 push。约定：

- 提交信息写 `chore(release): 重出包 —— <一句话原因>`
- 顺手在这个文件或对应文档里补一条，不要让这次提交变成空提交

别改成「定时构建」或「手动 dispatch 忽略」：出包是有代价的（7 分钟 + 一个版本号），
留一个显式的动作，才不会出现「用户装到的包不知道是哪一版前端」。
判断包里是哪一版看 release notes 的「包内前端」那行，与线上 index.html 引用对一下。

## 七、链接语音库（.mdd）：手机端靠 SAF，只在新包里有效

2026-11-18 起设置页多了一个「链接 .mdd」。按环境分（2026-11-19 更正：Chrome 132 起
Android 与 WebView 也有 File System Access 了，旧表里「手机浏览器都没有」已不成立）：

| 环境 | 有没有入口 | 机制 |
|---|---|---|
| 桌面 Chrome / Edge 86+ | 有 | File System Access 句柄，重启后可能要再授权 |
| **APK（build-38 起）** | 有 | SAF 持久授权 + `readAt` 按区间读（优先于 picker） |
| 手机 Chrome 132+ / 三星 29+ | 有 | 同一个 picker |
| 微信等内置 WebView / 老浏览器 | 没有 | 没有 picker 也没有 SAF，只能导入（抽词头音约 369 MB） |

涉及三处，**缺一处就退回「只能导入」**——不会崩，但按钮不出现：

1. `mobile/android-patches/MoreadNativePlugin.java`：`linkFile` / `readAt` / `releaseLink` / `querySize`
2. `app/17i-native-source.js`：`canLink` / `linkFile` / `linkedSource`
3. `app/17g3-mdd-link.js`：`mddLinkImport` 认 `{ kind: 'native' }`

**出包后必须在真机上验这四条**（沙箱里编不了 Java，CI 只保证编译过，不保证能读）：

- [ ] 设置里能看到「链接 .mdd」，点了弹系统文件选择器
- [ ] 选一个 1 GB 以上的 .mdd，几秒内出「已链接 N 条」；手机存储占用**不变**
- [ ] 点词能听到真人发音（说明 `readAt` 走通了）
- [ ] 杀掉 App 再打开，点词还是真人发音（说明持久授权真的记下来了）

第 4 条最容易漏：`takePersistableUriPermission` 没生效时，当场一切正常，
只有重启后才变成「点了没声音」——那时候已经想不起来是这一步的问题。

另外：取消链接要调 `releaseLink` 把授权还回去。Android 每个包只给 128 条持久授权，
用满之后 `take` 是**静默失败**（不抛不报），表现就是「明明选了文件，下次打开读不到」。

### 两个只在 Android 上才犯的错（2026-11-18 都踩过）

**accept 不能写扩展名。** `accept=".mdx,.mdd"` 在 WebView 里会交给 Capacitor 的
`BridgeWebChromeClient.showFilePicker`，它用 `MimeTypeMap.getMimeTypeFromExtension`
查每个后缀，**查不到的直接丢掉**。.mdx / .mdd 系统根本不认识 → 传下去只剩 text/csv
那几个 → 选择器里所有文件变灰，一个都点不动，用户看到的就是「选不了文件」。
现在 accept 写 `*/*`，格式判断挪到 17c-dict-05 的 `dictNameOk`。

**bindDictLink 不能立即调用。** app.js 是**一个 IIFE**，片段按文件名排序，17c 在 17i
前面。`defer` 脚本执行时 `document.readyState` 已经是 `interactive`，那条
`readyState === 'loading'` 的分支走不到，`bindDictUi()` 会立刻跑 —— 那一刻
`window.MoreadNative`（17i 才定义）还不存在，「这台机器能不能链接」永远判成否，
手机上两个链接按钮一次都不出现。现在让出一拍（`setTimeout(dictBindLink, 0)`）。

**querySize 不能只信 OpenableColumns。** 有些 provider 这一列给 null，拿不到大小
等于「选上了却读不了」（17i 会抛「读不到大小」）。现在用 `getStatSize()` 兜底。

**选择器要指定初始目录。** .mdd 是几个月前下的文件，从来不在 SAF 的「最近使用」里。
不给 `EXTRA_INITIAL_URI` 时读者一进来看到空列表，会以为这里不允许选 .mdd。

### .mdx 的链接：这一版只走 picker，壳内 SAF 还没接（build-40）

build-40 修了一个从上线起就存在的 bug：链接成功后 `meta.mode` 写的是 `'link'`，
而查词分派只认 `'direct'` → 掉进条目表（链接模式早已清空）→ **整本词典一个字都查不到**，
界面只说「没收录」。现在 `direct` 与 `link` 都走直读。

链接入口的实际范围，判据是「有没有 picker」，不是「是不是手机」：

| 环境 | 入口 | 机制 |
|---|---|---|
| 桌面 Chrome / Edge 86+ | 有 | 文件句柄 |
| 手机 Chrome 132+ / 三星 29+ | 有 | 同一个 picker |
| APK + WebView 132+ | 有 | 同一个 picker（这一版之前会被「手机端只能链接语音库」挡住） |
| APK + 老 WebView | 没有 | 提示用「导入词库」 |
| Safari / Firefox | 没有 | 提示用「导入词库」 |

壳内 SAF 的 `.mdx` 路径**仍未实现**（解析层已就绪，缺三处接线），见 ADR-013。

另外两条行为变化：换另一本词库前会先确认（同名同大小视为重新链接，不问）；
链接成功后会清掉旧的展开条目（从 CSV/TXT 切过来的情况）。


