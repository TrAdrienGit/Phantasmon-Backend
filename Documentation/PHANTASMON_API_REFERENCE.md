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

### `GET /version`

- **Auth** : Public
- **Réponse 200 OK**

```json
{ "current_version": "0.1.0", "min_supported_version": "0.1.0" }
```

À appeler par le client **avant** de démarrer le flux d'auth Mojang (CAD Partie 3 §E) — un client trop
ancien (`client_version < min_supported_version`) doit être invité à se mettre à jour sans même tenter de
s'authentifier. Valeurs configurables via `phantasmon.version.current`/`phantasmon.version.min-supported`
(`application.properties`). Implémentation : `com.mystaria.phantasmon_backend.version.VersionController`.

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

### `POST /pokemon`

- **Auth** : Bearer (obligatoire)
- **Headers requête**

```
Content-Type: application/json
Authorization: Bearer <access_token>
```

- **Corps requête** — `owner_uuid` n'est **jamais** accepté du client : le propriétaire vient toujours du
  JWT authentifié. `box_id`/`box_slot`/`team_slot` sont optionnels — si tous absents, le premier
  emplacement PC libre est assigné automatiquement.

```json
{
  "request_uuid": "b1a4c2b0-1234-4d5e-8f90-abcdef123456",
  "species": "pikachu",
  "form": null,
  "level": 50,
  "nature": "timid",
  "ability": "static",
  "is_shiny": true,
  "cobblemon_data_version": "1.8.1",
  "data": {
    "nickname": "Sparky",
    "gender": "female",
    "tera_type": "electric",
    "ivs": { "hp": 31, "atk": 31, "def": 31, "spa": 31, "spd": 31, "spe": 31 },
    "evs": { "hp": 0, "atk": 252, "def": 0, "spa": 0, "spd": 4, "spe": 252 },
    "moves": ["thunderbolt", "quick-attack"],
    "held_item": "light-ball"
  }
}
```

- **Réponse 201 Created**

```json
{
  "uuid": "9f8e7d6c-5432-1abc-def0-123456789abc",
  "owner_uuid": "b1a4c2b0-1234-4d5e-8f90-abcdef123456",
  "species": "pikachu",
  "form": null,
  "level": 50,
  "nature": "timid",
  "ability": "static",
  "is_shiny": true,
  "box_id": 1,
  "box_slot": 1,
  "team_slot": null,
  "cobblemon_data_version": "1.8.1",
  "data": { "...": "..." }
}
```

Rejouer la même requête avec le même `request_uuid` renvoie la **même** réponse (201) sans créer de
second Pokémon (idempotence via `common.IdempotencyService`, table `idempotency_keys`).

- **Réponse 422 Unprocessable Content** — légalité violée (IVs/EVs uniquement en V1 — voir limite ci-dessous)

```json
{ "error_code": "ERROR_LEGALITY_EV_TOTAL_EXCEEDED", "details": { "total": 528, "max": 510 } }
```

Autres codes possibles : `ERROR_LEGALITY_IV_OUT_OF_RANGE`, `ERROR_LEGALITY_EV_OUT_OF_RANGE`.

- **Réponse 409 Conflict** — `ERROR_POKEMON_SLOT_OCCUPIED` (emplacement PC déjà pris) ou
  `ERROR_POKEMON_PC_FULL` (les 576 emplacements sont occupés).

**Limite connue (V1)** : `PokemonLegalityService` ne valide que les IVs/EVs. La cohérence
espèce/capacité/moveset (CAD Partie 3 §B) n'est **pas** vérifiée côté backend — ce service n'a aucune
dépendance Minecraft/Cobblemon et ne peut donc pas savoir quelles capacités/attaques existent réellement
pour une espèce donnée ; cette résolution reste entièrement côté client.

---

### `GET /players/{uuid}/pokemon`

- **Auth** : Bearer — `{uuid}` doit être le joueur authentifié lui-même (sinon 403 `ERROR_OWNERSHIP_MISMATCH`, aucune route admin de consultation croisée n'existe encore)
- **Réponse 200 OK** : tableau de `Pokemon` (même forme que la réponse de création)

### `GET /players/{uuid}/pc?box={n}`

- **Auth** : Bearer, même contrainte de propriétaire que ci-dessus
- **Réponse 200 OK** : tableau de `Pokemon` filtré sur `box_id={n}` (1 à 16)

### `PATCH /pokemon/{uuid}`

- **Auth** : Bearer — l'ownership du Pokémon ciblé est revérifié en base (403 `ERROR_OWNERSHIP_MISMATCH` sinon, 404 `ERROR_POKEMON_NOT_FOUND` s'il n'existe pas)
- **Corps requête** — tous les champs sont optionnels (mise à jour partielle) ; `data` déclenche une revalidation de légalité

```json
{ "level": 60, "team_slot": 1 }
```

- **Réponse 200 OK** : le `Pokemon` mis à jour

### `DELETE /pokemon/{uuid}`

- **Auth** : Bearer, même vérification d'ownership
- **Réponse 204 No Content**

### `POST /pokemon/{uuid}/clone`

- **Auth** : Bearer, même vérification d'ownership
- **Réponse 201 Created** : le clone, avec un nouvel `uuid`, placé automatiquement au premier emplacement PC libre (CAD Partie 1 §19)

Implémentation : `com.mystaria.phantasmon_backend.pokemon.{PokemonController,PokemonService,PokemonLegalityService}`.

---

### `POST /trades`

- **Auth** : Bearer — l'initiateur est déduit du JWT, jamais du corps de la requête
- **Corps requête**

```json
{
  "request_uuid": "b1a4c2b0-1234-4d5e-8f90-abcdef123456",
  "recipient_uuid": "9f8e7d6c-5432-1abc-def0-123456789abc",
  "offered_pokemon_uuid": "...",
  "requested_pokemon_uuid": "..."
}
```

`request_uuid` obligatoire (idempotence via `common.IdempotencyService`, même mécanisme que
`POST /pokemon` — pas dans le brouillon OpenAPI mais requis par `CONTEXT_CURSOR_BACKEND.md`, qui prime
sur l'OpenAPI en cas de divergence).

- **Réponse 201 Created**

```json
{
  "uuid": "...",
  "initiator_uuid": "b1a4c2b0-...",
  "recipient_uuid": "9f8e7d6c-...",
  "offered_pokemon": "...",
  "requested_pokemon": "...",
  "status": "PENDING"
}
```

- **Réponse 403 Forbidden** — `ERROR_OWNERSHIP_MISMATCH` : le `offered_pokemon_uuid` n'appartient pas à l'appelant
- **Réponse 409 Conflict** — `ERROR_TRADE_INVALID_RECIPIENT_POKEMON` (le `requested_pokemon_uuid` n'appartient pas à `recipient_uuid`) ou `ERROR_TRADE_SELF` (`recipient_uuid` == l'appelant)

Effet de bord : notifie le destinataire en temps réel via WebSocket (`TradeProposed`) s'il est connecté.

### `POST /trades/{uuid}/accept`

- **Auth** : Bearer — seul le **destinataire** du trade peut l'accepter (403 `ERROR_OWNERSHIP_MISMATCH` sinon)
- **Réponse 200 OK** — `status: "COMPLETED"`, les deux `owner_uuid` échangés en une seule transaction
- **Réponse 409 Conflict** :
  - `ERROR_TRADE_INVALID_STATE` si le trade n'est plus `PENDING` (déjà annulé/complété)
  - `ERROR_TRADE_OWNERSHIP_CHANGED` si l'un des deux Pokémon a changé de propriétaire depuis la proposition — dans ce cas le trade passe à `CANCELLED` (persisté malgré l'échec, CAD Partie 3 §D.2) mais **aucun** Pokémon ne change de propriétaire

Effet de bord : ré-assigne chaque Pokémon échangé au premier emplacement PC libre de son nouveau
propriétaire (son ancien `box_id`/`box_slot` appartient à l'ancien propriétaire et entrerait en conflit) ;
notifie les deux joueurs via WebSocket (`TradeAccepted`).

### `POST /trades/{uuid}/cancel`

- **Auth** : Bearer — l'initiateur **ou** le destinataire peuvent annuler tant que `PENDING`
- **Réponse 200 OK** — `status: "CANCELLED"` ; notifie les deux joueurs via WebSocket (`TradeCancelled`)
- **Réponse 409 Conflict** — `ERROR_TRADE_INVALID_STATE` si déjà résolu

### `GET /trades/{uuid}`

- **Auth** : Bearer — seuls l'initiateur et le destinataire peuvent consulter (403 sinon)

### `GET /players/{uuid}/trades`

- **Auth** : Bearer — `{uuid}` doit être le joueur authentifié lui-même
- **Réponse 200 OK** : tableau de `Trade` (initiés **ou** reçus)

Implémentation : `com.mystaria.phantasmon_backend.trade.{TradeController,TradeService}`.

---

### `POST /battles`

- **Auth** : Bearer — l'initiateur est déduit du JWT, jamais du corps de la requête
- **Corps requête**

```json
{
  "request_uuid": "b1a4c2b0-1234-4d5e-8f90-abcdef123456",
  "opponent_uuid": "9f8e7d6c-5432-1abc-def0-123456789abc",
  "team": ["...", "..."]
}
```

`request_uuid` obligatoire (idempotence, même mécanisme que `POST /pokemon`/`POST /trades`).
`team` est **uniquement** l'équipe de l'appelant (ownership revérifiée en base). L'équipe de
l'adversaire n'est **jamais** prise dans la requête — le backend la dérive lui-même de son équipe
active actuelle (`pokemon.team_slot IS NOT NULL`, triée par `team_slot`), pour qu'aucun des deux
joueurs ne puisse dicter ce que l'autre combat.

- **Réponse 201 Created**

```json
{
  "uuid": "...",
  "player_a": "b1a4c2b0-...",
  "player_b": "9f8e7d6c-...",
  "team_a": ["..."],
  "team_b": ["..."],
  "status": "ACTIVE",
  "result": null,
  "created_at": "...",
  "finished_at": null
}
```

La session démarre directement `ACTIVE` (pas d'étape d'acceptation séparée côté API — le CAD Partie 2 §9.1
suppose que les deux joueurs ont déjà convenu du combat côté client avant cet appel).

- **Réponse 403 Forbidden** — `ERROR_OWNERSHIP_MISMATCH` : un `pokemon_uuid` de `team` n'appartient pas à l'appelant
- **Réponse 404 Not Found** — `ERROR_PLAYER_NOT_FOUND` (`opponent_uuid` inconnu) ou `ERROR_POKEMON_NOT_FOUND`
- **Réponse 409 Conflict** — `ERROR_BATTLE_SELF` (`opponent_uuid` == l'appelant) ou `ERROR_BATTLE_OPPONENT_NO_TEAM` (l'adversaire n'a aucun Pokémon avec `team_slot` défini)
- **Réponse 422 Unprocessable Content** — `ERROR_BATTLE_INVALID_TEAM` (équipe vide, > 6 Pokémon, ou doublons)

### `GET /battles/{uuid}`

- **Auth** : Bearer — seuls `player_a` et `player_b` peuvent consulter (403 `ERROR_OWNERSHIP_MISMATCH` sinon)

### `POST /battles/{uuid}/result`

- **Auth** : Bearer — seuls `player_a` et `player_b` peuvent soumettre un résultat
- **Corps requête**

```json
{ "winner_uuid": "b1a4c2b0-...", "log": { "turns": 3 } }
```

- **Réponse 200 OK** — `status: "FINISHED"`, `result: {"winner_uuid": ..., "log": {...}}`, `finished_at` renseigné
- **Réponse 409 Conflict** — `ERROR_BATTLE_INVALID_STATE` si la session n'est plus `ACTIVE` (déjà terminée) — protège aussi contre une double soumission
- **Réponse 422 Unprocessable Content** — `ERROR_BATTLE_INVALID_RESULT` si `winner_uuid` n'est ni `player_a` ni `player_b`

**Garde-fous V1 (CAD Partie 2 §9.2)** : seuls ces contrôles de cohérence globale sont faits ici —
le backend ne rejoue aucun calcul de combat (pas de moteur Cobblemon côté serveur, hors scope V1,
voir `PHANTASMON_BACKEND_CONVENTIONS` règle 7). L'arbitrage réel (client hôte, alternance d'hôte
entre combats successifs) arrive avec le client Phase 9.

Implémentation : `com.mystaria.phantasmon_backend.battle.{BattleController,BattleService}`.

---

## WebSocket

### Connexion

```
ws://<host>:<port>/ws?token=<access_token>
```

Le `access_token` est vérifié **au handshake** (avant même l'upgrade WebSocket) — un token
manquant/invalide/expiré/de type `refresh` fait échouer la connexion avec un `401` HTTP, le client ne
reçoit jamais de session WebSocket ouverte. Implémentation : `JwtHandshakeInterceptor`.

### Format des messages

Enveloppe générique, dans les deux sens :

```json
{ "type": "Heartbeat", "data": {} }
```

### C2S (client → serveur) implémentés

| Type | `data` | Effet |
|---|---|---|
| `JoinServerGroup` | `{ "server_fingerprint": "...", "dimension": "minecraft:overworld" }` | Enregistre la présence du joueur, le regroupe avec les autres joueurs partageant le même `server_fingerprint` + `dimension` |
| `LeaveServerGroup` | `{}` | Retire la présence du joueur |
| `PositionUpdate` | `{ "x": 1.0, "y": 2.0, "z": 3.0, "dimension": "minecraft:overworld" }` | Met à jour la position/dimension courante |
| `Heartbeat` | `{}` | Rafraîchit `last_heartbeat_at` ; déclenche un `HeartbeatAck` |

### S2C (serveur → client) implémentés

| Type | `data` | Quand |
|---|---|---|
| `HeartbeatAck` | `{}` | Réponse immédiate à un `Heartbeat` |
| `Error` | `{ "error_code": "...", "details": {} }` | Message malformé (`ERROR_WS_MALFORMED_MESSAGE`) ou type inconnu (`ERROR_WS_UNKNOWN_MESSAGE_TYPE`) |
| `TradeProposed` | `{ "trade_uuid": "...", "initiator_uuid": "...", "offered_pokemon": "...", "requested_pokemon": "..." }` | Envoyé au destinataire d'un `POST /trades` (best-effort, seulement s'il est connecté) |
| `TradeAccepted` | `{ "trade_uuid": "..." }` | Envoyé aux deux joueurs après un `POST /trades/{uuid}/accept` réussi |
| `TradeCancelled` | `{ "trade_uuid": "..." }` | Envoyé aux deux joueurs après un `POST /trades/{uuid}/cancel` |

### Présence — comportement

- **En mémoire uniquement** (`presence.PresenceService`, `ConcurrentHashMap`), jamais persistée en base, jamais partagée entre plusieurs instances (CAD Partie 3 §H, hors périmètre).
- **Déconnexion** (fermeture de la session, propre ou brutale) : la présence est retirée automatiquement (`afterConnectionClosed`), équivalent à un `LeaveServerGroup` implicite.
- **TTL** : un joueur qui n'envoie plus de `Heartbeat` pendant `phantasmon.presence.ttl` (30s par défaut) est considéré déconnecté brutalement — une tâche planifiée (`PresenceTtlSweeper`, toutes les `phantasmon.presence.sweep-interval-ms` = 10s par défaut) retire sa présence et force la fermeture de sa session si elle est encore techniquement ouverte.

### Pas encore implémenté

`SendOutGhost`, `RecallGhost`, `BattleAction` (C2S) et `GhostEntitySpawn`, `GhostEntityMove`,
`GhostEntityDespawn`, `BattleState`, `BattleEnded` (S2C) — arrivent avec les domaines Ghost Entity
(client Phase 7) et battle (Phase 4/9). Voir
`Documentation/CAD_Ghost_Pokemon_Partie_2_Architecture_Technique.md` §11 pour leur forme cible.

---

## Non implémenté (cible OpenAPI, pour suivi)

Endpoints décrits dans `phantasmon-backend-openapi.yaml` mais absents du code à ce jour : `GET/PUT /players/{uuid}/team`, `POST /pokemon/import-showdown`, `GET /pokemon/{uuid}/export`, `GET/POST /admin/*`. Ils seront documentés ici avec exemples concrets au fur et à mesure de leur implémentation (voir `Documentation/CAD_Phantasmon_Partie_4_Plan_Developpement.md` pour l'ordre des phases).
