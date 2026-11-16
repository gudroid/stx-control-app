package br.com.guucds.stxcontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * STX Control (@guucds) — app Android.
 * A tela é o mesmo app web (assets/www/index.html), servido por uma origem https local
 * (appassets.androidplatform.net) para funcionar sem internet e com criptografia (licenças).
 * O Bluetooth é feito em código nativo (BleBridge), bem mais estável que o Bluetooth do navegador.
 */
public class MainActivity extends Activity {
    static final String TAG = "STXAPP";
    static final String HOME = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int REQ_PERM = 7, REQ_FILE = 8, REQ_MIC = 9;

    WebView web;
    BleBridge ble;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingMic;
    int guardFlags = 0;
    boolean debuggable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        Lg.on = debuggable;
        guardFlags = Guard.check(this);
        immersive();

        web = new WebView(this);
        web.setBackgroundColor(0xFF07090D);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        WebView.setWebContentsDebuggingEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " STXControlApp/" + BuildConfigVersion.name(this));

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                // WhatsApp, Instagram, Google Imagens etc.: abre no app certo
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { Lg.w(TAG, "link: " + e); }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (debuggable) Lg.d("STXJS", m.message() + " (" + m.lineNumber() + ")");
                return true;
            }

            // Microfone (RTA, medições, Ajustes IA): o WebView só libera se o Android também liberar
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean trusted = req.getOrigin() != null && "appassets.androidplatform.net".equals(req.getOrigin().getHost());
                    boolean wantsMic = false;
                    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                    if (!trusted || !wantsMic) { req.deny(); return; }
                    if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (pendingMic != null) pendingMic.deny();
                        pendingMic = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try { startActivityForResult(Intent.createChooser(i, "Escolher capa"), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });

        ble = new BleBridge(this);
        web.addJavascriptInterface(ble, "StxNative");
        askPermissions();
        if ((guardFlags & (Guard.BAD_SIG | Guard.BAD_WEB)) != 0) {
            web.loadData("<body style='background:#07090d;color:#fff;font-family:sans-serif;text-align:center;padding:12vh 8vw'>"
                    + "<h2>Versão modificada</h2><p>Este aplicativo foi alterado e não é a versão original do STX Control.<br>"
                    + "Baixe o app oficial com @guucds.</p></body>", "text/html", "utf-8");
            return;
        }
        web.loadUrl(HOME);
    }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    String[] neededPermissions() {
        List<String> l = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN);
            l.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return l.toArray(new String[0]);
    }

    boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : neededPermissions())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    void askPermissions() {
        if (Build.VERSION.SDK_INT >= 23 && !hasPermissions()) requestPermissions(neededPermissions(), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MIC) {
            if (pendingMic != null) {
                if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
                    pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else pendingMic.deny();
                pendingMic = null;
            }
            return;
        }
        ble.emit("perm", hasPermissions() ? "ok" : "denied");
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (result == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCb.onReceiveValue(r);
            fileCb = null;
        }
    }

    @Override
    public void onBackPressed() {
        // o próprio app decide (fecha janela aberta, volta uma tela); se não tiver para onde voltar, minimiza
        web.evaluateJavascript("(window.StxBack && window.StxBack()) ? 1 : 0", v -> {
            if (!"1".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        ble.shutdown();
        super.onDestroy();
    }
}
