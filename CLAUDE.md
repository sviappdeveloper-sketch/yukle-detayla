# Yükle Detayla — Claude devir notu

Bu dosya, uygulamada hangi Claude hesabı veya oturumu çalışırsa çalışsın kaldığı yerden devam edebilmesi için tutulur.
GitHub: `sviappdeveloper-sketch/yukle-detayla` (herkese açık depo; APK'lar ücretsiz derlensin diye)

## Çalışma kuralları (her oturumda uy)
- Kullanıcı kodlama bilmiyor ve Türkçe yazıyor. Türkçe ve sade cevap ver.
- Kullanıcı açıkça istemedikçe son kayıtlı sürümdeki hiçbir özelliği silme veya kaybetme. Değiştirmeden önce mevcut olanı diğer dallar dahil kontrol et.
- Uygulamaları birbirine karıştırma. Bir uygulamanın Firebase, giriş veya mağaza ayarı değişirken diğerlerinin ayarına dokunma.
- Geliştirici adını her yerde tam olarak **Svi App Developer** diye yaz. Site sviapp.com, iletişim adresi iletisim@sviapp.com.
- Derlemeyi teslim ederken yalnızca APK dosyasının kendisini gönder. Zip, rar veya yanında başka dosya olmasın.
- Tüm testlerde (TestFlight ve Play) sitaresivi@icloud.com test kullanıcısı olarak ekli olsun.
- Mağaza ve konsol adımlarını (AdMob, Play Console, App Store Connect, Firebase) mümkün olduğunca kendin yap.
- Derlemeler ücretsiz yoldan, GitHub Actions ile yapılıyor. Codemagic dakikaları bitti.
- **Bu dosyayı güncel tut:** Her oturumun sonunda yaptığın işi tarihiyle "Son işlemler" bölümünün en üstüne ekle, "Bekleyen işler" bölümünü güncelle, sonra commit'leyip push'la.

## Uygulama durumu
- Kullanıcı PDF yüklüyor, uygulama yapay zekâ ile özet ve test üretiyor. Sürüm **1.2**.
- Android tarafı Gradle ile, iOS tarafı `ios/` klasöründe. Yapay zekâ sunucusu `worker/` klasöründe. Sunucu yalnızca giriş yapmış üyelere, kişi başı günlük sınırla hizmet veriyor.
- Gerçek AdMob reklam kimlikleri kullanılıyor (test reklamı değil).
- GitHub Actions iş akışları: `android-apk.yml` (APK ve AAB), `appstore.yml`, `appstore-gonder.yml`, `testflight.yml`.
- Dallar: main, aab, apk-build, play-api-36.

## Son işlemler (en yeni en üstte)
- 2026-11-26: İletişim e-postası iletisim@sviapp.com oldu.
- 2026-11-26: TestFlight'a sitaresivi@icloud.com test kullanıcısı olarak eklendi; gerekirse dış test grubu kuruluyor.
- 2026-11-26: Giriş ekranında "E-posta ile giriş" düğmesi, Google ve Apple düğmeleri kadar belirgin yapıldı.
- 2026-11-26: Gerçek AdMob kimlikleri (iOS ve Android) eklendi. iOS'ta izleme izni (ATT) penceresi gösteriliyor (App Review 2.1).
- 2026-11-24: APK derlemesi Play Store paketini (AAB) de üretiyor.
- 2026-11-23: Güvenlik: yapay zekâya yalnızca üyeler erişebiliyor ve günlük sınır var.
- 2026-11-19: 1.2 sürümü: PDF'de iki parmakla yakınlaştırma, PDF silme, Belgelerim, kısa/uzun/yeni özet, kısa/uzun/yeni test ve konulara göre test. App Store derlemesi GitHub Actions'a taşındı.

## Bekleyen işler / kontrol edilecekler
- 1.2'nin App Store'daki inceleme ve yayın durumunu App Store Connect'ten kontrol et.
