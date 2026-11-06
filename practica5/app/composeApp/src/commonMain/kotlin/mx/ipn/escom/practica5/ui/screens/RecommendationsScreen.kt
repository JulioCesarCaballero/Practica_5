package mx.ipn.escom.practica5.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.platform.formatDateTime
import mx.ipn.escom.practica5.ui.BookRow
import mx.ipn.escom.practica5.ui.InfoLine
import mx.ipn.escom.practica5.ui.MessageBox

@Composable
fun RecommendationsScreen(container: AppContainer, onOpen: (Book) -> Unit) {
    val recs by remember { container.recommendations.recommendationsFlow() }.collectAsState(null)
    val explanation by remember { container.recommendations.explanationFlow() }.collectAsState(null)
    val favIds by remember { container.library.favoriteIdsFlow() }.collectAsState(emptySet())
    val online by container.connectivity.isOnline.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun generate() {
        scope.launch {
            loading = true
            message = container.recommendations.generate()
            loading = false
        }
    }

    // Primera visita: si no hay recomendaciones guardadas y hay red, se generan automáticamente.
    LaunchedEffect(recs == null) {
        if (recs?.isEmpty() == true && online) generate()
    }

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("¿Cómo se recomiendan?", style = MaterialTheme.typography.titleSmall)
                Text(
                    explanation ?: "Se cuentan los temas y autores de tus favoritos (3 pts) y de los libros que " +
                        "consultaste (1 pt); se buscan obras de los mejores criterios en Open Library y se excluyen tus favoritos.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { generate() }, enabled = !loading, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (recs.isNullOrEmpty()) "Generar recomendaciones" else "Actualizar recomendaciones")
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { InfoLine(it, isError = true) }

        val list = recs ?: emptyList()
        if (list.isEmpty()) {
            if (!loading) MessageBox(
                "Sin recomendaciones",
                if (online) "Presiona «Generar». Si aún no tienes historial ni favoritos se sugerirán clásicos populares."
                else "Sin conexión y sin recomendaciones guardadas.",
            )
        } else {
            InfoLine("${list.size} recomendaciones · generadas el ${formatDateTime(list.first().generatedAt)}")
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.book.workId }) { r ->
                    BookRow(
                        r.book, r.book.workId in favIds, onClick = { onOpen(r.book) },
                        onToggleFavorite = { scope.launch { container.library.toggleFavorite(r.book) } },
                        extra = "${r.reason} · ${r.score} pts",
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
