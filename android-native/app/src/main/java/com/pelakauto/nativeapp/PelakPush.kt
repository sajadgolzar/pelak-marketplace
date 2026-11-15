package com.pelakauto.nativeapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The public Firebase client identifiers are not a service-account key. */
internal object PelakPush {
    const val CHANNEL_ID = "pelak_native_alerts"
    private const val PREF = "pelak_native_push"
    private const val TOKEN = "last_token"
    fun initialize(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .build()
            FirebaseApp.initializeApp(context.applicationContext, options)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "پیام‌ها و آگهی‌های پلاک", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    fun token(context: Context, onResult: (String?) -> Unit) {
        try {
            initialize(context)
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { value ->
                    if (value.isNotBlank()) {
                        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .edit().putString(TOKEN, value).apply()
                    }
                    onResult(value.takeIf(String::isNotBlank))
                }
                .addOnFailureListener { onResult(null) }
        } catch (_: Exception) { onResult(null) }
    }
    fun lastToken(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(TOKEN, null)
    fun discardLocalToken(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(TOKEN).apply()
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }
}

class PelakMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences("pelak_native_push", Context.MODE_PRIVATE)
            .edit().putString("last_token", token).apply()
        // FCM may rotate tokens while the app is backgrounded. Register the new
        // token for the currently signed-in account instead of waiting for a login.
        val session = PelakSessionStore(this).load() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { PelakApi().registerPush(session, token) }
            // If the session has expired or the network is down, foreground login
            // registration will retry when the access token changes.
        }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // An unregister request may race with an already queued FCM delivery.
        // Never display account-specific alerts after local sign-out.
        val updateNotice = message.data["kind"] == "app_update"
        if (!updateNotice && PelakSessionStore(this).load() == null) return
        // An update notice must point only to our pinned site. The app never opens
        // a URL supplied by a push payload.
        if (updateNotice) {
            val announcedCode = message.data["version_code"]?.toIntOrNull() ?: return
            if (announcedCode <= BuildConfig.VERSION_CODE) return
        }
        PelakPush.initialize(this)
        val title = if (updateNotice) "نسخه جدید پلاک آماده است"
            else message.notification?.title ?: "اعلان پلاک"
        val body = if (updateNotice) "برای دریافت نسخه جدید، وارد پلاک شو و بررسی به‌روزرسانی را بزن."
            else message.notification?.body ?: "برای بررسی پیام جدید به پلاک برگرد."
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val open = PendingIntent.getActivity(this, id, launch, flags)
        val notification = NotificationCompat.Builder(this, PelakPush.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(80))
            .setContentText(body.take(250))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1000)))
            .setAutoCancel(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        try { NotificationManagerCompat.from(this).notify(id, notification) }
        catch (_: SecurityException) { /* Android 13+ notifications can be denied. */ }
    }
}
