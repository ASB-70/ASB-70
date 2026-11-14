package com.altheqa.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import org.json.JSONObject;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView wv;
    private ValueCallback<Uri[]> fileCb;
    private String host;
    private boolean offline = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final int REQ_FILE = 11;
    private static final int REQ_CONTACT = 12;

    private static final String JS =
        "(function(){if(window.__apk)return;window.__apk=1;"
      + "function b64(b){return new Promise(function(r,j){var f=new FileReader();f.onload=function(){r(String(f.result).split(',')[1]||'')};f.onerror=j;f.readAsDataURL(b)})}"
      + "navigator.canShare=function(){return true};"
      + "navigator.share=function(d){d=d||{};var fs=d.files||[];"
      + "if(fs.length){return b64(fs[0]).then(function(x){Android.share(d.title||'',d.text||'',fs[0].name||'file',fs[0].type||'application/octet-stream',x)})}"
      + "Android.share(d.title||'',[d.text,d.url].filter(Boolean).join('\\n'),'','','');return Promise.resolve()};"
      + "window.print=function(){Android.print()};"
      + "document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[download]');if(!a)return;"
      + "var h=a.href;if(!h)return;e.preventDefault();e.stopPropagation();"
      + "fetch(h).then(function(r){return r.blob()}).then(function(b){return b64(b).then(function(x){Android.saveFile(a.getAttribute('download')||'file',b.type||'application/octet-stream',x)})});},true);"
      + "})();";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        wv = new WebView(this);
        root.addView(wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        host = Uri.parse(BuildConfig.START_URL).getHost();
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " AltheqaApp/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        wv.addJavascriptInterface(new Bridge(), "Android");

        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(r.getUrl());
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { v.evaluateJavascript(JS, null); }
            @Override public void onPageFinished(WebView v, String url) { v.evaluateJavascript(JS, null); }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && !offline) {
                    offline = true;
                    v.loadUrl("file:///android_asset/offline.html");
                }
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        wv.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                DownloadManager_enqueue(url, ua, cd, mime);
            } catch (Exception e) { openExternal(Uri.parse(url)); }
        });
        if (b != null) wv.restoreState(b); else wv.loadUrl(BuildConfig.START_URL);
    }

    private void DownloadManager_enqueue(String url, String ua, String cd, String mime) {
        android.app.DownloadManager.Request rq = new android.app.DownloadManager.Request(Uri.parse(url));
        String name = URLUtil.guessFileName(url, cd, mime);
        rq.setMimeType(mime);
        rq.addRequestHeader("User-Agent", ua);
        rq.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        ((android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(rq);
        toast("بدأ التنزيل…");
    }

    private boolean handleUrl(Uri u) {
        String sc = u.getScheme() == null ? "" : u.getScheme();
        if ((sc.equals("https") || sc.equals("http")) && host.equalsIgnoreCase(u.getHost())) return false;
        if (sc.equals("file") || sc.equals("blob") || sc.equals("data") || sc.equals("about")) return false;
        openExternal(u);
        return true;
    }

    private void openExternal(Uri u) {
        try {
            Intent i = u.getScheme() != null && u.getScheme().equals("intent")
                ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            startActivity(i);
        } catch (Exception e) { toast("تعذر فتح الرابط"); }
    }

    private void bioResult(boolean ok) {
        ui.post(() -> wv.evaluateJavascript("window.__bio&&window.__bio(" + ok + ")", null));
    }

    private void toast(String t) { ui.post(() -> Toast.makeText(this, t, Toast.LENGTH_SHORT).show()); }

    private static String safeName(String n) {
        if (n == null || n.trim().isEmpty()) n = "file";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public class Bridge {
        @JavascriptInterface public void retry() {
            ui.post(() -> { offline = false; wv.loadUrl(BuildConfig.START_URL); });
        }

        @JavascriptInterface public void pickContact() {
            ui.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
                    startActivityForResult(i, REQ_CONTACT);
                } catch (Exception e) { toast("تعذر فتح جهات الاتصال"); }
            });
        }

        @JavascriptInterface public boolean bioAvailable() {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    android.hardware.biometrics.BiometricManager bm =
                        (android.hardware.biometrics.BiometricManager) getSystemService(BIOMETRIC_SERVICE);
                    return bm != null && bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
                }
                if (Build.VERSION.SDK_INT == 28) {
                    android.hardware.fingerprint.FingerprintManager fm =
                        (android.hardware.fingerprint.FingerprintManager) getSystemService(FINGERPRINT_SERVICE);
                    return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
                }
            } catch (Exception e) { return false; }
            return false;
        }

        @JavascriptInterface public void bioAuth(String title) {
            ui.post(() -> {
                if (Build.VERSION.SDK_INT < 28) { bioResult(false); return; }
                try {
                    android.hardware.biometrics.BiometricPrompt p =
                        new android.hardware.biometrics.BiometricPrompt.Builder(MainActivity.this)
                            .setTitle(title == null || title.isEmpty() ? getString(R.string.app_name) : title)
                            .setNegativeButton("إلغاء", getMainExecutor(), (d, w) -> bioResult(false))
                            .build();
                    p.authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) { bioResult(true); }
                            @Override public void onAuthenticationError(int code, CharSequence msg) { bioResult(false); }
                        });
                } catch (Exception e) { bioResult(false); }
            });
        }

        @JavascriptInterface public void print() {
            ui.post(() -> {
                try {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    String job = getString(R.string.app_name);
                    pm.print(job, wv.createPrintDocumentAdapter(job), new PrintAttributes.Builder().build());
                } catch (Exception e) { toast("تعذرت الطباعة"); }
            });
        }

        @JavascriptInterface public void printImage(String name, String mime, String b64) {
            ui.post(() -> {
                try {
                    final String job = (name == null || name.isEmpty()) ? "Document" : name;
                    final WebView pv = new WebView(MainActivity.this);
                    pv.getSettings().setJavaScriptEnabled(false);
                    pv.setWebViewClient(new WebViewClient() {
                        @Override public void onPageFinished(WebView v, String url) {
                            try {
                                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                                PrintAttributes pa = new PrintAttributes.Builder()
                                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build();
                                pm.print(job, v.createPrintDocumentAdapter(job), pa);
                            } catch (Exception e) { toast("تعذرت الطباعة"); }
                        }
                    });
                    String html = "<html><head><meta name='viewport' content='width=device-width'><style>@page{margin:0}body{margin:0}img{width:100%;display:block}</style></head><body><img src='data:" + mime + ";base64," + b64 + "'></body></html>";
                    pv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                } catch (Exception e) { toast("تعذرت الطباعة"); }
            });
        }

        @JavascriptInterface public void share(String title, String text, String name, String mime, String b64) {
            ui.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    if (b64 != null && !b64.isEmpty()) {
                        File dir = new File(getCacheDir(), "shared"); dir.mkdirs();
                        File f = new File(dir, safeName(name));
                        try (FileOutputStream o = new FileOutputStream(f)) { o.write(Base64.decode(b64, Base64.DEFAULT)); }
                        Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fp", f);
                        i.setType(mime == null || mime.isEmpty() ? "*/*" : mime);
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        i.setType("text/plain");
                    }
                    if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                    if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
                    startActivity(Intent.createChooser(i, "مشاركة"));
                } catch (Exception e) { toast("تعذرت المشاركة"); }
            });
        }

        @JavascriptInterface public void sendToCompany(String number, String text, String name, String mime, String b64) {
            ui.post(() -> {
                String num = number == null ? "" : number.replaceAll("\\D", "");
                try {
                    File dir = new File(getCacheDir(), "shared"); dir.mkdirs();
                    File f = new File(dir, safeName(name));
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(Base64.decode(b64, Base64.DEFAULT)); }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fp", f);
                    String[] pkgs = {"com.whatsapp", "com.whatsapp.w4b"};
                    for (String pk : pkgs) {
                        try {
                            getPackageManager().getPackageInfo(pk, 0);
                            Intent i = new Intent(Intent.ACTION_SEND);
                            i.setType(mime);
                            i.setPackage(pk);
                            i.putExtra(Intent.EXTRA_STREAM, uri);
                            i.putExtra(Intent.EXTRA_TEXT, text);
                            i.putExtra("jid", num + "@s.whatsapp.net");
                            i.setClipData(android.content.ClipData.newRawUri("invoice", uri));
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            grantUriPermission(pk, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(i);
                            return;
                        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
                    }
                    toast("واتساب غير مثبت. سيتم فتح المحادثة بالنص فقط");
                } catch (Exception e) { toast("تعذر فتح واتساب"); }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(text))));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public void saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                name = safeName(name);
                Uri out;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    out = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream o = getContentResolver().openOutputStream(out)) { o.write(data); }
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS); dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                    out = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fp", f);
                }
                final Uri fu = out; final String fm = mime;
                toast("تم الحفظ في التنزيلات: " + name);
                ui.post(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(fu, fm).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    } catch (Exception ignored) {}
                });
            } catch (Exception e) { toast("تعذر حفظ الملف"); }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        } else if (req == REQ_CONTACT) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try (Cursor c = getContentResolver().query(data.getData(),
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        String n = c.getString(0), p = c.getString(1);
                        wv.evaluateJavascript("window.__contact&&window.__contact(" + JSONObject.quote(n == null ? "" : n) + "," + JSONObject.quote(p == null ? "" : p) + ")", null);
                    }
                } catch (Exception e) { toast("تعذر قراءة جهة الاتصال"); }
            }
        } else super.onActivityResult(req, res, data);
    }

    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); if (!offline) wv.saveState(b); }

    @Override public void onBackPressed() {
        if (!offline && wv.canGoBack()) wv.goBack(); else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); wv.onPause(); CookieManager.getInstance().flush(); }
    @Override protected void onResume() { super.onResume(); wv.onResume(); }
}
