package mx.ipn.escom.practica5.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mx.ipn.escom.practica5.data.repo.SyncState
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.platform.formatDateTime

val Guinda = Color(0xFF6C1D45)

@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier.size(width = 56.dp, height = 84.dp)) {
    val url = book.coverUrl
    if (url == null) {
        Box(
            modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(book.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
    } else {
        // Coil guarda la portada en caché de disco, por lo que también se ve sin conexión.
        AsyncImage(
            model = url,
            contentDescription = "Portada de ${book.title}",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
fun BookRow(
    book: Book,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    extra: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                book.authorsText + (book.firstYear?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (extra != null) {
                Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Quitar de favoritos" else "Agregar a favoritos",
                    tint = if (isFavorite) Guinda else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Banner de conectividad/sincronización visible en todas las pantallas. */
@Composable
fun SyncBanner(state: SyncState) {
    Column {
        if (state.isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.isOnline) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Sin conexión · usando datos locales" +
                            (if (state.pending > 0) " · ${state.pending} cambio(s) pendiente(s)" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else if (state.pending > 0 || state.lastError != null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    state.lastError ?: "${state.pending} cambio(s) por sincronizar…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

fun lastSyncText(state: SyncState): String =
    state.lastSync?.let { "Última sincronización: ${formatDateTime(it)}" } ?: "Aún no se ha sincronizado"

@Composable
fun CenteredLoading(text: String = "Cargando…") {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.size(12.dp))
        Text(text)
    }
}

@Composable
fun MessageBox(
    title: String,
    message: String,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (isError) Icons.Filled.Warning else Icons.Filled.Info, null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(12.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun InfoLine(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
