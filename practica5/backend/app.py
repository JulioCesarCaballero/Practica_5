"""
Práctica 5 – Backend REST (continuación de la Práctica 2).

Flask + SQLite (volumen Docker) + JWT.
Endpoints:
  /api/health
  /api/auth/register | login | google | me | logout
  /api/books            (CRUD de la lista de lectura personal: GET/POST/PUT/DELETE)
  /api/favorites        (favoritos por usuario, PUT/DELETE idempotentes)
  /api/history          (historial de búsquedas, POST idempotente por client_id)
  /api/me/preferences   (preferencia share_with_admin)
  /api/admin/...        (rol administrador validado en el servidor)
"""
import datetime as dt
import json
import os
import re
import sqlite3
import uuid
from functools import wraps

import jwt
from flask import Flask, g, jsonify, request
from werkzeug.security import check_password_hash, generate_password_hash

SCHEMA = """
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS users(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT,
    google_sub TEXT UNIQUE,
    auth_provider TEXT NOT NULL DEFAULT 'local',
    role TEXT NOT NULL DEFAULT 'user',
    share_with_admin INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS revoked_tokens(
    jti TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS reading_items(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    author TEXT NOT NULL,
    status TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS favorites(
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_id TEXT NOT NULL,
    title TEXT NOT NULL,
    authors TEXT NOT NULL,
    cover_id INTEGER,
    first_year INTEGER,
    subjects TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY(user_id, work_id)
);
CREATE TABLE IF NOT EXISTS search_history(
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,
    query TEXT NOT NULL,
    results_count INTEGER NOT NULL,
    searched_at INTEGER NOT NULL,
    PRIMARY KEY(user_id, client_id)
);
"""

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
WORK_ID_RE = re.compile(r"^OL\d+W$")
UUID_RE = re.compile(r"^[0-9a-fA-F-]{8,64}$")
STATUSES = {"pendiente", "leyendo", "leido"}
TOKEN_TTL = dt.timedelta(days=7)


def now_iso():
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def error(message, status):
    return jsonify({"error": message}), status


# --------------------------------------------------------------------------- #
# Verificación de Google ID tokens
# --------------------------------------------------------------------------- #
def default_google_verifier(token, allowed_client_ids):
    """Valida el ID token de Google en el servidor y regresa sus claims."""
    if not allowed_client_ids:
        raise ValueError("GOOGLE_CLIENT_IDS no está configurado en el servidor")
    try:
        from google.auth.transport import requests as grequests
        from google.oauth2 import id_token

        info = id_token.verify_oauth2_token(token, grequests.Request(), audience=None)
    except ImportError:  # respaldo: endpoint tokeninfo de Google
        import requests

        resp = requests.get(
            "https://oauth2.googleapis.com/tokeninfo",
            params={"id_token": token},
            timeout=10,
        )
        if resp.status_code != 200:
            raise ValueError("Token de Google inválido")
        info = resp.json()
        if int(info.get("exp", 0)) < int(dt.datetime.now().timestamp()):
            raise ValueError("Token de Google expirado")
    if info.get("aud") not in allowed_client_ids:
        raise ValueError("El token no fue emitido para esta aplicación")
    if info.get("iss") not in ("accounts.google.com", "https://accounts.google.com"):
        raise ValueError("Emisor inválido")
    if str(info.get("email_verified")).lower() != "true":
        raise ValueError("El correo de Google no está verificado")
    return info


# --------------------------------------------------------------------------- #
# App factory
# --------------------------------------------------------------------------- #
def create_app(config=None):
    app = Flask(__name__)
    app.config.update(
        DATABASE_PATH=os.environ.get("DATABASE_PATH", "app.db"),
        JWT_SECRET=os.environ.get("JWT_SECRET", ""),
        GOOGLE_CLIENT_IDS=[
            c.strip() for c in os.environ.get("GOOGLE_CLIENT_IDS", "").split(",") if c.strip()
        ],
        ADMIN_EMAILS={
            e.strip().lower() for e in os.environ.get("ADMIN_EMAILS", "").split(",") if e.strip()
        },
        GOOGLE_VERIFIER=default_google_verifier,
    )
    if config:
        app.config.update(config)
    if len(app.config["JWT_SECRET"]) < 32:
        raise RuntimeError("JWT_SECRET debe tener al menos 32 caracteres (defínelo en .env)")

    # ---------------- base de datos ----------------
    def get_db():
        if "db" not in g:
            g.db = sqlite3.connect(app.config["DATABASE_PATH"])
            g.db.row_factory = sqlite3.Row
            g.db.execute("PRAGMA foreign_keys = ON")
        return g.db

    @app.teardown_appcontext
    def close_db(_exc):
        db = g.pop("db", None)
        if db is not None:
            db.close()

    with app.app_context():
        db = get_db()
        db.executescript(SCHEMA)
        db.execute("PRAGMA journal_mode = WAL")
        db.commit()

    # ---------------- helpers ----------------
    def user_json(row):
        return {
            "id": row["id"],
            "name": row["name"],
            "email": row["email"],
            "role": row["role"],
            "auth_provider": row["auth_provider"],
            "share_with_admin": bool(row["share_with_admin"]),
            "created_at": row["created_at"],
        }

    def role_for(email, current="user"):
        return "admin" if email.lower() in app.config["ADMIN_EMAILS"] else current

    def issue_token(user_row):
        now = dt.datetime.now(dt.timezone.utc)
        payload = {
            "sub": str(user_row["id"]),
            "role": user_row["role"],
            "jti": uuid.uuid4().hex,
            "iat": now,
            "exp": now + TOKEN_TTL,
        }
        return jwt.encode(payload, app.config["JWT_SECRET"], algorithm="HS256")

    def auth_response(user_row, status=200):
        return jsonify({"token": issue_token(user_row), "user": user_json(user_row)}), status

    def body():
        data = request.get_json(silent=True)
        return data if isinstance(data, dict) else None

    def require_auth(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            header = request.headers.get("Authorization", "")
            if not header.startswith("Bearer "):
                return error("Falta el token de autenticación", 401)
            try:
                claims = jwt.decode(
                    header[7:], app.config["JWT_SECRET"], algorithms=["HS256"],
                    options={"require": ["exp", "sub", "jti"]},
                )
            except jwt.ExpiredSignatureError:
                return error("La sesión expiró", 401)
            except jwt.InvalidTokenError:
                return error("Token inválido", 401)
            db = get_db()
            if db.execute("SELECT 1 FROM revoked_tokens WHERE jti=?", (claims["jti"],)).fetchone():
                return error("La sesión fue cerrada", 401)
            user = db.execute("SELECT * FROM users WHERE id=?", (int(claims["sub"]),)).fetchone()
            if user is None:
                return error("Usuario no encontrado", 401)
            g.user = user
            g.claims = claims
            return fn(*args, **kwargs)

        return wrapper

    def require_admin(fn):
        @wraps(fn)
        @require_auth
        def wrapper(*args, **kwargs):
            # El rol se lee de la BD en cada petición (no sólo del token ni de la UI).
            if g.user["role"] != "admin":
                return error("Acceso denegado: se requiere rol administrador", 403)
            return fn(*args, **kwargs)

        return wrapper

    # ---------------- errores genéricos ----------------
    @app.errorhandler(404)
    def not_found(_e):
        return error("Recurso no encontrado", 404)

    @app.errorhandler(405)
    def not_allowed(_e):
        return error("Método no permitido", 405)

    @app.errorhandler(500)
    def internal(_e):
        return error("Error interno del servidor", 500)

    # ---------------- health ----------------
    @app.get("/api/health")
    def health():
        return jsonify({"status": "ok", "time": now_iso()})

    # ---------------- autenticación ----------------
    @app.post("/api/auth/register")
    def register():
        data = body()
        if data is None:
            return error("JSON inválido", 400)
        name = str(data.get("name", "")).strip()
        email = str(data.get("email", "")).strip().lower()
        password = str(data.get("password", ""))
        if not name or len(name) > 80:
            return error("El nombre es obligatorio (máx. 80 caracteres)", 400)
        if not EMAIL_RE.match(email):
            return error("Correo inválido", 400)
        if len(password) < 8:
            return error("La contraseña debe tener al menos 8 caracteres", 400)
        db = get_db()
        if db.execute("SELECT 1 FROM users WHERE email=?", (email,)).fetchone():
            return error("El correo ya está registrado", 409)
        cur = db.execute(
            "INSERT INTO users(name,email,password_hash,auth_provider,role,created_at) "
            "VALUES(?,?,?,?,?,?)",
            (name, email, generate_password_hash(password), "local", role_for(email), now_iso()),
        )
        db.commit()
        user = db.execute("SELECT * FROM users WHERE id=?", (cur.lastrowid,)).fetchone()
        return auth_response(user, 201)

    @app.post("/api/auth/login")
    def login():
        data = body()
        if data is None:
            return error("JSON inválido", 400)
        email = str(data.get("email", "")).strip().lower()
        password = str(data.get("password", ""))
        db = get_db()
        user = db.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
        if user is None or not user["password_hash"] or not check_password_hash(
            user["password_hash"], password
        ):
            return error("Correo o contraseña incorrectos", 401)
        new_role = role_for(email, user["role"])
        if new_role != user["role"]:
            db.execute("UPDATE users SET role=? WHERE id=?", (new_role, user["id"]))
            db.commit()
            user = db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone()
        return auth_response(user)

    @app.post("/api/auth/google")
    def google_login():
        data = body()
        if data is None or not data.get("id_token"):
            return error("Falta id_token", 400)
        try:
            info = app.config["GOOGLE_VERIFIER"](
                str(data["id_token"]), app.config["GOOGLE_CLIENT_IDS"]
            )
        except Exception as exc:  # noqa: BLE001 – cualquier fallo de validación es 401
            return error(f"No se pudo validar la cuenta de Google: {exc}", 401)
        sub = str(info["sub"])
        email = str(info.get("email", "")).lower()
        name = str(info.get("name") or email.split("@")[0])
        db = get_db()
        user = db.execute("SELECT * FROM users WHERE google_sub=?", (sub,)).fetchone()
        status = 200
        if user is None:
            existing = db.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
            if existing is not None:
                # Vincula la cuenta local existente (correo verificado por Google).
                db.execute("UPDATE users SET google_sub=? WHERE id=?", (sub, existing["id"]))
                user_id = existing["id"]
            else:
                cur = db.execute(
                    "INSERT INTO users(name,email,google_sub,auth_provider,role,created_at) "
                    "VALUES(?,?,?,?,?,?)",
                    (name, email, sub, "google", role_for(email), now_iso()),
                )
                user_id = cur.lastrowid
                status = 201
            db.commit()
            user = db.execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone()
        new_role = role_for(email, user["role"])
        if new_role != user["role"]:
            db.execute("UPDATE users SET role=? WHERE id=?", (new_role, user["id"]))
            db.commit()
            user = db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone()
        return auth_response(user, status)

    @app.get("/api/auth/me")
    @require_auth
    def me():
        return jsonify(user_json(g.user))

    @app.post("/api/auth/logout")
    @require_auth
    def logout():
        db = get_db()
        db.execute(
            "INSERT OR IGNORE INTO revoked_tokens(jti, expires_at) VALUES(?,?)",
            (g.claims["jti"], int(g.claims["exp"])),
        )
        db.execute(
            "DELETE FROM revoked_tokens WHERE expires_at < ?",
            (int(dt.datetime.now(dt.timezone.utc).timestamp()),),
        )
        db.commit()
        return "", 204

    @app.put("/api/me/preferences")
    @require_auth
    def update_preferences():
        data = body()
        if data is None or not isinstance(data.get("share_with_admin"), bool):
            return error("share_with_admin (bool) es obligatorio", 400)
        db = get_db()
        db.execute(
            "UPDATE users SET share_with_admin=? WHERE id=?",
            (1 if data["share_with_admin"] else 0, g.user["id"]),
        )
        db.commit()
        user = db.execute("SELECT * FROM users WHERE id=?", (g.user["id"],)).fetchone()
        return jsonify(user_json(user))

    # ---------------- CRUD: lista de lectura ----------------
    def item_json(row):
        return {
            "id": row["id"],
            "title": row["title"],
            "author": row["author"],
            "status": row["status"],
            "notes": row["notes"],
            "updated_at": row["updated_at"],
        }

    def validate_item(data):
        if data is None:
            return None, "JSON inválido"
        title = str(data.get("title", "")).strip()
        author = str(data.get("author", "")).strip()
        status = str(data.get("status", "pendiente")).strip().lower()
        notes = str(data.get("notes", "")).strip()
        if not title or len(title) > 200:
            return None, "El título es obligatorio (máx. 200 caracteres)"
        if not author or len(author) > 120:
            return None, "El autor es obligatorio (máx. 120 caracteres)"
        if status not in STATUSES:
            return None, "Estado inválido (pendiente, leyendo, leido)"
        if len(notes) > 1000:
            return None, "Las notas no pueden exceder 1000 caracteres"
        return (title, author, status, notes), None

    def own_item(item_id):
        return get_db().execute(
            "SELECT * FROM reading_items WHERE id=? AND user_id=?", (item_id, g.user["id"])
        ).fetchone()

    @app.get("/api/books")
    @require_auth
    def list_items():
        rows = get_db().execute(
            "SELECT * FROM reading_items WHERE user_id=? ORDER BY updated_at DESC, id DESC",
            (g.user["id"],),
        ).fetchall()
        return jsonify([item_json(r) for r in rows])

    @app.get("/api/books/<int:item_id>")
    @require_auth
    def get_item(item_id):
        row = own_item(item_id)
        return jsonify(item_json(row)) if row else error("Libro no encontrado", 404)

    @app.post("/api/books")
    @require_auth
    def create_item():
        values, err = validate_item(body())
        if err:
            return error(err, 400)
        ts = now_iso()
        db = get_db()
        cur = db.execute(
            "INSERT INTO reading_items(user_id,title,author,status,notes,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (g.user["id"], *values, ts, ts),
        )
        db.commit()
        return jsonify(item_json(own_item(cur.lastrowid))), 201

    @app.put("/api/books/<int:item_id>")
    @require_auth
    def update_item(item_id):
        if own_item(item_id) is None:
            return error("Libro no encontrado", 404)
        values, err = validate_item(body())
        if err:
            return error(err, 400)
        db = get_db()
        db.execute(
            "UPDATE reading_items SET title=?,author=?,status=?,notes=?,updated_at=? "
            "WHERE id=? AND user_id=?",
            (*values, now_iso(), item_id, g.user["id"]),
        )
        db.commit()
        return jsonify(item_json(own_item(item_id)))

    @app.delete("/api/books/<int:item_id>")
    @require_auth
    def delete_item(item_id):
        db = get_db()
        cur = db.execute(
            "DELETE FROM reading_items WHERE id=? AND user_id=?", (item_id, g.user["id"])
        )
        db.commit()
        return ("", 204) if cur.rowcount else error("Libro no encontrado", 404)

    # ---------------- favoritos ----------------
    def fav_json(row):
        return {
            "work_id": row["work_id"],
            "title": row["title"],
            "authors": json.loads(row["authors"]),
            "cover_id": row["cover_id"],
            "first_year": row["first_year"],
            "subjects": json.loads(row["subjects"]),
            "created_at": row["created_at"],
        }

    def favorites_of(user_id):
        rows = get_db().execute(
            "SELECT * FROM favorites WHERE user_id=? ORDER BY created_at DESC", (user_id,)
        ).fetchall()
        return [fav_json(r) for r in rows]

    @app.get("/api/favorites")
    @require_auth
    def list_favorites():
        return jsonify(favorites_of(g.user["id"]))

    @app.put("/api/favorites/<work_id>")
    @require_auth
    def put_favorite(work_id):
        """Idempotente: repetir la operación no duplica el favorito."""
        if not WORK_ID_RE.match(work_id):
            return error("work_id inválido", 400)
        data = body()
        if data is None or not str(data.get("title", "")).strip():
            return error("title es obligatorio", 400)
        authors = [str(a)[:120] for a in (data.get("authors") or [])][:10]
        subjects = [str(s)[:80] for s in (data.get("subjects") or [])][:30]
        cover_id = data.get("cover_id") if isinstance(data.get("cover_id"), int) else None
        first_year = data.get("first_year") if isinstance(data.get("first_year"), int) else None
        db = get_db()
        db.execute(
            "INSERT INTO favorites(user_id,work_id,title,authors,cover_id,first_year,subjects,created_at) "
            "VALUES(?,?,?,?,?,?,?,?) "
            "ON CONFLICT(user_id, work_id) DO UPDATE SET title=excluded.title, "
            "authors=excluded.authors, cover_id=excluded.cover_id, "
            "first_year=excluded.first_year, subjects=excluded.subjects",
            (
                g.user["id"], work_id, str(data["title"])[:200], json.dumps(authors),
                cover_id, first_year, json.dumps(subjects), now_iso(),
            ),
        )
        db.commit()
        row = db.execute(
            "SELECT * FROM favorites WHERE user_id=? AND work_id=?", (g.user["id"], work_id)
        ).fetchone()
        return jsonify(fav_json(row))

    @app.delete("/api/favorites/<work_id>")
    @require_auth
    def delete_favorite(work_id):
        """Idempotente: borrar algo que ya no existe también responde 204."""
        db = get_db()
        db.execute("DELETE FROM favorites WHERE user_id=? AND work_id=?", (g.user["id"], work_id))
        db.commit()
        return "", 204

    # ---------------- historial ----------------
    def history_of(user_id, limit=200):
        rows = get_db().execute(
            "SELECT * FROM search_history WHERE user_id=? ORDER BY searched_at DESC LIMIT ?",
            (user_id, limit),
        ).fetchall()
        return [
            {
                "client_id": r["client_id"],
                "query": r["query"],
                "results_count": r["results_count"],
                "searched_at": r["searched_at"],
            }
            for r in rows
        ]

    @app.get("/api/history")
    @require_auth
    def list_history():
        return jsonify(history_of(g.user["id"]))

    @app.post("/api/history")
    @require_auth
    def add_history():
        """Idempotente por (user_id, client_id): los reintentos no duplican registros."""
        data = body()
        if data is None:
            return error("JSON inválido", 400)
        client_id = str(data.get("client_id", ""))
        query = str(data.get("query", "")).strip()
        if not UUID_RE.match(client_id):
            return error("client_id inválido", 400)
        if not query or len(query) > 200:
            return error("query inválida", 400)
        try:
            results_count = int(data.get("results_count", 0))
            searched_at = int(data.get("searched_at"))
        except (TypeError, ValueError):
            return error("results_count/searched_at deben ser enteros", 400)
        db = get_db()
        cur = db.execute(
            "INSERT OR IGNORE INTO search_history(user_id,client_id,query,results_count,searched_at) "
            "VALUES(?,?,?,?,?)",
            (g.user["id"], client_id, query, results_count, searched_at),
        )
        db.commit()
        return jsonify({"client_id": client_id, "created": cur.rowcount == 1}), (
            201 if cur.rowcount == 1 else 200
        )

    @app.delete("/api/history")
    @require_auth
    def clear_history():
        db = get_db()
        db.execute("DELETE FROM search_history WHERE user_id=?", (g.user["id"],))
        db.commit()
        return "", 204

    # ---------------- administración ----------------
    @app.get("/api/admin/users")
    @require_admin
    def admin_users():
        rows = get_db().execute(
            """SELECT u.*,
                      (SELECT COUNT(*) FROM favorites f WHERE f.user_id=u.id) AS fav_count,
                      (SELECT COUNT(*) FROM search_history h WHERE h.user_id=u.id) AS hist_count
               FROM users u ORDER BY u.id"""
        ).fetchall()
        return jsonify(
            [
                {
                    **user_json(r),
                    "favorites_count": r["fav_count"] if r["share_with_admin"] else None,
                    "history_count": r["hist_count"] if r["share_with_admin"] else None,
                }
                for r in rows
            ]
        )

    def authorized_target(user_id):
        target = get_db().execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone()
        if target is None:
            return None, error("Usuario no encontrado", 404)
        if not target["share_with_admin"] and target["id"] != g.user["id"]:
            return None, error("El usuario no autorizó compartir sus datos", 403)
        return target, None

    @app.get("/api/admin/users/<int:user_id>/history")
    @require_admin
    def admin_history(user_id):
        target, err = authorized_target(user_id)
        return err if err else jsonify(history_of(target["id"]))

    @app.get("/api/admin/users/<int:user_id>/favorites")
    @require_admin
    def admin_favorites(user_id):
        target, err = authorized_target(user_id)
        return err if err else jsonify(favorites_of(target["id"]))

    return app


if __name__ == "__main__":
    create_app().run(host="0.0.0.0", port=int(os.environ.get("PORT", "8000")))
