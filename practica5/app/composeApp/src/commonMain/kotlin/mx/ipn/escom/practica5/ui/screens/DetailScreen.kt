package mx.ipn.escom.practica5.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.data.repo.DetailOutcome
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.ui.BookCover
import mx.ipn.escom.practica5.ui.CenteredLoading
import mx.ipn.escom.practica5.ui.InfoLine
import mx.ipn.escom.practica5.ui.MessageBox

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(container: AppContainer, workId: String, fallback: Book?) {
    var outcome by remember { mutableStateOf<DetailOutcome?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val favIds by remember { container.library.favoriteIdsFlow() }.collectAsState(emptySet())
    val scope = rememberCoroutineScope()

    LaunchedEffect(workId, reload) {
        outcome = null
        outcome = container.library.detail(workId, fallback)
    }

    val o = outcome
    if (o == null) {
        CenteredLoading("Consultando Open Library…")
        return
    }
    val book = o.book
    if (book == null) {
        MessageBox("No disponible", o.message ?: "No se pudo cargar el libro", isError = true,
            actionLabel = "Reintentar", onAction = { reload++ })
        return
    }
    val isFav = book.workId in favIds

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        o.message?.let { InfoLine(it, isError = true) }
        Row(Modifier.padding(16.dp)) {
            BookCover(book, Modifier.size(width = 120.dp, height = 180.dp))
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(book.authorsText, style = MaterialTheme.typography.bodyMedium)
                book.firstYear?.let { Text("Primera publicación: $it", style = MaterialTheme.typography.bodySmall) }
                Text("ID Open Library: ${book.workId}", style = MaterialTheme.typography.bodySmall)
                val fav: () -> Unit = { scope.launch { container.library.toggleFavorite(book) } }
                if (isFav) {
                    OutlinedButton(onClick = fav) {
                        Icon(Icons.Filled.Favorite, null)
                        Spacer(Modifier.width(6.dp))
                        Text("En favoritos")
                    }
                } else {
                    Button(onClick = fav) {
                        Icon(Icons.Filled.FavoriteBorder, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Agregar a favoritos")
                    }
                }
            }
        }
        Text("Descripción", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            book.description ?: "Open Library no tiene descripción para esta obra.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (book.subjects.isNotEmpty()) {
            Text("Temas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            FlowRow(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                book.subjects.take(15).forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
            }
        }
    }
}
