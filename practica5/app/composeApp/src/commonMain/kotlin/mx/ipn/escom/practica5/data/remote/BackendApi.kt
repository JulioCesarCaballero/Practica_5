package mx.ipn.escom.practica5.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Cliente del backend REST propio (Flask). Toda la comunicación es HTTPS.
 * [tokenProvider] entrega el JWT actual; [onUnauthorized] se invoca ante un 401 con token.
 */
class BackendApi(
    private val client: HttpClient,
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
) {
    private val base = baseUrl.trimEnd('/') + "/api"

    val isConfigured: Boolean = baseUrl.startsWith("https://")

    private fun HttpRequestBuilder.auth() {
        tokenProvider()?.let { bearerAuth(it) }
    }

    private inline fun <reified T : Any> HttpRequestBuilder.jsonBody(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private val unauthorizedHook: () -> Unit = { if (tokenProvider() != null) onUnauthorized() }

    // ---------- autenticación ----------
    suspend fun register(req: RegisterRequest): ApiResult<AuthResponse> =
        safeCall { client.post("$base/auth/register") { jsonBody(req) } }

    suspend fun login(req: LoginRequest): ApiResult<AuthResponse> =
        safeCall { client.post("$base/auth/login") { jsonBody(req) } }

    suspend fun loginWithGoogle(idToken: String): ApiResult<AuthResponse> =
        safeCall { client.post("$base/auth/google") { jsonBody(GoogleLoginRequest(idToken)) } }

    suspend fun me(): ApiResult<UserDto> =
        safeCall(unauthorizedHook) { client.get("$base/auth/me") { auth() } }

    /** Revoca el token en el servidor. No dispara el cierre automático ante 401. */
    suspend fun logout(): ApiResult<Unit> =
        safeCall { client.post("$base/auth/logout") { auth() } }

    suspend fun updatePreferences(shareWithAdmin: Boolean): ApiResult<UserDto> =
        safeCall(unauthorizedHook) {
            client.put("$base/me/preferences") { auth(); jsonBody(PreferencesRequest(shareWithAdmin)) }
        }

    // ---------- CRUD lista de lectura ----------
    suspend fun listItems(): ApiResult<List<ReadingItemDto>> =
        safeCall(unauthorizedHook) { client.get("$base/books") { auth() } }

    suspend fun createItem(req: ReadingItemRequest): ApiResult<ReadingItemDto> =
        safeCall(unauthorizedHook) { client.post("$base/books") { auth(); jsonBody(req) } }

    suspend fun updateItem(id: Int, req: ReadingItemRequest): ApiResult<ReadingItemDto> =
        safeCall(unauthorizedHook) { client.put("$base/books/$id") { auth(); jsonBody(req) } }

    suspend fun deleteItem(id: Int): ApiResult<Unit> =
        safeCall(unauthorizedHook) { client.delete("$base/books/$id") { auth() } }

    // ---------- favoritos (idempotentes) ----------
    suspend fun listFavorites(): ApiResult<List<FavoriteDto>> =
        safeCall(unauthorizedHook) { client.get("$base/favorites") { auth() } }

    suspend fun putFavorite(workId: String, req: FavoriteRequest): ApiResult<FavoriteDto> =
        safeCall(unauthorizedHook) { client.put("$base/favorites/$workId") { auth(); jsonBody(req) } }

    suspend fun deleteFavorite(workId: String): ApiResult<Unit> =
        safeCall(unauthorizedHook) { client.delete("$base/favorites/$workId") { auth() } }

    // ---------- historial (idempotente por client_id) ----------
    suspend fun listHistory(): ApiResult<List<HistoryDto>> =
        safeCall(unauthorizedHook) { client.get("$base/history") { auth() } }

    suspend fun addHistory(entry: HistoryDto): ApiResult<Unit> =
        safeCall(unauthorizedHook) { client.post("$base/history") { auth(); jsonBody(entry) } }

    // ---------- administración (permiso validado en el servidor) ----------
    suspend fun adminUsers(): ApiResult<List<AdminUserDto>> =
        safeCall(unauthorizedHook) { client.get("$base/admin/users") { auth() } }

    suspend fun adminHistory(userId: Int): ApiResult<List<HistoryDto>> =
        safeCall(unauthorizedHook) { client.get("$base/admin/users/$userId/history") { auth() } }

    suspend fun adminFavorites(userId: Int): ApiResult<List<FavoriteDto>> =
        safeCall(unauthorizedHook) { client.get("$base/admin/users/$userId/favorites") { auth() } }
}
