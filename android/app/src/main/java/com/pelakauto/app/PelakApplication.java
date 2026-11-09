package com.pelakauto.app;

import android.app.Application;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

/** Initialize the public Firebase Android config only when the owner supplied it.
 * Never include Firebase service-account credentials in the app.
 */
public final class PelakApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        PelakMessagingService.createChannel(this);
        if (BuildConfig.FIREBASE_APP_ID.isEmpty() ||
            BuildConfig.FIREBASE_API_KEY.isEmpty() ||
            BuildConfig.FIREBASE_PROJECT_ID.isEmpty() ||
            BuildConfig.FIREBASE_SENDER_ID.isEmpty()) return;
        if (!FirebaseApp.getApps(this).isEmpty()) return;
        try {
            FirebaseOptions options = new FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build();
            FirebaseApp.initializeApp(this, options);
        } catch (RuntimeException ignored) {
            // Broken/missing config never prevents the marketplace from opening.
        }
    }
}
