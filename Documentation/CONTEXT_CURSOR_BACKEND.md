# Contexte projet — Phantasmon Backend (à destination des IA de Cursor)

Ce document donne aux assistants IA le contexte nécessaire pour générer du code cohérent avec le CAD (Parties 1-3, voir `phantasmon-docs`). À lire avant toute génération de code sur ce repo.

## 1. Rôle de ce service

Le Phantasmon Backend est la **source de vérité absolue** du système Phantasmon. Il ne dépend d'aucun serveur Minecraft — il tourne comme un service Spring Boot classique, complètement indépendant, et communique uniquement avec le mod client (`phantasmon-client`) via REST et WebSocket.

Rappel du principe fondateur du projet (voir `phantasmon-docs/CONTEXT_CURSOR.md`) : **aucun serveur Minecraft n'installe de code Phantasmon**. Ce backend est donc la seule "autorité serveur" du système au sens large — pas de mod serveur Minecraft en complément.

## 2. Stack

| | |
|---|---|
| Langage | Java 21 |
| Framework | Spring Boot 3.x |
| Build | Gradle |
| Base de données | PostgreSQL (schéma versionné Flyway) |
| API | REST + WebSocket |
| Auth | Vérification de session Mojang (`joinServer`/`hasJoined`) → JWT (Spring Security) |
| Tests | JUnit 5 + Testcontainers (PostgreSQL réel, jamais de H2 — le JSONB ne se comporte pas pareil) |

## 3. Structure de packages (par domaine, pas par couche technique)

```text
src/main/java/com/phantasmon/backend/
├── auth/           # flux Mojang, JWT, filtre Security
├── pokemon/        # entité Pokemon, PokemonLegalityService, CRUD/clone/import Showdown
├── trade/          # entité Trade, flux propose/accept/cancel (transaction atomique)
├── battle/         # BattleSession, arbitrage "client hôte + garde-fous"
├── presence/       # PlayerPresence en mémoire, groupement server_fingerprint+dimension, TTL cleanup
├── websocket/      # config WebSocket, handlers événements C2S/S2C
├── version/        # GET /version, table de compatibilité client/backend
├── admin/          # routes /admin/*, protection par rôle JWT
└── common/         # DTOs partagés, ApiException + codes ERROR_*, idempotence
```

Chaque module contient ses propres `*.controller`, `*.service`, `*.repository`, `*.dto`, `*.entity` internes. Pas de `controller/` ou `service/` global à la racine qui mélangerait tous les domaines.

## 4. Règles non négociables à respecter dans le code généré

1. **Ne jamais faire confiance au client.** Toute opération sensible (modification, suppression, échange, combat, duplication) revérifie l'ownership réel en base avant d'agir, même si le payload client semble légitime.
2. **Légalité des Pokémon imposée côté service, jamais seulement côté DTO/validation Bean.** `PokemonLegalityService` doit être appelé explicitement à la création ET à la modification ET à l'import Showdown — ce n'est pas optionnel, pas seulement une annotation `@Valid`.
   - IVs : chaque stat ∈ [0, 31]
   - EVs : chaque stat ∈ [0, 252], somme totale ≤ 510
3. **Idempotence obligatoire sur les endpoints de création.** Tout endpoint POST sensible (`/pokemon`, `/trades`, `/battles`) accepte un `request_uuid` et vérifie `idempotency_keys` avant traitement — retourne la réponse originale si déjà vu, ne retraite jamais.
4. **Erreurs toujours structurées, jamais de message brut.** Toute exception métier retourne un `error_code` (`ERROR_LEGALITY_EV_TOTAL_EXCEEDED`, `ERROR_OWNERSHIP_MISMATCH`, etc.) + `details`, jamais une string libre — le client traduit localement (FR/EN).
   ```json
   { "error_code": "ERROR_LEGALITY_EV_TOTAL_EXCEEDED", "details": { "total": 528, "max": 510 } }
   ```
5. **Transactions atomiques pour tout ce qui touche l'ownership.** Un trade change les deux `owner_uuid` dans une seule transaction, ou aucun changement n'est appliqué — jamais d'état intermédiaire.
6. **Pas de duplication des données Cobblemon.** Le champ `data` (JSONB) d'un Pokémon ne stocke que des identifiants (`species`, `moves`, `ability`...), jamais de stats de base ou de données de rendu — ça reste résolu côté client.
7. **`PlayerPresence` reste en mémoire, pas en base.** C'est un état éphémère (connexion en cours), pas une donnée persistante. Ne pas créer de table SQL pour ça — TTL de nettoyage géré par une tâche planifiée (`@Scheduled`).
8. **Pas de logique multi-instance.** Le routage WebSocket et `PlayerPresence` sont en mémoire locale à l'instance — pas de Redis/pub-sub à ce stade (hors scope V1, voir CAD Partie 3 §H). Ne pas ajouter cette complexité sans demande explicite.
9. **Aucune validation de plausibilité de position réseau** (anti-triche positionnel explicitement écarté par le CAD). Ne pas ajouter de logique de sanity-check sur les positions reçues sans demande explicite.
10. **TDD strict.** Un test écrit avant le code pour chaque nouvelle feature. Les tests d'intégration utilisent Testcontainers (vrai PostgreSQL), pas H2.

## 5. Schéma de base (Flyway, pas de `ddl-auto: update`)

Tables principales attendues (voir CAD Partie 2 §5 et Partie 3 §D) :

```text
players           (uuid, last_username, created_at, last_seen_at)
pokemon           (uuid, owner_uuid, species, form, level, nature, ability,
                    is_shiny, box_id, box_slot, team_slot,
                    cobblemon_data_version, data JSONB, created_at, updated_at)
trades            (uuid, initiator_uuid, recipient_uuid, offered_pokemon,
                    requested_pokemon, status, created_at, resolved_at)
battle_sessions   (uuid, player_a, player_b, team_a JSONB, team_b JSONB,
                    status, result JSONB, created_at, finished_at)
idempotency_keys  (request_uuid, player_uuid, endpoint, response_snapshot JSONB, created_at)
```

Toute évolution de schéma passe par une nouvelle migration Flyway (`src/main/resources/db/migration/V{n}__description.sql`), jamais par une modification rétroactive d'une migration déjà appliquée.

## 6. Endpoints de référence

```http
POST   /auth/session
GET    /version
GET    /players/{uuid}/pokemon
GET    /players/{uuid}/pc?box={n}
POST   /pokemon
PATCH  /pokemon/{uuid}
DELETE /pokemon/{uuid}
POST   /pokemon/{uuid}/clone
POST   /pokemon/import-showdown
GET    /pokemon/{uuid}/export
GET    /players/{uuid}/team
PUT    /players/{uuid}/team
POST   /trades
POST   /trades/{uuid}/accept
POST   /trades/{uuid}/cancel
GET    /trades/{uuid}
GET    /players/{uuid}/trades
POST   /battles
GET    /battles/{uuid}
POST   /battles/{uuid}/result
GET    /admin/pokemon/{uuid}
POST   /admin/pokemon/{uuid}/inspect
POST   /admin/players/{uuid}/audit
GET    /health
```

WebSocket (`wss://.../ws?token={jwt}`) :

```text
C2S: JoinServerGroup, LeaveServerGroup, PositionUpdate, SendOutGhost,
     RecallGhost, BattleAction, Heartbeat
S2C: GhostEntitySpawn, GhostEntityMove, GhostEntityDespawn,
     BattleState, BattleEnded, TradeProposed, TradeAccepted,
     TradeCancelled, Error, HeartbeatAck
```

## 7. Hors périmètre (ne pas générer sans demande explicite)

- Tout code visant à s'intégrer à un mod serveur Minecraft — il n'y en a pas et il n'y en aura pas.
- Tout moteur de calcul de combat Pokémon complet côté backend (le combat reste arbitré par le client hôte, le backend ne fait que des garde-fous de cohérence globale, voir CAD Partie 2 §9).
- Systèmes de quota/cooldown de création de Pokémon (accès libre confirmé, CAD Partie 3 §C).
- Toute solution de scalabilité horizontale (Redis, message broker) tant que ce n'est pas explicitement demandé.

## 8. Référence

Le détail complet (schémas, flux, décisions et leur justification) est dans `phantasmon-docs` : CAD Parties 1 à 3. En cas de doute sur un comportement non couvert ici, s'y référer avant d'improviser.
