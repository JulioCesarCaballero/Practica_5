package mx.ipn.escom.practica5

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mx.ipn.escom.practica5.data.local.LocalStore
import mx.ipn.escom.practica5.data.remote.BackendApi
import mx.ipn.escom.practica5.data.remote.OpenLibraryApi
import mx.ipn.escom.practica5.data.remote.createHttpClient
import mx.ipn.escom.practica5.data.repo.AdminRepository
import mx.ipn.escom.practica5.data.repo.AuthRepository
import mx.ipn.escom.practica5.data.repo.LibraryRepository
import mx.ipn.escom.practica5.data.repo.ReadingRepository
import mx.ipn.escom.practica5.data.repo.RecommendationRepository
import mx.ipn.escom.practica5.data.repo.SyncManager
import mx.ipn.escom.practica5.db.AppDatabase
import mx.ipn.escom.practica5.platform.ConnectivityMonitor
import mx.ipn.escom.practica5.platform.SessionStorage
import mx.ipn.escom.practica5.ui.SearchViewModel

/**
 * Contenedor de dependencias compartido. Cada plataforma sólo aporta: driver SQL,
 * almacenamiento seguro, monitor de red, URL del backend y el cliente de Google.
 */
class AppContainer(
    driver: SqlDriver,
    sessionStorage: SessionStorage,
    val connectivity: ConnectivityMonitor,
    apiBaseUrl: String,
    val googleConfigured: Boolean,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = createHttpClient()
    val store = LocalStore(AppDatabase(driver))
    val auth = AuthRepository(sessionStorage)
    val backend = BackendApi(
        client = httpClient,
        baseUrl = apiBaseUrl,
        tokenProvider = { auth.currentToken },
        onUnauthorized = { auth.expire("Tu sesión expiró o fue revocada. Inicia sesión de nuevo.") },
    )
    val openLibrary = OpenLibraryApi(httpClient)
    val sync = SyncManager(scope, store, backend, auth, connectivity)
    val library = LibraryRepository(store, openLibrary, auth, sync, connectivity)
    val reading = ReadingRepository(backend, store, auth, connectivity)
    val recommendations = RecommendationRepository(store, openLibrary, auth, connectivity)
    val admin = AdminRepository(backend)
    val searchViewModel = SearchViewModel(scope, library)

    init {
        auth.api = backend
        auth.restore() // la sesión validada previamente se restaura incluso sin Internet
        sync.start()
    }
}
