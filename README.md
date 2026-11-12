# 墨读 · Android 壳

Capacitor 8.5.1 包一层 WebView，加载线上 Web（`https://moread-66776.app.workbuddy.host`），
业务、阅读器、加密解密全部照旧 —— 壳只补 Web 做不到的四件事：

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

Web 是唯一部署点。壳启动后直接加载线上域名，**Origin 不变**，登录 / 借阅 / 许可 / 对象存储全部照旧。
代价是断网打不开（已下载的书仍在本地设备上）。

因此：**改界面、改阅读器、修 Web 上的 bug，都不需要重新出包** —— 打开 App 就是最新版。
只有壳本身（插件、权限、图标、targetSdk）变了才需要再构建一次。

## 目录

```
mobile/
├── package.json              Capacitor 8.5.1（锁版本）
├── capacitor.config.json     appId / appName / 远程 URL / https scheme
├── scripts/build.sh          setup / debug / release 一键构建
├── android-patches/          注入进 android 平台的源码
│   ├── MoreadNativePlugin.java   四个原生能力（431 行）
│   ├── ReminderReceiver.java     每日提醒的闹钟接收者
│   └── MainActivity.java         注册插件
└── android/                  cap add android 生成（.gitignore，CI 里现生成）
dist/index.html               占位：server.url 模式下不参与运行
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
