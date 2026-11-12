package com.pelakauto.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Displays a native Android notification for foreground FCM messages.
 * Background notification payloads are displayed by the Firebase SDK.
 */
public final class PelakMessagingService extends FirebaseMessagingService {
    public static final String CHANNEL_ID="pelak_updates";
    public static void createChannel(android.content.Context context) {
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager manager=(NotificationManager)
                context.getSystemService(NOTIFICATION_SERVICE);
            if(manager!=null){
                NotificationChannel channel=new NotificationChannel(
                    CHANNEL_ID,"اعلان‌های پلاک",NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("پیام‌ها و وضعیت آگهی‌های پلاک");
                manager.createNotificationChannel(channel);
            }
        }
    }
    @Override public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        createChannel(this);
        String title="اعلان جدید پلاک";
        String body="برای مشاهده جزئیات وارد پلاک شوید.";
        if(message.getNotification()!=null){
            if(message.getNotification().getTitle()!=null)
                title=message.getNotification().getTitle();
            if(message.getNotification().getBody()!=null)
                body=message.getNotification().getBody();
        }
        // Only the built-in app destination may be opened; ignore untrusted URL data.
        Intent open=new Intent(this,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // An allowlisted "kind" is interpreted by MainActivity; never trust remote URLs.
        String kind=message.getData().get("kind");
        if(kind!=null)open.putExtra("kind",kind);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0);
        // Unique request codes keep older notification destinations intact.
        int notificationId=(int)(System.currentTimeMillis() & 0x7fffffff);
        PendingIntent pending=PendingIntent.getActivity(this,notificationId,open,flags);
        NotificationCompat.Builder notification=new NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pelak_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(this).notify(notificationId,notification.build());
        }catch(SecurityException ignored){
            // Android 13+ user might have disabled notification permission.
        }
    }
    @Override public void onNewToken(String token) {
        super.onNewToken(token);
        // Registration occurs through the authenticated website on next app open.
        // Do not send unassociated tokens from the background service.
    }
}
