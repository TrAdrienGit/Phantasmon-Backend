# Architecture du backend

> Vérifié contre le code le 2026-10-03. Vue d'ensemble du système : [`system-overview.md`](system-overview.md).

## 1. Pile technique

| Élément | Choix |
|---|---|
| Langage | Java 21 (toolchain Gradle) |
| Framework | Spring Boot **4.1.1** : Web MVC, WebSocket, Security, Data JPA, Validation, RestClient |
| Base de données | PostgreSQL (développé sur la 18), schéma versionné par Flyway (`ddl-auto=none`) |
| Authentification | Vérification de session Mojang (`hasJoined`) puis JWT HMAC-SHA256 (jjwt 0.12.6) |
| JSON | Jackson 3 (`tools.jackson.databind`), noms de champs en **snake_case** globalement |
| Build | Gradle (wrapper), jar exécutable via `bootJar` |
| Tests | JUnit 5, Testcontainers PostgreSQL (jamais H2), MockMvc, client WebSocket réel |
| Divers | Lombok (compile only), DevTools (développement uniquement) |

**Modules Spring Boot 4 à déclarer explicitement** (sinon la fonctionnalité est inactive **sans aucun message**) :
`spring-boot-flyway` (sinon Flyway ne s'exécute jamais), `spring-boot-restclient` (sinon pas de
`RestClient.Builder`), `spring-boot-webmvc-test` (pour `@AutoConfigureMockMvc`).

## 2. Organisation des paquets

Organisation **par domaine** (pas par couche technique). Racine : `com.mystaria.phantasmon_backend`.

| Paquet | Responsabilité | Classes principales |
|---|---|---|
| `auth` | Vérification Mojang, émission/validation des JWT, chaîne Spring Security | `AuthController`, `MojangSessionClient`, `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig` |
| `player` | Identité des joueurs (UUID Mojang, dernier pseudo) | `Player`, `PlayerService.recordConnection` |
| `pokemon` | CRUD, légalité IV/EV, PC et équipe (déplacer ou échanger) | `PokemonController`, `PokemonService`, `PokemonLegalityService`, `PokemonRepository` |
| `trade` | Échanges REST asynchrones et échanges en direct (mémoire + transaction finale) | `TradeController`, `TradeService`, `LiveTradeService`, `LiveTradeSession` |
| `battle` | Sessions de combat REST et combats en direct (invitation, hôte, relais, chrono, résultat) | `BattleController`, `BattleService`, `LiveBattleService`, `BattleSession` |
| `presence` | Présence en mémoire, regroupement par empreinte + dimension, Ghost actif | `PresenceService`, `PlayerPresence`, `Position` |
| `websocket` | Authentification du handshake, dispatch des messages, registre des sessions, balayage TTL | `PhantasmonWebSocketHandler`, `JwtHandshakeInterceptor`, `SessionRegistry`, `PresenceTtlSweeper`, `WsMessage` |
| `version` | Handshake de version client | `VersionController` |
| `health` | Sonde `GET /health` (test réel de la base) | `HealthController` |
| `logging` | Un fichier de log par démarrage, rétention 5 Gio, log de chaque requête REST | `SessionLogFileEnvironmentPostProcessor`, `LogRetentionService`, `RequestLoggingFilter` |
| `common` | Erreurs structurées, idempotence, horloge injectable | `ApiException`, `ApiExceptionHandler`, `ErrorResponse`, `IdempotencyService`, `ClockConfig` |
| `config` | Chargement du `.env` en développement | `DotenvEnvironmentPostProcessor` |

Chaque domaine contient ses entités, dépôts, services, contrôleurs et DTO (records Java). Pas de paquet
`controller/` ou `service/` global.

## 3. Démarrage

1. `DotenvEnvironmentPostProcessor` (priorité maximale) charge le `.env` du répertoire courant s'il existe
   (les guillemets simples ou doubles entourant une valeur sont retirés). Absent en CI et en production : ignoré.
2. `SessionLogFileEnvironmentPostProcessor` fixe `logging.file.name` (un fichier par démarrage) si
   `LOGGING_ENABLED` vaut `true` (défaut). Les deux sont déclarés dans
   `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor`.
3. Flyway applique les migrations manquantes (`src/main/resources/db/migration`).
4. Le serveur écoute sur le port 8080 (REST + `/ws`).

`JWT_SECRET` est obligatoire : sans lui, le contexte ne démarre pas (`Could not resolve placeholder
'phantasmon.jwt.secret'`).

## 4. Requêtes REST

```text
Requête HTTP
  → RequestLoggingFilter          (méthode, chemin, statut, durée)
  → JwtAuthenticationFilter       (Bearer access token → principal = UUID du joueur)
  → SecurityConfig                (public : GET /health, GET /version, POST /auth/session, POST /auth/refresh, /ws)
  → Contrôleur (@Valid)           (validation Bean des DTO)
  → Service (@Transactional)      (propriété revérifiée en base, règles métier)
  → ApiException → ApiExceptionHandler → {"error_code", "details"} + statut HTTP
```

- Le **principal** est l'UUID du joueur extrait du JWT ; il n'est jamais lu dans le corps ou le chemin.
  `/players/{uuid}/…` exige que `{uuid}` soit le joueur authentifié (sinon `ERROR_OWNERSHIP_MISMATCH`).
- **Idempotence** : `POST /pokemon`, `POST /trades`, `POST /battles` passent par
  `IdempotencyService.executeIdempotent(request_uuid, joueur, endpoint, action)` : un `request_uuid` déjà vu
  rejoue la réponse stockée dans `idempotency_keys`. Ce n'est pas protégé contre deux requêtes identiques
  **simultanées** (vérifier puis enregistrer), ce qui suffit pour des tentatives successives.
- **Légalité** : `PokemonLegalityService` est appelé explicitement à la création et à chaque `PATCH` contenant
  `data` (jamais seulement par annotation).
- Une requête refusée par la validation Bean renvoie le format d'erreur par défaut de Spring (400), pas un
  `error_code`.

## 5. WebSocket

- **Point d'entrée** : `/ws?token=<access_token>`. `JwtHandshakeInterceptor` refuse le handshake (401) si le
  jeton est absent, invalide, expiré ou de type `refresh`. Origines autorisées : `*`.
- **Enveloppe** : `{"type": "...", "data": {...}}` dans les deux sens (`WsMessage`). Taille maximale d'un message
  texte : **1 Mio** (les paquets d'équipe Cobblemon dépassent les 8 Kio par défaut).
- **Dispatch** : `PhantasmonWebSocketHandler` route par `type` vers la présence, `LiveTradeService` ou
  `LiveBattleService`. Type inconnu → `Error` `ERROR_WS_UNKNOWN_MESSAGE_TYPE` ; JSON invalide →
  `ERROR_WS_MALFORMED_MESSAGE`.
- **Envoi** : `SessionRegistry.sendTo` synchronise les écritures par session (deux threads écrivant sur la même
  session lèveraient `TEXT_PARTIAL_WRITING`).
- **Fin de connexion** (propre, brutale ou forcée par le TTL) : présence retirée, Ghost éventuel retiré chez le
  groupe (`GhostEntityDespawn`), échange en direct annulé (`PARTNER_DISCONNECTED`), combat en cours terminé en
  `ABORTED`, invitations du joueur oubliées.

Protocole complet : [`reference/websocket-protocol.md`](../reference/websocket-protocol.md).

## 6. État en mémoire

Trois services tiennent un état volatil, perdu au redémarrage et non partagé entre instances (instance unique
assumée, CAD Partie 3 §H) :

| Service | Contenu | Concurrence |
|---|---|---|
| `PresenceService` | `PlayerPresence` par joueur : empreinte, dimension, position, Ghost actif, dernier heartbeat | `ConcurrentHashMap`, entrées immuables remplacées en bloc |
| `LiveTradeService` | Invitations (60 s) et sessions d'échange (offres, drapeaux « prêt ») | Un verrou global |
| `LiveBattleService` | Invitations (60 s), combats en cours (hôte, invité, chrono 90 s) | Un verrou global (méthodes `synchronized`) |

`PresenceTtlSweeper` s'exécute toutes les 10 s et retire les présences sans heartbeat depuis 30 s, en fermant la
session WebSocket correspondante si elle est encore ouverte (le Ghost du joueur expiré n'est probablement pas
retiré chez les autres : BUG-4).

Règles de diffusion à retenir :

- `PresenceService.groupMembers(joueur)` **exclut l'appelant**. Les événements Ghost utilisent
  `broadcastToGroupAndSelf` pour que le propriétaire voie aussi son propre Ghost.
- À l'arrivée dans un groupe (`JoinServerGroup`), le joueur reçoit un `GhostEntitySpawn` pour chaque Ghost déjà
  sorti dans ce groupe.

## 7. Transactions métier sensibles

| Opération | Garanties |
|---|---|
| Déplacement PC/équipe (`PATCH`) | Déplacement ou échange avec l'occupant ; l'ancienne place est libérée et flushée avant d'être réattribuée, pour ne jamais violer les index uniques `uq_pokemon_pc_slot` / `uq_pokemon_team_slot`. |
| Acceptation d'un échange REST | Une transaction : propriété des deux Pokémon revérifiée, propriétaires échangés, chaque Pokémon placé au premier emplacement libre du PC de son nouveau propriétaire. Si la propriété a changé : échange `CANCELLED` (persisté grâce à `noRollbackFor`) et aucun transfert. Effet de bord suspecté de ce `noRollbackFor` : BUG-5. |
| Échange en direct | `TradeService.completeLiveTrade` : une transaction ; propriété **et** présence dans l'équipe revérifiées ; chaque Pokémon prend l'emplacement d'équipe de l'autre ; ligne `trades` `COMPLETED` ; Ghost échangé rappelé. |
| Suppression | Refusée si le Pokémon est engagé dans un échange `PENDING` (`ERROR_POKEMON_IN_PENDING_TRADE`). |
| Combat en direct | Ligne `battle_sessions` `ACTIVE` créée au démarrage (`player_a` = `host_uuid` = hôte), puis `FINISHED` ou `ABORTED`. |

## 8. Journalisation

- Un fichier par démarrage : `log/Log-Phantasmon-Backend_<AAAA-MM-JJ>_<HH-MM-SS>.txt`, même contenu que la
  console (niveau `INFO`).
- `LogRetentionService` limite le dossier à **5 Gio** (au démarrage puis toutes les heures) en supprimant les
  plus anciens fichiers `Log-Phantasmon-Backend_*.txt` uniquement.
- `RequestLoggingFilter` trace chaque requête REST ; les événements WebSocket importants (connexion, groupe,
  sortie de Ghost, échanges, combats) sont tracés par leurs services.
- Ne pas remplacer ce mécanisme par un `logback-spring.xml` conditionnel : `<springProperty>` n'est pas résolu
  assez tôt et le basculement devient peu fiable.

## 9. Tests

Voir [`guides/testing.md`](../guides/testing.md). En résumé : TDD, tests d'intégration contre un vrai
PostgreSQL (Testcontainers), tests WebSocket avec un serveur embarqué et un vrai client WebSocket.
