package mx.ipn.escom.practica5.data.repo

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import mx.ipn.escom.practica5.data.local.LocalStore
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.OpenLibraryApi
import mx.ipn.escom.practica5.data.remote.map
import mx.ipn.escom.practica5.data.remote.userMessage
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.domain.RecommendedBook
import mx.ipn.escom.practica5.platform.ConnectivityMonitor
import mx.ipn.escom.practica5.platform.ioDispatcher
import mx.ipn.escom.practica5.platform.nowMillis

/** Genera recomendaciones y las persiste para poder verlas sin conexión. */
class RecommendationRepository(
    private val store: LocalStore,
    private val openLibrary: OpenLibraryApi,
    private val auth: AuthRepository,
    private val connectivity: ConnectivityMonitor,
) {
    private fun uid() = auth.currentUser?.localId

    fun recommendationsFlow(): Flow<List<RecommendedBook>> = uid()?.let { store.recommendationsFlow(it) } ?: emptyFlow()
    fun explanationFlow(): Flow<String?> = uid()?.let { store.metaFlow(it, LocalStore.META_RECS_INFO) } ?: emptyFlow()

    /** @return null si todo salió bien; si no, el mensaje de error (se conservan las previas). */
    suspend fun generate(): String? {
        val uid = uid() ?: return "No hay sesión activa"
        if (!connectivity.isOnline.value) return "Sin conexión: se muestran las recomendaciones generadas anteriormente"

        val (favorites, viewed, history) = withContext(ioDispatcher) {
            Triple(store.favorites(uid), store.viewedBooks(uid), store.history(uid))
        }
        val plan = RecommendationEngine.buildPlan(favorites, viewed, history)

        val results = coroutineScope {
            plan.seeds.map { seed -> async { seed to fetch(seed) } }.map { it.await() }
        }
        val ok = results.mapNotNull { (seed, r) -> if (r is ApiResult.Success) seed to r.data else null }
        val firstError = results.firstOrNull { it.second !is ApiResult.Success }?.second?.userMessage()
        if (ok.isEmpty()) return firstError ?: "No se pudieron obtener recomendaciones"

        val now = nowMillis()
        val recs = RecommendationEngine.rank(ok, favorites.map { it.workId }.toSet(), now)
        withContext(ioDispatcher) {
            store.transaction {
                store.cacheBooks(recs.map { it.book })
                store.replaceRecommendations(uid, recs)
                store.setMeta(uid, LocalStore.META_RECS_INFO, plan.explanation)
            }
        }
        return if (recs.isEmpty()) "No hubo candidatos nuevos para tus criterios" else firstError
    }

    private suspend fun fetch(seed: RecommendationEngine.Seed): ApiResult<List<Book>> = when (seed.kind) {
        RecommendationEngine.Kind.SUBJECT -> openLibrary.subject(seed.value)
        RecommendationEngine.Kind.AUTHOR -> openLibrary.searchByAuthor(seed.value)
        RecommendationEngine.Kind.QUERY -> openLibrary.search(seed.value, limit = 15).map { it.second }
        RecommendationEngine.Kind.POPULAR -> openLibrary.subject(RecommendationEngine.POPULAR_SUBJECT)
    }
}
