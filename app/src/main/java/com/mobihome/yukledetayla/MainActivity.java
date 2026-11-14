package com.mobihome.yukledetayla;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.OAuthProvider;
import com.google.firebase.auth.UserInfo;

import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA = 1002;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private InterstitialAd interstitial;
    private final Executor bg = Executors.newSingleThreadExecutor();
    private boolean pendingDelete = false;
    private boolean adsStarted = false;
    private ConsentInformation consent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
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
                if (accept != null) for (String a : accept) {
                    if (a == null) continue;
                    if (a.startsWith("image")) wantsImage = true;
                    if (a.contains("pdf")) wantsPdf = true;
                }
                if (params.isCaptureEnabled() && wantsImage && openCamera()) return true;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(wantsPdf ? "application/pdf" : wantsImage ? "image/*" : "*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, wantsPdf ? "PDF seç" : "Fotoğraf seç"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Reklamlar: önce (AB/Birleşik Krallık'ta) izin formu, sonra AdMob
        try {
            consent = UserMessagingPlatform.getConsentInformation(this);
            consent.requestConsentInfoUpdate(this, new ConsentRequestParameters.Builder().build(),
                    () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, err -> startAds()),
                    err -> startAds());
            if (consent.canRequestAds()) startAds();
        } catch (Throwable t) {
            startAds();
        }

        // Apple girişi yarıda kaldıysa sonucu al
        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            if (auth.getPendingAuthResult() != null) {
                auth.getPendingAuthResult()
                        .addOnSuccessListener(r -> sendUser(r.getUser(), "Apple"))
                        .addOnFailureListener(e -> sendError("Apple girişi tamamlanamadı."));
            }
        } catch (Throwable ignored) {}

        web.loadUrl("file:///android_asset/index.html");
    }

    // ===================== Giriş =====================
    private FirebaseAuth auth() {
        try { return FirebaseAuth.getInstance(); } catch (Throwable t) { return null; }
    }

    private void signInGoogle() {
        FirebaseAuth auth = auth();
        int resId = getResources().getIdentifier("default_web_client_id", "string", getPackageName());
        if (auth == null || resId == 0) { sendError("Giriş sistemi henüz ayarlanmadı (Firebase)."); return; }
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(getString(resId)).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager cm = CredentialManager.create(this);
        cm.getCredentialAsync(this, request, new CancellationSignal(), bg,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential c = response.getCredential();
                        if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                            try {
                                GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(c.getData());
                                AuthCredential ac = GoogleAuthProvider.getCredential(g.getIdToken(), null);
                                runOnUiThread(() -> {
                                    FirebaseUser cu = auth.getCurrentUser();
                                    if (pendingDelete && cu != null) {
                                        cu.reauthenticate(ac)
                                                .addOnSuccessListener(v -> deleteNow())
                                                .addOnFailureListener(e -> { pendingDelete = false; sendDeleted(false, e.getMessage()); });
                                        return;
                                    }
                                    auth.signInWithCredential(ac)
                                            .addOnSuccessListener(r -> sendUser(r.getUser(), "Google"))
                                            .addOnFailureListener(e -> sendError("Google girişi başarısız: " + e.getMessage()));
                                });
                            } catch (Exception e) {
                                sendError("Google girişi okunamadı.");
                            }
                        } else {
                            sendError("Beklenmeyen giriş türü.");
                        }
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        if (pendingDelete) { pendingDelete = false; sendDeleted(false, "Doğrulama iptal edildi."); return; }
                        if (e instanceof GetCredentialCancellationException) sendError("Giriş iptal edildi.");
                        else sendError("Google girişi yapılamadı: " + e.getMessage());
                    }
                });
    }

    private void signInApple() {
        FirebaseAuth auth = auth();
        if (auth == null) { sendError("Giriş sistemi henüz ayarlanmadı (Firebase)."); return; }
        OAuthProvider.Builder pb = OAuthProvider.newBuilder("apple.com");
        pb.setScopes(Arrays.asList("email", "name"));
        pb.addCustomParameter("locale", "tr");
        auth.startActivityForSignInWithProvider(this, pb.build())
                .addOnSuccessListener(r -> sendUser(r.getUser(), "Apple"))
                .addOnFailureListener(e -> sendError("Apple girişi yapılamadı: " + e.getMessage()));
    }

    // ===================== Hesap silme =====================
    private void deleteAccount() {
        FirebaseAuth a = auth();
        FirebaseUser u = a == null ? null : a.getCurrentUser();
        if (u == null) { sendDeleted(true, null); return; }
        u.delete()
                .addOnSuccessListener(v -> afterDeleted())
                .addOnFailureListener(e -> {
                    if (e instanceof FirebaseAuthRecentLoginRequiredException) {
                        pendingDelete = true;
                        boolean apple = false;
                        for (UserInfo info : u.getProviderData()) if ("apple.com".equals(info.getProviderId())) apple = true;
                        if (apple) {
                            OAuthProvider.Builder pb = OAuthProvider.newBuilder("apple.com");
                            u.startActivityForReauthenticateWithProvider(this, pb.build())
                                    .addOnSuccessListener(r -> deleteNow())
                                    .addOnFailureListener(err -> { pendingDelete = false; sendDeleted(false, err.getMessage()); });
                        } else {
                            signInGoogle();
                        }
                    } else {
                        sendDeleted(false, e.getMessage());
                    }
                });
    }

    private void deleteNow() {
        FirebaseAuth a = auth();
        FirebaseUser u = a == null ? null : a.getCurrentUser();
        pendingDelete = false;
        if (u == null) { afterDeleted(); return; }
        u.delete().addOnSuccessListener(v -> afterDeleted())
                .addOnFailureListener(e -> sendDeleted(false, e.getMessage()));
    }

    private void afterDeleted() {
        FirebaseAuth a = auth();
        if (a != null) a.signOut();
        try { CredentialManager.create(this).clearCredentialStateAsync(
                new androidx.credentials.ClearCredentialStateRequest(), new CancellationSignal(), bg,
                new CredentialManagerCallback<Void, androidx.credentials.exceptions.ClearCredentialException>() {
                    @Override public void onResult(Void v) {}
                    @Override public void onError(@NonNull androidx.credentials.exceptions.ClearCredentialException e) {}
                }); } catch (Throwable ignored) {}
        sendDeleted(true, null);
    }

    private void sendDeleted(boolean ok, String err) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            if (!ok) o.put("error", err == null ? "Hesap silinemedi." : err);
            js("window.onNativeDeleted && window.onNativeDeleted(" + JSONObject.quote(o.toString()) + ")");
        } catch (Exception ignored) {}
    }

    private String userJson(FirebaseUser u, String provider) {
        if (u == null) return "";
        try {
            JSONObject o = new JSONObject();
            o.put("uid", u.getUid());
            String name = u.getDisplayName();
            String email = u.getEmail();
            if (provider == null) {
                provider = "";
                for (UserInfo info : u.getProviderData()) {
                    if ("google.com".equals(info.getProviderId())) provider = "Google";
                    if ("apple.com".equals(info.getProviderId())) provider = "Apple";
                    if ((name == null || name.isEmpty()) && info.getDisplayName() != null) name = info.getDisplayName();
                    if ((email == null || email.isEmpty()) && info.getEmail() != null) email = info.getEmail();
                }
            }
            o.put("name", name == null || name.isEmpty() ? (email != null ? email.split("@")[0] : "Kullanıcı") : name);
            o.put("email", email == null ? "" : email);
            o.put("photo", u.getPhotoUrl() == null ? "" : u.getPhotoUrl().toString());
            o.put("provider", provider);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void sendUser(FirebaseUser u, String provider) {
        String json = userJson(u, provider);
        js("window.onNativeAuth && window.onNativeAuth(" + JSONObject.quote(json) + ")");
    }

    private void sendError(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", msg);
            js("window.onNativeAuth && window.onNativeAuth(" + JSONObject.quote(o.toString()) + ")");
        } catch (Exception ignored) {}
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    // ===================== Reklam =====================
    private void startAds() {
        runOnUiThread(() -> {
            if (adsStarted) return;
            if (consent != null && !consent.canRequestAds()) return;
            adsStarted = true;
            try { MobileAds.initialize(this, status -> loadInterstitial()); } catch (Throwable ignored) {}
        });
    }

    private void loadInterstitial() {
        runOnUiThread(() -> {
            try {
                InterstitialAd.load(this, BuildConfig.ADMOB_INTERSTITIAL_ID, new AdRequest.Builder().build(),
                        new InterstitialAdLoadCallback() {
                            @Override public void onAdLoaded(@NonNull InterstitialAd ad) { interstitial = ad; }
                            @Override public void onAdFailedToLoad(@NonNull LoadAdError e) { interstitial = null; }
                        });
            } catch (Throwable ignored) {}
        });
    }

    private void showInterstitial() {
        runOnUiThread(() -> {
            if (interstitial == null) { loadInterstitial(); return; }
            InterstitialAd ad = interstitial;
            interstitial = null;
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override public void onAdDismissedFullScreenContent() { loadInterstitial(); }
                @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError e) { loadInterstitial(); }
            });
            ad.show(this);
        });
    }

    // ===================== Paylaşım =====================
    private void shareImage(String b64, String text) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            File dir = new File(getCacheDir(), "share");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "yukle-detayla-soru.png");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            if (text != null && !text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
            send.setClipData(ClipData.newRawUri("", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> startActivity(Intent.createChooser(send, "Soruyu paylaş")));
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Paylaşılamadı", Toast.LENGTH_SHORT).show());
        }
    }

    private void shareFile(String b64, String mime, String name, String text) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            File dir = new File(getCacheDir(), "share");
            if (!dir.exists()) dir.mkdirs();
            String safe = (name == null || name.isEmpty()) ? "yukle-detayla.pdf" : name.replaceAll("[^\\p{L}\\p{N}._-]", "-");
            File f = new File(dir, safe);
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime == null || mime.isEmpty() ? "application/pdf" : mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            if (text != null && !text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
            send.setClipData(ClipData.newRawUri("", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> startActivity(Intent.createChooser(send, "Testi paylaş")));
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Paylaşılamadı", Toast.LENGTH_SHORT).show());
        }
    }

    private void shareText(String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        runOnUiThread(() -> startActivity(Intent.createChooser(send, "Paylaş")));
    }

    // ===================== JavaScript köprüsü =====================
    private class Bridge {
        @JavascriptInterface public void signIn(String provider) {
            runOnUiThread(() -> { if ("apple".equals(provider)) signInApple(); else signInGoogle(); });
        }
        @JavascriptInterface public void signOut() {
            FirebaseAuth a = auth();
            if (a != null) a.signOut();
            try { CredentialManager.create(MainActivity.this).clearCredentialStateAsync(
                    new androidx.credentials.ClearCredentialStateRequest(), new CancellationSignal(), bg,
                    new CredentialManagerCallback<Void, androidx.credentials.exceptions.ClearCredentialException>() {
                        @Override public void onResult(Void v) {}
                        @Override public void onError(@NonNull androidx.credentials.exceptions.ClearCredentialException e) {}
                    }); } catch (Throwable ignored) {}
        }
        @JavascriptInterface public String getUser() {
            FirebaseAuth a = auth();
            return a == null ? "" : userJson(a.getCurrentUser(), null);
        }
        @JavascriptInterface public void showInterstitial() { MainActivity.this.showInterstitial(); }
        @JavascriptInterface public void deleteAccount() { runOnUiThread(MainActivity.this::deleteAccount); }
        @JavascriptInterface public void shareImage(String b64, String text) { MainActivity.this.shareImage(b64, text); }
        @JavascriptInterface public void shareText(String text) { MainActivity.this.shareText(text); }
        @JavascriptInterface public void shareFile(String b64, String mime, String name, String text) { MainActivity.this.shareFile(b64, mime, name, text); }
    }

    // ===================== Dosya / kamera =====================
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
        web.evaluateJavascript("(window.appBack && window.appBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) MainActivity.super.onBackPressed();
        });
    }
}
