import UIKit
import WebKit
import AuthenticationServices
import CryptoKit
import AppTrackingTransparency
import FirebaseCore
import FirebaseAuth
import GoogleSignIn
import GoogleMobileAds
import UserMessagingPlatform

// =====================================================================
// Yükle Detayla iOS kabuğu
// Web uygulaması (public/ klasörü) "yukle://app/" adresinden açılır.
// Web tarafı Android ile aynı köprüyü (window.Android) kullanır; burada
// aynı adla bir JavaScript katmanı enjekte edilir ve mesajlar "yd" kanalından gelir.
// =====================================================================
final class AppViewController: UIViewController {
  static let scheme = "yukle"
  private var web: WKWebView!

  // reklam
  private var adsStarted = false, askedConsent = false
  private var inter: InterstitialAd?
  private var interLoading = false
  static var adsReady: Bool {
    let id = (Bundle.main.object(forInfoDictionaryKey: "GADApplicationIdentifier") as? String) ?? ""
    return id.hasPrefix("ca-app-pub-") && !id.contains("3940256099942544")
  }

  // giriş
  private enum Pending { case none, signIn, deleteAccount }
  private var pending: Pending = .none
  private var currentNonce: String?
  // Apple girişi: denetleyici istek bitene kadar tutulur; pencere hiç açılmadan "iptal" dönerse bir kez yeniden denenir
  private var appleController: ASAuthorizationController?
  private var appleStartedAt = Date.distantPast
  private var appleRetried = false

  override var preferredStatusBarStyle: UIStatusBarStyle { .default }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = UIColor(named: "LaunchBG")

    let cfg = WKWebViewConfiguration()
    if let root = Bundle.main.url(forResource: "public", withExtension: nil) {
      cfg.setURLSchemeHandler(BundleSchemeHandler(root: root), forURLScheme: Self.scheme)
    }
    cfg.websiteDataStore = .default()
    cfg.userContentController.add(WeakMessageHandler(self), name: "yd")
    cfg.userContentController.addUserScript(WKUserScript(source: Self.bridgeJS(user: userJSON()),
                                                         injectionTime: .atDocumentStart, forMainFrameOnly: true))

    web = WKWebView(frame: view.bounds, configuration: cfg)
    web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    web.isOpaque = false
    web.backgroundColor = UIColor(named: "LaunchBG")
    web.scrollView.backgroundColor = UIColor(named: "LaunchBG")
    web.scrollView.contentInsetAdjustmentBehavior = .never
    web.allowsLinkPreview = false
    web.navigationDelegate = self
    web.uiDelegate = self
    view.addSubview(web)
    web.load(URLRequest(url: URL(string: "\(Self.scheme)://app/index.html")!))
  }

  // window.Android ile aynı arayüz (Android kabuğuyla birebir)
  static func bridgeJS(user: String) -> String {
    let u = jsString(user)
    return """
    (function(){
      window.__YD_USER = \(u);
      function post(m){ try{ window.webkit.messageHandlers.yd.postMessage(m); }catch(e){} }
      window.Android = {
        signIn: function(p){ post({a:'signIn', p:String(p)}); },
        signOut: function(){ window.__YD_USER=''; post({a:'signOut'}); },
        getUser: function(){ return window.__YD_USER || ''; },
        deleteAccount: function(){ post({a:'deleteAccount'}); },
        emailAuth: function(k, e, p){ post({a:'emailAuth', k:String(k), e:String(e), p:String(p||'')}); },
        showInterstitial: function(){ post({a:'ad'}); },
        shareImage: function(b, t){ post({a:'shareImage', b:String(b), t:String(t||'')}); },
        shareText: function(t){ post({a:'shareText', t:String(t||'')}); },
        shareFile: function(b, m, n, t){ post({a:'shareFile', b:String(b), m:String(m||''), n:String(n||''), t:String(t||'')}); }
      };
    })();
    """
  }

  static func jsString(_ s: String) -> String {
    let data = try? JSONSerialization.data(withJSONObject: [s], options: [])
    let arr = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
    return String(arr.dropFirst().dropLast())
  }

  private func js(_ code: String) {
    DispatchQueue.main.async { self.web.evaluateJavaScript(code, completionHandler: nil) }
  }

  // ---------------- köprü mesajları ----------------
  fileprivate func handle(_ body: [String: Any]) {
    switch body["a"] as? String ?? "" {
    case "signIn":
      pending = .signIn
      if (body["p"] as? String) == "apple" { startApple() } else { startGoogle() }
    case "signOut":
      try? Auth.auth().signOut()
      GIDSignIn.sharedInstance.signOut()
    case "deleteAccount":
      deleteAccount()
    case "emailAuth":
      emailAuth(kind: body["k"] as? String ?? "in", email: body["e"] as? String ?? "", pass: body["p"] as? String ?? "")
    case "ad":
      showInterstitial()
    case "shareImage":
      let data = Data(base64Encoded: body["b"] as? String ?? "", options: .ignoreUnknownCharacters)
      var items: [Any] = []
      if let d = data, let img = UIImage(data: d) { items.append(img) }
      if let t = body["t"] as? String, !t.isEmpty { items.append(t) }
      share(items)
    case "shareText":
      share([body["t"] as? String ?? ""])
    case "shareFile":
      guard let d = Data(base64Encoded: body["b"] as? String ?? "", options: .ignoreUnknownCharacters) else { return }
      let raw = (body["n"] as? String) ?? "yukle-detayla.pdf"
      let name = raw.isEmpty ? "yukle-detayla.pdf" : raw.replacingOccurrences(of: "/", with: "-")
      let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
      do { try d.write(to: url, options: .atomic) } catch { return }
      var items: [Any] = [url]
      if let t = body["t"] as? String, !t.isEmpty { items.append(t) }
      share(items)
    default: break
    }
  }

  private func share(_ items: [Any]) {
    guard !items.isEmpty else { return }
    let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
    vc.popoverPresentationController?.sourceView = view
    vc.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
    present(vc, animated: true)
  }

  // ---------------- kullanıcı ----------------
  private var firebaseReady: Bool { FirebaseApp.app() != nil }

  private func userJSON(_ forced: User? = nil, provider: String? = nil) -> String {
    guard firebaseReady, let u = forced ?? Auth.auth().currentUser else { return "" }
    var prov = provider ?? ""
    var name = u.displayName ?? ""
    var email = u.email ?? ""
    for info in u.providerData {
      if provider == nil {
        if info.providerID == "password", prov.isEmpty { prov = "E-posta" }
        if info.providerID == "google.com" { prov = "Google" }
        if info.providerID == "apple.com" { prov = "Apple" }
      }
      if name.isEmpty, let n = info.displayName { name = n }
      if email.isEmpty, let e = info.email { email = e }
    }
    if name.isEmpty { name = email.components(separatedBy: "@").first ?? "Kullanıcı" }
    if name.isEmpty { name = "Kullanıcı" }
    let o: [String: Any] = ["uid": u.uid, "name": name, "email": email,
                            "photo": u.photoURL?.absoluteString ?? "", "provider": prov]
    guard let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return "" }
    return s
  }

  private func sendUser(_ u: User?, provider: String) {
    let s = userJSON(u, provider: provider)
    let q = Self.jsString(s)
    js("window.__YD_USER=\(q); window.onNativeAuth && window.onNativeAuth(\(q));")
  }

  private func sendError(_ msg: String) {
    let o = ["error": msg]
    let s = (try? JSONSerialization.data(withJSONObject: o)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    js("window.onNativeAuth && window.onNativeAuth(\(Self.jsString(s)));")
  }

  private func sendDeleted(ok: Bool, error: String = "") {
    let o: [String: Any] = ok ? ["ok": true] : ["ok": false, "error": error]
    let s = (try? JSONSerialization.data(withJSONObject: o)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    if ok { js("window.__YD_USER='';") }
    js("window.onNativeDeleted && window.onNativeDeleted(\(Self.jsString(s)));")
  }

  // ---------------- e-posta ve şifre ----------------
  private func emailAuth(kind: String, email: String, pass: String) {
    guard firebaseReady else { sendError("Giriş sistemi henüz ayarlanmadı (Firebase)."); return }
    if kind == "forgot" {
      Auth.auth().sendPasswordReset(withEmail: email) { [weak self] error in
        guard let self = self else { return }
        if let error = error { self.sendReset(ok: false, error: Self.emailErr(error)) } else { self.sendReset(ok: true) }
      }
      return
    }
    let done: (AuthDataResult?, Error?) -> Void = { [weak self] result, error in
      guard let self = self else { return }
      if let error = error { self.sendError(Self.emailErr(error)); return }
      self.sendUser(result?.user, provider: "E-posta")
    }
    if kind == "up" { Auth.auth().createUser(withEmail: email, password: pass, completion: done) }
    else { Auth.auth().signIn(withEmail: email, password: pass, completion: done) }
  }
  private func sendReset(ok: Bool, error: String = "") {
    let o: [String: Any] = ok ? ["ok": true] : ["ok": false, "error": error]
    let s = (try? JSONSerialization.data(withJSONObject: o)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    js("window.onNativeReset && window.onNativeReset(\(Self.jsString(s)));")
  }
  static func emailErr(_ error: Error) -> String {
    let code = (error as NSError).code
    switch code {
    case 17008: return "Geçerli bir e-posta yaz."
    case 17026: return "Şifre en az 6 karakter olmalı."
    case 17007, 17025: return "Bu e-posta ile zaten üyelik var. Giriş yap."
    case 17004, 17009, 17011: return "E-posta ya da şifre hatalı."
    case 17006: return "E-posta ile giriş şu an kapalı."
    case 17010: return "Çok fazla deneme. Biraz sonra tekrar dene."
    case 17020: return "İnternet bağlantını kontrol et."
    default: return "İşlem yapılamadı. Tekrar dene. (\(code))"
    }
  }

  // ---------------- Google ----------------
  private func startGoogle() {
    guard firebaseReady, let clientID = FirebaseApp.app()?.options.clientID else {
      finishAuth(error: "Giriş sistemi henüz ayarlanmadı (Firebase)."); return
    }
    GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
    GIDSignIn.sharedInstance.signIn(withPresenting: self) { [weak self] result, error in
      guard let self = self else { return }
      if let error = error {
        let cancelled = (error as NSError).code == -5   // kGIDSignInErrorCodeCanceled
        self.finishAuth(error: cancelled ? "Giriş iptal edildi." : "Google girişi yapılamadı: \(error.localizedDescription)")
        return
      }
      guard let user = result?.user, let idToken = user.idToken?.tokenString else {
        self.finishAuth(error: "Google girişi okunamadı."); return
      }
      let cred = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: user.accessToken.tokenString)
      self.useCredential(cred, provider: "Google", appleAuthCode: nil)
    }
  }

  // ---------------- Apple ----------------
  private func startApple() {
    guard firebaseReady else { finishAuth(error: "Giriş sistemi henüz ayarlanmadı (Firebase)."); return }
    appleRetried = false
    performApple()
  }
  private func performApple() {
    let nonce = Self.randomNonce()
    currentNonce = nonce
    let req = ASAuthorizationAppleIDProvider().createRequest()
    req.requestedScopes = [.fullName, .email]
    req.nonce = Self.sha256(nonce)
    let c = ASAuthorizationController(authorizationRequests: [req])
    c.delegate = self
    c.presentationContextProvider = self
    appleController = c
    appleStartedAt = Date()
    c.performRequests()
  }

  // giriş veya silme için yeniden doğrulama
  private func useCredential(_ cred: AuthCredential, provider: String, appleAuthCode: String?) {
    if pending == .deleteAccount, let user = Auth.auth().currentUser {
      user.reauthenticate(with: cred) { [weak self] _, error in
        guard let self = self else { return }
        if let error = error { self.pending = .none; self.sendDeleted(ok: false, error: error.localizedDescription); return }
        self.revokeThenDelete(appleAuthCode: appleAuthCode)
      }
      return
    }
    Auth.auth().signIn(with: cred) { [weak self] result, error in
      guard let self = self else { return }
      self.pending = .none
      if let error = error { self.sendError("\(provider) girişi başarısız: \(error.localizedDescription)"); return }
      self.sendUser(result?.user, provider: provider)
    }
  }

  private func finishAuth(error: String) {
    if pending == .deleteAccount { pending = .none; sendDeleted(ok: false, error: error) }
    else { pending = .none; sendError(error) }
  }

  // ---------------- hesap silme ----------------
  private func deleteAccount() {
    guard firebaseReady, let user = Auth.auth().currentUser else { sendDeleted(ok: true); return }
    pending = .deleteAccount
    // Apple ile girenlerde Apple'daki izin de geri alınır: önce Apple'dan yeni bir yetki kodu alınır
    if user.providerData.contains(where: { $0.providerID == "apple.com" }) { startApple(); return }
    user.delete { [weak self] error in
      guard let self = self else { return }
      if let e = error as NSError?, e.code == 17014 {   // requiresRecentLogin
        if !user.providerData.contains(where: { $0.providerID == "google.com" }) {   // e-posta üyeliği
          self.pending = .none
          self.sendDeleted(ok: false, error: "Güvenlik için çıkış yapıp yeniden giriş yap, sonra hesabını tekrar sil.")
          return
        }
        self.startGoogle()   // yeniden doğrula, sonra sil
        return
      }
      self.pending = .none
      if let error = error { self.sendDeleted(ok: false, error: error.localizedDescription); return }
      GIDSignIn.sharedInstance.signOut()
      self.sendDeleted(ok: true)
    }
  }

  private func revokeThenDelete(appleAuthCode: String?) {
    let del = { [weak self] in
      guard let self = self, let user = Auth.auth().currentUser else { self?.pending = .none; self?.sendDeleted(ok: true); return }
      user.delete { [weak self] error in
        guard let self = self else { return }
        self.pending = .none
        if let error = error { self.sendDeleted(ok: false, error: error.localizedDescription); return }
        GIDSignIn.sharedInstance.signOut()
        self.sendDeleted(ok: true)
      }
    }
    if let code = appleAuthCode {
      Auth.auth().revokeToken(withAuthorizationCode: code) { _ in del() }
    } else { del() }
  }

  // ---------------- nonce ----------------
  static func randomNonce(length: Int = 32) -> String {
    let chars = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
    var result = ""
    var remaining = length
    while remaining > 0 {
      var bytes = [UInt8](repeating: 0, count: 16)
      _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
      for b in bytes where remaining > 0 && Int(b) < chars.count * (256 / chars.count) {
        result.append(chars[Int(b) % chars.count]); remaining -= 1
      }
    }
    return result
  }
  static func sha256(_ input: String) -> String {
    SHA256.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  // ---------------- izleme izni + reklamlar ----------------
  private var activeObs: NSObjectProtocol?
  override func viewDidAppear(_ animated: Bool) {
    super.viewDidAppear(animated)
    if activeObs == nil {
      activeObs = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
        self?.requestTrackingIfNeeded()
      }
    }
    requestTrackingIfNeeded()
  }
  private var trackingAsking = false
  private func requestTrackingIfNeeded() {
    guard Self.adsReady, !trackingAsking else { return }
    guard ATTrackingManager.trackingAuthorizationStatus == .notDetermined else { afterTracking(); return }
    trackingAsking = true
    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
      guard let self = self else { return }
      guard UIApplication.shared.applicationState == .active else { self.trackingAsking = false; return }
      ATTrackingManager.requestTrackingAuthorization { [weak self] status in
        DispatchQueue.main.async {
          guard let self = self else { return }
          self.trackingAsking = false
          if status != .notDetermined { self.afterTracking() }
        }
      }
    }
  }
  private func afterTracking() { if !askedConsent { askedConsent = true; setupAds() } }
  private func setupAds() {
    let info = ConsentInformation.shared
    info.requestConsentInfoUpdate(with: RequestParameters()) { [weak self] _ in
      guard let self = self else { return }
      ConsentForm.loadAndPresentIfRequired(from: self) { [weak self] _ in self?.startAdsIfAllowed() }
    }
    if info.canRequestAds { startAdsIfAllowed() }
  }
  private func startAdsIfAllowed() {
    guard ConsentInformation.shared.canRequestAds, !adsStarted else { return }
    adsStarted = true
    MobileAds.shared.start(completionHandler: nil)
    loadInter()
  }
  private func loadInter() {
    guard adsStarted, inter == nil, !interLoading else { return }
    interLoading = true
    let unit = (Bundle.main.object(forInfoDictionaryKey: "YDAdInterstitial") as? String) ?? ""
    InterstitialAd.load(with: unit, request: Request()) { [weak self] ad, _ in
      self?.interLoading = false; self?.inter = ad
    }
  }
  private func showInterstitial() {
    guard let ad = inter else { loadInter(); return }
    inter = nil
    ad.fullScreenContentDelegate = self
    ad.present(from: self)
  }
}

extension AppViewController: FullScreenContentDelegate {
  func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) { loadInter() }
  func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) { loadInter() }
}

extension AppViewController: ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
  func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
    if let w = view.window { return w }
    let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }
    return windows.first(where: { $0.isKeyWindow }) ?? windows.first ?? ASPresentationAnchor()
  }
  func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
    appleController = nil
    guard let cred = authorization.credential as? ASAuthorizationAppleIDCredential,
          let nonce = currentNonce,
          let tokenData = cred.identityToken, let idToken = String(data: tokenData, encoding: .utf8) else {
      finishAuth(error: "Apple girişi okunamadı."); return
    }
    let code = cred.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
    let fc = OAuthProvider.appleCredential(withIDToken: idToken, rawNonce: nonce, fullName: cred.fullName)
    useCredential(fc, provider: "Apple", appleAuthCode: code)
  }
  func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
    appleController = nil
    let ns = error as NSError
    let cancelled = ns.domain == ASAuthorizationErrorDomain && ns.code == ASAuthorizationError.Code.canceled.rawValue
    // Giriş penceresi açılmadan (1 sn içinde) "iptal" dönerse kullanıcı iptal etmemiştir: bir kez yeniden dene
    if cancelled && !appleRetried && Date().timeIntervalSince(appleStartedAt) < 1.0 {
      appleRetried = true
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { [weak self] in self?.performApple() }
      return
    }
    if cancelled && Date().timeIntervalSince(appleStartedAt) < 1.0 {
      finishAuth(error: "Apple ile giriş penceresi açılamadı. Ayarlar'da Apple Hesabına giriş yapıldığını kontrol edip tekrar dene.")
      return
    }
    finishAuth(error: cancelled ? "Giriş iptal edildi." : "Apple girişi yapılamadı (\(ns.code)): \(error.localizedDescription)")
  }
}

// WKUserContentController denetleyiciyi güçlü tutar; döngüyü kırmak için zayıf aracı
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
  weak var target: AppViewController?
  init(_ t: AppViewController) { target = t }
  func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
    if let body = message.body as? [String: Any] { target?.handle(body) }
  }
}

// dış bağlantılar Safari'de açılır
extension AppViewController: WKNavigationDelegate, WKUIDelegate {
  func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
               decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
    guard let url = action.request.url else { decisionHandler(.allow); return }
    if url.scheme == Self.scheme || url.scheme == "about" || url.scheme == "data" || url.scheme == "blob" {
      decisionHandler(.allow); return
    }
    if action.targetFrame?.isMainFrame == false && (url.scheme == "https" || url.scheme == "http") && action.navigationType == .other {
      decisionHandler(.allow); return
    }
    UIApplication.shared.open(url)
    decisionHandler(.cancel)
  }
  func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { webView.reload() }
}

// Uygulama içindeki public/ klasörünü "yukle://app/..." adresinden sunar
private final class BundleSchemeHandler: NSObject, WKURLSchemeHandler {
  let root: URL
  init(root: URL) { self.root = root.standardizedFileURL }

  func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
    guard let url = task.request.url else { task.didFailWithError(URLError(.badURL)); return }
    var path = url.path
    if path.isEmpty || path == "/" { path = "/index.html" }
    let file = root.appendingPathComponent(String(path.dropFirst())).standardizedFileURL
    guard file.path.hasPrefix(root.path), let data = try? Data(contentsOf: file) else {
      let r = HTTPURLResponse(url: url, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/plain"])!
      task.didReceive(r); task.didReceive(Data()); task.didFinish(); return
    }
    let headers = ["Content-Type": Self.mime(file.pathExtension), "Content-Length": String(data.count), "Cache-Control": "no-cache"]
    let r = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
    task.didReceive(r); task.didReceive(data); task.didFinish()
  }
  func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}

  static func mime(_ ext: String) -> String {
    switch ext.lowercased() {
    case "html", "htm": return "text/html; charset=utf-8"
    case "js": return "text/javascript; charset=utf-8"
    case "css": return "text/css; charset=utf-8"
    case "json": return "application/json"
    case "png": return "image/png"
    case "svg": return "image/svg+xml"
    default: return "application/octet-stream"
    }
  }
}
