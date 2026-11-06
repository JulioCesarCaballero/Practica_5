package mx.ipn.escom.practica5.platform

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * "Iniciar sesión con Google" usando Credential Manager.
 * [webClientId] es el client ID de tipo "Aplicación web" (el backend valida el token contra él).
 * El client ID de tipo Android (paquete + SHA-1) sólo se registra en Google Cloud.
 */
class AndroidGoogleAuthClient(
    private val activity: Activity,
    private val webClientId: String,
) : GoogleAuthClient {
    private val credentialManager = CredentialManager.create(activity)

    override suspend fun requestIdToken(): GoogleSignInResult {
        if (webClientId.isBlank()) {
            return GoogleSignInResult.Error("Falta GOOGLE_WEB_CLIENT_ID en local.properties")
        }
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleSignInResult.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleSignInResult.Error("Tipo de credencial no soportado")
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.Error("No hay cuentas de Google disponibles en el dispositivo")
        } catch (e: GetCredentialException) {
            GoogleSignInResult.Error("Google: ${e.message ?: e.type}")
        } catch (e: GoogleIdTokenParsingException) {
            GoogleSignInResult.Error("Respuesta de Google inválida")
        }
    }

    override suspend fun signOut() {
        runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
    }
}
