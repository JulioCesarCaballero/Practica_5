package mx.ipn.escom.practica5.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.userMessage
import mx.ipn.escom.practica5.platform.GoogleAuthClient
import mx.ipn.escom.practica5.ui.Guinda

@Composable
fun LoginScreen(container: AppContainer, googleAuth: GoogleAuthClient) {
    var registerMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") } 
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val online by container.connectivity.isOnline.collectAsState()
    val notice by container.auth.notice.collectAsState()
    val scope = rememberCoroutineScope()

    fun submit(block: suspend () -> ApiResult<*>?) {
        if (!online) {
            error = "Sin conexión. El primer inicio de sesión requiere Internet; una sesión ya validada se conserva offline."
            return
        }
        if (!container.backend.isConfigured) {
            error = "Configura API_BASE_URL (https://…) en local.properties y recompila."
            return
        }
        scope.launch {
            loading = true
            error = null
            container.auth.consumeNotice()
            val r = block()
            loading = false
            if (r != null && r !is ApiResult.Success) error = r.userMessage()
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Libros P5", style = MaterialTheme.typography.headlineMedium, color = Guinda, fontWeight = FontWeight.Bold)
        Text(
            "Práctica 5 · Desarrollo de aplicaciones móviles nativas · ESCOM IPN",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

        if (registerMode) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Nombre") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Correo") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Contraseña" + if (registerMode) " (mín. 8)" else "") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    submit {
                        if (registerMode) container.auth.register(name, email, password)
                        else container.auth.login(email, password)
                    }
                },
                enabled = email.isNotBlank() && password.isNotBlank() && (!registerMode || name.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (registerMode) "Crear cuenta" else "Iniciar sesión") }

            OutlinedButton(
                onClick = { submit { container.auth.loginWithGoogle(googleAuth) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continuar con Google") }
            if (!container.googleConfigured) {
                Text(
                    "Google no configurado: agrega GOOGLE_WEB_CLIENT_ID en local.properties.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
            }

            TextButton(onClick = { registerMode = !registerMode; error = null }) {
                Text(if (registerMode) "¿Ya tienes cuenta? Inicia sesión" else "¿No tienes cuenta? Regístrate")
            }
        }
        if (!online) {
            Text("Sin conexión", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
