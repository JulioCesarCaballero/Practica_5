import UIKit
import GoogleSignIn
import ComposeApp

/// Implementa el contrato que Kotlin declaró (IosGoogleSignInHandler) con el SDK oficial de Google.
final class GoogleSignInHandler: NSObject, IosGoogleSignInHandler {

    func requestIdToken(webClientId: String, callback: IosGoogleSignInCallback) {
        let iosClientId = AppConfig.value("GIDClientID")
        guard !iosClientId.isEmpty else {
            callback.onError(message: "Falta IOS_CLIENT_ID en Config.xcconfig")
            return
        }
        // serverClientID = client ID web => el id_token trae esa audiencia y el backend lo acepta.
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: iosClientId,
            serverClientID: webClientId
        )
        guard let presenter = Self.topViewController() else {
            callback.onError(message: "No hay pantalla desde donde mostrar Google")
            return
        }
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
            if let error = error as NSError? {
                if error.domain == kGIDSignInErrorDomain,
                   error.code == GIDSignInError.canceled.rawValue {
                    callback.onCancelled()
                } else {
                    callback.onError(message: "Google: \(error.localizedDescription)")
                }
                return
            }
            guard let token = result?.user.idToken?.tokenString else {
                callback.onError(message: "Google no devolvió id_token")
                return
            }
            callback.onSuccess(idToken: token)
        }
    }

    func signOut() {
        GIDSignIn.sharedInstance.signOut()
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
