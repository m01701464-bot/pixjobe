package kz.yaroslav.leads;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                openExternal(req.getUrl().toString());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    private void reply(String cb, boolean ok, int status, String body) {
        String js = "window.__nativeReply(" + JSONObject.quote(cb) + "," + ok + "," + status + "," + JSONObject.quote(body == null ? "" : body) + ")";
        main.post(() -> web.evaluateJavascript(js, null));
    }

    class Bridge {
        /** Network request done natively so the page is not limited by CORS. */
        @JavascriptInterface
        public void request(String cb, String method, String url, String headersJson, String body) {
            pool.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(90000);
                    c.setRequestProperty("User-Agent", "LeadsApp/1.0 (Android)");
                    if (headersJson != null && !headersJson.isEmpty()) {
                        JSONObject h = new JSONObject(headersJson);
                        Iterator<String> it = h.keys();
                        while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, h.getString(k)); }
                    }
                    if (body != null && !body.isEmpty()) {
                        c.setDoOutput(true);
                        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                    }
                    int code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    if (in != null) {
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                            String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
                        }
                    }
                    reply(cb, code < 400, code, sb.toString());
                } catch (Exception e) {
                    reply(cb, false, 0, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void dial(String number) {
            main.post(() -> {
                try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number))); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void open(String url) { main.post(() -> openExternal(url)); }

        /** Writes a CSV to the cache and opens the share sheet (Telegram, Drive, Excel...). */
        @JavascriptInterface
        public void shareFile(String name, String content) {
            main.post(() -> {
                try {
                    File dir = new File(getCacheDir(), "exports");
                    dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        fo.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM so Excel reads Cyrillic
                        fo.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, "kz.yaroslav.leads.files", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/csv");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Отправить таблицу"));
                } catch (Exception e) {
                    web.evaluateJavascript("toast(" + JSONObject.quote("Не удалось сохранить: " + e.getMessage()) + ")", null);
                }
            });
        }
    }
}
