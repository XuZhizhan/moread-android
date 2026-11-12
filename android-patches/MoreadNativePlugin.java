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

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
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
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

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
    private static final String KEY_ALIAS = "moread_device_rsa";
    private static final String NOTIF_CHANNEL = "moread";
    private static final String IMPORT_DIR = "imports";
    private static final int PICK_FILE_REQ = 4001;

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
                .setSmallIcon(getContext().getApplicationInfo().icon)
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
        int hour = call.getInt("hour", 20);
        int minute = call.getInt("minute", 0);
        String title = call.getString("title", "墨读");
        String body = call.getString("body", "有书到期了，进来看看");

        Context ctx = getContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.putExtra("title", title);
        intent.putExtra("body", body);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, minute);
        cal.set(java.util.Calendar.SECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_MONTH, 1);

        if (Build.VERSION.SDK_INT >= 23) {
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
        startActivityForResult(call, intent, PICK_FILE_REQ);
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

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE_REQ) return;
        PluginCall saved = getSavedCall();
        if (saved == null) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            saved.resolve(ok(false, "cancelled"));
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
            saved.resolve(r);
        } catch (Exception e) {
            saved.resolve(ok(false, "read-failed"));
        }
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
                        .setDigests(KeyProperties.DIGEST_SHA256)
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

    @PluginMethod
    public void secureKeyUnwrap(PluginCall call) {
        String wrappedB64 = call.getString("wrappedKeyB64", "");
        try {
            byte[] wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, getPrivateKey());
            byte[] raw = cipher.doFinal(wrapped);
            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("rawKeyB64", Base64.encodeToString(raw, Base64.NO_WRAP));
            call.resolve(r);
        } catch (Exception e) {
            call.resolve(ok(false, "unwrap-failed"));
        }
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
