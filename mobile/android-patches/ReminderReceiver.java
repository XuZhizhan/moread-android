package com.moread.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 每日提醒的闹钟接收者：由 AlarmManager 触发，发一条本地通知。
 * 只做「提醒」，不执行借阅 sweep——真正的 sweep 仍在 Web 打开时跑。
 */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        if (title == null) title = "墨读";
        if (body == null) body = "有书到期了，进来看看";

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("moread", "墨读", NotificationManager.IMPORTANCE_DEFAULT));
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent tap = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                context, 0, tap,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));

        Notification n = new Notification.Builder(context, "moread")
                .setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try { nm.notify("moread-reminder", 1, n); } catch (SecurityException ignored) {}
    }
}
