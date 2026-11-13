package com.mobihome.yukledetayla;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA = 1002;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // Dış bağlantıları tarayıcıda aç
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;

                String[] accept = params.getAcceptTypes();
                boolean wantsImage = false, wantsPdf = false;
                if (accept != null) {
                    for (String a : accept) {
                        if (a == null) continue;
                        if (a.startsWith("image")) wantsImage = true;
                        if (a.contains("pdf")) wantsPdf = true;
                    }
                }

                // Kamera ile fotoğraf çek
                if (params.isCaptureEnabled() && wantsImage) {
                    if (openCamera()) return true;
                }

                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                if (wantsPdf) i.setType("application/pdf");
                else if (wantsImage) i.setType("image/*");
                else i.setType("*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(i, wantsPdf ? "PDF seç" : "Fotoğraf seç"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Dosya seçici bulunamadı", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private boolean openCamera() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "sayfa_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(cam, REQ_CAMERA);
            return true;
        } catch (Exception e) {
            cameraUri = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (requestCode == REQ_CAMERA) {
                if (cameraUri != null) result = new Uri[]{cameraUri};
            } else if (requestCode == REQ_FILE && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    result = new Uri[clip.getItemCount()];
                    for (int k = 0; k < clip.getItemCount(); k++) result[k] = clip.getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        cameraUri = null;
    }

    @Override
    public void onBackPressed() {
        // Önce sayfanın kendi geri işlemini dene (testten özete, özetten ana ekrana)
        web.evaluateJavascript("(window.appBack && window.appBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) MainActivity.super.onBackPressed();
        });
    }
}
