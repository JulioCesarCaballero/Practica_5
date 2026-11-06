package mx.ipn.escom.practica5.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.userMessage
import mx.ipn.escom.practica5.data.repo.Session
import mx.ipn.escom.practica5.platform.formatDateTime
import mx.ipn.escom.practica5.ui.lastSyncText

@Composable
fun ProfileScreen(
    container: AppContainer,
    session: Session,
    onOpenAdmin: () -> Unit,
    onSearchAgain: (String) -> Unit,
) {
    val user = session.user
    val sync by container.sync.state.collectAsState()
    val history by remember { container.library.historyFlow() }.collectAsState(emptyList())
    var adminTest by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(user.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(user.email)
                    Text(
                        "Rol: ${if (user.isAdmin) "administrador" else "usuario"} · Acceso: " +
                            if (user.authProvider == "google") "Google" else "correo y contraseña",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Sesión validada: ${formatDateTime(session.validatedAt)}", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Compartir mi historial y favoritos con el administrador", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = user.shareWithAdmin,
                            onCheckedChange = { v -> scope.launch { container.library.setShareWithAdmin(v) } },
                        )
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sincronización", style = MaterialTheme.typography.titleMedium)
                    Text(if (sync.isOnline) "Conectado" else "Sin conexión", style = MaterialTheme.typography.bodySmall)
                    Text(lastSyncText(sync), style = MaterialTheme.typography.bodySmall)
                    Text("Operaciones pendientes: ${sync.pending}", style = MaterialTheme.typography.bodySmall)
                    sync.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { scope.launch { container.sync.sync() } }, enabled = !sync.isSyncing) {
                        Text(if (sync.isSyncing) "Sincronizando…" else "Sincronizar ahora")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Administración", style = MaterialTheme.typography.titleMedium)
                    if (user.isAdmin) {
                        Button(onClick = onOpenAdmin) { Text("Abrir panel de administrador") }
                    }
                    // Permite demostrar que el backend valida el permiso (403 para usuarios normales).
                    OutlinedButton(onClick = {
                        scope.launch {
                            adminTest = when (val r = container.admin.users()) {
                                is ApiResult.Success -> "Permitido: GET /api/admin/users devolvió ${r.data.size} usuario(s) en ${r.elapsedMs} ms"
                                else -> "Respuesta del servidor → ${r.userMessage()}"
                            }
                        }
                    }) { Text("Probar acceso a /api/admin/users") }
                    adminTest?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Historial de búsquedas (${history.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
        if (history.isEmpty()) {
            item { Text("Aún no hay búsquedas.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        }
        items(history, key = { it.clientId }) { h ->
            Column(Modifier.fillMaxWidth().clickable { onSearchAgain(h.query) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(h.query, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatDateTime(h.searchedAt)} · ${h.resultsCount} resultado(s) · " +
                        if (h.synced) "sincronizado" else "pendiente de sincronizar",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        container.searchViewModel.reset()
                        container.auth.logout()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Cerrar sesión") }
        }
    }
}
