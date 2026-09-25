# Phantasmon Backend — Référence API (implémentation réelle)

**Statut :** document vivant, mis à jour à chaque endpoint REST ou WebSocket ajouté/modifié dans le code.
**Différence avec `phantasmon-backend-openapi.yaml`** : l'OpenAPI décrit le contrat cible complet (Partie 4). Ce document ne liste que ce qui est **réellement implémenté** dans le repo à l'instant présent, avec des exemples concrets de headers et de corps JSON. En cas de divergence entre les deux, ce document reflète l'état du code, l'OpenAPI reflète la cible.

Règle de maintenance : toute PR qui ajoute, modifie ou supprime un endpoint REST/WS met à jour ce fichier dans le même changement.

---

## Légende

- **Auth** : `Public` (aucun header requis) ou `Bearer` (`Authorization: Bearer <jwt>` requis, voir Partie 2 §3 du CAD).
- Tous les corps JSON sont des exemples, pas le schéma complet (voir l'OpenAPI pour le schéma formel une fois l'endpoint aligné dessus).

---

## REST

### `GET /health`

- **Auth** : Public
- **Headers requête** : aucun
- **Corps requête** : aucun
- **Réponse 200 OK**

```json
{
  "status": "UP",
  "database": "UP"
}
```

- **Réponse 503 Service Unavailable** (base de données injoignable)

```json
{
  "status": "DOWN",
  "database": "DOWN"
}
```

Implémentation : `com.mystaria.phantasmon_backend.health.HealthController`. Vérifie la connectivité PostgreSQL via un `SELECT 1` (`JdbcTemplate`) à chaque appel — ce n'est pas un simple "pong" statique.

---

### `POST /auth/session`

- **Auth** : Public
- **Headers requête**

```
Content-Type: application/json
```

- **Corps requête**

```json
{
  "uuid": "b1a4c2b0-1234-4d5e-8f90-abcdef123456",
  "username": "Bichou",
  "server_id": "a1b2c3d4e5f6..."
}
```

`uuid`/`username`/`server_id` sont obligatoires. `server_id` est la chaîne aléatoire que le client a utilisée pour son propre appel `joinServer` côté Mojang (CAD Partie 2 §3.1) — le backend ne fait que vérifier ce que Mojang confirme via `hasJoined`, il ne génère jamais ce `server_id` lui-même.

- **Réponse 200 OK**

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires_in": 1200
}
```

`expires_in` est en secondes (durée de vie de l'`access_token`, configurée via `phantasmon.jwt.access-ttl`).

- **Réponse 401 Unauthorized** — Mojang ne confirme pas la session (mauvaise preuve, joueur inconnu, compte offline/cracked — hors périmètre)

```json
{
  "error_code": "ERROR_AUTH_MOJANG_VERIFICATION_FAILED",
  "details": { "username": "Bichou" }
}
```

- **Réponse 401 Unauthorized** — l'UUID annoncé par le client ne correspond pas à celui confirmé par Mojang (jamais faire confiance au client)

```json
{
  "error_code": "ERROR_AUTH_UUID_MISMATCH",
  "details": {
    "claimed_uuid": "b1a4c2b0-1234-4d5e-8f90-abcdef123456",
    "verified_uuid": "9f8e7d6c-5432-1abc-def0-123456789abc"
  }
}
```

- **Réponse 400 Bad Request** — validation basique (`username`/`server_id` vides, `uuid` manquant) : format d'erreur standard Spring, pas encore un `error_code` structuré (pas couvert par l'OpenAPI pour cet endpoint).

Implémentation : `com.mystaria.phantasmon_backend.auth.AuthController` + `MojangSessionClient` (appelle réellement `https://sessionserver.mojang.com/session/minecraft/hasJoined`) + `JwtService` (HMAC-SHA256, secret via `JWT_SECRET`). Effet de bord : crée ou met à jour le `Player` correspondant (`PlayerService.recordConnection`).

**Utiliser le token émis** : `Authorization: Bearer <access_token>` sur toute route protégée (tout sauf `GET /health`, `POST /auth/session` et `POST /auth/refresh` actuellement). Un token de type `refresh` est rejeté s'il est présenté comme `access_token` (claim `type` vérifiée côté serveur).

---

### `POST /auth/refresh`

- **Auth** : Public (c'est le `refresh_token` lui-même qui prouve l'identité, pas un `Authorization: Bearer`)
- **Headers requête**

```
Content-Type: application/json
```

- **Corps requête**

```json
{
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

- **Réponse 200 OK** — nouveau couple de tokens (le `refresh_token` est *rotaté*, l'ancien n'est jamais renvoyé une seconde fois)

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires_in": 1200
}
```

- **Réponse 401 Unauthorized** — token invalide, expiré, mal signé, de type `access` au lieu de `refresh`, ou joueur introuvable en base

```json
{
  "error_code": "ERROR_AUTH_INVALID_REFRESH_TOKEN",
  "details": {}
}
```

Implémentation : `AuthController.refresh`. Ne recontacte jamais Mojang — vérifie uniquement la signature/expiration/type du JWT via `JwtService.parseRefreshToken`, puis relit le `Player` en base pour connaître son `last_username` courant. Met aussi à jour `last_seen_at` (équivalent d'un heartbeat de session).

**Limite connue (V1)** : il n'existe pas de table de révocation des refresh tokens (aucune table dédiée dans `PHANTASMON_DB_SCHEMA.md`). La rotation change le token renvoyé au client, mais l'ancien `refresh_token` reste cryptographiquement valide jusqu'à sa propre expiration (7 jours par défaut) même après rotation — une vraie invalidation nécessiterait un store côté backend (V2, cf. CAD Partie 2 §3.2).

---

## WebSocket

Aucun canal WebSocket implémenté pour l'instant. Voir `Documentation/CAD_Ghost_Pokemon_Partie_2_Architecture_Technique.md` §11 pour les événements C2S/S2C cibles (`JoinServerGroup`, `SendOutGhost`, `GhostEntitySpawn`, etc.) — à documenter ici avec un exemple de payload dès leur implémentation.

---

## Non implémenté (cible OpenAPI, pour suivi)

Endpoints décrits dans `phantasmon-backend-openapi.yaml` mais absents du code à ce jour : `GET /version`, `GET/POST/PATCH/DELETE /pokemon*`, `GET/PUT /players/{uuid}/team`, `POST /trades*`, `POST /battles*`, `GET/POST /admin/*`. Ils seront documentés ici avec exemples concrets au fur et à mesure de leur implémentation (voir `Documentation/CAD_Phantasmon_Partie_4_Plan_Developpement.md` pour l'ordre des phases).
