package com.pelakauto.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.net.http.SslError;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.*;

/** PELAK Android web shell v0.1; website and Supabase remain unchanged. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://pelakauto.com/?simple=1";
    private static final int FILE_REQUEST = 301;
    private static final int CAMERA_PERMISSION = 302;
    private static final int MEDIA_PERMISSION = 303;
    private WebView web;
    private FrameLayout root;
    private LinearLayout offline;
    private ProgressBar loading;
    private ValueCallback<Uri[]> fileCallback;
    private WebChromeClient.FileChooserParams fileParams;
    private Uri cameraUri;
    private PermissionRequest mediaRequest;
    private PelakPushBridge pushBridge;
    private boolean failed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16,35,63));
        getWindow().setNavigationBarColor(Color.rgb(16,35,63));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1,-1));
        configureBrowser();
        pushBridge=PelakPushBridge.install(this,web);
        PelakMessagingService.createChannel(this);
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        loading.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFC9A158));
        root.addView(loading, new FrameLayout.LayoutParams(-1,dp(3),Gravity.TOP));
        createOfflineView();
        if (state == null || web.restoreState(state) == null) web.loadUrl(startupUrl(getIntent()));
    }


    /** Allow only known internal routes from notification extras, never arbitrary URLs. */
    private static String notificationView(Intent intent) {
        if(intent==null)return null;
        String value=intent.getStringExtra("pelak_target");
        if(value==null)value=intent.getStringExtra("kind"); // Firebase background payload
        if(value==null)return null;
        switch(value) {
            case "listing_needs_revision":
            case "listing_published":
            case "mine": return "mine";
            case "listing_report":
            case "new_draft":
            case "admin": return "admin";
            case "listing_message":
            case "messages": return "messages";
            case "support_message":
            case "support": return "support";
            default: return null;
        }
    }
    private static String startupUrl(Intent intent) {
        String view=notificationView(intent);
        return view==null?HOME:HOME+"&pelakView="+view;
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if(web!=null&&notificationView(intent)!=null)web.loadUrl(startupUrl(intent));
    }

    private void configureBrowser() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true); // Supabase session persistence.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // User-selected photo URIs.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return navigate(r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return navigate(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView v,String url,Bitmap icon) {
                failed=false; offline.setVisibility(View.GONE);
                loading.setProgress(2);loading.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView v,String url) {
                loading.setVisibility(View.GONE);
                if(!failed)offline.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if(r.isForMainFrame())showOffline();
            }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e) {
                if(r.isForMainFrame() && e.getStatusCode()>=500)showOffline();
            }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,SslError e) {
                handler.cancel();showOffline(); // Never ignore SSL certificate errors.
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view,int progress) {
                loading.setProgress(progress);
                if(progress==100)loading.setVisibility(View.GONE);
            }
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params) {
                if(fileCallback!=null)fileCallback.onReceiveValue(null);
                fileCallback=callback;fileParams=params;
                if(canOfferCamera(params) && Build.VERSION.SDK_INT>=23
                  && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
                } else launchChooser();
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(()->requestMediaAccess(request));
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if(mediaRequest==request)mediaRequest=null;
            }
        });
        web.setDownloadListener((url,agent,disposition,mime,length)->openExternal(Uri.parse(url)));
    }

    private boolean trusted(Uri uri) {
        if(uri==null || !"https".equalsIgnoreCase(uri.getScheme()))return false;
        String host=uri.getHost();
        return "pelakauto.com".equalsIgnoreCase(host) || "www.pelakauto.com".equalsIgnoreCase(host);
    }
    private boolean navigate(Uri uri) {
        if(uri==null)return true;
        String scheme=uri.getScheme();
        if("about".equals(scheme)||"blob".equals(scheme))return false;
        if(trusted(uri))return false;
        if("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)
          ||"tel".equalsIgnoreCase(scheme)||"sms".equalsIgnoreCase(scheme)
          ||"mailto".equalsIgnoreCase(scheme))openExternal(uri);
        return true; // Unknown schemes never load in the embedded browser.
    }
    private void openExternal(Uri uri) {
        try {startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));}
        catch(ActivityNotFoundException e){Toast.makeText(this,"برنامه‌ای برای بازکردن لینک پیدا نشد",Toast.LENGTH_SHORT).show();}
    }
    private void showOffline(){failed=true;loading.setVisibility(View.GONE);offline.setVisibility(View.VISIBLE);}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private void createOfflineView(){
        offline=new LinearLayout(this);offline.setOrientation(LinearLayout.VERTICAL);
        offline.setBackgroundColor(Color.WHITE);offline.setPadding(dp(20),dp(20),dp(20),dp(20));
        offline.setGravity(Gravity.CENTER);
        TextView logo=new TextView(this);logo.setText("PELAK");logo.setTextSize(30);
        logo.setGravity(Gravity.CENTER);logo.setTypeface(null,Typeface.BOLD);
        logo.setTextColor(0xFF10233F);offline.addView(logo);
        TextView title=new TextView(this);title.setText(R.string.offline_title);
        title.setTextSize(20);title.setTextColor(0xFF10233F);title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(18);offline.addView(title,tp);
        TextView subtitle=new TextView(this);subtitle.setText(R.string.offline_description);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(12);offline.addView(subtitle,sp);
        Button retry=new Button(this);retry.setText(R.string.retry);
        retry.setOnClickListener(v->{failed=false;offline.setVisibility(View.GONE);web.loadUrl(HOME);});
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(22);offline.addView(retry,bp);
        root.addView(offline,new FrameLayout.LayoutParams(-1,-1));offline.setVisibility(View.GONE);
    }
    private boolean canOfferCamera(WebChromeClient.FileChooserParams params){
        if(params==null)return false;
        String[] types=params.getAcceptTypes();
        if(types==null||types.length==0)return true;
        for(String t:types)if(t==null||t.isEmpty()||t.contains("image")||t.contains("*/*"))return true;
        return false;
    }
    private String acceptedMime(){
        if(fileParams!=null)for(String t:fileParams.getAcceptTypes())
            if(t!=null&&t.contains("/"))return t.trim();
        return "*/*";
    }
    private void launchChooser(){
        if(fileCallback==null)return;
        Intent gallery=new Intent(Intent.ACTION_GET_CONTENT);
        gallery.addCategory(Intent.CATEGORY_OPENABLE);
        gallery.setType(acceptedMime());
        gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,fileParams!=null&&
            fileParams.getMode()==WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        ArrayList<Intent> cameras=new ArrayList<>();cameraUri=null;
        if(canOfferCamera(fileParams)&&(Build.VERSION.SDK_INT<23||
            checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)){
            try {
                File dir=getExternalCacheDir();if(dir==null)dir=getCacheDir();
                File photo=File.createTempFile("pelak-photo-",".jpg",dir);
                cameraUri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",photo);
                Intent capture=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                capture.putExtra(MediaStore.EXTRA_OUTPUT,cameraUri);
                capture.setClipData(ClipData.newRawUri("PELAK photo",cameraUri));
                capture.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if(capture.resolveActivity(getPackageManager())!=null)cameras.add(capture);
            }catch(IOException|IllegalArgumentException e){cameraUri=null;}
        }
        try {
            if(fileParams!=null&&fileParams.isCaptureEnabled()&&!cameras.isEmpty())
                startActivityForResult(cameras.get(0),FILE_REQUEST);
            else {
                Intent chooser=Intent.createChooser(gallery,"انتخاب عکس برای پلاک");
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,cameras.toArray(new Intent[0]));
                startActivityForResult(chooser,FILE_REQUEST);
            }
        }catch(ActivityNotFoundException e){deliverFiles(null);Toast.makeText(this,"انتخاب عکس در دسترس نیست",Toast.LENGTH_LONG).show();}
    }
    private void deliverFiles(Uri[] files){
        ValueCallback<Uri[]> callback=fileCallback;fileCallback=null;fileParams=null;cameraUri=null;
        if(callback!=null)callback.onReceiveValue(files);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=FILE_REQUEST)return;
        Uri[] uris=null;
        if(result==RESULT_OK){
            ArrayList<Uri> list=new ArrayList<>();
            if(data!=null&&data.getClipData()!=null){
                ClipData items=data.getClipData();
                for(int i=0;i<items.getItemCount();i++){
                    Uri item=items.getItemAt(i).getUri();if(item!=null)list.add(item);
                }
            }else if(data!=null&&data.getData()!=null)list.add(data.getData());
            else if(cameraUri!=null)list.add(cameraUri);
            if(!list.isEmpty())uris=list.toArray(new Uri[0]);
        }
        deliverFiles(uris);
    }
    private void requestMediaAccess(PermissionRequest request){
        if(!trusted(request.getOrigin())){request.deny();return;}
        ArrayList<String> permissions=new ArrayList<>();
        for(String resource:request.getResources()){
            if(PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)){
                if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        !=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.RECORD_AUDIO);
            }else if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)){
                if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.CAMERA)
                        !=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.CAMERA);
            }else{request.deny();return;}
        }
        if(permissions.isEmpty())request.grant(request.getResources());
        else {
            if(mediaRequest!=null)mediaRequest.deny();
            mediaRequest=request;
            requestPermissions(permissions.toArray(new String[0]),MEDIA_PERMISSION);
        }
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==PelakPushBridge.NOTIFICATION_PERMISSION_REQUEST){
            if(pushBridge!=null)pushBridge.onPermissionResult(grants);
        } else if(code==CAMERA_PERMISSION)launchChooser(); // Gallery still works if denied.
        else if(code==MEDIA_PERMISSION&&mediaRequest!=null){
            boolean allowed=grants.length>0;
            for(int status:grants)allowed&=status==PackageManager.PERMISSION_GRANTED;
            if(allowed&&trusted(mediaRequest.getOrigin()))mediaRequest.grant(mediaRequest.getResources());
            else mediaRequest.deny();
            mediaRequest=null;
        }
    }
    @Override public void onBackPressed(){
        if(offline.getVisibility()==View.VISIBLE){offline.setVisibility(View.GONE);web.loadUrl(HOME);}
        else if(web.canGoBack())web.goBack();
        else new AlertDialog.Builder(this).setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit,(d,w)->finish())
            .setNegativeButton(R.string.cancel,null).show();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        if(web!=null)web.saveState(state);super.onSaveInstanceState(state);
    }
    @Override protected void onPause(){
        super.onPause();if(web!=null)web.onPause();CookieManager.getInstance().flush();
    }
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
    @Override protected void onDestroy(){
        if(fileCallback!=null)deliverFiles(null);
        if(mediaRequest!=null)mediaRequest.deny();mediaRequest=null;
        if(web!=null){root.removeView(web);web.destroy();web=null;}
        super.onDestroy();
    }
}
