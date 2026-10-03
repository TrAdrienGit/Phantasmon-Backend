# Catalogue des codes d'erreur

> Vérifié contre le code le 2026-10-03 (backend : `ApiException` et messages WebSocket ; client :
> `network/BackendErrorMessages.java`). Ajouter toute nouvelle erreur ici **et** sa traduction côté client
> (`BackendErrorMessages` + `lang/fr_fr.json` + `lang/en_us.json`).

## Format

```json
{ "error_code": "ERROR_LEGALITY_EV_TOTAL_EXCEEDED", "details": { "total": 528, "max": 510 } }
```

- REST : corps de la réponse, avec le statut HTTP indiqué.
- WebSocket : `data` du message `Error` (avec `details`), `TradeSessionError` ou `BattleSessionError` (sans `details`).
- Le client n'affiche **jamais** le code brut : il le traduit. Un code sans traduction tombe sur
  `phantasmon.error.unknown`.

Canaux : **REST**, **WS** (`Error`), **WS-T** (`TradeSessionError` ou `reason` de `TradeSessionCancelled`),
**WS-B** (`BattleSessionError`).

## Authentification

| Code | Statut | Canal | Signification | Clé de traduction client |
|---|---|---|---|---|
| `ERROR_AUTH_MOJANG_VERIFICATION_FAILED` | 401 | REST | Mojang ne confirme pas la session | `phantasmon.auth.error.mojang_verification_failed` |
| `ERROR_AUTH_UUID_MISMATCH` | 401 | REST | UUID annoncé ≠ UUID vérifié par Mojang | `phantasmon.auth.error.uuid_mismatch` |
| `ERROR_AUTH_INVALID_REFRESH_TOKEN` | 401 | REST | Refresh token invalide, expiré ou joueur inconnu | `phantasmon.auth.error.invalid_refresh_token` |

## Propriété et existence

| Code | Statut | Canal | Signification | Clé de traduction client |
|---|---|---|---|---|
| `ERROR_OWNERSHIP_MISMATCH` | 403 | REST, WS, WS-T | La ressource appartient à un autre joueur | `phantasmon.error.ownership_mismatch` |
| `ERROR_PLAYER_NOT_FOUND` | 404 | REST | Joueur inconnu (`POST /battles`) | — |
| `ERROR_POKEMON_NOT_FOUND` | 404 | REST, WS, WS-T | Pokémon inexistant | `phantasmon.pokemon.error.not_found` |
| `ERROR_TRADE_NOT_FOUND` | 404 | REST | Échange inexistant | `phantasmon.trade.error.not_found` |
| `ERROR_BATTLE_NOT_FOUND` | 404 | REST | Session de combat inexistante | — |

## Pokémon et légalité

| Code | Statut | Canal | Signification | Clé de traduction client |
|---|---|---|---|---|
| `ERROR_LEGALITY_IV_OUT_OF_RANGE` | 422 | REST | Un IV hors de [0, 31] | `phantasmon.pokemon.error.legality_iv` |
| `ERROR_LEGALITY_EV_OUT_OF_RANGE` | 422 | REST | Un EV hors de [0, 252] | `phantasmon.pokemon.error.legality_ev` |
| `ERROR_LEGALITY_EV_TOTAL_EXCEEDED` | 422 | REST | Total des EV > 510 (`details.total`, `details.max`) | `phantasmon.pokemon.error.legality_ev_total` |
| `ERROR_POKEMON_SLOT_OCCUPIED` | 409 | REST | Emplacement demandé déjà pris (création) ou conflit d'index résiduel | `phantasmon.pokemon.error.slot_occupied` |
| `ERROR_POKEMON_PC_FULL` | 409 | REST | Plus aucune case libre (480/480) | `phantasmon.pokemon.error.pc_full` |
| `ERROR_POKEMON_INCOMPLETE_BOX_DESTINATION` | 422 | REST | Un seul de `box_id` / `box_slot` fourni | `phantasmon.pokemon.error.incomplete_box_destination` |
| `ERROR_POKEMON_IN_PENDING_TRADE` | 409 | REST | Suppression d'un Pokémon engagé dans un échange `PENDING` | `phantasmon.pokemon.error.in_pending_trade` |
| `ERROR_POKEMON_NOT_IN_TEAM` | — | WS | `SendOutGhost` sur un Pokémon du PC | `phantasmon.ghost.error.not_in_team` |

## Échanges

| Code | Statut | Canal | Signification | Clé de traduction client |
|---|---|---|---|---|
| `ERROR_TRADE_SELF` | 409 | REST, WS-T | Échange avec soi-même | `phantasmon.trade.error.self` |
| `ERROR_TRADE_INVALID_RECIPIENT_POKEMON` | 409 | REST | Le Pokémon demandé n'appartient pas au destinataire | `phantasmon.trade.error.invalid_recipient_pokemon` |
| `ERROR_TRADE_INVALID_STATE` | 409 | REST | L'échange n'est plus `PENDING` | `phantasmon.trade.error.invalid_state` |
| `ERROR_TRADE_OWNERSHIP_CHANGED` | 409 | REST, WS-T | Un Pokémon a changé de propriétaire entre-temps | `phantasmon.trade.error.ownership_changed` |
| `ERROR_TRADE_PARTNER_UNAVAILABLE` | — | WS-T | La cible n'est pas connectée au WebSocket | `phantasmon.trade.error.partner_unavailable` |
| `ERROR_TRADE_PARTNER_BUSY` | — | WS-T | La cible est déjà dans un échange | `phantasmon.trade.error.partner_busy` |
| `ERROR_TRADE_ALREADY_IN_SESSION` | — | WS-T | L'appelant est déjà dans un échange | `phantasmon.trade.error.already_in_session` |
| `ERROR_TRADE_INVITE_NOT_FOUND` | — | WS-T | Invitation inconnue, destinée à un autre ou expirée (60 s) | `phantasmon.trade.error.invite_not_found` |
| `ERROR_TRADE_NOT_IN_SESSION` | — | WS-T | Action d'échange hors session | `phantasmon.trade.error.not_in_session` |
| `ERROR_TRADE_OFFER_NOT_IN_TEAM` | 409 | WS-T | L'offre n'est pas dans l'équipe active | `phantasmon.trade.error.offer_not_in_team` |
| `ERROR_TRADE_OFFERS_INCOMPLETE` | — | WS-T | « Prêt » alors qu'une offre manque | `phantasmon.trade.error.offers_incomplete` |
| `ERROR_UNKNOWN` | — | WS-T | Échec inattendu de l'exécution finale (`reason` de `TradeSessionCancelled`) | `phantasmon.error.unknown` |

## Combats

| Code | Statut | Canal | Signification | Clé de traduction client |
|---|---|---|---|---|
| `ERROR_BATTLE_SELF` | 409 | REST, WS-B | Combat contre soi-même | `phantasmon.battle.error.self` |
| `ERROR_BATTLE_INVALID_TEAM` | 422 | REST | Équipe vide, plus de 6 Pokémon ou doublons | — |
| `ERROR_BATTLE_OPPONENT_NO_TEAM` | 409 | REST | L'adversaire n'a pas d'équipe active | — |
| `ERROR_BATTLE_INVALID_STATE` | 409 | REST | Session plus `ACTIVE` | — |
| `ERROR_BATTLE_INVALID_RESULT` | 422 | REST, WS-B | Vainqueur hors participants | `phantasmon.battle.error.invalid_result` |
| `ERROR_BATTLE_ALREADY_IN_BATTLE` | — | WS-B | L'appelant est déjà en combat | `phantasmon.battle.error.already_in_battle` |
| `ERROR_BATTLE_PARTNER_UNAVAILABLE` | — | WS-B | La cible n'est pas connectée au WebSocket | `phantasmon.battle.error.partner_unavailable` |
| `ERROR_BATTLE_PARTNER_BUSY` | — | WS-B | La cible est déjà en combat | `phantasmon.battle.error.partner_busy` |
| `ERROR_BATTLE_INVITE_NOT_FOUND` | — | WS-B | Invitation inconnue ou expirée (60 s) | `phantasmon.battle.error.invite_not_found` |
| `ERROR_BATTLE_EMPTY_TEAM` | — | WS-B | Un des joueurs n'a aucun Pokémon en équipe | `phantasmon.battle.error.empty_team` |
| `ERROR_BATTLE_NOT_IN_BATTLE` | — | WS-B | Action hors combat ou mauvais `battle_uuid` (ignoré silencieusement par le client) | `phantasmon.battle.error.not_battling` |
| `ERROR_BATTLE_NOT_HOST` | — | WS-B | `BattlePacket` / `BattleResult` envoyé par l'invité | `phantasmon.battle.error.not_host` |
| `ERROR_BATTLE_NOT_GUEST` | — | WS-B | `BattleChoice` envoyé par l'hôte | `phantasmon.battle.error.not_guest` |

Les codes marqués « — » côté client concernent des routes REST que le client actuel n'appelle pas.

## Protocole WebSocket

| Code | Canal | Signification |
|---|---|---|
| `ERROR_WS_MALFORMED_MESSAGE` | WS | JSON illisible, enveloppe invalide ou champ attendu manquant |
| `ERROR_WS_UNKNOWN_MESSAGE_TYPE` | WS | `type` inconnu (`details.type`) |

## Erreurs propres au client

Messages affichés par le client sans code backend (extraits) : `phantasmon.error.not_authenticated` (commande
utilisée avant connexion), `phantasmon.error.network` (backend injoignable), `phantasmon.auth.network_error`,
`phantasmon.pokemon.import.parse_error` (texte Showdown illisible), `phantasmon.ghost.error.no_team_lead`
(emplacement 1 vide), `phantasmon.battle.error.engine` (moteur de combat non démarré).
