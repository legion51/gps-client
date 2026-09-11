package org.traccar.family;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class FamilyNotifications {
    static final int REQUEST = 6101;
    static final int ACTIVE = 6102;
    private static final String CHANNEL = "family_requests";

    static boolean available(Context context) {
        builder(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26
                || manager.getNotificationChannel(CHANNEL).getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static Notification.Builder builder(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Семейный доступ", NotificationManager.IMPORTANCE_HIGH));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        return builder.setSmallIcon(android.R.drawable.ic_dialog_info).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true);
    }

    static void request(Context context, FamilyRequest request) throws Exception {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!available(context)) {
            request.state(context, "failed"); return;
        }
        Intent intent = request.intent(context, ConsentActivity.class);
        PendingIntent open = PendingIntent.getActivity(context, REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = request.kind.equals("audio") ? "Запрос звука вокруг" : request.kind.equals("screenshot")
                ? "Запрос снимка экрана" : "Запрос громкого сигнала";
        Notification.Builder builder = builder(context).setContentTitle(title)
                .setContentText("Нажмите, чтобы разрешить или отклонить запрос")
                .setContentIntent(open).setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(Math.max(1, request.expires - System.currentTimeMillis()));
        manager.notify(REQUEST, builder.build());
    }

    static Notification active(Context context, String kind) {
        PendingIntent stop = PendingIntent.getService(context, ACTIVE,
                new Intent(context, CaptureService.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder(context).setContentTitle(kind.equals("audio") ? "Передаётся звук микрофона"
                        : kind.equals("screenshot") ? "Создаётся снимок экрана" : "Звучит сигнал поиска")
                .setContentText("Семейный доступ · нажмите «Остановить» для завершения")
                .setOngoing(true).addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Остановить", stop).build()).build();
    }
}
