package mx.ipn.escom.practica5.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.data.repo.Session
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.platform.GoogleAuthClient
import mx.ipn.escom.practica5.platform.PlatformBackHandler
import mx.ipn.escom.practica5.ui.screens.AdminScreen
import mx.ipn.escom.practica5.ui.screens.AdminUserScreen
import mx.ipn.escom.practica5.ui.screens.DetailScreen
import mx.ipn.escom.practica5.ui.screens.FavoritesScreen
import mx.ipn.escom.practica5.ui.screens.LoginScreen
import mx.ipn.escom.practica5.ui.screens.ProfileScreen
import mx.ipn.escom.practica5.ui.screens.ReadingScreen
import mx.ipn.escom.practica5.ui.screens.RecommendationsScreen
import mx.ipn.escom.practica5.ui.screens.SearchScreen

private val AppColors = lightColorScheme(
    primary = Guinda,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4D9E5),
    secondary = Color(0xFF7A5C66),
)

/** Raíz de la interfaz compartida (Compose Multiplatform). */
@Composable
fun App(container: AppContainer, googleAuth: GoogleAuthClient) {
    MaterialTheme(colorScheme = AppColors) {
        Surface(Modifier.fillMaxSize()) {
            val session by container.auth.session.collectAsState()
            val current = session
            if (current == null) {
                LoginScreen(container, googleAuth)
            } else {
                // key: al cambiar de cuenta se recrea todo el árbol y sus flujos (datos por usuario).
                key(current.user.id) { MainScreen(container, current) }
            }
        }
    }
}

sealed interface Route {
    data class Detail(val workId: String, val fallback: Book?) : Route
    data object Admin : Route
    data class AdminUser(val userId: Int, val name: String) : Route
}

private enum class Tab(val label: String, val icon: ImageVector) {
    SEARCH("Buscar", Icons.Filled.Search),
    FAVORITES("Favoritos", Icons.Filled.Favorite),
    RECOMMENDATIONS("Para ti", Icons.Filled.Star),
    READING("Mis libros", Icons.AutoMirrored.Filled.List),
    PROFILE("Perfil", Icons.Filled.Person),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(container: AppContainer, session: Session) {
    var tab by remember { mutableStateOf(Tab.SEARCH) }
    val stack = remember { mutableStateListOf<Route>() }
    val syncState by container.sync.state.collectAsState()
    val scope = rememberCoroutineScope()

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    val openBook: (Book) -> Unit = { push(Route.Detail(it.workId, it)) }
    PlatformBackHandler(enabled = stack.isNotEmpty()) { pop() }

    val route = stack.lastOrNull()
    val title = when (route) {
        is Route.Detail -> "Detalle"
        Route.Admin -> "Administración"
        is Route.AdminUser -> route.name
        null -> tab.label
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (route != null) {
                        IconButton(onClick = { pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { container.sync.sync() } },
                        enabled = !syncState.isSyncing,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Sincronizar ahora")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Guinda,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            if (route == null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncBanner(syncState)
            when (route) {
                is Route.Detail -> DetailScreen(container, route.workId, route.fallback)
                Route.Admin -> AdminScreen(container) { id, name -> push(Route.AdminUser(id, name)) }
                is Route.AdminUser -> AdminUserScreen(container, route.userId)
                null -> when (tab) {
                    Tab.SEARCH -> SearchScreen(container, openBook)
                    Tab.FAVORITES -> FavoritesScreen(container, openBook)
                    Tab.RECOMMENDATIONS -> RecommendationsScreen(container, openBook)
                    Tab.READING -> ReadingScreen(container)
                    Tab.PROFILE -> ProfileScreen(
                        container = container,
                        session = session,
                        onOpenAdmin = { push(Route.Admin) },
                        onSearchAgain = { q ->
                            container.searchViewModel.search(q)
                            tab = Tab.SEARCH
                        },
                    )
                }
            }
        }
    }
}
