package mx.ipn.escom.practica5.data.repo

import mx.ipn.escom.practica5.data.remote.AdminUserDto
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.BackendApi
import mx.ipn.escom.practica5.data.remote.FavoriteDto
import mx.ipn.escom.practica5.data.remote.HistoryDto

/**
 * Consultas del rol administrador. La interfaz oculta el panel a usuarios normales,
 * pero la autorización real la decide el backend (403 si no es admin o si el usuario
 * consultado no autorizó compartir sus datos).
 */
class AdminRepository(private val api: BackendApi) {
    suspend fun users(): ApiResult<List<AdminUserDto>> = api.adminUsers()
    suspend fun history(userId: Int): ApiResult<List<HistoryDto>> = api.adminHistory(userId)
    suspend fun favorites(userId: Int): ApiResult<List<FavoriteDto>> = api.adminFavorites(userId)
}
