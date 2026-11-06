# Estrategia de almacenamiento local, sincronización y conflictos

## 1. Qué se guarda localmente (SQLDelight, `practica5.db`)

| Tabla | Contenido | Separación por cuenta | ¿Disponible al reabrir sin red? |
|---|---|---|---|
| `cached_book` | Datos de obras de Open Library (título, autores, año, portada, temas, descripción) + `updated_at` | Compartida (datos públicos) | Sí |
| `search_cache` | Consulta normalizada → IDs de resultados, total y fecha | `user_id` | Sí |
| `search_history` | Búsquedas con UUID (`client_id`), fecha, nº de resultados y bandera `synced` | `user_id` | Sí |
| `favorite` | Favoritos del usuario | `user_id` | Sí |
| `viewed_book` | Libros cuyo detalle se consultó (insumo de recomendaciones) | `user_id` | Sí |
| `reading_item` | Copia del CRUD `/api/books` | `user_id` | Sí (sólo lectura) |
| `recommendation` | Recomendaciones con motivo, puntaje y fecha de generación | `user_id` | Sí |
| `pending_op` | Cola de operaciones por enviar | `user_id` | Sí |
| `sync_meta` | Última sincronización, último error y explicación de recomendaciones | `user_id` | Sí |

La sesión (JWT + datos básicos del usuario) se guarda aparte en **EncryptedSharedPreferences** (AES‑256, llave en Android Keystore). Las portadas las guarda Coil en su caché de disco. El respaldo en la nube está deshabilitado para la base y la sesión (`allowBackup=false`, `data_extraction_rules.xml`).

**Justificación:** todo lo que el usuario ya vio o produjo (búsquedas, detalle, favoritos, historial, recomendaciones, su lista de lectura) debe poder consultarse sin red; las escrituras del CRUD requieren al servidor porque el backend asigna IDs y es la fuente de verdad.

## 2. Cola de operaciones pendientes

| Tipo | Petición al servidor | Idempotencia |
|---|---|---|
| `FAV_ADD` | `PUT /api/favorites/{work_id}` | Upsert por `(user_id, work_id)` |
| `FAV_REMOVE` | `DELETE /api/favorites/{work_id}` | Borrar algo inexistente también es `204` |
| `HISTORY_ADD` | `POST /api/history` | `INSERT OR IGNORE` por `(user_id, client_id)` |
| `PREF_SHARE` | `PUT /api/me/preferences` | Asigna un valor absoluto |

* La llave `op_key = usuario|categoría|objetivo` hace que una nueva operación sobre el mismo libro **reemplace** a la anterior: marcar → desmarcar → marcar sin conexión deja **una sola** operación (`FAV_ADD`).
* Al confirmar una operación se borra con `DELETE … WHERE op_key = ? AND created_at = ?`: si el usuario volvió a cambiar el favorito mientras se enviaba, la operación nueva no se pierde.
* Como todas las peticiones son idempotentes, un reintento después de un timeout (cuando el servidor sí alcanzó a aplicar el cambio) **no duplica** datos.

## 3. Ciclo de sincronización

Se ejecuta al abrir la app con sesión, al iniciar sesión, cada vez que `ConnectivityMonitor` pasa de offline a online, tras cada cambio local (si hay red) y con el botón «Sincronizar».

1. **PUSH:** envía la cola en orden de creación.
   * Éxito → se elimina la operación.
   * Error transitorio (sin red, timeout, 5xx, 429, 408) → hasta 3 intentos con backoff 1 s, 2 s, 4 s; si persiste, la operación se conserva (`attempts`, `last_error`) y la sincronización se detiene para respetar el orden.
   * Error permanente (400/403/404) → se descarta y se informa, para que una operación inválida no bloquee la cola.
   * 401 → la sesión se cierra y se pide iniciar sesión de nuevo (las operaciones siguen guardadas para esa cuenta).
2. **PULL:** descarga favoritos, historial, lista de lectura y perfil (también con reintentos).
3. Se guarda `last_sync` (sólo si no hubo errores) y `last_sync_error`. La UI muestra el banner de estado, el número de pendientes y la fecha de la última sincronización.

Un `Mutex` evita sincronizaciones simultáneas.

## 4. Conflictos y prioridad de los datos

* **Servidor = fuente de verdad** para favoritos, historial, CRUD y perfil.
* **Excepción:** si un favorito o la preferencia tienen una operación local pendiente, **gana la intención local más reciente** (no se sobrescribe con el PULL; se enviará en la siguiente sincronización).
* **Historial:** es de sólo inserción, se combina por `client_id` (unión sin duplicados).
* **CRUD `/api/books`:** las escrituras requieren conexión; si no hay red se muestra el error y no se aplica nada localmente, por lo que no hay conflictos de edición concurrente. Tras cada GET se reemplaza la copia local (last‑write‑wins del servidor, campo `updated_at`).
* **Datos públicos (Open Library):** se refrescan cada vez que se consultan con red; sin red se muestran con la fecha en que se guardaron.
