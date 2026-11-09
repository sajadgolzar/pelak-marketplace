package com.pelakauto.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.JavaScriptReplyProxy;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.HashSet;
import java.util.Arrays;

/** HTTPS-origin restricted JS/native messaging; never exposes Supabase JWT to Android. */
public final class PelakPushBridge {
    public static final int NOTIFICATION_PERMISSION_REQUEST = 641;
    private final Activity activity;
    private JavaScriptReplyProxy pending;
    private boolean pendingPermission;

    private PelakPushBridge(Activity activity) {
        this.activity=activity;
    }

    public static PelakPushBridge install(Activity activity, WebView web) {
        if(FirebaseApp.getApps(activity).isEmpty() ||
           !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            return null;

        PelakPushBridge bridge=new PelakPushBridge(activity);
        HashSet<String> origins=new HashSet<>(Arrays.asList(
            "https://pelakauto.com", "https://www.pelakauto.com"));
        WebViewCompat.addWebMessageListener(web,"PelakAndroidPush",origins,
            (view,message,sourceOrigin,isMainFrame,reply) -> {
                if(!isMainFrame || !isTrusted(sourceOrigin))return;
                String request=message.getData();
                if("disable".equals(request)){
                    bridge.disableToken(reply);return;
                }
                if(!"get-token".equals(request)&&!"enable".equals(request))return;
                bridge.requestToken("enable".equals(request),reply);
            }
        );
        return bridge;
    }
    private static boolean isTrusted(Uri origin) {
        if(origin==null || !"https".equalsIgnoreCase(origin.getScheme()))return false;
        String host=origin.getHost();
        return "pelakauto.com".equalsIgnoreCase(host) ||
               "www.pelakauto.com".equalsIgnoreCase(host);
    }
    private void requestToken(boolean enable,JavaScriptReplyProxy reply) {
        if(Build.VERSION.SDK_INT>=33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED) {
            if(!enable){reply.postMessage("permission-required");return;}
            pending=reply;
            pendingPermission=true;
            activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        sendToken(reply);
    }
    private void sendToken(JavaScriptReplyProxy reply) {
        FirebaseMessaging.getInstance().getToken()
          .addOnSuccessListener(token->{
              if(token!=null&&!token.isEmpty())reply.postMessage("token:"+token);
              else reply.postMessage("error:no-token");
          })
          .addOnFailureListener(e->reply.postMessage("error:firebase-unavailable"));
    }
    private void disableToken(JavaScriptReplyProxy reply){
        FirebaseMessaging.getInstance().deleteToken()
           .addOnSuccessListener(ignored->reply.postMessage("disabled"))
           .addOnFailureListener(err->reply.postMessage("error:delete-token-failed"));
    }
    public void onPermissionResult(int[] results) {
        if(!pendingPermission)return;
        JavaScriptReplyProxy reply=pending;pending=null;pendingPermission=false;
        if(reply==null)return;
        if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)
            sendToken(reply);
        else reply.postMessage("permission-required");
    }
}
