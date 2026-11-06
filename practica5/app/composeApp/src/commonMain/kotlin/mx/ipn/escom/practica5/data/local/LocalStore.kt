package mx.ipn.escom.practica5.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mx.ipn.escom.practica5.db.AppDatabase
import mx.ipn.escom.practica5.db.Favorite
import mx.ipn.escom.practica5.db.Pending_op
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.domain.HistoryEntry
import mx.ipn.escom.practica5.domain.ReadingItem
import mx.ipn.escom.practica5.domain.RecommendedBook
import mx.ipn.escom.practica5.platform.ioDispatcher
import mx.ipn.escom.practica5.platform.nowMillis

/** Acceso a la base local. Todas las consultas personales reciben el userId de la sesión. */
class LocalStore(db: AppDatabase) {
    private val q = db.appDatabaseQueries

    companion object {
        private const val SEP = "\u001F"
        fun encode(list: List<String>): String = list.joinToString(SEP)
        fun decode(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(SEP)

        const val META_LAST_SYNC = "last_sync"
        const val META_LAST_SYNC_ERROR = "last_sync_error"
        const val META_RECS_INFO = "recs_info"
    }

    fun <R> transaction(block: () -> R): R = q.transactionWithResult { block() }

    // ---------------------------------------------------------------- libros
    private fun bookMapper(
        workId: String, title: String, authors: String, firstYear: Long?, coverId: Long?,
        subjects: String, description: String?,
    ) = Book(workId, title, decode(authors), firstYear?.toInt(), coverId, decode(subjects), description)

    fun cacheBooks(books: List<Book>) {
        val now = nowMillis()
        q.transaction {
            books.forEach { b0 ->
                // No se pierden los temas ya guardados si la nueva fuente no los trae.
                val b = if (b0.subjects.isEmpty()) b0.copy(subjects = getBook(b0.workId)?.subjects ?: emptyList()) else b0
                q.insertBookIfAbsent(
                    b.workId, b.title, encode(b.authors), b.firstYear?.toLong(), b.coverId,
                    encode(b.subjects), now,
                )
                q.updateBookSummary(
                    b.title, encode(b.authors), b.firstYear?.toLong(), b.coverId,
                    encode(b.subjects), now, b.workId,
                )
            }
        }
    }

    fun cacheBookDetail(workId: String, description: String?, subjects: List<String>) {
        q.updateBookDetail(description, encode(subjects), nowMillis(), workId)
    }

    fun getBook(workId: String): Book? =
        q.selectBook(workId) { id, title, authors, year, cover, subjects, description, _ ->
            bookMapper(id, title, authors, year, cover, subjects, description)
        }.executeAsOneOrNull()

    fun markViewed(userId: String, workId: String) = q.upsertViewed(userId, workId, nowMillis())

    fun viewedBooks(userId: String): List<Book> =
        q.viewedBooksForUser(userId) { id, title, authors, year, cover, subjects, description ->
            bookMapper(id, title, authors, year, cover, subjects, description)
        }.executeAsList()

    // ---------------------------------------------------------------- búsquedas en caché
    data class CachedSearch(val books: List<Book>, val total: Int, val updatedAt: Long)

    fun saveSearch(userId: String, query: String, total: Int, books: List<Book>) {
        q.transaction {
            cacheBooks(books)
            q.upsertSearch(userId, query, encode(books.map { it.workId }), total.toLong(), nowMillis())
        }
    }

    fun cachedSearch(userId: String, query: String): CachedSearch? {
        val row = q.selectSearch(userId, query).executeAsOneOrNull() ?: return null
        val books = decode(row.work_ids).mapNotNull { getBook(it) }
        return CachedSearch(books, row.total.toInt(), row.updated_at)
    }

    // ---------------------------------------------------------------- historial
    fun insertHistory(userId: String, entry: HistoryEntry) = q.insertHistory(
        entry.clientId, userId, entry.query, entry.resultsCount.toLong(), entry.searchedAt,
        if (entry.synced) 1L else 0L,
    )

    fun markHistorySynced(clientId: String) = q.markHistorySynced(clientId)

    fun historyFlow(userId: String): Flow<List<HistoryEntry>> =
        q.historyForUser(userId).asFlow().mapToList(ioDispatcher).map { rows ->
            rows.map { HistoryEntry(it.client_id, it.query_text, it.results_count.toInt(), it.searched_at, it.synced == 1L) }
        }

    fun history(userId: String): List<HistoryEntry> = q.historyForUser(userId).executeAsList().map {
        HistoryEntry(it.client_id, it.query_text, it.results_count.toInt(), it.searched_at, it.synced == 1L)
    }

    // ---------------------------------------------------------------- favoritos
    private fun Favorite.toBook() = Book(
        work_id, title, decode(authors), first_year?.toInt(), cover_id, decode(subjects),
    )

    fun favoritesFlow(userId: String): Flow<List<Book>> =
        q.favoritesForUser(userId).asFlow().mapToList(ioDispatcher).map { rows -> rows.map { it.toBook() } }

    fun favoriteIdsFlow(userId: String): Flow<Set<String>> =
        q.favoriteIdsForUser(userId).asFlow().mapToList(ioDispatcher).map { it.toSet() }

    fun favorites(userId: String): List<Book> = q.favoritesForUser(userId).executeAsList().map { it.toBook() }

    fun isFavorite(userId: String, workId: String): Boolean =
        q.countFavorite(userId, workId).executeAsOne() > 0

    fun insertFavorite(userId: String, book: Book, createdAt: Long = nowMillis()) = q.insertFavorite(
        userId, book.workId, book.title, encode(book.authors), book.coverId, book.firstYear?.toLong(),
        encode(book.subjects), createdAt,
    )

    fun deleteFavorite(userId: String, workId: String) = q.deleteFavorite(userId, workId)

    fun deleteFavoritesForUser(userId: String) = q.deleteFavoritesForUser(userId)

    // ---------------------------------------------------------------- cola de operaciones
    fun enqueue(userId: String, type: String, target: String, payload: String) =
        q.upsertOp("$userId|${type.substringBefore('_')}|$target", userId, type, target, payload, nowMillis())

    fun pendingOps(userId: String): List<Pending_op> = q.opsForUser(userId).executeAsList()

    fun pendingCountFlow(userId: String): Flow<Long> =
        q.countOpsForUser(userId).asFlow().mapToOne(ioDispatcher)

    fun completeOp(op: Pending_op) = q.deleteOp(op.op_key, op.created_at)

    fun failOp(op: Pending_op, error: String) = q.markOpFailed(error, op.op_key)

    // ---------------------------------------------------------------- CRUD en caché
    fun readingFlow(userId: String): Flow<List<ReadingItem>> =
        q.readingForUser(userId).asFlow().mapToList(ioDispatcher).map { rows ->
            rows.map { ReadingItem(it.id.toInt(), it.title, it.author, it.status, it.notes, it.updated_at) }
        }

    fun replaceReading(userId: String, items: List<ReadingItem>) = q.transaction {
        q.deleteReadingForUser(userId)
        items.forEach { upsertReading(userId, it) }
    }

    fun upsertReading(userId: String, item: ReadingItem) = q.insertReading(
        userId, item.id.toLong(), item.title, item.author, item.status, item.notes, item.updatedAt,
    )

    fun deleteReading(userId: String, id: Int) = q.deleteReading(userId, id.toLong())

    // ---------------------------------------------------------------- recomendaciones
    fun recommendationsFlow(userId: String): Flow<List<RecommendedBook>> =
        q.recommendationsForUser(userId).asFlow().mapToList(ioDispatcher).map { rows ->
            rows.map {
                RecommendedBook(
                    Book(it.work_id, it.title, decode(it.authors), it.first_year?.toInt(), it.cover_id),
                    it.reason, it.score.toInt(), it.generated_at,
                )
            }
        }

    fun replaceRecommendations(userId: String, recs: List<RecommendedBook>) = q.transaction {
        q.deleteRecommendationsForUser(userId)
        recs.forEach { r ->
            q.insertRecommendation(
                userId, r.book.workId, r.book.title, encode(r.book.authors), r.book.coverId,
                r.book.firstYear?.toLong(), r.reason, r.score.toLong(), r.generatedAt,
            )
        }
    }

    // ---------------------------------------------------------------- metadatos
    fun setMeta(userId: String, key: String, value: String) = q.setMeta(userId, key, value)

    fun getMeta(userId: String, key: String): String? = q.getMeta(userId, key).executeAsOneOrNull()

    fun metaFlow(userId: String, key: String): Flow<String?> =
        q.getMeta(userId, key).asFlow().mapToOneOrNull(ioDispatcher)
}
