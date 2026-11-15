package com.moread.reader;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * 墨读原生桥：Web 做不到的四件事。
 *
 * 契约见 docs/android-plan.md 第三节；JS 侧由 native.js 从
 * Capacitor.Plugins.MoreadNative 自动组装 window.MoreadNative，本类不注入 JS。
 *
 * 方法名必须与 native.js 里 call('xxx') 一一对应。
 */
@CapacitorPlugin(
        name = "MoreadNative",
        permissions = {
                /* 通知权限必须登记成别名：Capacitor 8 的插件基类已经没有
                   requestPermissions(String[], int) 那套旧接口了（那是 Capacitor 2 的写法），
                   现在只能走 requestPermissionForAlias + @PermissionCallback。 */
                @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
        })
public class MoreadNativePlugin extends Plugin {

    private static final String KEYSTORE = "AndroidKeyStore";
    /* v2：2026-11-15 换别名，让设备上换一套**重新生成**的密钥。
       旧钥匙是按「只授权 SHA-256」建的，而 OAEP 的 MGF1 老通路会带 SHA-1，
       Keystore 在 init 阶段就拒（真机：InvalidKeyException: Keystore operation failed）。
       换别名 = 不改旧条目、不删数据，新参数下一次 ensure 自然生效。 */
    private static final String KEY_ALIAS = "moread_device_rsa_v2";
    private static final String NOTIF_CHANNEL = "moread";
    private static final String IMPORT_DIR = "imports";

    // ------------------------------------------------------------------ 通知

    @PluginMethod
    public void notify(PluginCall call) {
        String title = call.getString("title", "墨读");
        String body = call.getString("body", "");
        String tag = call.getString("tag", "moread");
        int id = call.getInt("id", 0);

        Context ctx = getContext();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            /* 先要权限，拿到之后回到 onNotificationPermission 把这一条补发出去。
               call 里还带着 title / body，所以回调里直接再调一次 notify 就行 —— 不必另存字段。 */
            requestPermissionForAlias("notifications", call, "onNotificationPermission");
            return;
        }

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(NOTIF_CHANNEL, "墨读", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("借阅到期、预约到书提醒");
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }

        Intent tap = new Intent(ctx, getActivity().getClass());
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, id, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification n = new Notification.Builder(ctx, NOTIF_CHANNEL)
                /* 小图标必须是**单色剪影**。原来塞的是应用图标（自适应图标），
                   Android 8+ 画不出来，通知栏上会是个白方块。这里先用系统自带的小闹钟，
                   等自己那套图标做了再换成项目里的单色版。 */
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        try {
            nm.notify(tag, id, n);
            call.resolve(ok(true, null));
        } catch (SecurityException e) {
            call.resolve(ok(false, "no-permission"));
        }
    }

    /** 权限回调：授予了就补发刚才那一条，没授予就照实回 no-permission（Web 侧据此换文案）。 */
    @PermissionCallback
    private void onNotificationPermission(PluginCall call) {
        if (getPermissionState("notifications") == PermissionState.GRANTED) {
            notify(call);
        } else {
            call.resolve(ok(false, "no-permission"));
        }
    }

    @PluginMethod
    public void scheduleDailyReminder(PluginCall call) {
        /* Android 13+ 没授权就排闹钟，闹钟会照常触发、通知被系统丢掉 ——
           验收时看到的是「排成功了但什么都没发生」。所以排之前先把权限要到。
           和 notify 一样走别名 + @PermissionCallback。 */
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "onReminderPermission");
            return;
        }
        scheduleDailyReminderNow(call);
    }

    @PermissionCallback
    private void onReminderPermission(PluginCall call) {
        if (getPermissionState("notifications") == PermissionState.GRANTED) {
            scheduleDailyReminderNow(call);
        } else {
            call.resolve(ok(false, "no-permission"));
        }
    }

    private void scheduleDailyReminderNow(PluginCall call) {
        int hour = call.getInt("hour", 20);
        int minute = call.getInt("minute", 0);
        String title = call.getString("title", "墨读");
        String body = call.getString("body", "有书到期了，进来看看");

        Context ctx = getContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.putExtra("title", title);
        intent.putExtra("body", body);
        /* hour/minute 要带上：闹钟是一次性的，响过之后由 ReminderReceiver 按这两个值
           算出下一次再排（见那边的注释）。不带的话明天就断了。 */
        intent.putExtra("hour", hour);
        intent.putExtra("minute", minute);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, minute);
        cal.set(java.util.Calendar.SECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_MONTH, 1);

        if (Build.VERSION.SDK_INT >= 23) {
            /* setAndAllowWhileIdle 是**一次性**闹钟（doze 下也尽量准），响完就没了。
               每天重复靠 ReminderReceiver 自己续排，不能用 setRepeating ——
               API 19 起 setRepeating 会被系统打散成不精确窗口，反而错过时间点。 */
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            am.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
        }
        call.resolve(ok(true, null));
    }

    @PluginMethod
    public void cancelDailyReminder(PluginCall call) {
        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(getContext(), ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                getContext(), 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        am.cancel(pi);
        pi.cancel();
        call.resolve(ok(true, null));
    }

    // ------------------------------------------------------------------ 文件导入

    @PluginMethod
    public void pickFile(PluginCall call) {
        String accept = call.getString("accept", "application/epub+zip");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(accept.indexOf('/') > 0 ? accept : "application/epub+zip");
        /* 必须用「回调方法名」这个重载（Capacitor 3+ 的 AndroidX 通路）。
           用 int requestCode 那个老重载时，回执要靠 Bridge.getPluginWithRequestCode
           按 @CapacitorPlugin(requestCodes = …) 反查插件；我们没登记，那个注解也只为
           旧插件保留 —— 结果就是回执永远送不到插件，pickFile 的 Promise 一直挂着：
           自检面板停在「文件选择（SAF）：测试中…」，后面两项一项都不跑
           （2026-11-13 真机实测，构建 11）。 */
        startActivityForResult(call, intent, "pickFileResult");
    }

    @ActivityCallback
    private void pickFileResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.resolve(ok(false, "cancelled"));
            return;
        }
        try {
            Uri uri = data.getData();
            Context ctx = getContext();
            String name = queryName(ctx, uri);
            File dir = new File(ctx.getCacheDir(), IMPORT_DIR);
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, java.util.UUID.randomUUID().toString() + "-" + safeName(name));

            long size = 0;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) { fos.write(buf, 0, n); size += n; }
            }

            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("name", name);
            r.put("size", size);
            r.put("filePath", out.getAbsolutePath()); // JS 侧用 Capacitor.convertFileSrc 转成可 fetch 地址
            call.resolve(r);
        } catch (Exception e) {
            call.resolve(ok(false, "read-failed"));
        }
    }

    @PluginMethod
    public void releaseTempFile(PluginCall call) {
        String path = call.getString("path", "");
        if (!path.isEmpty()) {
            File f = new File(path);
            if (f.exists()) f.delete(); // 忽略失败
        }
        call.resolve(ok(true, null));
    }

    // ------------------------------------------------------------------ 设备私钥（Keystore）

    @PluginMethod
    public void secureKeyEnsure(PluginCall call) {
        try {
            boolean created = !existsInternal();
            if (created) {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE);
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_ENCRYPT)
                        .setKeySize(2048)
                        /* SHA-1 也要授权：某些 ROM 的 Keystore 在 OAEP 上会按
                           「变换名里隐含的 MGF1」来校验摘要，而老通路隐含 SHA-1。
                           我们不主动用 SHA-1（OAEP 参数里写的是 SHA-256/SHA-256），
                           这里只是不再让引擎在 init 阶段就把操作拒掉。 */
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                        .setUserAuthenticationRequired(false)
                        .build();
                kpg.initialize(spec);
                kpg.generateKeyPair();
            }
            PublicKey pub = getPublicKey();
            X509EncodedKeySpec spki = new X509EncodedKeySpec(pub.getEncoded());
            String spkiB64 = Base64.encodeToString(spki.getEncoded(), Base64.NO_WRAP);

            java.security.interfaces.RSAPublicKey rsa = (java.security.interfaces.RSAPublicKey) pub;
            BigInteger n = rsa.getModulus();
            BigInteger e = rsa.getPublicExponent();

            JSObject r = new JSObject();
            r.put("publicKeySpkiB64", spkiB64);
            r.put("nHex", n.toString(16));
            r.put("eHex", e.toString(16));
            r.put("created", created);
            call.resolve(r);
        } catch (Exception ex) {
            call.resolve(ok(false, "keystore-error"));
        }
    }

    @PluginMethod
    public void secureKeyExists(PluginCall call) {
        JSObject r = new JSObject();
        r.put("exists", existsInternal());
        call.resolve(r);
    }

    /* 能力探针：这把钥匙在这台机器上到底能干什么。
       2026-11-15 真机（OnePlus PJH110 / sdk=35）连 OAEP 的 init 都过不去，
       面板上只有一句 unwrap-failed，判断不了是钥匙坏、摘要没授权还是 ROM 不支持。
       所以这里把「本地加密 → 本地解密」四组各跑一遍：pkcs1（钥匙是否健康）、
       OAEP-SHA256、OAEP-SHA256+MGF1-SHA1、OAEP-SHA1。结果原样回给前端。 */
    @PluginMethod
    public void secureKeyProbe(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ok", true);
        PublicKey pub = null;
        PrivateKey priv = null;
        try { pub = getPublicKey(); } catch (Exception e) { r.put("pub", e.getClass().getSimpleName()); }
        try { priv = getPrivateKey(); } catch (Exception e) { r.put("priv", e.getClass().getSimpleName()); }
        try {
            android.security.keystore.KeyInfo ki = (android.security.keystore.KeyInfo)
                    KeyFactory.getInstance("RSA", KEYSTORE).getKeySpec(priv, android.security.keystore.KeyInfo.class);
            StringBuilder d = new StringBuilder();
            for (String s : ki.getDigests()) { if (d.length() > 0) d.append('+'); d.append(s); }
            r.put("info", "digest=" + d + ", hw=" + ki.isInsideSecureHardware());
        } catch (Exception e) {
            r.put("info", "info-" + e.getClass().getSimpleName());
        }
        if (pub != null && priv != null) {
            r.put("pkcs1", probeRoundtrip(pub, priv, null));
            r.put("oaep256", probeRoundtrip(pub, priv, OAEP_SHA256));
            r.put("oaep256s1", probeRoundtrip(pub, priv,
                    new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)));
            r.put("oaep1", probeRoundtrip(pub, priv, OAEP_SHA1));
        }
        call.resolve(r);
    }

    /** 本地加密 + 解密一条龙：只看这把钥匙能不能完成这次操作，不掺 WebCrypto。 */
    private static String probeRoundtrip(PublicKey pub, PrivateKey priv, OAEPParameterSpec spec) {
        String tf = spec == null ? "RSA/ECB/PKCS1Padding" : "RSA/ECB/OAEPPadding";
        try {
            byte[] msg = "moread-probe".getBytes("UTF-8");
            Cipher enc = Cipher.getInstance(tf);
            if (spec == null) enc.init(Cipher.ENCRYPT_MODE, pub);
            else enc.init(Cipher.ENCRYPT_MODE, pub, spec);
            byte[] ct = enc.doFinal(msg);
            Cipher dec = Cipher.getInstance(tf);
            if (spec == null) dec.init(Cipher.DECRYPT_MODE, priv);
            else dec.init(Cipher.DECRYPT_MODE, priv, spec);
            byte[] pt = dec.doFinal(ct);
            return "moread-probe".equals(new String(pt, "UTF-8")) ? "ok" : "mismatch";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        }
    }

    /* OAEP 的 MGF1 必须显式写成 SHA-256。
       JCE 里 "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" 这个名字只约定「摘要用 SHA-256」，
       **MGF1 的默认值仍是 SHA-1**；而 WebCrypto 的 RSA-OAEP(hash: SHA-256) 摘要与 MGF1
       都是 SHA-256。两边 MGF1 不一致，解出来必然是填充错误；再加上这把 Keystore 密钥
       建的时候 setDigests(SHA256)，连 init 这一步都会被 Keystore 拒掉。
       2026-11-15 真机：ensure 与加密都成功，unwrap 直接 unwrap-failed —— 就是这个。 */
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private static final OAEPParameterSpec OAEP_SHA1 = new OAEPParameterSpec(
            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);

    /* 解封装按四条逐一试：同样是 OAEP，各家 ROM 对「变换名 + 摘要 + MGF1」的接受程度不一样。
       · OAEPPadding              —— 完全由 OAEPParameterSpec 决定摘要与 MGF1
       · …WithSHA-256AndMGF1Padding —— 名字里隐含一套默认参数，部分机型只认这条
       前两条用 SHA-256（和 WebCrypto 默认一致）；后两条退到 SHA-1 —— 2026-11-15 真机
       （sdk=35 / OnePlus PJH110）连 init 都过不去（InvalidKeyException），只能把 SHA-1
       这一档也备上：真到那一步，前端把 hash 换成 SHA-1 就能通，不必再改壳。 */
    private static final String[] UNWRAP_TRANSFORMS = {
            "RSA/ECB/OAEPPadding",
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
            "RSA/ECB/OAEPPadding",
            "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
    };
    private static final OAEPParameterSpec[] UNWRAP_SPECS = {
            OAEP_SHA256, OAEP_SHA256, OAEP_SHA1, OAEP_SHA1
    };

    @PluginMethod
    public void secureKeyUnwrap(PluginCall call) {
        String wrappedB64 = call.getString("wrappedKeyB64", "");
        byte[] wrapped;
        try {
            wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP);
        } catch (Exception e) {
            JSObject o = ok(false, "bad-input");
            o.put("detail", e.getClass().getSimpleName());
            call.resolve(o);
            return;
        }

        StringBuilder errs = new StringBuilder();
        for (int i = 0; i < UNWRAP_TRANSFORMS.length; i++) {
            String t = UNWRAP_TRANSFORMS[i];
            try {
                Cipher cipher = Cipher.getInstance(t);
                cipher.init(Cipher.DECRYPT_MODE, getPrivateKey(), UNWRAP_SPECS[i]);
                byte[] raw = cipher.doFinal(wrapped);
                JSObject r = new JSObject();
                r.put("ok", true);
                r.put("rawKeyB64", Base64.encodeToString(raw, Base64.NO_WRAP));
                r.put("via", t);   // 哪条变换通的不影响结论，但排查时值钱
                call.resolve(r);
                return;
            } catch (Exception e) {
                if (errs.length() > 0) errs.append(" | ");
                errs.append(t).append(" → ")
                        .append(e.getClass().getSimpleName()).append(": ")
                        .append(String.valueOf(e.getMessage()));
            }
        }
        /* 两条都不通才回失败，并把每条的错误原样带上：
           只回 "unwrap-failed" 的代价已经付过一次（又打了一个包）。
           机型与 SDK 一起带上 —— 这类问题几乎都是某个 ROM 的 Keystore 行为差异，
           没有这两个数字，下一轮还是得猜。 */
        JSObject o = ok(false, "unwrap-failed");
        o.put("detail", "sdk=" + Build.VERSION.SDK_INT + ", model=" + Build.MODEL + " | " + errs);
        call.resolve(o);
    }

    @PluginMethod
    public void secureKeyReset(PluginCall call) {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(KEY_ALIAS);
            call.resolve(ok(true, null));
        } catch (Exception e) {
            call.resolve(ok(false, "keystore-error"));
        }
    }

    // ------------------------------------------------------------------ 系统栏 / 应用

    @PluginMethod
    public void setSystemBars(PluginCall call) {
        Activity a = getActivity();
        if (a == null) { call.resolve(ok(false, "no-activity")); return; }
        String statusColor = call.getString("statusBarColor", "#f4f0e7");
        String navColor = call.getString("navigationBarColor", "#f4f0e7");
        boolean lightIcons = call.getBoolean("lightIcons", false);
        a.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 21) {
                a.getWindow().setStatusBarColor(Color.parseColor(statusColor));
                a.getWindow().setNavigationBarColor(Color.parseColor(navColor));
            }
            int flags = a.getWindow().getDecorView().getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= 23) {
                if (lightIcons) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            a.getWindow().getDecorView().setSystemUiVisibility(flags);
            call.resolve(ok(true, null));
        });
    }

    @PluginMethod
    public void exitApp(PluginCall call) {
        Activity a = getActivity();
        if (a != null) a.finishAffinity();
        call.resolve(ok(true, null));
    }

    @PluginMethod
    public void openExternal(PluginCall call) {
        String url = call.getString("url", "");
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve(ok(true, null));
        } catch (Exception e) {
            call.resolve(ok(false, "no-handler"));
        }
    }

    // ------------------------------------------------------------------ 工具

    private boolean existsInternal() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            return ks.containsAlias(KEY_ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    private PrivateKey getPrivateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        return (PrivateKey) ks.getKey(KEY_ALIAS, null);
    }

    private PublicKey getPublicKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        return ks.getCertificate(KEY_ALIAS).getPublicKey();
    }

    private static String queryName(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return "book.epub";
    }

    private static String safeName(String name) {
        if (name == null) return "book.epub";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static JSObject ok(boolean ok, String reason) {
        JSObject o = new JSObject();
        o.put("ok", ok);
        if (reason != null) o.put("reason", reason);
        return o;
    }
}
