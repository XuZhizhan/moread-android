package com.moread.reader;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 每日提醒的闹钟接收者：由 AlarmManager 触发，发一条本地通知，然后把下一次排上。
 * 只做「提醒」，不执行借阅 sweep——真正的 sweep 仍在 Web 打开时跑。
 *
 * 为什么要自己续排：插件那边用的是 setAndAllowWhileIdle，那是**一次性**闹钟
 * （doze 下也尽量准的代价就是只能响一次）。不在这里补下一天，"每日"提醒就只会有第一天。
 */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        if (title == null) title = "墨读";
        if (body == null) body = "有书到期了，进来看看";
        int hour = intent.getIntExtra("hour", 20);
        int minute = intent.getIntExtra("minute", 0);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("moread", "墨读", NotificationManager.IMPORTANCE_DEFAULT));
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;   // 没授权就什么都不做；下一次续排也不排，等用户重新开
        }

        Intent tap = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pi = PendingIntent.getActivity(
                context, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification n = new Notification.Builder(context, "moread")
                /* 单色剪影，不能用应用图标（自适应图标在通知栏画成白方块） */
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try { nm.notify("moread-reminder", 1, n); } catch (SecurityException ignored) {}

        /*
         * 续排明天：用**同一个** PendingIntent（requestCode 0、同一个 intent），
         * 这样 cancelDailyReminder 里的 am.cancel 依然能把这串闹钟取消掉。
         * 时间按 hour/minute 重新算，而不是 now + 24h —— 后者遇到夏令时/改时区会漂。
         */
        java.util.Calendar next = java.util.Calendar.getInstance();
        next.set(java.util.Calendar.HOUR_OF_DAY, hour);
        next.set(java.util.Calendar.MINUTE, minute);
        next.set(java.util.Calendar.SECOND, 0);
        next.set(java.util.Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        PendingIntent again = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), again);
        }
    }
}
