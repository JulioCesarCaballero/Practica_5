package mx.ipn.escom.practica5.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import mx.ipn.escom.practica5.data.local.LocalStore
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.BackendApi
import mx.ipn.escom.practica5.data.remote.ReadingItemDto
import mx.ipn.escom.practica5.data.remote.ReadingItemRequest
import mx.ipn.escom.practica5.data.remote.map
import mx.ipn.escom.practica5.domain.ReadingItem
import mx.ipn.escom.practica5.platform.ConnectivityMonitor
import mx.ipn.escom.practica5.platform.ioDispatcher

private fun ReadingItemDto.toDomain() = ReadingItem(id, title, author, status, notes, updatedAt)

/**
 * CRUD de la Práctica 2 (GET, POST, PUT, DELETE sobre /api/books).
 * La lectura funciona offline desde la caché; las escrituras requieren servidor y
 * muestran el error correspondiente si no hay conexión.
 */
class ReadingRepository(
    private val api: BackendApi,
    private val store: LocalStore,
    private val auth: AuthRepository,
    private val connectivity: ConnectivityMonitor,
) {
    private fun uid() = auth.currentUser?.localId

    fun itemsFlow(): Flow<List<ReadingItem>> = uid()?.let { store.readingFlow(it) } ?: emptyFlow()

    private fun offline(): ApiResult.NetworkError? =
        if (connectivity.isOnline.value) null
        else ApiResult.NetworkError("sin conexión; esta operación requiere al servidor y no se aplicó")

    suspend fun refresh(): ApiResult<List<ReadingItem>> {
        offline()?.let { return it }
        val uid = uid() ?: return ApiResult.HttpError(401, "Sin sesión")
        val r = api.listItems().map { list -> list.map { it.toDomain() } }
        if (r is ApiResult.Success) withContext(ioDispatcher) { store.replaceReading(uid, r.data) }
        return r
    }

    suspend fun create(title: String, author: String, status: String, notes: String): ApiResult<ReadingItem> {
        offline()?.let { return it }
        val uid = uid() ?: return ApiResult.HttpError(401, "Sin sesión")
        val r = api.createItem(ReadingItemRequest(title.trim(), author.trim(), status, notes.trim())).map { it.toDomain() }
        if (r is ApiResult.Success) withContext(ioDispatcher) { store.upsertReading(uid, r.data) }
        return r
    }

    suspend fun update(id: Int, title: String, author: String, status: String, notes: String): ApiResult<ReadingItem> {
        offline()?.let { return it }
        val uid = uid() ?: return ApiResult.HttpError(401, "Sin sesión")
        val r = api.updateItem(id, ReadingItemRequest(title.trim(), author.trim(), status, notes.trim())).map { it.toDomain() }
        if (r is ApiResult.Success) withContext(ioDispatcher) { store.upsertReading(uid, r.data) }
        if (r is ApiResult.HttpError && r.code == 404) withContext(ioDispatcher) { store.deleteReading(uid, id) }
        return r
    }

    suspend fun delete(id: Int): ApiResult<Unit> {
        offline()?.let { return it }
        val uid = uid() ?: return ApiResult.HttpError(401, "Sin sesión")
        val r = api.deleteItem(id)
        if (r is ApiResult.Success || (r is ApiResult.HttpError && r.code == 404)) {
            withContext(ioDispatcher) { store.deleteReading(uid, id) }
        }
        return r
    }
}
