import SwiftUI
import GoogleSignIn

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // Regreso de Google Sign-In (esquema URL = IOS_REVERSED_CLIENT_ID)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
    }
}
