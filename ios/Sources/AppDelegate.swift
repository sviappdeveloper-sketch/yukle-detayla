import UIKit
import FirebaseCore
import GoogleSignIn

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  func application(_ application: UIApplication,
                   didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
      FirebaseApp.configure()
    }
    let w = UIWindow(frame: UIScreen.main.bounds)
    w.backgroundColor = UIColor(named: "LaunchBG")
    w.rootViewController = AppViewController()
    w.makeKeyAndVisible()
    window = w
    return true
  }

  // Google girişinden dönüş
  func application(_ app: UIApplication, open url: URL,
                   options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
    return GIDSignIn.sharedInstance.handle(url)
  }
}
