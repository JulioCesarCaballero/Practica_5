import uuid

import pytest

from app import create_app

SECRET = "x" * 40


def fake_google_verifier(token, allowed):
    if token == "bad":
        raise ValueError("token inválido")
    email = token  # en las pruebas el "token" es el correo
    return {"sub": "g-" + email, "email": email, "name": "Google User",
            "aud": "test-client", "email_verified": True}


@pytest.fixture()
def client(tmp_path):
    app = create_app({
        "DATABASE_PATH": str(tmp_path / "test.db"),
        "JWT_SECRET": SECRET,
        "ADMIN_EMAILS": {"admin@escom.mx"},
        "GOOGLE_CLIENT_IDS": ["test-client"],
        "GOOGLE_VERIFIER": fake_google_verifier,
        "TESTING": True,
    })
    return app.test_client()


def register(client, email, name="Ana", password="secreto123"):
    r = client.post("/api/auth/register", json={"name": name, "email": email, "password": password})
    assert r.status_code == 201, r.json
    return {"Authorization": "Bearer " + r.json["token"]}, r.json["user"]


def test_register_login_me_logout(client):
    h, user = register(client, "ana@escom.mx")
    assert user["role"] == "user"
    assert client.post("/api/auth/register", json={"name": "A", "email": "ana@escom.mx",
                                                    "password": "secreto123"}).status_code == 409
    assert client.post("/api/auth/login", json={"email": "ana@escom.mx",
                                                 "password": "mala"}).status_code == 401
    r = client.post("/api/auth/login", json={"email": "ANA@escom.mx", "password": "secreto123"})
    assert r.status_code == 200
    assert client.get("/api/auth/me", headers=h).json["email"] == "ana@escom.mx"
    assert client.post("/api/auth/logout", headers=h).status_code == 204
    assert client.get("/api/auth/me", headers=h).status_code == 401  # token revocado


def test_auth_required_and_malformed(client):
    assert client.get("/api/books").status_code == 401
    assert client.get("/api/books", headers={"Authorization": "Bearer basura"}).status_code == 401
    r = client.post("/api/auth/login", data="no-json", content_type="text/plain")
    assert r.status_code == 400


def test_crud_books_and_isolation(client):
    h1, _ = register(client, "a@escom.mx")
    h2, _ = register(client, "b@escom.mx")
    r = client.post("/api/books", headers=h1, json={"title": "Dune", "author": "Herbert",
                                                     "status": "leyendo", "notes": "bien"})
    assert r.status_code == 201
    item_id = r.json["id"]
    assert len(client.get("/api/books", headers=h1).json) == 1
    assert client.get(f"/api/books/{item_id}", headers=h2).status_code == 404  # otro usuario
    r = client.put(f"/api/books/{item_id}", headers=h1,
                   json={"title": "Dune", "author": "F. Herbert", "status": "leido"})
    assert r.json["status"] == "leido"
    assert client.put(f"/api/books/{item_id}", headers=h1,
                      json={"title": "", "author": "x"}).status_code == 400
    assert client.delete(f"/api/books/{item_id}", headers=h2).status_code == 404
    assert client.delete(f"/api/books/{item_id}", headers=h1).status_code == 204
    assert client.get("/api/books", headers=h1).json == []


def test_favorites_idempotent(client):
    h, _ = register(client, "c@escom.mx")
    fav = {"title": "The Hobbit", "authors": ["Tolkien"], "cover_id": 1, "subjects": ["Fantasy"]}
    for _ in range(3):  # reintentos
        assert client.put("/api/favorites/OL262758W", headers=h, json=fav).status_code == 200
    assert len(client.get("/api/favorites", headers=h).json) == 1
    assert client.put("/api/favorites/../etc", headers=h, json=fav).status_code in (400, 404)
    assert client.delete("/api/favorites/OL262758W", headers=h).status_code == 204
    assert client.delete("/api/favorites/OL262758W", headers=h).status_code == 204
    assert client.get("/api/favorites", headers=h).json == []


def test_history_idempotent(client):
    h, _ = register(client, "d@escom.mx")
    cid = str(uuid.uuid4())
    payload = {"client_id": cid, "query": "tolkien", "results_count": 20, "searched_at": 1700000000000}
    assert client.post("/api/history", headers=h, json=payload).status_code == 201
    assert client.post("/api/history", headers=h, json=payload).status_code == 200
    assert len(client.get("/api/history", headers=h).json) == 1


def test_admin_permissions(client):
    admin_h, admin = register(client, "admin@escom.mx")
    assert admin["role"] == "admin"
    user_h, user = register(client, "e@escom.mx")
    # usuario normal: acceso denegado validado en servidor
    assert client.get("/api/admin/users", headers=user_h).status_code == 403
    assert client.get(f"/api/admin/users/{admin['id']}/history", headers=user_h).status_code == 403
    # admin: lista visible, pero datos sólo si el usuario autorizó
    assert client.get("/api/admin/users", headers=admin_h).status_code == 200
    assert client.get(f"/api/admin/users/{user['id']}/favorites", headers=admin_h).status_code == 403
    r = client.put("/api/me/preferences", headers=user_h, json={"share_with_admin": True})
    assert r.json["share_with_admin"] is True
    assert client.get(f"/api/admin/users/{user['id']}/favorites", headers=admin_h).status_code == 200
    assert client.get(f"/api/admin/users/{user['id']}/history", headers=admin_h).status_code == 200
    assert client.get("/api/admin/users/999/history", headers=admin_h).status_code == 404


def test_google_login_and_linking(client):
    r = client.post("/api/auth/google", json={"id_token": "nuevo@gmail.com"})
    assert r.status_code == 201 and r.json["user"]["auth_provider"] == "google"
    assert client.post("/api/auth/google", json={"id_token": "nuevo@gmail.com"}).status_code == 200
    assert client.post("/api/auth/google", json={"id_token": "bad"}).status_code == 401
    assert client.post("/api/auth/google", json={}).status_code == 400
    # vinculación con cuenta local existente
    register(client, "local@gmail.com")
    r = client.post("/api/auth/google", json={"id_token": "local@gmail.com"})
    assert r.status_code == 200 and r.json["user"]["auth_provider"] == "local"


def test_secret_required(tmp_path):
    with pytest.raises(RuntimeError):
        create_app({"DATABASE_PATH": str(tmp_path / "x.db"), "JWT_SECRET": "corto"})
