# Informe técnico – Práctica 5

**Consulta de bases de datos vía APIs y funcionalidades específicas en aplicaciones multiplataforma**

Instituto Politécnico Nacional · Escuela Superior de Cómputo
Ingeniería en Sistemas Computacionales (Plan 2020) · Desarrollo de aplicaciones móviles nativas
Alumno: Caballero Pérez Julio César · Grupo 7CV4 · Noviembre de 2026

---

## 1. Introducción

La práctica extiende el cliente CRUD de la Práctica 2 hacia una aplicación construida con **Kotlin Multiplatform (KMP)** y **Compose Multiplatform**. La aplicación «Libros P5» consulta el backend REST propio (Flask, dockerizado) para autenticación, CRUD, favoritos, historial y administración, e integra la API pública **Open Library** para buscar libros, mostrar su detalle y generar recomendaciones. Toda la información relevante se guarda en una base local (SQLDelight) y se sincroniza con el servidor mediante una cola de operaciones idempotentes, de modo que la app sigue siendo útil sin conexión.

**Alcance:** por decisión del equipo, esta entrega implementa y prueba únicamente el destino **Android (API 24+)**. El código se organizó para que la lógica y la interfaz queden en `commonMain` y sólo los servicios de plataforma vivan en `androidMain`.

## 2. Arquitectura

```
┌──────────────────────────── composeApp ────────────────────────────┐
│ commonMain (compartido)                                            │
│  UI Compose ── SearchViewModel                                     │
│      │                                                             │
│  Repositorios: Auth · Library · Reading · Recommendation · Admin   │
│      │                 │                                           │
│  SyncManager ──── LocalStore (SQLDelight) ── RecommendationEngine  │
│      │                                                             │
│  BackendApi / OpenLibraryApi (Ktor + kotlinx.serialization)        │
│  Contratos: SessionStorage · ConnectivityMonitor · GoogleAuthClient│
├────────────────────────────────────────────────────────────────────┤
│ androidMain (específico)                                           │
│  MainActivity · Practica5App (contenedor)                          │
│  EncryptedSharedPreferences · ConnectivityManager.NetworkCallback  │
│  Credential Manager (Google) · OkHttp · AndroidSqliteDriver        │
└────────────────────────────────────────────────────────────────────┘
          │ HTTPS (Cloudflare Tunnel)            │ HTTPS
   Backend Flask + SQLite (Docker)        openlibrary.org / covers
```

| Capa | Componentes comunes | Componentes nativos Android |
|---|---|---|
| Presentación | Pantallas Compose (login, búsqueda, detalle, favoritos, recomendaciones, CRUD, perfil, admin), banner de conectividad | `MainActivity`, `BackHandler` |
| Dominio/datos | Repositorios, `SyncManager`, `RecommendationEngine`, DTOs | — |
| Red | Ktor Client, `ApiResult` (éxito, error HTTP, red, datos malformados) | Motor OkHttp, `network_security_config` (sólo HTTPS) |
| Persistencia | Esquema y consultas SQLDelight (`AppDatabase.sq`) | `AndroidSqliteDriver`, EncryptedSharedPreferences |
| Plataforma | Interfaces `SessionStorage`, `ConnectivityMonitor`, `GoogleAuthClient` | Implementaciones con Keystore, NetworkCallback y Credential Manager |

El `AppContainer` (inyección manual de dependencias) vive en la clase `Application`, por lo que el estado sobrevive a rotaciones.

## 3. Endpoints utilizados

**Backend propio** (`/api`, JSON, JWT Bearer): `health`; `auth/register`, `auth/login`, `auth/me` (Práctica 2); CRUD `books` y `books/{id}` (Práctica 2); `auth/google`, `auth/logout`, `me/preferences`, `favorites`, `favorites/{work_id}`, `history`, `admin/users`, `admin/users/{id}/history`, `admin/users/{id}/favorites` (nuevos). La tabla completa está en el README.

**Open Library:**

| Uso | Endpoint |
|---|---|
| Búsqueda | `GET /search.json?q=…&fields=key,title,author_name,first_publish_year,cover_i,subject&limit=20` |
| Búsqueda por autor (recomendaciones) | `GET /search.json?author=…` |
| Detalle | `GET /works/{OLxxxW}.json` (la descripción puede ser texto u objeto) |
| Recomendaciones por tema | `GET /subjects/{tema}.json?limit=12` |
| Portadas | `https://covers.openlibrary.org/b/id/{cover_id}-M.jpg` |

Se envía un `User-Agent` identificable, se piden sólo los campos necesarios y se manejan 429 (límite), 5xx, timeouts y JSON inválido.

## 4. Modelo de datos

**Servidor (SQLite en volumen Docker):**

* `users(id, name, email UNIQUE, password_hash, google_sub UNIQUE, auth_provider, role, share_with_admin, created_at)`
* `reading_items(id, user_id→users, title, author, status, notes, created_at, updated_at)` – CRUD
* `favorites(user_id, work_id, title, authors JSON, cover_id, first_year, subjects JSON, created_at)` – PK compuesta
* `search_history(user_id, client_id, query, results_count, searched_at)` – PK compuesta
* `revoked_tokens(jti, expires_at)`

**Cliente (SQLDelight):** `cached_book`, `search_cache`, `search_history`, `favorite`, `viewed_book`, `reading_item`, `recommendation`, `pending_op`, `sync_meta`. Todas las tablas personales incluyen `user_id`. Se evitó `UPSERT` porque Android 7 trae SQLite 3.9.

## 5. Persistencia y sincronización

Descrita a detalle en `SINCRONIZACION.md`. Puntos clave:

1. Cada búsqueda exitosa guarda los libros y la lista de resultados con su fecha; sin red se muestran «guardados el dd/mm/aaaa hh:mm».
2. Favoritos, preferencia e historial se modifican localmente al instante y se encolan; la cola se colapsa por objetivo (sin duplicados) y el servidor acepta las operaciones de forma idempotente.
3. Al iniciar, al iniciar sesión, al recuperar la conexión o a petición, se hace PUSH (con reintentos 1 s/2 s/4 s) y luego PULL; el servidor es la fuente de verdad salvo para los cambios locales aún pendientes.
4. La interfaz muestra estado online/offline en tiempo real, número de pendientes, última sincronización y errores.

## 6. Autenticación y seguridad

* **Correo/contraseña:** hash scrypt (Werkzeug), JWT HS256 con `exp` (7 días) y `jti`; el cierre de sesión revoca el `jti`.
* **Google:** la app obtiene un ID token con **Credential Manager** (`GetSignInWithGoogleOption`, client ID web); el backend lo valida con `google-auth` (firma, audiencia, emisor, expiración, correo verificado), crea o vincula la cuenta y emite su propio JWT. La app nunca guarda el ID token.
* **Sesión segura:** el JWT se guarda en EncryptedSharedPreferences (AES‑256‑GCM/SIV con llave del Android Keystore), sin respaldo en la nube. Offline se conserva una sesión previamente validada; con red se revalida con `/auth/me` y un 401 cierra la sesión.
* **Autorización:** el rol se lee de la base en cada petición; `/api/admin/*` responde 403 a usuarios normales aunque llamen al endpoint directamente, y el admin sólo ve datos de usuarios con `share_with_admin = true`. Cada consulta filtra por el `user_id` del token, por lo que una sesión no puede leer datos de otra.
* **Transporte y secretos:** la app prohíbe tráfico en claro; `JWT_SECRET`, client IDs y URLs se configuran en `.env` / `local.properties`, ambos ignorados por git. Los logs HTTP no incluyen encabezados (no exponen el token).

## 7. Recomendaciones

Regla reproducible implementada en `RecommendationEngine` (código común y probado):

1. Para cada tema y autor se calcula `puntaje = 3 × (favoritos que lo contienen) + 1 × (libros consultados que lo contienen)`. Se descartan etiquetas genéricas de bibliotecas («Accessible book», «Protected DAISY», códigos con `:`/`=`/dígitos, etc.).
2. Se toman los 2 temas y el autor con mayor puntaje (desempate alfabético) y se consultan en Open Library.
3. Cada candidato acumula el puntaje de los criterios que lo produjeron; se excluyen los favoritos; se ordena por puntaje y título y se muestran hasta 10, cada uno con su motivo (p. ej. «Tema «fantasy»: está en 2 favoritos y 1 libro consultado · 7 pts»).
4. **Sin favoritos ni libros vistos:** se usa la búsqueda más frecuente del historial. **Sin ningún dato:** se muestran clásicos populares (`classic_literature`) explicando por qué.
5. Las recomendaciones, su fecha y la explicación del criterio se guardan en la base local para verlas sin conexión.

## 8. Resultados

* Backend: 8 pruebas automatizadas aprobadas (autenticación, CRUD, aislamiento, idempotencia, permisos de administrador, Google).
* Motor de recomendaciones: 5 pruebas unitarias aprobadas; parseo de Open Library y JSON malformado cubiertos en `commonTest`.
* Pruebas funcionales en Android: ver `MATRIZ_PRUEBAS.md` (escenarios F1–F14 con capturas).
* Evidencias: capturas/video de búsqueda, login (correo y Google), favoritos, recomendaciones, modo offline, sincronización y rol administrador en `docs/evidencias/`.

## 9. Conclusiones

KMP permitió escribir una sola vez la interfaz, el acceso a red, la base local, la sincronización y la lógica de recomendaciones; lo específico de la plataforma se redujo a cuatro piezas pequeñas detrás de interfaces (almacenamiento seguro, conectividad, Google y motor HTTP/driver SQL). Diseñar las operaciones del servidor para que fueran idempotentes simplificó mucho la sincronización: los reintentos dejan de ser un riesgo de duplicados y la cola local puede colapsarse por objetivo. Mantener al servidor como fuente de verdad, con prioridad para los cambios locales todavía pendientes, ofreció un comportamiento predecible para el usuario tanto online como offline. Como trabajo futuro queda habilitar los destinos iOS implementando las mismas interfaces con Keychain, `NWPathMonitor` y Google Sign-In for iOS.

## 10. Bibliografía (APA 7)

Android Developers. (2025). *Authenticate users with Sign in with Google*. Google. https://developer.android.com/identity/sign-in/credential-manager-siwg

Android Developers. (2025). *Monitor connectivity status and connection metering*. Google. https://developer.android.com/training/basics/network-ops/reading-network-state

Cash App. (2024). *SQLDelight documentation* (Versión 2.0). https://sqldelight.github.io/sqldelight/

Google for Developers. (2025). *Verify the Google ID token on your server side*. https://developers.google.com/identity/gsi/web/guides/verify-google-id-token

Internet Archive. (s. f.). *Open Library APIs*. Recuperado el 6 de noviembre de 2026 de https://openlibrary.org/developers/api

JetBrains. (2025). *Kotlin Multiplatform documentation*. https://www.jetbrains.com/help/kotlin-multiplatform-dev/

JetBrains. (2025). *Ktor Client documentation*. https://ktor.io/docs/client-create-new-application.html

Jones, M., Bradley, J., & Sakimura, N. (2015). *JSON Web Token (JWT)* (RFC 7519). Internet Engineering Task Force. https://doi.org/10.17487/RFC7519

Pallets Projects. (2025). *Flask documentation* (Versión 3.1). https://flask.palletsprojects.com/
