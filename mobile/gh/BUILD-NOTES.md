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

## 二、开屏资源要同步进 precache 清单

`index.html` 里凡是有 `<img src>` / `<link href>` 的本地文件，
`build.mjs` 的 `precache` 列表里必须有它 —— 否则 `fetch-web.mjs`
的自检会发现「index.html 引用了清单里没有的文件」并**直接判死整条构建**。

- 字标 `assets/launch/wordmark-*.svg`：**已进**（各 10KB，每次冷启动必用）
- 底图 `assets/launch/splash-*.jpg`：**故意不进**（1.5MB，会让首装下载
  从 2.1MB 抬到 3.6MB），走 SW 运行时缓存

`fetch-web.mjs` 现在会在缺失时先补抓、抓不到才失败，但补抓会打印警告 ——
看到警告就去补 `build.mjs` 的清单，别让它长期缺着。

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

## 四、tag 从 Releases 推导，不用 GITHUB_RUN_NUMBER

`ci.sh` 取「上一个 release 的编号 +1」。run number 是累计跑数，
失败一次就永久跳号，而人是在按编号比对版本的。

重跑同名 tag 走 `gh release upload --clobber`，不会重号。

## 五、大改动走分支 + PR，不要直推 main

`android.yml` 是 `on: push: branches: [main]`，推 main 立刻烧一次 CI。
推到其他分支**不触发任何 workflow**，可以先在那里用 raw 校验内容完整，
再开 PR 合进 main。合进 main 那一下才真正触发构建。
