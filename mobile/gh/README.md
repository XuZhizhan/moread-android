# 悦读星球 · Android 壳

Capacitor 8.5.1 包一层 WebView，**加载 APK 内打包的前端**（构建时按 precache-manifest
从线上抓取，见 `scripts/fetch-web.mjs`），业务、阅读器、加密解密全部照旧 ——
壳只补 Web 做不到的四件事：

| 能力 | 实现 | Web 侧契约 |
|---|---|---|
| 系统通知 | NotificationChannel + NotificationManager | `native.notify` |
| 每日提醒 | AlarmManager + ReminderReceiver（只提醒，不 sweep） | `native.scheduleDailyReminder` |
| 本地文件导入 | SAF（ACTION_OPEN_DOCUMENT）→ 缓存文件 → `convertFileSrc` | `native.pickFile` |
| 设备私钥 | Android Keystore（RSA-2048 / OAEP-SHA256，不可导出） | `native.secureKey` |

## 拿安装包

两种都行：

1. **Releases** 页 → 最新一次 `build-N` → 下载 `moread-debug.apk`
2. **Actions** → 最新一次 `android-debug` → 页面底部 artifact

手机上安装时需要允许「安装未知来源应用」。

> 这是 **debug 签名**：以后若换正式签名包，必须先卸载再装（数据会丢，介意就先用着）。

## 为什么壳这么小

前端只有一个发布点（线上站点），但 APK **不是**打开线上网页 —— 它加载的是构建时抓进包里的
那份 dist。这样冷启动不用先下 700KB 网页，代价是**前端改了必须重出包**：

| 改了什么 | 要不要重出包 |
|---|---|
| 界面、阅读器、Web 上的 bug | 要（包内 dist 是死的，打开 App 不会变成最新版） |
| 壳本身（插件、权限、图标、targetSdk） | 要 |

发布顺序因此是硬的：**先发 Web，再出包**。反过来会打出一个「版本号正常、界面正常、
只有刚修的那处不生效」的包 —— 这种误判在 build 号上完全看不出来（build-30 就是这么废的）。

### 换 origin 的代价（build-31 起）

去掉 `server.url` 之后，页面 origin 从站点域名变成 `https://localhost`，**所有请求从同源变成跨域**。
跨域会先走 CORS 预检，服务端只放行一份固定的头白名单；请求里多带一个白名单外的头，
浏览器就把**整个请求拒发**，前端只能拿到 `TypeError: Failed to fetch` ——
表现是「网络明明是好的，某个功能就是不通」，极容易往网络故障上误判。

已经踩过一次：云存储 SDK 默认给每个存储请求挂 `x-client-info: storage-js/2.x`，
它不在白名单里，于是封面一片空白、点下载报「网络连接中断」，而借阅列表
（只带 content-type + 访问密钥两个白名单内的头）一切正常。修法在 Web 侧的 `cloud.js`
（建好云客户端后把白名单外的头摘掉）。

**以后要往前端请求里加自定义头，先做一次预检核对：**

```bash
curl -s -o /dev/null -D - -X OPTIONS \
  'https://moread-66776.app.workbuddy.host/.cloud/storage/object/runtime/x' \
  -H 'Origin: https://localhost' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: <要加的那个头>'
```

回包的 `Access-Control-Allow-Headers` 里没列出它，就不能加 —— 同源时代看不出问题，
改成包内加载后才会突然全线失败。

## 目录

```
mobile/
├── package.json              Capacitor 8.5.1（锁版本）
├── capacitor.config.json     appId / appName / https scheme（无 server.url = 走包内 dist）
├── scripts/build.sh          setup / debug / release 一键构建
├── android-patches/          注入进 android 平台的源码
│   ├── MoreadNativePlugin.java   四个原生能力（431 行）
│   ├── ReminderReceiver.java     每日提醒的闹钟接收者
│   └── MainActivity.java         注册插件
└── android/                  cap add android 生成（.gitignore，CI 里现生成）
dist/                         构建时从线上抓的前端（仓库里是占位页，CI 里被整体替换）
```

## 本地构建（可选）

需要 JDK 17+ 与 Android SDK（compileSdk 36），然后：

```bash
cd mobile
npm install
bash scripts/build.sh debug
# 产物：mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

## 已知边界

- **不支持真后台同步**：WorkManager 跑不了 WebView 里的 JS，原生也读不到 IndexedDB。
  这版只发每日提醒，真正的到期 / 预约扫描仍在打开 App 时执行。
- **设备私钥还没切到 Keystore**：原生侧已就绪，服务端签发仍用 WebCrypto 公钥，
  链路切换（登记设备公钥 + OAEP-SHA256 封装）是后续批次。
- **targetSdk 36 / minSdk 24**：覆盖 Android 7.0 以上。
