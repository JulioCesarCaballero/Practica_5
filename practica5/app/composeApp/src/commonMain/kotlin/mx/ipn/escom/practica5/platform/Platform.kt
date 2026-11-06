package mx.ipn.escom.practica5.platform

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** Dispatcher para E/S (base de datos, red). */
expect val ioDispatcher: CoroutineDispatcher

/** UUID aleatorio (v4) para identificar operaciones de forma idempotente. */
expect fun randomUuid(): String

/** Crea el cliente HTTP con el motor de la plataforma (OkHttp en Android, Darwin en iOS). */
expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

/** Botón/gesto "atrás" del sistema. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Almacenamiento seguro de la sesión (EncryptedSharedPreferences en Android, Keychain en iOS). */
interface SessionStorage {
    fun save(json: String)
    fun load(): String?
    fun clear()
}

/** Estado de conectividad observable en tiempo real. */
interface ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>
}

sealed interface GoogleSignInResult {
    data class Success(val idToken: String) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class Error(val message: String) : GoogleSignInResult
}

/** Inicio de sesión con Google propio de cada plataforma (Credential Manager en Android). */
interface GoogleAuthClient {
    suspend fun requestIdToken(): GoogleSignInResult
    suspend fun signOut()
}
