package com.altheqa.pricelabel;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * ملصق الأسعار — Android shell around the live site.
 * The page talks to this activity through window.AndroidBridge to save images into the
 * gallery and to share them, which a plain WebView cannot do on its own.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://asb-70.github.io/ASB-70/labels/";
    private static final String HOST = "asb-70.github.io";
    private static final int PICK_FILES = 1001;
    private static final int ASK_STORAGE = 1002;
    private static final String ALBUM = "ملصق الأسعار";

    private static final String SHARED_PATH = "/ASB-70/labels/__shared__/";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    /** images other apps shared into this app, waiting for the page to pick them up */
    private final ArrayList<Uri> incoming = new ArrayList<>();
    /** images the page is preparing to share out */
    private final ArrayList<Uri> outgoing = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#f3f4f6"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " PriceLabelApp/1.0");

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equalsIgnoreCase(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = sharedResponse(request.getUrl());
                return r != null ? r : super.shouldInterceptRequest(view, request);
            }
        });
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return sharedResponse(request.getUrl());
                }
            });
        } catch (Throwable ignored) { }

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                String mime = "image/*";
                String[] accept = params.getAcceptTypes();
                if (accept != null) {
                    for (String a : accept) {
                        if (a != null && a.contains("zip")) { mime = "application/zip"; break; }
                    }
                }
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType(mime);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(pick, "اختيار الصور"), PICK_FILES);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    toast("ما في تطبيق لاختيار الصور");
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
        takeShared(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (takeShared(intent) && web != null) {
            web.evaluateJavascript("window.__androidShared && window.__androidShared()", null);
        }
    }

    /** Keeps the images another app (the gallery) shared with us. */
    private boolean takeShared(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        List<Uri> got = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) got.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) got.addAll(list);
        }
        if (got.isEmpty()) {
            ClipData clip = intent.getClipData();
            if (clip != null && (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action))) {
                for (int i = 0; i < clip.getItemCount(); i++) if (clip.getItemAt(i).getUri() != null) got.add(clip.getItemAt(i).getUri());
            }
        }
        if (got.isEmpty()) return false;
        synchronized (incoming) { incoming.clear(); incoming.addAll(got); }
        intent.setAction(Intent.ACTION_MAIN);   // don't import the same images again after rotation
        toast("وصلت " + got.size() + " صورة، عم ضيفها…");
        return true;
    }

    /** Serves shared image N to the page at …/labels/__shared__/N without copying it. */
    private WebResourceResponse sharedResponse(Uri url) {
        if (url == null || !HOST.equalsIgnoreCase(url.getHost())) return null;
        String path = url.getPath();
        if (path == null || !path.startsWith(SHARED_PATH)) return null;
        try {
            int idx = Integer.parseInt(path.substring(SHARED_PATH.length()));
            Uri src;
            synchronized (incoming) { src = incoming.get(idx); }
            String mime = getContentResolver().getType(src);
            if (mime == null) mime = "image/jpeg";
            InputStream in = getContentResolver().openInputStream(src);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse(mime, null, 200, "OK", headers, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    /** Writes bytes into the gallery (images) or Downloads (anything else). */
    private void saveBytes(byte[] bytes, String name, String mime) throws Exception {
        boolean image = mime != null && mime.startsWith("image/");
        ContentResolver resolver = getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS) + "/" + ALBUM);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = image
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = resolver.insert(collection, v);
            if (uri == null) throw new Exception("insert failed");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("no stream");
                out.write(bytes);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, v, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS), ALBUM);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir failed");
            File f = new File(dir, name);
            int n = 1;
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            while (f.exists()) f = new File(dir, base + " (" + (n++) + ")" + ext);
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    /** Methods the web page can call as window.AndroidBridge.* */
    private class Bridge {
        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public int sharedCount() { synchronized (incoming) { return incoming.size(); } }

        @JavascriptInterface
        public String sharedName(int i) {
            try {
                Uri u; synchronized (incoming) { u = incoming.get(i); }
                String last = u.getLastPathSegment();
                try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
                } catch (Exception ignored) { }
                return last != null ? last : "";
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void clearShared() { synchronized (incoming) { incoming.clear(); } }

        /** Streaming share: begin, add one image at a time, then commit (keeps memory low for big batches). */
        @JavascriptInterface
        public boolean shareBegin() {
            File dir = new File(getCacheDir(), "share");
            if (dir.exists()) {
                File[] old = dir.listFiles();
                if (old != null) for (File o : old) //noinspection ResultOfMethodCallIgnored
                    o.delete();
            } else if (!dir.mkdirs()) return false;
            synchronized (outgoing) { outgoing.clear(); }
            return true;
        }

        @JavascriptInterface
        public boolean shareAdd(String name, String base64) {
            try {
                File dir = new File(getCacheDir(), "share");
                String clean = name.replaceAll("[\\\\/:*?\"<>|]", "");
                File f = new File(dir, clean);
                int n = 1;
                while (f.exists()) f = new File(dir, (n++) + "-" + clean);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(Base64.decode(base64, Base64.DEFAULT)); }
                Uri u = FileProvider.getUriForFile(MainActivity.this, "com.altheqa.pricelabel.files", f);
                synchronized (outgoing) { outgoing.add(u); }
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean shareCommit() {
            final ArrayList<Uri> uris;
            synchronized (outgoing) { uris = new ArrayList<>(outgoing); }
            if (uris.isEmpty()) return false;
            final Intent send;
            if (uris.size() == 1) {
                send = new Intent(Intent.ACTION_SEND);
                send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            send.setType("image/jpeg");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> startActivity(Intent.createChooser(send, "مشاركة الصور")));
            return true;
        }

        @JavascriptInterface
        public boolean saveFile(String base64, String name, String mime) {
            if (needsStoragePermission()) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE));
                toast("اسمح للتطبيق بحفظ الصور، وبعدين جرّب مرة تانية");
                return false;
            }
            try {
                saveBytes(Base64.decode(base64, Base64.DEFAULT), name, mime);
                toast(mime != null && mime.startsWith("image/") ? "انحفظت بالمعرض: " + name : "انحفظ بالتنزيلات: " + name);
                return true;
            } catch (Exception e) {
                toast("ما قدرت احفظ الملف");
                return false;
            }
        }

        @JavascriptInterface
        public boolean shareFiles(String json) {
            try {
                JSONArray arr = new JSONArray(json);
                File dir = new File(getCacheDir(), "share");
                if (dir.exists()) {
                    File[] old = dir.listFiles();
                    if (old != null) for (File o : old) //noinspection ResultOfMethodCallIgnored
                        o.delete();
                } else if (!dir.mkdirs()) return false;
                final ArrayList<Uri> uris = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String name = o.optString("name", "image-" + (i + 1) + ".jpg").replaceAll("[\\\\/:*?\"<>|]", "");
                    File f = new File(dir, name);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(Base64.decode(o.getString("data"), Base64.DEFAULT));
                    }
                    uris.add(FileProvider.getUriForFile(MainActivity.this, "com.altheqa.pricelabel.files", f));
                }
                if (uris.isEmpty()) return false;
                final Intent send;
                if (uris.size() == 1) {
                    send = new Intent(Intent.ACTION_SEND);
                    send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                }
                send.setType("image/jpeg");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "مشاركة الصور")));
                return true;
            } catch (Exception e) {
                toast("ما قدرت أشارك الصور");
                return false;
            }
        }
    }
}
