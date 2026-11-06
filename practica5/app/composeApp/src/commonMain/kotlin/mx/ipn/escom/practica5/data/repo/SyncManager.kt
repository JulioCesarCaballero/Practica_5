package mx.ipn.escom.practica5.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import mx.ipn.escom.practica5.data.local.LocalStore
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.BackendApi
import mx.ipn.escom.practica5.data.remote.FavoriteDto
import mx.ipn.escom.practica5.data.remote.FavoriteRequest
import mx.ipn.escom.practica5.data.remote.HistoryDto
import mx.ipn.escom.practica5.data.remote.appJson
import mx.ipn.escom.practica5.data.remote.isRetryable
import mx.ipn.escom.practica5.data.remote.map
import mx.ipn.escom.practica5.data.remote.userMessage
import mx.ipn.escom.practica5.db.Pending_op
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.domain.HistoryEntry
import mx.ipn.escom.practica5.domain.ReadingItem
import mx.ipn.escom.practica5.platform.ConnectivityMonitor
import mx.ipn.escom.practica5.platform.ioDispatcher
import mx.ipn.escom.practica5.platform.nowMillis

object OpType {
    const val FAV_ADD = "FAV_ADD"
    const val FAV_REMOVE = "FAV_REMOVE"
    const val HISTORY_ADD = "HISTORY_ADD"
    const val PREF_SHARE = "PREF_SHARE"
}

data class SyncState(
    val isOnline: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSync: Long? = null,
    val lastError: String? = null,
    val pending: Long = 0,
)

/**
 * Sincronización explícita servidor <-> base local.
 *
 * Estrategia:
 *  1. PUSH: se envían las operaciones pendientes en orden de creación. Las operaciones son
 *     idempotentes en el servidor (PUT/DELETE de favoritos, POST de historial con client_id),
 *     por lo que un reintento nunca duplica datos.
 *  2. PULL: se descarga el estado del servidor (fuente de verdad) y se reemplaza la copia local,
 *     respetando las operaciones que aún no se pudieron enviar (la intención local más reciente gana).
 *  3. Errores transitorios (red, 5xx, 429) se reintentan con backoff exponencial (1 s, 2 s, 4 s).
 *     Errores permanentes (4xx) descartan la operación para no bloquear la cola.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManager(
    private val scope: CoroutineScope,
    private val store: LocalStore,
    private val api: BackendApi,
    private val auth: AuthRepository,
    private val connectivity: ConnectivityMonitor,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState(isOnline = connectivity.isOnline.value))
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun start() {
        // Sincroniza al iniciar sesión, al abrir la app y cada vez que se recupera la conexión.
        scope.launch {
            combine(
                connectivity.isOnline,
                auth.session.map { it?.user?.localId }.distinctUntilChanged(),
            ) { online, uid -> online to uid }
                .distinctUntilChanged()
                .collect { (online, uid) ->
                    _state.update { it.copy(isOnline = online) }
                    if (online && uid != null) {
                        auth.revalidate()
                        sync()
                    }
                }
        }
        // Contador de pendientes y fecha de última sincronización de la cuenta activa.
        scope.launch {
            auth.session.map { it?.user?.localId }.distinctUntilChanged()
                .flatMapLatest { uid ->
                    if (uid == null) flowOf(0L to null)
                    else combine(
                        store.pendingCountFlow(uid),
                        store.metaFlow(uid, LocalStore.META_LAST_SYNC),
                    ) { count, last -> count to last?.toLongOrNull() }
                }
                .collect { (count, last) -> _state.update { it.copy(pending = count, lastSync = last) } }
        }
    }

    /** Pide una sincronización en segundo plano (p. ej. tras marcar un favorito). */
    fun requestSync() {
        if (connectivity.isOnline.value && auth.currentUser != null) scope.launch { sync() }
    }

    /** @return true si terminó sin errores. */
    suspend fun sync(): Boolean {
        val user = auth.currentUser ?: return false
        if (!connectivity.isOnline.value) {
            _state.update { it.copy(lastError = "Sin conexión: se sincronizará al recuperar Internet") }
            return false
        }
        if (!api.isConfigured) {
            _state.update { it.copy(lastError = "API_BASE_URL no configurada o no es HTTPS") }
            return false
        }
        if (!mutex.tryLock()) return false // ya hay una sincronización en curso
        try {
            _state.update { it.copy(isSyncing = true, lastError = null) }
            val uid = user.localId
            val pushError = flushPending(uid)
            val pullError = if (auth.currentUser?.id == user.id) pull(uid) else null
            val error = pushError ?: pullError
            withContext(ioDispatcher) {
                if (error == null) store.setMeta(uid, LocalStore.META_LAST_SYNC, nowMillis().toString())
                store.setMeta(uid, LocalStore.META_LAST_SYNC_ERROR, error ?: "")
            }
            _state.update { it.copy(lastError = error) }
            return error == null
        } finally {
            _state.update { it.copy(isSyncing = false) }
            mutex.unlock()
        }
    }

    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> ApiResult<T>): ApiResult<T> {
        var wait = 1_000L
        var result = block()
        repeat(attempts - 1) {
            if (result is ApiResult.Success || !result.isRetryable || !connectivity.isOnline.value) return result
            delay(wait)
            wait *= 2
            result = block()
        }
        return result
    }

    // ------------------------------------------------------------------ PUSH
    private suspend fun flushPending(uid: String): String? {
        var lastError: String? = null
        repeat(3) { // rondas: incluye operaciones que se agreguen mientras se envía
            val ops = withContext(ioDispatcher) { store.pendingOps(uid) }
            if (ops.isEmpty()) return lastError
            for (op in ops) {
                if (auth.currentUser?.localId != uid) return "Cambió la sesión durante la sincronización"
                val result = withRetry { execute(op) }
                when {
                    result is ApiResult.Success || result is ApiResult.Malformed -> withContext(ioDispatcher) {
                        store.completeOp(op)
                        if (op.type == OpType.HISTORY_ADD) store.markHistorySynced(op.target)
                    }
                    result is ApiResult.HttpError && result.code == 401 ->
                        return "Sesión no válida; inicia sesión de nuevo"
                    result.isRetryable -> {
                        withContext(ioDispatcher) { store.failOp(op, result.userMessage()) }
                        return "Pendientes sin enviar: ${result.userMessage()}"
                    }
                    else -> { // error permanente (400/403/404): se descarta para no bloquear la cola
                        withContext(ioDispatcher) { store.completeOp(op) }
                        lastError = "Operación descartada (${op.type}): ${result.userMessage()}"
                    }
                }
            }
        }
        return lastError
    }

    private suspend fun execute(op: Pending_op): ApiResult<Unit> = try {
        when (op.type) {
            OpType.FAV_ADD -> api.putFavorite(
                op.target, appJson.decodeFromString(FavoriteRequest.serializer(), op.payload),
            ).map { }
            OpType.FAV_REMOVE -> api.deleteFavorite(op.target)
            OpType.HISTORY_ADD -> api.addHistory(appJson.decodeFromString(HistoryDto.serializer(), op.payload))
            OpType.PREF_SHARE -> api.updatePreferences(op.payload.toBoolean()).also { r ->
                if (r is ApiResult.Success) auth.updateUser(r.data)
            }.map { }
            else -> ApiResult.Malformed("Tipo de operación desconocido: ${op.type}")
        }
    } catch (e: IllegalArgumentException) { // payload local corrupto
        ApiResult.Malformed(e.message ?: "payload inválido")
    }

    // ------------------------------------------------------------------ PULL
    private suspend fun pull(uid: String): String? {
        val favorites = withRetry { api.listFavorites() }
        val history = withRetry { api.listHistory() }
        val items = withRetry { api.listItems() }
        val me = withRetry { api.me() }
        if (auth.currentUser?.localId != uid) return "Cambió la sesión durante la sincronización"

        withContext(ioDispatcher) {
            store.transaction {
                if (favorites is ApiResult.Success) mergeFavorites(uid, favorites.data)
                if (history is ApiResult.Success) history.data.forEach { h ->
                    store.insertHistory(uid, HistoryEntry(h.clientId, h.query, h.resultsCount, h.searchedAt, synced = true))
                    store.markHistorySynced(h.clientId)
                }
                if (items is ApiResult.Success) store.replaceReading(
                    uid, items.data.map { ReadingItem(it.id, it.title, it.author, it.status, it.notes, it.updatedAt) },
                )
                Unit
            }
        }
        if (me is ApiResult.Success) {
            val prefPending = withContext(ioDispatcher) { store.pendingOps(uid).any { it.type == OpType.PREF_SHARE } }
            if (!prefPending) auth.updateUser(me.data)
        }
        return listOf(favorites, history, items, me)
            .firstOrNull { it !is ApiResult.Success }?.userMessage()
    }

    /** El servidor es la fuente de verdad, salvo los favoritos con operaciones aún pendientes. */
    private fun mergeFavorites(uid: String, server: List<FavoriteDto>) {
        val pending = store.pendingOps(uid).filter { it.type.startsWith("FAV") }.associateBy { it.target }
        val local = store.favorites(uid).associateBy { it.workId }
        val serverIds = server.map { it.workId }.toSet()
        store.deleteFavoritesForUser(uid)
        server.forEach { f ->
            if (pending[f.workId]?.type == OpType.FAV_REMOVE) return@forEach
            val created = f.createdAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                ?: nowMillis()
            val book = Book(f.workId, f.title, f.authors, f.firstYear, f.coverId, f.subjects)
            store.cacheBooks(listOf(book))
            store.insertFavorite(uid, book, created)
        }
        local.values.forEach { b ->
            if (b.workId !in serverIds && pending[b.workId]?.type == OpType.FAV_ADD) store.insertFavorite(uid, b)
        }
    }
}
