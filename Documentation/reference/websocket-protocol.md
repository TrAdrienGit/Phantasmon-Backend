# Protocole WebSocket

> **Document vivant** : décrit les messages **réellement implémentés**. Tout message ajouté, modifié ou retiré
> met à jour ce fichier dans le même changement. Vérifié contre le code le 2026-10-03.
>
> Implémentation : `websocket.PhantasmonWebSocketHandler` (dispatch), `presence.PresenceService`,
> `trade.LiveTradeService`, `battle.LiveBattleService`, `hub.HubService`. Côté client : `ghost.GhostSession`.

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
| `Error` `{ "error_code", "details" }` | Erreurs génériques : `ERROR_WS_MALFORMED_MESSAGE` (JSON illisible ou champ manquant), `ERROR_WS_UNKNOWN_MESSAGE_TYPE`, refus de `SendOutGhost` et des messages `Hub*` |
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
| `BattleInvite` | `{ "target_uuid", "team"?: "GHOST" \| "COBBLEMON", "party"?: [Pokemon…] }` | Invite. `team` absent = `GHOST` (équipe active de Ghost) ; `COBBLEMON` = combat avec une **copie** de l'équipe Cobblemon réelle, envoyée dans `party` (voir ci-dessous). Refus : `ERROR_BATTLE_SELF`, `ERROR_BATTLE_ALREADY_IN_BATTLE`, `ERROR_BATTLE_PARTNER_UNAVAILABLE`, `ERROR_BATTLE_PARTNER_BUSY`, `ERROR_BATTLE_INVALID_PARTY`. |
| `BattleInviteResponse` | `{ "invite_uuid", "accept": true, "team"?, "party"? }` | Accepte → ouvre le **lobby** : `BattleLobbyUpdated` aux deux, puis `GhostEntityDespawn` pour chaque Ghost sorti (ils restent rappelés jusqu'à la fin du combat). `team` / `party` facultatifs = choix d'équipe initial, modifiable dans le lobby. Refuse → `BattleInviteDeclined`. Équipe Cobblemon invalide : `ERROR_BATTLE_INVALID_PARTY`, l'invitation reste valable. Invitation inconnue ou expirée (`phantasmon.battle.invite-ttl`, 60 s) : `ERROR_BATTLE_INVITE_NOT_FOUND`. |
| `BattleLobbySetTeam` | `{ "lobby_uuid", "team": "GHOST" \| "COBBLEMON", "party"? }` | Change d'équipe (mêmes règles que l'invitation) ; le lead revient au premier Pokémon et l'adversaire n'est **plus prêt** (ce qu'il a vu a changé). Refus : `ERROR_BATTLE_LOBBY_LOCKED` (joueur prêt), `ERROR_BATTLE_INVALID_PARTY`, `ERROR_BATTLE_LOBBY_NOT_FOUND`. |
| `BattleLobbySetLead` | `{ "lobby_uuid", "index": 0-5 }` | Choisit le lead (index dans `own_team`). Seul l'auteur reçoit un `BattleLobbyUpdated` : l'adversaire n'apprend ni le lead ni qu'il a changé. Refus : `ERROR_BATTLE_LOBBY_LOCKED`, `ERROR_WS_MALFORMED_MESSAGE` (index hors équipe). |
| `BattleLobbySetReady` | `{ "lobby_uuid", "ready": bool }` | Prêt (équipe et lead verrouillés) ou plus prêt. Les deux prêts → le combat démarre (`BattleSessionStarted`). Équipe vide : `ERROR_BATTLE_EMPTY_TEAM`. |
| `BattleLobbySetFormat` | `{ "lobby_uuid", "format_id" }` | Format du combat, pour les deux (TODO-24) : `free` ou un des 19 formats Smogon proposés (ids Showdown, ex. `gen9nationaldex`, `gen9ou`). Les deux joueurs ne sont plus prêts. Refus : `ERROR_BATTLE_UNKNOWN_FORMAT`. |
| `BattleLobbyTimerEnable` | `{ "lobby_uuid" }` | Active le timer du lobby (`phantasmon.battle.lobby-timer`, 150 s) pour les deux, une fois. À expiration, qui n'est pas prêt prend son **premier** Pokémon comme lead et le combat démarre ; le chrono de combat (90 s) est alors activé d'office. |
| `BattleLobbyLeave` | `{ "lobby_uuid" }` | Quitte le lobby : `BattleLobbyCancelled` (`LEFT`) aux deux. |
| `BattlePacket` | `{ "battle_uuid", "id": "cobblemon:…", "payload": "<base64>" }` | **Hôte uniquement** (`ERROR_BATTLE_NOT_HOST`). Paquet S2C Cobblemon encodé par son propre codec, relayé tel quel à l'invité. L'identifiant `phantasmon:action_effect` transporte une animation d'attaque. |
| `BattleChoice` | `{ "battle_uuid", "id": "cobblemon:battle_select_actions", "payload": "<base64>" }` | **Invité uniquement** (`ERROR_BATTLE_NOT_GUEST`). Choix de l'invité, relayé tel quel à l'hôte. |
| `BattleTimerEnable` | `{ "battle_uuid" }` | Active le chrono (90 s par tour) pour les deux, une seule fois, sans retour possible. Ignoré s'il est déjà actif. L'hôte applique l'action automatique à expiration. |
| `BattleResult` | `{ "battle_uuid", "winner_uuid" \| null }` | **Hôte uniquement**. Vainqueur parmi les deux joueurs, ou `null` pour un nul (`ERROR_BATTLE_INVALID_RESULT` sinon). Session `FINISHED`. |
| `BattleLeave` | `{ "battle_uuid" }` | Abandon : l'autre joueur gagne (`reason` = `FORFEIT`). |

Action de combat hors combat ou avec un mauvais `battle_uuid` : `ERROR_BATTLE_NOT_IN_BATTLE`.

### S2C

| Type | `data` | Destinataire |
|---|---|---|
| `BattleInviteReceived` | `{ "invite_uuid", "from_uuid", "from_name", "from_team": "GHOST" \| "COBBLEMON" }` | Invité |
| `BattleInviteSent` | `{ "invite_uuid", "to_uuid", "to_name" }` | Inviteur |
| `BattleInviteDeclined` | `{ "invite_uuid", "by_uuid", "by_name" }` | Inviteur |
| `BattleLobbyUpdated` | `{ "lobby_uuid", "opponent_uuid", "opponent_name", "own_team_source", "own_team": [Pokemon…], "own_lead", "own_ready", "opponent_team_source", "opponent_team": [{ "species", "form", "is_shiny", "gender" }…], "opponent_ready", "timer_total_seconds", "timer_seconds_left" \| null, "timer_by_name" \| null, "format_id", "formats": [{ "id", "name" }…], "own_team_issues": [[{ "code", "subject" }…]…], "opponent_team_flags": [bool…] }` | Chaque joueur, sa propre vue. L'équipe adverse n'est qu'un **aperçu** (modèle et nom : ni niveau, ni objet, ni capacités, ni IV/EV) ; le lead adverse n'est jamais envoyé. Format : `own_team_issues` détaille, par Pokémon, les règles enfreintes (`BANNED`, `NONSTANDARD`, `CLAUSE`, `TEAM_SIZE`, `LITTLE_CUP`, `SAME_TYPE`) ; pour l'adversaire seulement un drapeau par Pokémon. « Prêt » est refusé (`ERROR_BATTLE_TEAM_NOT_ALLOWED`) tant que sa propre équipe enfreint le format. Pendant le lobby, `SendOutGhost` répond `ERROR_GHOST_IN_BATTLE` et les deux joueurs sont occupés pour toute autre invitation. |
| `BattleLobbyCancelled` | `{ "lobby_uuid", "reason": "LEFT" \| "PARTNER_DISCONNECTED" \| "BACKEND_LOST" \| "EMPTY_TEAM" \| "TEAM_NOT_ALLOWED", "by_name" \| null }` | Les deux |
| `BattleSessionStarted` | `{ "battle_uuid", "role": "HOST" \| "GUEST", "opponent_uuid", "opponent_name", "own_team": [Pokemon…], "opponent_team": [Pokemon…], "own_team_source", "opponent_team_source": "GHOST" \| "COBBLEMON", "format": { "id", "name", "battle_rules": [..], "adjust_level" }, "intro": 0..4 }` | Les deux, quand les deux sont prêts dans le lobby. `intro` : cinématique d'intro tirée au sort par le backend, la même pour les deux joueurs (TODO-26 : 0 X/Y, 1 Épée/Bouclier, 2 Diamant/Perle, 3 Émeraude, 4 Noir/Blanc ; absent = 0). `format.battle_rules` : règles que le moteur de Showdown applique (Sleep Clause Mod…) ; `format.adjust_level` : niveau imposé (100, 5 en Little Cup, 0 = niveau propre) ; en 1v1, seul le lead est envoyé. Chaque équipe est réordonnée **lead en premier** (`team_slot` renuméroté 1..n). `opponent_team` n'est envoyé **qu'à l'hôte**. Jusqu'à la fin du combat, `SendOutGhost` répond `ERROR_GHOST_IN_BATTLE`. Si le timer du lobby était actif, suivi de `BattleTimerEnabled`. |
| `BattlePacket` | comme en C2S | Invité |
| `BattleChoice` | comme en C2S | Hôte |
| `BattleTimerEnabled` | `{ "battle_uuid", "by_uuid", "by_name", "seconds": 90 }` | Les deux |
| `BattleEnded` | `{ "battle_uuid", "winner_uuid" \| null, "reason": "FINISHED" \| "FORFEIT" \| "PARTNER_DISCONNECTED" \| "BACKEND_LOST" }` | Les deux ; aussi stocké dans `battle_sessions.result` |
| `BattleSessionError` | `{ "error_code" }` | Auteur de l'action refusée |

**Ghost contre Pokémon normal** (CAD Partie 1 §31, décision D-22) : chaque joueur choisit ses Ghost ou une copie de son
équipe Cobblemon. `party` : 1 à 6 objets de la forme d'un Pokémon Ghost (`uuid` = UUID Cobblemon du vrai Pokémon,
`species`, `form`, `level`, `nature`, `ability`, `is_shiny`, `cobblemon_data_version`, `data` avec `ivs`, `evs`, `moves`,
`held_item`, `tera_type`, `gender`, `nickname`, `friendship`), validés comme un Ghost (bornes, légalité IV/EV, taille,
UUID distincts). Le combat se joue sur des copies : les vrais Pokémon ne sont jamais modifiés. `battle_sessions` garde les
UUID engagés, réels compris.

Une déconnexion pendant un combat le termine en `ABORTED`, sans vainqueur (`PARTNER_DISCONNECTED`). Si le backend
s'arrête, chaque combat en cours devient un **nul** (`FINISHED`, sans vainqueur, `BACKEND_LOST`) annoncé aux deux
joueurs avant la fermeture des connexions ; s'il plante, les sessions restées `ACTIVE` sont conclues de la même façon
à son redémarrage (CAD Partie 1 §44). Un client qui perd la connexion termine lui-même le combat et l'annonce nul.

### Spectateurs (2026-10-07)

Regarder un combat Ghost « comme dans Cobblemon » ([D-31](../architecture/decisions.md#d-31--spectateurs-dun-combat-ghost)).
Le moteur de Cobblemon de l'hôte produit déjà un flux pour ses spectateurs (`PokemonBattle.sendSpectatorUpdate`,
informations publiques uniquement) : l'hôte le relaie par le backend, qui le recopie à chaque spectateur. Le relais
de l'invité (`BattlePacket`) n'est jamais recopié : il porte l'équipe et les demandes privées de l'invité.

| C2S | `data` | Effet |
|---|---|---|
| `BattleSpectate` | `{ "target_uuid" }` | Regarder le combat que joue `target_uuid` (hôte ou invité). Refus (`BattleSessionError`) : `ERROR_WS_MALFORMED_MESSAGE`, `ERROR_BATTLE_SELF`, `ERROR_BATTLE_ALREADY_IN_BATTLE` (l'appelant joue, est en lobby ou regarde déjà), `ERROR_BATTLE_SPECTATE_NOT_BATTLING` |
| `BattleSpectatorPacket` | `{ "battle_uuid", "id", "payload", "spectator_uuid"? }` | **Hôte seulement** (`ERROR_BATTLE_NOT_HOST`) : un paquet du flux spectateur (mêmes `id` / `payload` que `BattlePacket`). Avec `spectator_uuid` : pour ce spectateur seul (son rattrapage), sinon pour tous |
| `BattleSpectateLeave` | `{ "battle_uuid" }` | Arrêter de regarder (bouton Retour de l'écran de combat de Cobblemon) |

| S2C | `data` | Destinataires |
|---|---|---|
| `BattleSpectateStarted` | `{ "battle_uuid", "host_uuid", "host_name", "guest_uuid", "guest_name" }` | Le spectateur |
| `BattleSpectatorJoined` | `{ "battle_uuid", "spectator_uuid", "spectator_name" }` | Les deux joueurs ; l'hôte envoie alors au spectateur le rattrapage (`BattleInitializePacket` sans camp + historique du chat), puis le flux |
| `BattleSpectatorLeft` | `{ "battle_uuid", "spectator_uuid" }` | Les deux joueurs (départ ou déconnexion du spectateur) |
| `BattleSpectatorPacket` | `{ "battle_uuid", "id", "payload" }` | Le ou les spectateurs |
| `BattleSpectateEnded` | `{ "battle_uuid", "reason", "winner_uuid"? }` | Le spectateur : `LEFT` après `BattleSpectateLeave`, sinon la fin du combat (mêmes `reason` / `winner_uuid` que `BattleEnded`) |

Regarder occupe le joueur comme un combat : il ne peut ni inviter ni être invité, ni entrer dans un lobby, tant
qu'il regarde.

### Terrain vu par les joueurs alentour (2026-10-07)

Comme dans Cobblemon, où tout joueur proche voit les Pokémon d'un combat, sans le regarder
([D-33](../architecture/decisions.md#d-33--terrain-dun-combat-ghost-visible-par-les-joueurs-alentour)). Sont
**témoins** d'un combat : les membres du groupe serveur (`server_fingerprint + dimension`) de l'hôte ou de l'invité
et, si l'un des deux est dans le Global Hub, tous les membres du Hub — sauf les deux joueurs et les spectateurs. La
liste est réévaluée chaque seconde et avant chaque paquet du flux. Aucun message C2S nouveau : l'hôte réutilise
`BattleSpectatorPacket` (avec `spectator_uuid` = le témoin pour son rattrapage, sans pour tous).

| S2C | `data` | Destinataires |
|---|---|---|
| `BattleFieldViewerJoined` | `{ "battle_uuid", "viewer_uuid" }` | L'hôte : il envoie au témoin le terrain tel qu'il est (`BattleInitializePacket` sans camp, sans historique du chat), puis le flux |
| `BattleFieldViewerLeft` | `{ "battle_uuid", "viewer_uuid" }` | L'hôte : ce témoin ne reçoit plus rien |
| `BattleFieldPacket` | `{ "battle_uuid", "id", "payload" }` | Le ou les témoins : un paquet du flux spectateur (Pokémon, sorties, rappels, K.O., animations, Méga / Z / Téra) |
| `BattleFieldEnded` | `{ "battle_uuid", "reason" }` | Le témoin : `OUT_OF_RANGE` (autre serveur ou dimension, sortie du Hub, déconnexion), `SPECTATING` (il regarde désormais ce combat), sinon la fin du combat (même `reason` que `BattleEnded`) |

### Combat solo d'un admin (2026-10-07)

| C2S | `data` | Effet |
|---|---|---|
| `BattleSoloStart` | `{}` | **Admin** ([D-32](../architecture/decisions.md#d-32--combat-solo-dun-admin-contre-un-miroir-de-son-équipe)) : combat contre un miroir de son équipe Ghost. Refus : `ERROR_ADMIN_REQUIRED`, `ERROR_BATTLE_ALREADY_IN_BATTLE`, `ERROR_BATTLE_EMPTY_TEAM`. Réponse : `BattleSessionStarted` avec `role` `HOST`, `"solo": true`, `opponent_uuid` = uuid du miroir (stable, propre à cet admin), `opponent_name` = « pseudo (miroir) », `opponent_team` = `own_team`, format Libre. Le reste comme un combat normal (spectateurs compris) ; `BattleResult` accepte le miroir comme vainqueur ; un seul `BattleEnded` ; **rien n'est stocké** dans `battle_sessions` |

## 6. Échanges asynchrones (notifications)

Envoyés par `TradeService` après les appels REST, uniquement aux joueurs connectés :

| S2C | `data` | Destinataire |
|---|---|---|
| `TradeProposed` | `{ "trade_uuid", "initiator_uuid", "offered_pokemon", "requested_pokemon" }` | Destinataire de `POST /trades` |
| `TradeAccepted` | `{ "trade_uuid" }` | Les deux, après `POST /trades/{uuid}/accept` |
| `TradeCancelled` | `{ "trade_uuid" }` | Les deux, après `POST /trades/{uuid}/cancel` |

## 6 bis. Global Hub (Phantasmon Network)

Étape N2 de [`network-cahier-des-charges.md`](../specifications/network-cahier-des-charges.md) (§5.3 à §5.8).
Implémentation : `hub.HubService` (mémoire, comme la présence). **Plusieurs hubs** créés par les admins (D-35), chacun
un espace séparé : ses membres, avatars, Ghost et chat ne sont vus que dans ce hub ; au plus `phantasmon.hub.capacity`
joueurs (50) **par hub**. On y entre par un Hub Anchor ([`rest-api.md`](rest-api.md#hub-anchors-phantasmon-network)) du serveur
et de la dimension de sa présence. Les positions du Hub sont **relatives à l'Anchor** : jamais de coordonnées réelles
ni d'adresse de serveur.

**Anchors** (D-30) : on entre par n'importe quel Anchor du serveur et de la dimension où l'on est, jamais par celui
d'un autre serveur. **Visibilité** : chaque membre reçoit tous les autres, joueurs du même serveur compris (ils
peuvent être entrés par un autre Anchor) **du même hub** ; le client masque lui-même un avatar qui ferait doublon avec
le vrai joueur. Le chat va à tous les membres du hub.

| C2S | `data` | Effet |
|---|---|---|
| `HubJoin` | `{ "anchor_uuid" }` | Entre dans le Hub par cet Anchor, quel que soit son créateur. Refus : `ERROR_WS_MALFORMED_MESSAGE` (UUID absent ou invalide), `ERROR_HUB_ANCHOR_NOT_FOUND`, `ERROR_HUB_ANCHOR_WRONG_SERVER` (pas de `JoinServerGroup`, ou Anchor d'un autre serveur ou d'une autre dimension), `ERROR_HUB_FULL` (`details.capacity`, `details.hub` : le hub de cet Anchor est plein). Déjà membre : sortie puis entrée par le nouvel Anchor (éventuellement d'un autre hub). |
| `HubLeave` | `{}` | Sort du Hub ; répond `HubLeft` `LEFT` |
| `HubMove` | `{ "x", "z", "y_offset", "yaw", "head_yaw", "pitch", "pose", "on_ground", "skin_parts"? }` | Nouvel état de l'avatar. `x`, `z` : coordonnées Hub (relatives au centre de l'Anchor, après rotation par son `yaw`) : \|x\| ≤ largeur du hub / 2, \|z\| ≤ longueur / 2 ; `y_offset` : hauteur au-dessus du sol local, de 0 à la hauteur du hub ; sinon `ERROR_HUB_OUT_OF_BOUNDS` (`details.half_size_x`, `half_size_z`, `height`). `pose` ∈ `STANDING`, `CROUCHING`, `SWIMMING`, `FALL_FLYING` ; `on_ground` booléen ; `skin_parts` facultatif : masque `PlayerModelPart` de Minecraft (couches extérieures du skin et cape affichées), entier de 0 à 127 ; champ manquant, non fini ou hors limites : `ERROR_WS_MALFORMED_MESSAGE`. Hors du Hub : `ERROR_HUB_NOT_JOINED`. À envoyer jusqu'à 10 fois par seconde, seulement quand l'état change. |
| `HubChat` | `{ "message" }` | Message du chat du Hub : codes `§x` retirés, espaces de bord retirés ; vide → `ERROR_WS_MALFORMED_MESSAGE`, > 256 caractères → `ERROR_HUB_CHAT_TOO_LONG`, moins d'une seconde après le précédent → `ERROR_HUB_CHAT_RATE_LIMITED`, hors du Hub → `ERROR_HUB_NOT_JOINED`. Écrit dans le journal du backend, jamais en base. |

| S2C | `data` | Destinataires |
|---|---|---|
| `HubJoined` | `{ "members": [ { "player_uuid", "username", "state" \| null, "ghost" \| null } ] }` | Le joueur qui entre : tous les autres membres, avec leur dernier état (`null` s'ils n'ont encore envoyé aucun `HubMove`) et leur Ghost sorti (mêmes champs que `HubGhostSpawn`, `null` sinon) |
| `HubPlayerEnter` | `{ "player_uuid", "username", "state": null }` | Tous les autres membres |
| `HubPlayerMove` | `{ "player_uuid", "state": { "x", "z", "y_offset", "yaw", "head_yaw", "pitch", "pose", "on_ground", "skin_parts"? } }` | Tous les autres membres |
| `HubPlayerLeave` | `{ "player_uuid" }` | Tous les autres membres : sortie, changement de serveur, Anchor supprimé, déconnexion ou TTL |
| `HubLeft` | `{ "reason": "LEFT" \| "SERVER_CHANGED" \| "ANCHOR_DELETED" }` | Le joueur sorti du Hub (pas d'envoi sur déconnexion ni TTL) |
| `HubGhostSpawn` | `{ "player_uuid", "pokemon_uuid", "species", "form" \| null, "is_shiny", "level", "gender" \| null, "nickname" \| null }` | Tous les autres membres : un membre sort un Ghost dans le Hub, ou entre dans le Hub avec un Ghost déjà sorti. Mêmes champs que `GhostEntitySpawn`, **sans `position`** : le client le fait suivre l'avatar |
| `HubGhostDespawn` | `{ "player_uuid", "pokemon_uuid" }` | Tous les autres membres : rappel, Ghost échangé, début d'un combat Ghost (tout ce qui envoie `GhostEntityDespawn` au groupe) ; la sortie du Hub suffit sinon (`HubPlayerLeave`) |
| `HubChatMessage` | `{ "player_uuid", "username", "message", "sent_at" }` | Tous les membres du hub, expéditeur compris |
| `HubCatalogChanged` | `{}` | **Tous les clients connectés** : un admin a créé, supprimé ou rechargé un hub (D-35) ; le client relit `GET /hubs` (suggestions de commandes, constructions) et les Anchors |

**Sorties automatiques** :

- `JoinServerGroup` vers une autre empreinte ou dimension, `PositionUpdate` avec une autre dimension, ou
  `LeaveServerGroup` → `HubLeft` `SERVER_CHANGED` (l'Anchor d'entrée ne s'applique plus).
- Suppression de l'Anchor d'entrée (`DELETE /hub/anchors/{uuid}`, créateur ou admin) → `HubLeft` `ANCHOR_DELETED`
  pour chaque membre entré par lui.
- Fermeture de la connexion ou expiration TTL → sortie silencieuse, `HubPlayerLeave` pour les autres.

Les Ghost suivent toujours leur propriétaire : `SendOutGhost` / `RecallGhost` (et les rappels automatiques) envoient
à la fois `GhostEntity*` au groupe serveur et `HubGhost*` au Hub (`GhostRecall`, `HubService.onGhostSentOut` /
`onGhostRecalled`).

## 7. Écarts avec le CAD

`BattleAction` (C2S) et `BattleState` (S2C) prévus par le CAD (Partie 2 §11) n'existent pas : ils sont remplacés
par le relais des paquets Cobblemon (`BattlePacket` / `BattleChoice`). Voir la décision D-05.
