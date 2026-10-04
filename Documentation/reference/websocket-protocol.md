# Protocole WebSocket

> **Document vivant** : décrit les messages **réellement implémentés**. Tout message ajouté, modifié ou retiré
> met à jour ce fichier dans le même changement. Vérifié contre le code le 2026-10-03.
>
> Implémentation : `websocket.PhantasmonWebSocketHandler` (dispatch), `presence.PresenceService`,
> `trade.LiveTradeService`, `battle.LiveBattleService`. Côté client : `ghost.GhostSession`.

## 1. Connexion

```text
ws://<hôte>:8080/ws?token=<access_token>
```

- Le jeton est vérifié **au handshake** (`JwtHandshakeInterceptor`) : absent, invalide, expiré ou de type
  `refresh` → réponse HTTP `401`, aucune session ouverte.
- Un client = une connexion, partagée par la présence, les Ghost, les échanges et les combats. Une **seule
  connexion par joueur** : une nouvelle connexion ferme la précédente, sans toucher à la présence, à l'échange
  ni au combat en cours (SEC-8).
- Taille maximale d'un message : **1 Mio**.
- Débit : 40 messages par seconde en continu, rafales jusqu'à 200 ; au-delà les messages sont ignorés et une erreur
  `ERROR_WS_RATE_LIMITED` est envoyée une fois par série (SEC-5).

### Enveloppe

Tous les messages, dans les deux sens :

```json
{ "type": "PositionUpdate", "data": { "x": 1.0, "y": 64.0, "z": -3.5, "dimension": "minecraft:overworld" } }
```

Les UUID sont des chaînes. Les noms de champs de `data` sont en snake_case.

### Erreurs

| Message | Usage |
|---|---|
| `Error` `{ "error_code", "details" }` | Erreurs génériques : `ERROR_WS_MALFORMED_MESSAGE` (JSON illisible ou champ manquant), `ERROR_WS_UNKNOWN_MESSAGE_TYPE`, et refus de `SendOutGhost` |
| `TradeSessionError` `{ "error_code" }` | Refus d'une action d'échange en direct (la session continue) |
| `BattleSessionError` `{ "error_code" }` | Refus d'une action de combat en direct |

Catalogue complet : [`error-codes.md`](error-codes.md).

## 2. Présence

| C2S | `data` | Effet |
|---|---|---|
| `JoinServerGroup` | `{ "server_fingerprint": "…", "dimension": "minecraft:overworld" }` | Enregistre la présence ; groupe = même empreinte **et** même dimension. Le joueur reçoit aussitôt un `GhostEntitySpawn` pour chaque Ghost déjà sorti dans son groupe. Empreinte et dimension obligatoires, non vides, 128 caractères au plus, sinon `ERROR_WS_MALFORMED_MESSAGE` (SEC-4). |
| `PositionUpdate` | `{ "x", "y", "z", "dimension" }` | Met à jour position et dimension (dimension obligatoire, mêmes règles) ; si le joueur a un Ghost sorti, diffuse `GhostEntityMove`. Envoyé chaque seconde par le client. |
| `Heartbeat` | `{}` | Rafraîchit `last_heartbeat_at` ; répond `HeartbeatAck`. Envoyé chaque seconde par le client. |
| `LeaveServerGroup` | `{}` | Retire la présence (et le Ghost éventuel) |

| S2C | `data` | Quand |
|---|---|---|
| `HeartbeatAck` | `{}` | Réponse à `Heartbeat` |

**Empreinte de serveur** (calculée par le client) : `"singleplayer"` dans un monde local, sinon SHA-256 (hexadécimal)
de l'adresse saisie pour rejoindre le serveur.

**TTL** : sans `Heartbeat` depuis `phantasmon.presence.ttl` (30 s), la présence est retirée par un balayage toutes
les `phantasmon.presence.sweep-interval-ms` (10 s) et la session est fermée. Toute fermeture de session (propre,
brutale ou TTL) équivaut à un `LeaveServerGroup`, annule l'échange en direct et termine le combat en cours. Après une expiration TTL, le Ghost du joueur est retiré chez les autres
(`GhostEntityDespawn`).

## 3. Ghost

> Les positions (`GhostEntitySpawn` / `GhostEntityMove`) sont reçues par **tout** membre du groupe, et n'importe quel
> joueur authentifié peut rejoindre le groupe d'un serveur dont il connaît l'adresse : limite assumée (LIM-9, D-21).

| C2S | `data` | Effet |
|---|---|---|
| `SendOutGhost` | `{ "pokemon_uuid": "…" }` | Vérifie en base que le Pokémon existe (`ERROR_POKEMON_NOT_FOUND`), appartient au joueur (`ERROR_OWNERSHIP_MISMATCH`) et est dans son **équipe active** (`ERROR_POKEMON_NOT_IN_TEAM`), et que le joueur n'est pas en combat Ghost (`ERROR_GHOST_IN_BATTLE`). Puis l'enregistre comme Ghost actif et diffuse `GhostEntitySpawn`. Refus via `Error`. |
| `RecallGhost` | `{}` | Efface le Ghost actif (sans effet s'il n'y en a pas) et diffuse `GhostEntityDespawn` |

| S2C | `data` | Destinataires |
|---|---|---|
| `GhostEntitySpawn` | `{ "player_uuid", "pokemon_uuid", "species", "form" \| null, "is_shiny", "level", "gender": "M" \| "F" \| null, "nickname" \| null, "position": {"x","y","z"} \| null }` | Groupe **et** propriétaire. Également envoyé en rattrapage à un joueur qui rejoint le groupe. |
| `GhostEntityMove` | `{ "player_uuid", "pokemon_uuid", "position": {"x","y","z"} }` | Groupe et propriétaire, à chaque `PositionUpdate` du propriétaire ayant un Ghost sorti |
| `GhostEntityDespawn` | `{ "player_uuid", "pokemon_uuid" }` | Groupe et propriétaire : rappel, déconnexion du propriétaire, Ghost échangé en direct, ou début d'un combat Ghost (Ghost des deux joueurs) |

`GhostEntitySpawn` embarque les données de rendu (`species`, `form`, `is_shiny`, `level`, `gender` tel que stocké
dans `data.gender`, `nickname` tel que stocké dans `data.nickname`, pour l'indicateur `[Ghost]`) car un client ne peut pas lire les Pokémon d'un autre joueur par REST. `position` vaut `null`
si le propriétaire n'a encore envoyé aucun `PositionUpdate`.

## 4. Échange en direct

Négociation en mémoire (`LiveTradeService`) ; seule l'exécution finale est écrite en base. Un joueur ne participe
qu'à une session à la fois. Seuls les Pokémon de l'**équipe active** sont échangeables.

### C2S

| Type | `data` | Effet et refus (`TradeSessionError`) |
|---|---|---|
| `TradeInvite` | `{ "target_uuid" }` | Invite un joueur (remplace une invitation précédente vers la même cible). Refus : `ERROR_TRADE_SELF`, `ERROR_TRADE_ALREADY_IN_SESSION`, `ERROR_TRADE_PARTNER_UNAVAILABLE` (cible sans session WebSocket), `ERROR_TRADE_PARTNER_BUSY`. |
| `TradeInviteResponse` | `{ "invite_uuid", "accept": true }` | Accepte → `TradeSessionStarted` aux deux ; refuse → `TradeInviteDeclined` à l'inviteur. Invitation inconnue, destinée à un autre ou plus vieille que `phantasmon.trade.invite-ttl` (60 s) : `ERROR_TRADE_INVITE_NOT_FOUND`. |
| `TradeSelectOffer` | `{ "pokemon_uuid" }` | Choisit son offre, revérifiée en base : `ERROR_POKEMON_NOT_FOUND`, `ERROR_OWNERSHIP_MISMATCH`, `ERROR_TRADE_OFFER_NOT_IN_TEAM`. **Tout changement d'offre remet « prêt » à faux pour les deux joueurs.** |
| `TradeSetReady` | `{ "ready": true }` | Se déclare prêt ou retire son accord. `ERROR_TRADE_OFFERS_INCOMPLETE` si une offre manque. Les deux prêts → exécution immédiate. |
| `TradeLeave` | `{}` | Quitte : session annulée, le partenaire reçoit `TradeSessionCancelled` (`PARTNER_LEFT`). |

`TradeSelectOffer` / `TradeSetReady` hors session : `ERROR_TRADE_NOT_IN_SESSION`.

### S2C

| Type | `data` | Destinataire |
|---|---|---|
| `TradeInviteReceived` | `{ "invite_uuid", "from_uuid", "from_name" }` | Invité |
| `TradeInviteSent` | `{ "invite_uuid", "to_uuid", "to_name" }` | Inviteur (confirmation) |
| `TradeInviteDeclined` | `{ "invite_uuid", "by_uuid", "by_name" }` | Inviteur |
| `TradeSessionStarted` | `{ "session_uuid", "partner_uuid", "partner_name", "own_team": [Pokemon…], "partner_team": [Pokemon…] }` | Chacun, de son point de vue. Équipes = objets `Pokemon` complets (schéma REST) de l'équipe active. |
| `TradeSessionUpdate` | `{ "session_uuid", "own_offer" \| null, "partner_offer" \| null, "own_ready", "partner_ready" }` | Chacun, après chaque offre ou changement d'état « prêt » accepté |
| `TradeSessionCompleted` | `{ "session_uuid", "trade_uuid", "given_pokemon", "received_pokemon" }` | Les deux. `trade_uuid` = ligne `trades` `COMPLETED`. |
| `TradeSessionCancelled` | `{ "session_uuid", "reason" }` | `PARTNER_LEFT` / `PARTNER_DISCONNECTED` au partenaire ; aux deux si l'exécution échoue (`reason` = code d'erreur, ex. `ERROR_TRADE_OWNERSHIP_CHANGED`, `ERROR_TRADE_OFFER_NOT_IN_TEAM`) |
| `TradeSessionError` | `{ "error_code" }` | Auteur de l'action refusée |

### Exécution

Une transaction (`TradeService.completeLiveTrade`) : propriété et appartenance à l'équipe revérifiées ; chaque
Pokémon change de propriétaire en prenant **l'emplacement d'équipe exact** que l'autre libère ; ligne `trades`
`COMPLETED` ; un Ghost échangé est rappelé (`GhostEntityDespawn` à son ancien groupe et à son ancien propriétaire).

```text
Alice -> TradeInvite {target_uuid: Bob}       Bob <- TradeInviteReceived ; Alice <- TradeInviteSent
Bob   -> TradeInviteResponse {accept: true}   Alice, Bob <- TradeSessionStarted
Alice -> TradeSelectOffer {pokemon_uuid: A}   Alice, Bob <- TradeSessionUpdate
Bob   -> TradeSelectOffer {pokemon_uuid: B}   Alice, Bob <- TradeSessionUpdate
Alice -> TradeSetReady {ready: true}          Alice, Bob <- TradeSessionUpdate
Bob   -> TradeSetReady {ready: true}          Alice, Bob <- TradeSessionUpdate puis TradeSessionCompleted
```

## 5. Combat en direct

Le client **hôte** exécute le moteur de combat de Cobblemon ; le backend ne simule rien. Il gère l'invitation,
désigne l'hôte, relaie, gère le chrono et enregistre le résultat dans `battle_sessions` (ligne `ACTIVE` au
démarrage, `player_a` = `host_uuid` = hôte).

**Choix de l'hôte** : l'inviteur pour le tout premier combat d'une paire de joueurs, puis alternance (celui qui
n'a pas hébergé le combat précédent de cette paire).

### C2S

| Type | `data` | Effet et refus (`BattleSessionError`) |
|---|---|---|
| `BattleInvite` | `{ "target_uuid" }` | Invite. Refus : `ERROR_BATTLE_SELF`, `ERROR_BATTLE_ALREADY_IN_BATTLE`, `ERROR_BATTLE_PARTNER_UNAVAILABLE`, `ERROR_BATTLE_PARTNER_BUSY`. |
| `BattleInviteResponse` | `{ "invite_uuid", "accept": true }` | Accepte → `BattleSessionStarted` aux deux ; refuse → `BattleInviteDeclined`. Invitation inconnue ou expirée (`phantasmon.battle.invite-ttl`, 60 s) : `ERROR_BATTLE_INVITE_NOT_FOUND`. Équipe vide d'un côté : `ERROR_BATTLE_EMPTY_TEAM` aux deux. |
| `BattlePacket` | `{ "battle_uuid", "id": "cobblemon:…", "payload": "<base64>" }` | **Hôte uniquement** (`ERROR_BATTLE_NOT_HOST`). Paquet S2C Cobblemon encodé par son propre codec, relayé tel quel à l'invité. L'identifiant `phantasmon:action_effect` transporte une animation d'attaque. |
| `BattleChoice` | `{ "battle_uuid", "id": "cobblemon:battle_select_actions", "payload": "<base64>" }` | **Invité uniquement** (`ERROR_BATTLE_NOT_GUEST`). Choix de l'invité, relayé tel quel à l'hôte. |
| `BattleTimerEnable` | `{ "battle_uuid" }` | Active le chrono (90 s par tour) pour les deux, une seule fois, sans retour possible. Ignoré s'il est déjà actif. L'hôte applique l'action automatique à expiration. |
| `BattleResult` | `{ "battle_uuid", "winner_uuid" \| null }` | **Hôte uniquement**. Vainqueur parmi les deux joueurs, ou `null` pour un nul (`ERROR_BATTLE_INVALID_RESULT` sinon). Session `FINISHED`. |
| `BattleLeave` | `{ "battle_uuid" }` | Abandon : l'autre joueur gagne (`reason` = `FORFEIT`). |

Action de combat hors combat ou avec un mauvais `battle_uuid` : `ERROR_BATTLE_NOT_IN_BATTLE`.

### S2C

| Type | `data` | Destinataire |
|---|---|---|
| `BattleInviteReceived` | `{ "invite_uuid", "from_uuid", "from_name" }` | Invité |
| `BattleInviteSent` | `{ "invite_uuid", "to_uuid", "to_name" }` | Inviteur |
| `BattleInviteDeclined` | `{ "invite_uuid", "by_uuid", "by_name" }` | Inviteur |
| `BattleSessionStarted` | `{ "battle_uuid", "role": "HOST" \| "GUEST", "opponent_uuid", "opponent_name", "own_team": [Pokemon…], "opponent_team": [Pokemon…] }` | Les deux. `opponent_team` n'est envoyé **qu'à l'hôte**. Suivi d'un `GhostEntityDespawn` pour chaque joueur qui avait un Ghost sorti ; jusqu'à la fin du combat, `SendOutGhost` répond `ERROR_GHOST_IN_BATTLE`. |
| `BattlePacket` | comme en C2S | Invité |
| `BattleChoice` | comme en C2S | Hôte |
| `BattleTimerEnabled` | `{ "battle_uuid", "by_uuid", "by_name", "seconds": 90 }` | Les deux |
| `BattleEnded` | `{ "battle_uuid", "winner_uuid" \| null, "reason": "FINISHED" \| "FORFEIT" \| "PARTNER_DISCONNECTED" \| "BACKEND_LOST" }` | Les deux ; aussi stocké dans `battle_sessions.result` |
| `BattleSessionError` | `{ "error_code" }` | Auteur de l'action refusée |

Une déconnexion pendant un combat le termine en `ABORTED`, sans vainqueur (`PARTNER_DISCONNECTED`). Si le backend
s'arrête, chaque combat en cours devient un **nul** (`FINISHED`, sans vainqueur, `BACKEND_LOST`) annoncé aux deux
joueurs avant la fermeture des connexions ; s'il plante, les sessions restées `ACTIVE` sont conclues de la même façon
à son redémarrage (CAD Partie 1 §44). Un client qui perd la connexion termine lui-même le combat et l'annonce nul.

## 6. Échanges asynchrones (notifications)

Envoyés par `TradeService` après les appels REST, uniquement aux joueurs connectés :

| S2C | `data` | Destinataire |
|---|---|---|
| `TradeProposed` | `{ "trade_uuid", "initiator_uuid", "offered_pokemon", "requested_pokemon" }` | Destinataire de `POST /trades` |
| `TradeAccepted` | `{ "trade_uuid" }` | Les deux, après `POST /trades/{uuid}/accept` |
| `TradeCancelled` | `{ "trade_uuid" }` | Les deux, après `POST /trades/{uuid}/cancel` |

## 7. Écarts avec le CAD

`BattleAction` (C2S) et `BattleState` (S2C) prévus par le CAD (Partie 2 §11) n'existent pas : ils sont remplacés
par le relais des paquets Cobblemon (`BattlePacket` / `BattleChoice`). Voir la décision D-05.
