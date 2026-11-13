# Yükle Detayla — Android Uygulaması Kurulum Rehberi

Uygulama 3 parçadan oluşur ve **tamamen ücretsizdir**:

1. **Android uygulaması** (bu klasör) → Codemagic'te APK olarak derlenir.
2. **Yapay zekâ sunucusu** (`worker/worker.js`) → Cloudflare'de ücretsiz çalışır, anahtarı saklar.
3. **Google Gemini ücretsiz API anahtarı** → kredi kartı gerekmez.

> Ücretsiz kotada günde yaklaşık 150+ PDF işlenebilir (her PDF 3 istek; bir model dolunca
> sunucu otomatik olarak sıradaki ücretsiz modele geçer). Ücretsiz kotada gönderilen içerik
> Google tarafından ürünlerini geliştirmek için kullanılabilir.

---

## 1. Ücretsiz Gemini anahtarı al

1. https://aistudio.google.com adresine Google hesabınla gir.
2. **Get API key → Create API key** → anahtarı kopyala (`AIza...` ile başlar).
3. Faturalandırma (billing) EKLEME — eklemezsen hiçbir zaman ücret çıkmaz.

## 2. Cloudflare sunucusunu kur

1. https://dash.cloudflare.com adresinde ücretsiz hesap aç.
2. Sol menü: **Compute (Workers) → Workers & Pages → Create → Create Worker**.
3. Adını `yukle-detayla` yap → **Deploy**.
4. **Edit code**'a bas, oradaki kodu tamamen sil, `worker/worker.js` dosyasının içeriğini yapıştır → **Deploy**.
5. Worker sayfasında **Settings → Variables and Secrets → Add**:
   - `GEMINI_API_KEY` → Tür: **Secret** → 1. adımdaki `AIza...` anahtarın
6. Worker'ın adresini kopyala (ör. `https://yukle-detayla.KULLANICIADIN.workers.dev`).

## 3. Uygulamaya sunucu adresini yaz

`app/src/main/assets/config.js` dosyasını aç ve `apiUrl` satırına 2. adımdaki adresi yaz:

```js
apiUrl: "https://yukle-detayla.KULLANICIADIN.workers.dev",
```

## 4. GitHub'a yükle

1. GitHub'da yeni bir depo aç: `yukle-detayla` (Private olabilir).
2. Bu klasördeki tüm dosyaları depoya yükle (**Add file → Upload files**, klasörleri sürükleyerek).
   `codemagic.yaml` dosyası en üst seviyede olmalı.

## 5. Codemagic'te APK derle

1. https://codemagic.io → **Add application** → GitHub → `yukle-detayla` deposunu seç.
2. Proje tipi sorulursa **codemagic.yaml** seç.
3. **Start new build** → workflow: **Yükle Detayla APK** → Start.
4. Derleme bitince **Artifacts** bölümünden `app-debug.apk` dosyasını indir ve telefona kur.

---

## Sorun giderme

| Sorun | Çözüm |
|---|---|
| "Yapay zekâ sunucusu ayarlı değil" | 3. adımdaki `apiUrl` yazılmamış. |
| "Yetkisiz" | `worker.js` içindeki `APP_KEY` ile `config.js` içindeki `appKey` aynı değil. |
| "Günlük ücretsiz kullanım sınırı" | Tüm ücretsiz modellerin günlük kotası dolmuş; ertesi gün açılır. |
| "Sunucuda GEMINI_API_KEY ayarlı değil" | 2. adım 5'teki anahtar eklenmemiş. |

## Play Store için

Bu derleme test içindir (debug APK). Play Store'a yüklemek için imzalı **AAB** gerekir;
istediğinde Codemagic ayarını buna göre güncelleriz.
