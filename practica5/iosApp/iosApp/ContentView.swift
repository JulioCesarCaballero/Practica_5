import SwiftUI
import UIKit
import ComposeApp

/// Configuración sin secretos: viene de Config.xcconfig (ignorado por git) vía Info.plist.
enum AppConfig {
    static func value(_ key: String) -> String {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
    static var apiBaseUrl: String { value("API_BASE_URL") }
    static var webClientId: String { value("WEB_CLIENT_ID") }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            handler: GoogleSignInHandler(),
            apiBaseUrl: AppConfig.apiBaseUrl,
            webClientId: AppConfig.webClientId
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard) // el teclado lo maneja Compose
    }
}
