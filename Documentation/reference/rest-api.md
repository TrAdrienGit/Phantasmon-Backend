# Référence de l'API REST

> **Document vivant** : décrit ce qui est **réellement implémenté**. Toute modification d'un endpoint met à jour
> ce fichier, [`openapi.yaml`](openapi.yaml) et, si besoin, [`error-codes.md`](error-codes.md) dans le même
> changement. Vérifié contre le code le 2026-10-03.
>
> Protocole temps réel : [`websocket-protocol.md`](websocket-protocol.md).

## Conventions

| Sujet | Règle |
|---|---|
| URL de base | `http://<hôte>:8080` (pas de préfixe de version) |
| Format | JSON, noms de champs en **snake_case** |
| Authentification | `Public` : aucun en-tête. `Bearer` : `Authorization: Bearer <access_token>`. Un jeton absent ou invalide sur une route `Bearer` est refusé par Spring Security (403 par défaut, sans `error_code`). Un `refresh_token` présenté comme `access_token` est refusé. |
| Propriétaire | Toujours déduit du JWT, jamais du corps de la requête. Sur `/players/{uuid}/…`, `{uuid}` doit être le joueur authentifié, sinon `403 ERROR_OWNERSHIP_MISMATCH`. |
| Erreurs métier | `{"error_code": "ERROR_…", "details": {…}}` (catalogue : [`error-codes.md`](error-codes.md)) |
| Erreurs de validation | Champ obligatoire manquant ou hors bornes : `400 ERROR_VALIDATION_FAILED` (`details.fields` = champs fautifs, en snake_case) ; corps JSON illisible, UUID de chemin invalide ou paramètre de requête manquant : `400 ERROR_MALFORMED_REQUEST` (`details.parameter` le cas échéant) |
| Idempotence | `POST /pokemon` et `POST /trades` exigent un `request_uuid` (UUID généré par le client). Rejouer la même requête (même joueur, même route) renvoie la réponse d'origine sans rien recréer ; le même `request_uuid` présenté par un autre joueur ou sur une autre route est refusé (`409 ERROR_IDEMPOTENCY_KEY_REUSED`). |

## Sommaire

| Méthode | Chemin | Auth | Rôle |
|---|---|---|---|
| `GET` | [`/health`](#get-health) | Public | Disponibilité du backend et de la base |
| `GET` | [`/version`](#get-version) | Public | Versions client courante et minimale |
| `POST` | [`/auth/challenge`](#post-authchallenge) | Public | Défi de connexion à usage unique |
| `POST` | [`/auth/session`](#post-authsession) | Public | Preuve Mojang → JWT |
| `POST` | [`/auth/refresh`](#post-authrefresh) | Public | Renouvellement des jetons |
| `POST` | [`/pokemon`](#post-pokemon) | Bearer | Créer un Pokémon |
| `GET` | [`/players/{uuid}/pokemon`](#get-playersuuidpokemon) | Bearer | Tous les Pokémon du joueur |
| `GET` | [`/players/{uuid}/pc?box={n}`](#get-playersuuidpcboxn) | Bearer | Une boîte du PC |
| `PATCH` | [`/pokemon/{uuid}`](#patch-pokemonuuid) | Bearer | Modifier ou déplacer |
| `DELETE` | [`/pokemon/{uuid}`](#delete-pokemonuuid) | Bearer | Supprimer |
| `POST` | [`/pokemon/{uuid}/clone`](#post-pokemonuuidclone) | Bearer | Cloner |
| `POST` | [`/trades`](#post-trades) | Bearer | Proposer un échange asynchrone |
| `POST` | [`/trades/{uuid}/accept`](#post-tradesuuidaccept) | Bearer | Accepter |
| `POST` | [`/trades/{uuid}/cancel`](#post-tradesuuidcancel) | Bearer | Annuler |
| `GET` | [`/trades/{uuid}`](#get-tradesuuid) | Bearer | Détail |
| `GET` | [`/players/{uuid}/trades`](#get-playersuuidtrades) | Bearer | Échanges du joueur |
| `GET` | [`/battles/{uuid}`](#get-battlesuuid) | Bearer | Détail |

---

## Santé et version

### `GET /health`

Exécute un `SELECT 1` sur PostgreSQL à chaque appel. Utilisé par le client avant l'auto-login.

```json
200 OK
{ "status": "UP", "database": "UP" }
```
```json
503 Service Unavailable
{ "status": "DOWN", "database": "DOWN" }
```

### `GET /version`

Appelé par le client **avant** l'authentification (CAD Partie 3 §E). Valeurs : `phantasmon.version.current` et
`phantasmon.version.min-supported`.

```json
200 OK
{ "current_version": "0.1.0", "min_supported_version": "0.1.0" }
```

Côté client : version < `min_supported_version` → refus avec lien de mise à jour ; version <
`current_version` → avertissement non bloquant.

---

## Authentification

### `POST /auth/challenge`

Première étape de la connexion. Sans corps, sans authentification.

```json
200 OK
{ "challenge": "3f9c2a7e5b1d4c8a9e0f6b2d7a4c1e8f" }
```

128 bits aléatoires (hexadécimal), valables `phantasmon.auth.challenge-ttl` (60 s), **à usage unique**. Gardés en
mémoire (10 000 au plus en même temps, au-delà `429 ERROR_AUTH_TOO_MANY_CHALLENGES`).

### `POST /auth/session`

Le client a d'abord appelé `joinServer` chez Mojang avec, comme `server_id`, un défi obtenu par
`POST /auth/challenge` ; le backend consomme le défi puis vérifie via `hasJoined`
(`https://sessionserver.mojang.com/session/minecraft/hasJoined`). En cas de succès, le joueur est créé ou mis à
jour (`last_username`, `last_seen_at`).

Pourquoi un défi (SEC-1, [`project/security-audit.md`](../project/security-audit.md)) : tout serveur Minecraft que
rejoint un joueur reçoit une preuve `hasJoined` pour **son** `server_id` ; sans défi émis par le backend, ce serveur
pourrait la rejouer ici et obtenir un jeton au nom du joueur.

```json
Requête
{
  "uuid": "b1a4c2b0-1234-4d5e-8f90-abcdef123456",
  "username": "Bichou",
  "server_id": "a1b2c3d4e5f6"
}
```
```json
200 OK
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires_in": 1200
}
```

`expires_in` est la durée de vie de l'`access_token` en secondes (`phantasmon.jwt.access-ttl`, 20 min par
défaut). Le `refresh_token` vit 7 jours (`phantasmon.jwt.refresh-ttl`).

| Statut | `error_code` | Cas |
|---|---|---|
| 401 | `ERROR_AUTH_INVALID_CHALLENGE` | `server_id` qui n'est pas un défi émis par le backend, expiré ou déjà utilisé (Mojang n'est pas interrogé) |
| 401 | `ERROR_AUTH_MOJANG_VERIFICATION_FAILED` | Mojang ne confirme pas la session (`details.username`) |
| 401 | `ERROR_AUTH_UUID_MISMATCH` | L'UUID annoncé diffère de celui confirmé par Mojang (`details.claimed_uuid`, `details.verified_uuid`) |
| 400 | `ERROR_VALIDATION_FAILED` | `uuid` manquant, `username` ou `server_id` vide |

### `POST /auth/refresh`

Le `refresh_token` sert lui-même de preuve. Ne recontacte pas Mojang ; relit le joueur en base et met à jour
`last_seen_at`. Les deux jetons sont renouvelés (l'ancien refresh token reste toutefois valide jusqu'à son
expiration, voir décision D-16).

```json
Requête
{ "refresh_token": "eyJhbGciOiJIUzI1NiJ9..." }
```

Réponse `200` : même forme que `POST /auth/session`.

| Statut | `error_code` | Cas |
|---|---|---|
| 401 | `ERROR_AUTH_INVALID_REFRESH_TOKEN` | Jeton invalide, expiré, de type `access`, ou joueur introuvable |

---

## Pokémon

### Objet `Pokemon`

```json
{
  "uuid": "9f8e7d6c-5432-1abc-def0-123456789abc",
  "owner_uuid": "b1a4c2b0-1234-4d5e-8f90-abcdef123456",
  "species": "samurott",
  "form": "hisui",
  "level": 100,
  "nature": "timid",
  "ability": "torrent",
  "is_shiny": true,
  "box_id": 1,
  "box_slot": 1,
  "team_slot": null,
  "cobblemon_data_version": "1.8.1",
  "data": {
    "nickname": "Bichou",
    "gender": "M",
    "tera_type": "grass",
    "ivs": { "hp": 31, "atk": 31, "def": 31, "spa": 31, "spd": 31, "spe": 31 },
    "evs": { "hp": 0, "atk": 144, "def": 64, "spa": 0, "spd": 136, "spe": 0 },
    "moves": ["avalanche", "aquatail", "bodyslam", "darkpulse"],
    "held_item": "assault_vest"
  }
}
```

- Un Pokémon a **soit** `box_id` + `box_slot` (PC : boîtes 1-16, cases 1-30), **soit** `team_slot` (1-6).
- `data` est un JSONB libre. Clés utilisées par le client : `nickname`, `gender` (`"M"`/`"F"`, absente =
  aléatoire), `tera_type`, `ivs`, `evs`, `moves`, `held_item`, `friendship`. Seuls `ivs` et `evs` sont validés par le
  backend. **Attention** : ces clés sont en camelCase (ce sont des clés de `Map`, que la stratégie snake_case de
  Jackson ne renomme pas), contrairement à tous les autres champs de l'API.
- Les identifiants suivent les conventions de Cobblemon (voir `Phantasmon-Client/Documentation/architecture/showdown-import.md`).

### `POST /pokemon`

```json
Requête
{
  "request_uuid": "0f3c8d1e-6a2b-4c5d-9e8f-112233445566",
  "species": "pikachu",
  "form": null,
  "level": 50,
  "nature": "timid",
  "ability": "static",
  "is_shiny": false,
  "cobblemon_data_version": "1.8.1",
  "data": { "ivs": { "...": 31 }, "evs": { "...": 0 }, "moves": ["thunderbolt"] }
}
```

| Champ | Obligatoire | Contraintes |
|---|---|---|
| `request_uuid` | oui | UUID |
| `species`, `nature`, `ability`, `cobblemon_data_version` | oui | non vides ; 64 caractères au plus (`nature`, `cobblemon_data_version` : 32) |
| `level` | oui | 1 à 100 |
| `data` | oui | objet de 16 Kio au plus une fois sérialisé ; `data.nickname` : texte de 20 caractères au plus ; `ivs` / `evs` : objets de nombres |
| `form`, `is_shiny` | non | `form` : 64 caractères au plus ; `is_shiny` faux par défaut |
| `box_id` + `box_slot` | non | 1-16 et 1-30 |
| `team_slot` | non | 1-6 |

Sans emplacement fourni, le Pokémon va dans la **première case libre du PC**.

| Statut | `error_code` | Cas |
|---|---|---|
| 201 | — | Créé, renvoie un `Pokemon` |
| 422 | `ERROR_LEGALITY_IV_OUT_OF_RANGE`, `ERROR_LEGALITY_EV_OUT_OF_RANGE`, `ERROR_LEGALITY_EV_TOTAL_EXCEEDED` | IV ∉ [0, 31], EV ∉ [0, 252] ou total EV > 510 |
| 422 | `ERROR_LEGALITY_NICKNAME_TOO_LONG`, `ERROR_LEGALITY_INVALID_DATA`, `ERROR_POKEMON_DATA_TOO_LARGE` | Surnom > 20 caractères ; `ivs` / `evs` / `nickname` mal typés (`details.field`) ; `data` > 16 Kio (mêmes règles pour `PATCH`) |
| 400 | `ERROR_VALIDATION_FAILED` | Champ obligatoire manquant ou chaîne trop longue |
| 409 | `ERROR_IDEMPOTENCY_KEY_REUSED` | `request_uuid` déjà utilisé par un autre joueur ou une autre route |
| 409 | `ERROR_POKEMON_SLOT_OCCUPIED` | L'emplacement demandé est pris |
| 409 | `ERROR_POKEMON_PC_FULL` | Les 480 cases du PC sont occupées |

### `GET /players/{uuid}/pokemon`

Tous les Pokémon du joueur (PC et équipe), tableau de `Pokemon`. Le client en déduit l'équipe (`team_slot` non nul).

### `GET /players/{uuid}/pc?box={n}`

Contenu de la boîte `n` (1 à 16), tableau de `Pokemon`. Le paramètre `box` est obligatoire.

### `PATCH /pokemon/{uuid}`

Mise à jour partielle : seuls les champs présents sont appliqués.

| Champ | Effet |
|---|---|
| `level` | 1 à 100 |
| `nature`, `ability`, `is_shiny` | Remplacés tels quels |
| `cobblemon_data_version` | Version de Cobblemon du client qui modifie (32 caractères au plus) ; conservée si absente. Le client l'envoie à chaque édition complète (CAD Partie 2 §6.1 : version de création **ou de dernière modification**) |
| `data` | **Remplace tout** le JSONB (pas de fusion), puis contrôle de légalité IV/EV |
| `team_slot` | Déplace vers cet emplacement d'équipe |
| `box_id` + `box_slot` | Déplace vers cette case du PC (les deux ensemble) |

Règle de déplacement : une destination vide → déplacement ; une destination occupée par un autre Pokémon du
joueur → **les deux échangent leur place**, quelle que soit la combinaison PC/équipe. Déplacer un Pokémon vers sa
propre place ne fait rien. Une destination d'équipe efface la place PC et inversement.

```json
{ "team_slot": 2 }
{ "box_id": 3, "box_slot": 12 }
{ "nature": "jolly", "ability": "intimidate", "is_shiny": true }
```

| Statut | `error_code` | Cas |
|---|---|---|
| 200 | — | `Pokemon` mis à jour |
| 403 | `ERROR_OWNERSHIP_MISMATCH` | Le Pokémon appartient à un autre joueur |
| 404 | `ERROR_POKEMON_NOT_FOUND` | Pokémon inexistant |
| 422 | `ERROR_POKEMON_INCOMPLETE_BOX_DESTINATION` | Un seul de `box_id`/`box_slot` fourni |
| 422 | `ERROR_LEGALITY_*` | `data` illégal |
| 409 | `ERROR_POKEMON_SLOT_OCCUPIED` | Conflit résiduel sur un index unique |

### `DELETE /pokemon/{uuid}`

Suppression définitive (pas de corbeille). `204 No Content`.

| Statut | `error_code` | Cas |
|---|---|---|
| 403 / 404 | `ERROR_OWNERSHIP_MISMATCH` / `ERROR_POKEMON_NOT_FOUND` | Comme `PATCH` |
| 409 | `ERROR_POKEMON_IN_PENDING_TRADE` | Engagé dans un échange `PENDING` (offert ou demandé) : l'annuler d'abord |

### `POST /pokemon/{uuid}/clone`

Copie avec un nouvel UUID, placée dans la première case libre du PC. `201` + `Pokemon`. Erreurs : comme `PATCH`,
plus `409 ERROR_POKEMON_PC_FULL`.

---

## Échanges asynchrones

> Le mode principal est l'**échange en direct** par WebSocket ([`websocket-protocol.md`](websocket-protocol.md#4-échange-en-direct)).
> Un échange en direct terminé crée aussi une ligne `COMPLETED`, visible par les deux `GET` ci-dessous.

### Objet `Trade`

```json
{
  "uuid": "…",
  "initiator_uuid": "…",
  "recipient_uuid": "…",
  "offered_pokemon": "…",
  "requested_pokemon": "…",
  "status": "PENDING"
}
```

`status` ∈ `PENDING`, `ACCEPTED`, `CANCELLED`, `COMPLETED` (`ACCEPTED` est prévu par le schéma mais jamais
produit : une acceptation réussie donne directement `COMPLETED`).

### `POST /trades`

```json
{
  "request_uuid": "…",
  "recipient_uuid": "…",
  "offered_pokemon_uuid": "…",
  "requested_pokemon_uuid": "…"
}
```

`201` + `Trade` `PENDING`. Notifie le destinataire par WebSocket (`TradeProposed`) s'il est connecté.

| Statut | `error_code` | Cas |
|---|---|---|
| 403 | `ERROR_OWNERSHIP_MISMATCH` | `offered_pokemon_uuid` n'appartient pas à l'appelant |
| 404 | `ERROR_POKEMON_NOT_FOUND` | Un des Pokémon n'existe pas |
| 409 | `ERROR_TRADE_INVALID_RECIPIENT_POKEMON` | `requested_pokemon_uuid` n'appartient pas au destinataire |
| 409 | `ERROR_TRADE_SELF` | Destinataire = appelant |

### `POST /trades/{uuid}/accept`

Réservé au **destinataire**. Une transaction : propriétaires échangés, chaque Pokémon placé dans la première case
libre du PC de son nouveau propriétaire, statut `COMPLETED`. Notifie les deux joueurs (`TradeAccepted`).

| Statut | `error_code` | Cas |
|---|---|---|
| 403 | `ERROR_OWNERSHIP_MISMATCH` | L'appelant n'est pas le destinataire |
| 404 | `ERROR_TRADE_NOT_FOUND` | Échange inexistant |
| 409 | `ERROR_TRADE_INVALID_STATE` | L'échange n'est plus `PENDING` |
| 409 | `ERROR_TRADE_OWNERSHIP_CHANGED` | Un des Pokémon a changé de propriétaire : l'échange passe `CANCELLED`, aucun transfert |
| 409 | `ERROR_POKEMON_PC_FULL` | Le PC d'un des deux joueurs est plein (vérifié avant tout transfert) : aucun transfert, l'échange reste `PENDING` |

### `POST /trades/{uuid}/cancel`

Initiateur ou destinataire, tant que `PENDING`. `200` + `Trade` `CANCELLED`, notification `TradeCancelled` aux
deux. Erreurs : `403`, `404`, `409 ERROR_TRADE_INVALID_STATE`.

### `GET /trades/{uuid}`

Réservé aux deux participants (`403` sinon, `404 ERROR_TRADE_NOT_FOUND`).

### `GET /players/{uuid}/trades`

Échanges initiés ou reçus par le joueur, tous statuts confondus.

---

## Sessions de combat (REST)

> Les sessions de combat sont créées, relayées et conclues **uniquement** par le combat en direct (WebSocket,
> [`websocket-protocol.md`](websocket-protocol.md#5-combat-en-direct)), qui choisit l'hôte et applique les
> garde-fous. Les anciennes routes `POST /battles` et `POST /battles/{uuid}/result` ont été **retirées** le
> 2026-10-04 (SEC-3) : elles permettaient d'ouvrir un combat contre n'importe qui sans son accord et de déclarer le
> vainqueur depuis n'importe quel participant, y compris l'invité d'un combat en direct.

### Objet `BattleSession`

```json
{
  "uuid": "…",
  "player_a": "…",
  "player_b": "…",
  "team_a": ["…"],
  "team_b": ["…"],
  "status": "ACTIVE",
  "result": null,
  "created_at": "2026-10-03T12:00:00Z",
  "finished_at": null
}
```

`status` ∈ `PENDING`, `ACTIVE`, `FINISHED`, `ABORTED`. `host_uuid` (colonne V8) n'est pas exposé.

### `GET /battles/{uuid}`

Réservé aux deux joueurs (`403`), `404 ERROR_BATTLE_NOT_FOUND`.

---

## Prévu par le CAD, non implémenté

`GET/PUT /players/{uuid}/team`, `POST /pokemon/import-showdown`, `GET /pokemon/{uuid}/export`, routes `/admin/*`.
Raisons : décisions D-09 et D-20 ([`architecture/decisions.md`](../architecture/decisions.md)).
