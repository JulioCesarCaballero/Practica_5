package mx.ipn.escom.practica5.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.ui.BookRow
import mx.ipn.escom.practica5.ui.InfoLine
import mx.ipn.escom.practica5.ui.MessageBox

@Composable
fun FavoritesScreen(container: AppContainer, onOpen: (Book) -> Unit) {
    val favorites by remember { container.library.favoritesFlow() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()

    if (favorites.isEmpty()) {
        MessageBox("Sin favoritos", "Marca libros con el corazón desde la búsqueda o el detalle. Se guardan por cuenta y funcionan sin conexión.")
        return
    }
    Column(Modifier.fillMaxSize()) {
        InfoLine("${favorites.size} favorito(s) · guardados localmente y sincronizados con el servidor")
        LazyColumn(Modifier.fillMaxSize()) {
            items(favorites, key = { it.workId }) { b ->
                BookRow(b, true, onClick = { onOpen(b) }, onToggleFavorite = { scope.launch { container.library.toggleFavorite(b) } })
                HorizontalDivider()
            }
        }
    }
}
