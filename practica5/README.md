# Práctica 5 – Libros P5 (Kotlin Multiplatform · Android)

**Unidad de aprendizaje:** Desarrollo de aplicaciones móviles nativas · ESCOM IPN · Plan 2020
**Alumno:** Caballero Pérez Julio César · 7CV4

> 🇬🇧 English version below.

Aplicación móvil con **Kotlin Multiplatform + Compose Multiplatform** que consume el backend REST propio (continuación de la Práctica 2) y la API pública **Open Library**. Incluye búsqueda, historial, favoritos, recomendaciones verificables, inicio de sesión con Google, rol administrador, caché local con SQLDelight y sincronización con cola de operaciones pendientes.

> **Alcance de esta entrega:** sólo se implementa y prueba el destino **Android** (API 24+). Toda la lógica, la base local, el cliente HTTP y la interfaz están en `commonMain`; lo específico de Android está aislado en `androidMain/platform` (ver «Arquitectura»).

---

## Estructura del repositorio

```
practica5/
├── backend/                 API REST Flask (JWT, CRUD, favoritos, historial, admin, Google)
│   ├── app.py
│   ├── tests/test_api.py    pruebas automatizadas (pytest)
│   └── Dockerfile
├── docker-compose.yml       API + túnel HTTPS opcional (perfil "https")
├── .env.example             variables del backend (sin secretos reales)
├── app/                     proyecto Gradle Kotlin Multiplatform
│   ├── composeApp/src/
│   │   ├── commonMain/      código compartido: UI, repositorios, Ktor, SQLDelight, sincronización
│   │   ├── androidMain/     MainActivity + implementaciones Android (sesión cifrada, red, Google)
│   │   └── commonTest/      pruebas unitarias compartidas
│   └── local.properties.example
└── docs/
    ├── INFORME_TECNICO.md
    ├── MATRIZ_PRUEBAS.md
    └── SINCRONIZACION.md
```

## Requisitos

| Herramienta | Versión |
|---|---|
| Android Studio | Ladybug 2024.2 o superior (con plugin Kotlin Multiplatform recomendado) |
| JDK | 17 (el que incluye Android Studio) |
| Android SDK | compileSdk 35, minSdk 24 (Android 7.0) |
| Emulador/dispositivo | Android 7.0+ **con Google Play** (para el inicio con Google) |
| Docker Desktop | 4.x con Docker Compose v2 |

Versiones principales: Kotlin 2.1.20 · Compose Multiplatform 1.8.0 · Ktor 3.1.2 · SQLDelight 2.0.2 · kotlinx.serialization 1.8.1 · Coil 3.1.0 · Credential Manager 1.5.0.

## 1. Arrancar el backend (Docker Compose)

```bash
cp .env.example .env
# Edita .env: JWT_SECRET (≥32 caracteres), GOOGLE_CLIENT_IDS y ADMIN_EMAILS
python -c "import secrets;print(secrets.token_hex(32))"   # genera un JWT_SECRET

docker compose --profile https up -d --build
docker compose logs tunnel | grep trycloudflare           # URL https://xxxx.trycloudflare.com
curl https://xxxx.trycloudflare.com/api/health
```

* El perfil `https` levanta un **Cloudflare Quick Tunnel** que publica la API con HTTPS válido, porque la app **bloquea el tráfico sin cifrar** (`network_security_config.xml`). La URL cambia cada vez que se reinicia el túnel; actualízala en `local.properties`.
* Alternativa: desplegar el contenedor en cualquier servicio con HTTPS (Render, Railway, etc.).
* La base SQLite vive en el volumen `db-data` y persiste entre reinicios.
* Pruebas del backend: `cd backend && pip install -r requirements.txt && pytest -q` (8 pruebas).

## 2. Configurar Google Sign-In (sin subir secretos)

1. En [Google Cloud Console](https://console.cloud.google.com/) crea un proyecto y configura la **pantalla de consentimiento OAuth** (tipo externo; agrega tus cuentas como usuarios de prueba).
2. **Credenciales → ID de cliente OAuth → Aplicación web.** Copia el ID: va en `GOOGLE_WEB_CLIENT_ID` (app) y en `GOOGLE_CLIENT_IDS` (`.env` del backend).
3. **Credenciales → ID de cliente OAuth → Android** con paquete `mx.ipn.escom.practica5` y la huella SHA‑1 de tu keystore de depuración:
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
   (En Windows: `%USERPROFILE%\.android\debug.keystore`.) Este ID no se copia a la app; Google lo usa para validar la firma del APK.
4. El backend valida el **ID token** (firma, `aud`, `iss`, expiración y `email_verified`) con `google-auth` antes de emitir su propio JWT.

## 3. Compilar y ejecutar la app Android

```bash
cd app
cp local.properties.example local.properties   # agrega API_BASE_URL y GOOGLE_WEB_CLIENT_ID
./gradlew :composeApp:assembleDebug            # Windows: gradlew.bat :composeApp:assembleDebug
./gradlew :composeApp:installDebug
./gradlew :composeApp:testDebugUnitTest         # pruebas de commonTest
```

O desde Android Studio: abrir la carpeta `app/`, sincronizar Gradle y ejecutar la configuración **composeApp**.

`local.properties` está en `.gitignore`; los valores se inyectan en `BuildConfig` al compilar.

## 4. Uso rápido para la demostración

1. Regístrate con correo y contraseña **o** usa «Continuar con Google».
2. **Buscar**: escribe «tolkien» → resultados reales con tiempo de respuesta; toca uno para ver el detalle.
3. Marca ♥ favoritos. Abre **Para ti** → recomendaciones con su criterio y puntaje.
4. **Mis libros**: CRUD (GET/POST/PUT/DELETE) contra `/api/books`.
5. Activa modo avión: aparece el banner «Sin conexión»; búsquedas guardadas, favoritos, historial y recomendaciones siguen disponibles; marcar/desmarcar favoritos queda en la cola.
6. Desactiva modo avión: la app sincroniza sola (contador de pendientes → 0, «Última sincronización» se actualiza).
7. **Perfil → Probar acceso a /api/admin/users**: un usuario normal recibe **403**; una cuenta incluida en `ADMIN_EMAILS` ve el panel. El admin sólo puede abrir los datos de usuarios que activaron «Compartir con el administrador».

## Endpoints

| Método | Ruta | Origen | Descripción |
|---|---|---|---|
| GET | `/api/health` | Práctica 2 | Estado del servicio |
| POST | `/api/auth/register` | Práctica 2 | Registro (contraseña con hash scrypt) |
| POST | `/api/auth/login` | Práctica 2 | Login → JWT (7 días) |
| GET | `/api/auth/me` | Práctica 2 | Usuario de la sesión |
| GET/POST | `/api/books` | Práctica 2 (CRUD) | Listar / crear libro de la lista de lectura |
| GET/PUT/DELETE | `/api/books/{id}` | Práctica 2 (CRUD) | Consultar / actualizar / borrar |
| POST | `/api/auth/google` | **Nuevo** | Valida ID token de Google y emite JWT propio |
| POST | `/api/auth/logout` | **Nuevo** | Revoca el token (lista de `jti` revocados) |
| PUT | `/api/me/preferences` | **Nuevo** | Preferencia `share_with_admin` |
| GET | `/api/favorites` | **Nuevo** | Favoritos del usuario |
| PUT/DELETE | `/api/favorites/{work_id}` | **Nuevo** | Alta/baja **idempotentes** |
| GET/POST/DELETE | `/api/history` | **Nuevo** | Historial; POST idempotente por `client_id` |
| GET | `/api/admin/users` | **Nuevo** | Sólo rol admin (403 si no) |
| GET | `/api/admin/users/{id}/history` · `/favorites` | **Nuevo** | Admin + consentimiento del usuario |

> Si tu Práctica 2 usaba otro recurso para el CRUD, enlaza aquí el commit/etiqueta de la Práctica 2 (`git tag practica2`) y ajusta la columna «Origen».

## Sincronización (resumen)

Ver [`docs/SINCRONIZACION.md`](docs/SINCRONIZACION.md). En corto: **cola local de operaciones idempotentes** (PUSH) → **descarga del estado del servidor** (PULL) con el servidor como fuente de verdad, excepto los cambios locales todavía pendientes. Reintentos con backoff 1 s/2 s/4 s. Se dispara al abrir la app, al iniciar sesión, al recuperar la conexión y manualmente.

## Arquitectura

* **commonMain (compartido):** UI Compose, `AppContainer`, `BackendApi`/`OpenLibraryApi` (Ktor), `LocalStore` (SQLDelight), `AuthRepository`, `SyncManager`, `LibraryRepository`, `ReadingRepository`, `RecommendationEngine`.
* **androidMain (específico):** `MainActivity`, `Practica5App`, `AndroidSessionStorage` (EncryptedSharedPreferences + Keystore), `AndroidConnectivityMonitor` (NetworkCallback), `AndroidGoogleAuthClient` (Credential Manager), motor OkHttp y driver SQLite.
* Para agregar iOS en el futuro se declararían `iosArm64()/iosSimulatorArm64()` y las implementaciones `actual` equivalentes (Keychain, NWPathMonitor, Google Sign-In for iOS, motor Darwin, NativeSqliteDriver).

## Herramientas de IA

Se utilizó un asistente de IA (Claude, Anthropic) como apoyo para generar código base y documentación.

---

# 🇬🇧 English

**Libros P5** is a **Kotlin Multiplatform + Compose Multiplatform** app (this delivery targets **Android only**, API 24+) that consumes our own REST backend (continuation of Lab 2) and the public **Open Library** API. Features: text search with detail view, search history, favorites, rule-based recommendations, Google Sign-In, admin role validated server-side, SQLDelight offline cache and a pending-operations queue synced on reconnect.

## Requirements
Android Studio Ladybug+, JDK 17, Android SDK 35 (minSdk 24), an emulator/device with Google Play, Docker Desktop with Compose v2.

## Backend
```bash
cp .env.example .env        # set JWT_SECRET (≥32 chars), GOOGLE_CLIENT_IDS, ADMIN_EMAILS
docker compose --profile https up -d --build
docker compose logs tunnel | grep trycloudflare   # public HTTPS URL
cd backend && pip install -r requirements.txt && pytest -q
```
The app only allows HTTPS, so use the Cloudflare quick tunnel (profile `https`) or any HTTPS deployment.

## Google OAuth (no secrets committed)
Create a **Web application** OAuth client (its ID goes to `GOOGLE_WEB_CLIENT_ID` in `app/local.properties` and to `GOOGLE_CLIENT_IDS` in `.env`) and an **Android** OAuth client with package `mx.ipn.escom.practica5` and your debug SHA‑1. The backend verifies the Google ID token (signature, audience, issuer, expiry, verified email) and issues its own JWT.

## Android app
```bash
cd app
cp local.properties.example local.properties   # API_BASE_URL, GOOGLE_WEB_CLIENT_ID
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest
```

## Sync strategy
Idempotent local operation queue (PUT/DELETE favorites, POST history with a client UUID, preference update) is pushed first, then server state is pulled and replaces local data, except items that still have pending local operations (latest local intent wins). Transient errors are retried with exponential backoff (1 s, 2 s, 4 s); permanent 4xx errors drop the operation so the queue never blocks. Sync runs on app start, login, reconnection and on demand. Details in `docs/SINCRONIZACION.md`.

## Endpoints
See the table above: Lab 2 endpoints (auth + `/api/books` CRUD) are reused; Google login, logout/revocation, favorites, history, preferences and admin endpoints are new.

## AI tools
An AI assistant (Claude, Anthropic) was used to help draft code and documentation.
