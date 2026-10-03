# Tests du backend

## 1. Principes

- **TDD strict** : le test est écrit avant le code, vu en échec, puis au vert.
- **PostgreSQL réel** via Testcontainers, **jamais H2** (le JSONB et les index partiels ne s'y comportent pas pareil).
- **WebSocket réel** : les tests d'intégration démarrent un serveur embarqué et s'y connectent avec un vrai client
  WebSocket (`StandardWebSocketClient`).
- Tout nouvel endpoint, message WebSocket ou règle métier est couvert par au moins un test de succès et un test
  par erreur métier.

## 2. Lancer

```bash
./gradlew test
```

- **Docker doit tourner** (Docker Desktop sous Windows ; il met ~25 s à être prêt après son lancement).
- `JWT_SECRET` doit être défini (le `.env` local suffit ; la CI injecte une valeur factice).
- Rapport HTML : `build/reports/tests/test/index.html`.
- Un seul test : `./gradlew test --tests "com.mystaria.phantasmon_backend.pokemon.PokemonControllerTest"`.

La datasource de test est fournie par `TestcontainersConfiguration` (`@ServiceConnection`), qui remplace les
variables `BDD_*`.

## 3. Organisation

| Classe | Couvre |
|---|---|
| `auth/JwtServiceTest`, `auth/JwtAuthenticationFilterTest` | Émission et validation des jetons, types access/refresh |
| `auth/AuthControllerTest` | `POST /auth/session` (Mojang simulé), `POST /auth/refresh` |
| `player/PlayerServiceTest` | Création et mise à jour des joueurs |
| `pokemon/PokemonLegalityServiceTest` | Règles IV/EV |
| `pokemon/PokemonControllerTest` | CRUD, idempotence, PC/équipe, déplacements et échanges de places |
| `pokemon/PokemonRepositoryTest` | Requêtes et contraintes SQL |
| `common/IdempotencyServiceTest` | Rejeu d'une réponse stockée |
| `trade/TradeControllerTest` | Échanges REST, atomicité, propriété changée |
| `trade/LiveTradeWebSocketIntegrationTest` | Échange en direct de bout en bout |
| `battle/BattleControllerTest` | Sessions REST et garde-fous |
| `battle/LiveBattleWebSocketIntegrationTest` | Combat en direct : invitation, hôte, relais, chrono, fin |
| `presence/PresenceServiceTest` | Regroupement, TTL |
| `websocket/PhantasmonWebSocketIntegrationTest` | Handshake, présence, Ghost (spawn, move, despawn, rattrapage, déconnexion) |
| `version/VersionControllerTest`, `health/HealthControllerTest` | Endpoints publics |
| `logging/*Test` | Fichier de session, rétention, journalisation des requêtes |
| `PhantasmonBackendApplicationTests` | Démarrage du contexte |

Environ 127 méthodes `@Test` au 2026-10-03.

## 4. Pièges connus

| Piège | Solution |
|---|---|
| `jsonPath("$[?(…)].champ").doesNotExist()` échoue (« found: [null] ») sur une projection filtrée | Asserter la valeur attendue plutôt que l'absence |
| `Map.of(...)` avec une valeur `null` lève `NullPointerException` | Utiliser une `HashMap` pour les charges utiles pouvant contenir `null` |
| Une méthode « membres du groupe » exclut l'appelant | Vérifier ce qu'une méthode réutilisée exclut, pas seulement ce qu'elle renvoie |
| Chaque `@SpringBootTest` créerait son propre fichier dans `log/` | Toujours déclarer `@SpringBootTest(properties = "phantasmon.logging.enabled=false")` |
| Un `src/test/resources/application.properties` **masque** entièrement celui de `main` (pas de fusion), ce qui casse la résolution de `phantasmon.jwt.secret` | Ne pas en créer ; passer les surcharges par `@SpringBootTest(properties = …)` |

## 5. Intégration continue

`.github/workflows/build.yml` (push et pull request sur `main`) : JDK 21 Temurin, cache Gradle,
`./gradlew build -x test`, puis `./gradlew test` avec `JWT_SECRET` factice. Le rapport de test est publié en
artefact en cas d'échec, le jar dans tous les cas. Docker est disponible nativement sur les runners
`ubuntu-latest`.
