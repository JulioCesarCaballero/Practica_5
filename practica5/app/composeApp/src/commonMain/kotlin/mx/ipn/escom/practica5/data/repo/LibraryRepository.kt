package mx.ipn.escom.practica5.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import mx.ipn.escom.practica5.data.local.LocalStore
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.FavoriteRequest
import mx.ipn.escom.practica5.data.remote.HistoryDto
import mx.ipn.escom.practica5.data.remote.OpenLibraryApi
import mx.ipn.escom.practica5.data.remote.appJson
import mx.ipn.escom.practica5.data.remote.userMessage
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.domain.HistoryEntry
import mx.ipn.escom.practica5.platform.ConnectivityMonitor
import mx.ipn.escom.practica5.platform.formatDateTime
import mx.ipn.escom.practica5.platform.ioDispatcher
import mx.ipn.escom.practica5.platform.nowMillis
import mx.ipn.escom.practica5.platform.randomUuid

sealed interface SearchOutcome {
    val query: String

    data class Results(
        override val query: String,
        val books: List<Book>,
        val total: Int,
        val fromCache: Boolean,
        val updatedAt: Long,
        val elapsedMs: Long?,
        val warning: String?,
    ) : SearchOutcome

    data class Empty(override val query: String) : SearchOutcome
    data class Failure(override val query: String, val message: String) : SearchOutcome
}

data class DetailOutcome(
    val book: Book?,
    val fromCache: Boolean,
    val message: String?,
)

/** Búsqueda en Open Library, detalle, favoritos e historial (con caché y modo offline). */
class LibraryRepository(
    private val store: LocalStore,
    private val openLibrary: OpenLibraryApi,
    private val auth: AuthRepository,
    private val sync: SyncManager,
    private val connectivity: ConnectivityMonitor,
) {
    private fun uid(): String? = auth.currentUser?.localId

    fun favoritesFlow(): Flow<List<Book>> = uid()?.let { store.favoritesFlow(it) } ?: emptyFlow()
    fun favoriteIdsFlow(): Flow<Set<String>> = uid()?.let { store.favoriteIdsFlow(it) } ?: emptyFlow()
    fun historyFlow(): Flow<List<HistoryEntry>> = uid()?.let { store.historyFlow(it) } ?: emptyFlow()

    suspend fun search(rawQuery: String): SearchOutcome {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ")
        val uid = uid() ?: return SearchOutcome.Failure(query, "No hay sesión activa")
        if (query.length < 2) return SearchOutcome.Failure(query, "Escribe al menos 2 caracteres")
        val key = query.lowercase()

        val outcome: SearchOutcome = if (connectivity.isOnline.value) {
            when (val r = openLibrary.search(query)) {
                is ApiResult.Success -> {
                    val (total, books) = r.data
                    withContext(ioDispatcher) { store.saveSearch(uid, key, total, books) }
                    if (books.isEmpty()) SearchOutcome.Empty(query)
                    else SearchOutcome.Results(query, books, total, false, nowMillis(), r.elapsedMs, null)
                }
                else -> fromCache(uid, query, key, "${r.userMessage()} · Mostrando resultados guardados")
                    ?: SearchOutcome.Failure(query, r.userMessage())
            }
        } else {
            fromCache(uid, query, key, null)
                ?: SearchOutcome.Failure(query, "Sin conexión y no hay resultados guardados para «$query»")
        }

        val count = when (outcome) {
            is SearchOutcome.Results -> outcome.books.size
            is SearchOutcome.Empty -> 0
            is SearchOutcome.Failure -> -1
        }
        if (count >= 0) recordHistory(uid, query, count)
        return outcome
    }

    private suspend fun fromCache(uid: String, query: String, key: String, warning: String?): SearchOutcome? {
        val cached = withContext(ioDispatcher) { store.cachedSearch(uid, key) } ?: return null
        if (cached.books.isEmpty()) return SearchOutcome.Empty(query)
        return SearchOutcome.Results(
            query, cached.books, cached.total, fromCache = true, updatedAt = cached.updatedAt, elapsedMs = null,
            warning = warning ?: "Sin conexión · resultados guardados el ${formatDateTime(cached.updatedAt)}",
        )
    }

    /** Guarda el historial localmente y lo encola para el servidor (idempotente por client_id). */
    private suspend fun recordHistory(uid: String, query: String, count: Int) {
        val entry = HistoryEntry(randomUuid(), query, count, nowMillis(), synced = false)
        val payload = appJson.encodeToString(
            HistoryDto.serializer(), HistoryDto(entry.clientId, entry.query, entry.resultsCount, entry.searchedAt),
        )
        withContext(ioDispatcher) {
            store.transaction {
                store.insertHistory(uid, entry)
                store.enqueue(uid, OpType.HISTORY_ADD, entry.clientId, payload)
            }
        }
        sync.requestSync()
    }

    suspend fun detail(workId: String, fallback: Book?): DetailOutcome {
        val uid = uid() ?: return DetailOutcome(fallback, true, "No hay sesión activa")
        var book = withContext(ioDispatcher) {
            if (fallback != null && store.getBook(workId) == null) store.cacheBooks(listOf(fallback))
            store.getBook(workId)
        } ?: fallback

        if (!connectivity.isOnline.value) {
            if (book != null) withContext(ioDispatcher) { store.markViewed(uid, workId) }
            return DetailOutcome(
                book, true,
                if (book == null) "Sin conexión y este libro no está guardado" else "Sin conexión · mostrando datos guardados",
            )
        }
        return when (val r = openLibrary.work(workId)) {
            is ApiResult.Success -> {
                val work = r.data
                val description = OpenLibraryApi.descriptionText(work)
                val subjects = work.subjects.take(25).ifEmpty { book?.subjects ?: emptyList() }
                book = (book ?: Book(workId, work.title ?: "Sin título", emptyList(), coverId = work.covers.firstOrNull { it > 0 }))
                    .copy(description = description, subjects = subjects)
                val finalBook = book
                withContext(ioDispatcher) {
                    store.cacheBooks(listOf(finalBook))
                    store.cacheBookDetail(workId, description, subjects)
                    store.markViewed(uid, workId)
                }
                DetailOutcome(book, false, null)
            }
            else -> {
                if (book != null) withContext(ioDispatcher) { store.markViewed(uid, workId) }
                DetailOutcome(book, true, r.userMessage())
            }
        }
    }

    /**
     * Marca/desmarca un favorito. Funciona sin conexión: el cambio se aplica localmente y se
     * encola; si se cambia varias veces antes de sincronizar sólo se envía el último estado.
     * @return el nuevo estado (true = favorito).
     */
    suspend fun toggleFavorite(original: Book): Boolean {
        val uid = uid() ?: return false
        val nowFavorite = withContext(ioDispatcher) {
            // Completa los temas con la caché (p. ej. libros que vienen de recomendaciones).
            val book = if (original.subjects.isEmpty()) {
                original.copy(subjects = store.getBook(original.workId)?.subjects ?: emptyList())
            } else original
            store.transaction {
                if (store.isFavorite(uid, book.workId)) {
                    store.deleteFavorite(uid, book.workId)
                    store.enqueue(uid, OpType.FAV_REMOVE, book.workId, "")
                    false
                } else {
                    store.cacheBooks(listOf(book))
                    store.insertFavorite(uid, book)
                    val payload = appJson.encodeToString(
                        FavoriteRequest.serializer(),
                        FavoriteRequest(book.title, book.authors, book.coverId, book.firstYear, book.subjects.take(25)),
                    )
                    store.enqueue(uid, OpType.FAV_ADD, book.workId, payload)
                    true
                }
            }
        }
        sync.requestSync()
        return nowFavorite
    }

    suspend fun isFavorite(workId: String): Boolean {
        val uid = uid() ?: return false
        return withContext(ioDispatcher) { store.isFavorite(uid, workId) }
    }

    /** Preferencia "compartir con el administrador": también se encola sin conexión. */
    suspend fun setShareWithAdmin(value: Boolean) {
        val uid = uid() ?: return
        withContext(ioDispatcher) { store.enqueue(uid, OpType.PREF_SHARE, "share_with_admin", value.toString()) }
        auth.updateLocalUser { it.copy(shareWithAdmin = value) }
        sync.requestSync()
    }
}
