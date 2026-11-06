package mx.ipn.escom.practica5.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.AuthResponse
import mx.ipn.escom.practica5.data.remote.BackendApi
import mx.ipn.escom.practica5.data.remote.LoginRequest
import mx.ipn.escom.practica5.data.remote.RegisterRequest
import mx.ipn.escom.practica5.data.remote.UserDto
import mx.ipn.escom.practica5.data.remote.appJson
import mx.ipn.escom.practica5.domain.User
import mx.ipn.escom.practica5.platform.GoogleAuthClient
import mx.ipn.escom.practica5.platform.GoogleSignInResult
import mx.ipn.escom.practica5.platform.SessionStorage
import mx.ipn.escom.practica5.platform.nowMillis

@Serializable
data class StoredSession(val token: String, val user: UserDto, val validatedAt: Long)

data class Session(val token: String, val user: User, val validatedAt: Long)

fun UserDto.toDomain() = User(id, name, email, role, authProvider, shareWithAdmin)

/**
 * Maneja registro, login (correo/contraseña y Google), restauración y cierre de sesión.
 * El JWT se guarda cifrado mediante [SessionStorage]; nunca se escribe en logs.
 */
class AuthRepository(private val storage: SessionStorage) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Se inyecta después de construir el cliente (el cliente necesita el token de aquí). */
    lateinit var api: BackendApi

    var googleClient: GoogleAuthClient? = null

    val currentToken: String? get() = _session.value?.token
    val currentUser: User? get() = _session.value?.user

    /** Restaura la sesión guardada (funciona sin conexión). */
    fun restore() {
        val json = storage.load() ?: return
        val stored = runCatching { appJson.decodeFromString(StoredSession.serializer(), json) }.getOrNull()
        if (stored == null) {
            storage.clear()
            return
        }
        _session.value = Session(stored.token, stored.user.toDomain(), stored.validatedAt)
    }

    private fun persist(token: String, user: UserDto) {
        val now = nowMillis()
        storage.save(appJson.encodeToString(StoredSession.serializer(), StoredSession(token, user, now)))
        _session.value = Session(token, user.toDomain(), now)
    }

    private fun handleAuth(result: ApiResult<AuthResponse>): ApiResult<User> = when (result) {
        is ApiResult.Success -> {
            persist(result.data.token, result.data.user)
            ApiResult.Success(result.data.user.toDomain(), result.elapsedMs)
        }
        is ApiResult.HttpError -> result
        is ApiResult.NetworkError -> result
        is ApiResult.Malformed -> result
    }

    suspend fun login(email: String, password: String): ApiResult<User> =
        handleAuth(api.login(LoginRequest(email.trim(), password)))

    suspend fun register(name: String, email: String, password: String): ApiResult<User> =
        handleAuth(api.register(RegisterRequest(name.trim(), email.trim(), password)))

    /** Obtiene el ID token con el SDK nativo y lo valida en el backend. */
    suspend fun loginWithGoogle(client: GoogleAuthClient): ApiResult<User>? {
        googleClient = client
        return when (val r = client.requestIdToken()) {
            is GoogleSignInResult.Success -> handleAuth(api.loginWithGoogle(r.idToken))
            is GoogleSignInResult.Cancelled -> null
            is GoogleSignInResult.Error -> ApiResult.NetworkError(r.message)
        }
    }

    /** Revalida el token con el servidor cuando hay red. Un 401 cierra la sesión (vía onUnauthorized). */
    suspend fun revalidate(): ApiResult<UserDto> {
        val token = currentToken ?: return ApiResult.HttpError(401, "Sin sesión")
        val result = api.me()
        if (result is ApiResult.Success && currentToken == token) persist(token, result.data)
        return result
    }

    fun updateUser(user: UserDto) {
        val token = currentToken ?: return
        persist(token, user)
    }

    /** Actualiza la preferencia localmente (también cuando se cambia sin conexión). */
    fun updateLocalUser(transform: (User) -> User) {
        val s = _session.value ?: return
        val updated = transform(s.user)
        val dto = UserDto(updated.id, updated.name, updated.email, updated.role, updated.authProvider, updated.shareWithAdmin)
        storage.save(appJson.encodeToString(StoredSession.serializer(), StoredSession(s.token, dto, s.validatedAt)))
        _session.value = s.copy(user = updated)
    }

    /** Cierre de sesión: revoca el token en el servidor (si hay red) y borra la sesión local. */
    suspend fun logout(revokeRemotely: Boolean = true) {
        if (revokeRemotely && currentToken != null) runCatching { api.logout() }
        runCatching { googleClient?.signOut() }
        clearLocal()
    }

    /** Usado ante un 401: la sesión ya no es válida en el servidor. */
    fun clearLocal() {
        storage.clear()
        _session.value = null
    }

    private val _notice = MutableStateFlow<String?>(null)
    /** Mensaje para la pantalla de login (p. ej. "La sesión expiró"). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun expire(message: String) {
        if (_session.value == null) return
        clearLocal()
        _notice.value = message
    }

    fun consumeNotice() {
        _notice.value = null
    }
}
