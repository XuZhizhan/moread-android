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
