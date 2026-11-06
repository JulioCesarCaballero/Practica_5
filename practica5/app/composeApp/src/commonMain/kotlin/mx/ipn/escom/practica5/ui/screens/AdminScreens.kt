package mx.ipn.escom.practica5.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.data.remote.AdminUserDto
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.FavoriteDto
import mx.ipn.escom.practica5.data.remote.HistoryDto
import mx.ipn.escom.practica5.data.remote.userMessage
import mx.ipn.escom.practica5.platform.formatDateTime
import mx.ipn.escom.practica5.ui.CenteredLoading
import mx.ipn.escom.practica5.ui.InfoLine
import mx.ipn.escom.practica5.ui.MessageBox

@Composable
fun AdminScreen(container: AppContainer, onOpenUser: (Int, String) -> Unit) {
    var result by remember { mutableStateOf<ApiResult<List<AdminUserDto>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { result = null; result = container.admin.users() }

    when (val r = result) {
        null -> CenteredLoading()
        is ApiResult.Success -> LazyColumn(Modifier.fillMaxSize()) {
            item { InfoLine("${r.data.size} usuario(s) · ${r.elapsedMs} ms · sólo se pueden abrir los que autorizaron compartir sus datos") }
            items(r.data, key = { it.id }) { u ->
                Column(
                    Modifier.fillMaxWidth().clickable { onOpenUser(u.id, u.name) }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("${u.name} (${u.role})", fontWeight = FontWeight.SemiBold)
                    Text(u.email, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (u.shareWithAdmin) "Autorizó · ${u.favoritesCount ?: 0} favoritos · ${u.historyCount ?: 0} búsquedas"
                        else "No autorizó compartir sus datos",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (u.shareWithAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
            }
        }
        else -> MessageBox("Acceso al panel", r.userMessage(), isError = true, actionLabel = "Reintentar", onAction = { reload++ })
    }
}

@Composable
fun AdminUserScreen(container: AppContainer, userId: Int) {
    var history by remember { mutableStateOf<ApiResult<List<HistoryDto>>?>(null) }
    var favorites by remember { mutableStateOf<ApiResult<List<FavoriteDto>>?>(null) }
    LaunchedEffect(userId) {
        history = container.admin.history(userId)
        favorites = container.admin.favorites(userId)
    }
    val h = history
    val f = favorites
    if (h == null || f == null) {
        CenteredLoading()
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Favoritos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
        when (f) {
            is ApiResult.Success -> {
                if (f.data.isEmpty()) item { InfoLine("Sin favoritos") }
                items(f.data, key = { "f" + it.workId }) { fav ->
                    Text("• ${fav.title} — ${fav.authors.joinToString(", ")}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            else -> item { InfoLine(f.userMessage(), isError = true) }
        }
        item { Text("Historial de búsquedas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
        when (h) {
            is ApiResult.Success -> {
                if (h.data.isEmpty()) item { InfoLine("Sin búsquedas") }
                items(h.data, key = { "h" + it.clientId }) { e ->
                    Text(
                        "• «${e.query}» · ${e.resultsCount} resultados · ${formatDateTime(e.searchedAt)}",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            else -> item { InfoLine(h.userMessage(), isError = true) }
        }
    }
}
