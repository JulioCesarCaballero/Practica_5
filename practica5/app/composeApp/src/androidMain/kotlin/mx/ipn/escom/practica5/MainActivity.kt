package mx.ipn.escom.practica5

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import mx.ipn.escom.practica5.platform.AndroidGoogleAuthClient
import mx.ipn.escom.practica5.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as Practica5App).container
        // Credential Manager necesita un contexto de Activity para mostrar el selector de cuentas.
        val google = AndroidGoogleAuthClient(this, BuildConfig.GOOGLE_WEB_CLIENT_ID)
        container.auth.googleClient = google
        setContent {
            App(container = container, googleAuth = google)
        }
    }
}
