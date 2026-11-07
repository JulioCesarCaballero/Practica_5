package mx.ipn.escom.practica5

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import mx.ipn.escom.practica5.db.AppDatabase
import mx.ipn.escom.practica5.platform.IosConnectivityMonitor
import mx.ipn.escom.practica5.platform.IosGoogleAuthClient
import mx.ipn.escom.practica5.platform.IosGoogleSignInHandler
import mx.ipn.escom.practica5.platform.IosSessionStorage
import mx.ipn.escom.practica5.ui.App
import platform.UIKit.UIViewController

/** Un único contenedor por proceso (equivale a `Practica5App` en Android). */
private var sharedContainer: AppContainer? = null

private fun container(apiBaseUrl: String, webClientId: String): AppContainer =
    sharedContainer ?: AppContainer(
        driver = NativeSqliteDriver(AppDatabase.Schema, "practica5.db"),
        sessionStorage = IosSessionStorage(),
        connectivity = IosConnectivityMonitor(),
        apiBaseUrl = apiBaseUrl.trim().trimEnd('/'),
        googleConfigured = webClientId.isNotBlank(),
    ).also { sharedContainer = it }

/**
 * Punto de entrada que llama SwiftUI (ContentView.swift).
 * En Swift: `MainViewControllerKt.MainViewController(handler:apiBaseUrl:webClientId:)`.
 */
fun MainViewController(
    handler: IosGoogleSignInHandler,
    apiBaseUrl: String,
    webClientId: String,
): UIViewController {
    val container = container(apiBaseUrl, webClientId)
    val google = IosGoogleAuthClient(handler, webClientId)
    container.auth.googleClient = google
    return ComposeUIViewController {
        App(container = container, googleAuth = google)
    }
}
