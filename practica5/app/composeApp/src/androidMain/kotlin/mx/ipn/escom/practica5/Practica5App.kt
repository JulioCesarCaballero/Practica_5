package mx.ipn.escom.practica5

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import mx.ipn.escom.practica5.db.AppDatabase
import mx.ipn.escom.practica5.platform.AndroidConnectivityMonitor
import mx.ipn.escom.practica5.platform.AndroidSessionStorage

/** El contenedor vive en la Application para sobrevivir a rotaciones de pantalla. */
class Practica5App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            driver = AndroidSqliteDriver(AppDatabase.Schema, this, "practica5.db"),
            sessionStorage = AndroidSessionStorage(this),
            connectivity = AndroidConnectivityMonitor(this),
            apiBaseUrl = BuildConfig.API_BASE_URL,
            googleConfigured = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank(),
        )
    }
}
