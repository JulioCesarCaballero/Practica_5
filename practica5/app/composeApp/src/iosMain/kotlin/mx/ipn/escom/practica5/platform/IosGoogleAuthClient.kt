package mx.ipn.escom.practica5.platform

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Callback que Swift invoca con el resultado de Google Sign-In. */
interface IosGoogleSignInCallback {
    fun onSuccess(idToken: String)
    fun onCancelled()
    fun onError(message: String)
}

/**
 * Lo implementa Swift (GoogleSignInHandler.swift) con el SDK oficial GoogleSignIn-iOS,
 * porque ese SDK es Swift/Objective-C y necesita un UIViewController para presentarse.
 */
interface IosGoogleSignInHandler {
    fun requestIdToken(webClientId: String, callback: IosGoogleSignInCallback)
    fun signOut()
}

/**
 * Adaptador a la interfaz común [GoogleAuthClient]. [webClientId] es el client ID de tipo
 * "Aplicación web": el ID token queda con esa audiencia y el backend lo valida igual que en Android.
 */
class IosGoogleAuthClient(
    private val handler: IosGoogleSignInHandler,
    private val webClientId: String,
) : GoogleAuthClient {

    override suspend fun requestIdToken(): GoogleSignInResult {
        if (webClientId.isBlank()) {
            return GoogleSignInResult.Error("Falta WEB_CLIENT_ID en Config.xcconfig")
        }
        return suspendCancellableCoroutine { cont ->
            handler.requestIdToken(webClientId, object : IosGoogleSignInCallback {
                override fun onSuccess(idToken: String) {
                    if (cont.isActive) cont.resume(GoogleSignInResult.Success(idToken))
                }

                override fun onCancelled() {
                    if (cont.isActive) cont.resume(GoogleSignInResult.Cancelled)
                }

                override fun onError(message: String) {
                    if (cont.isActive) cont.resume(GoogleSignInResult.Error(message))
                }
            })
        }
    }

    override suspend fun signOut() {
        runCatching { handler.signOut() }
    }
}
