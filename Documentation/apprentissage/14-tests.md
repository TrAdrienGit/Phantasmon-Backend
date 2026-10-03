# 14. Les tests

## 14.1 Le TDD

Le backend est développé en **TDD** (*Test-Driven Development*) :

1. écrire un test qui décrit le comportement voulu ;
2. le lancer et le voir **échouer** (il vérifie donc vraiment quelque chose) ;
3. écrire le code minimal qui le fait passer ;
4. nettoyer, en relançant toute la suite.

Bénéfices concrets dans ce projet : plusieurs bugs ont été trouvés **en écrivant le test**, avant même d'exister en
production (le `NullPointerException` de `Map.of`, la violation d'index lors d'un échange de places).

## 14.2 JUnit et AssertJ

**JUnit 5** exécute les tests ; **AssertJ** fournit des vérifications lisibles.

```java
// presence/PresenceServiceTest.java (raccourci)
@Test
void cleanupExpiredRemovesOnlyPresencesPastTtl() {
    AtomicReference<Instant> now = new AtomicReference<>(start);
    Clock movableClock = …;                                       // horloge contrôlée par le test
    PresenceService service = new PresenceService(movableClock, Duration.ofSeconds(30));
    service.join(stale, "fp", "minecraft:overworld");
    …
    now.set(start.plus(TTL).plusSeconds(1));                      // on « avance le temps »
    assertThat(service.cleanupExpired()).containsExactly(stale);
}
```

Ce test n'a besoin ni de Spring ni de base : `PresenceService` reçoit ses dépendances par son constructeur (chapitre
5), on lui en donne des fausses. C'est la raison d'être du `Clock` injecté.

## 14.3 Tests d'intégration avec Spring

Pour tester une route HTTP complète (filtres, sécurité, validation, base), on démarre l'application entière :

```java
// pokemon/PokemonControllerTest.java (raccourci)
@Import(TestcontainersConfiguration.class)                       // une vraie base PostgreSQL (voir 14.4)
@SpringBootTest(properties = "phantasmon.logging.enabled=false") // toute l'application, sans fichier de log
@AutoConfigureMockMvc                                            // un faux client HTTP, sans réseau
class PokemonControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private PlayerService playerService;

    @BeforeEach
    void setUpAuthenticatedPlayer() {
        ownerUuid = UUID.randomUUID();
        playerService.recordConnection(ownerUuid, "Bichou");
        bearerToken = "Bearer " + jwtService.issueAccessToken(ownerUuid, "Bichou");   // un vrai jeton signé
    }

    @Test
    void createReturns201AndPersistsPokemon() throws Exception {
        mockMvc.perform(post("/pokemon")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner_uuid").value(ownerUuid.toString()))
                .andExpect(jsonPath("$.box_id").value(1));
    }
}
```

- `@Autowired` demande un bean au conteneur (dans les tests, l'injection par champ est courante).
- `MockMvc` simule des requêtes HTTP et permet de vérifier statut et JSON (`jsonPath`).
- Chaque test crée son propre joueur avec un UUID aléatoire : les tests n'interfèrent pas entre eux.

## 14.4 Testcontainers : une vraie base jetable

```java
// src/test/java/…/TestcontainersConfiguration.java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    @Bean
    @ServiceConnection                                           // Spring utilise ce conteneur comme datasource
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
    }
}
```

**Testcontainers** démarre un conteneur Docker PostgreSQL pour les tests, puis le jette. On teste donc contre le
**même moteur** qu'en production, et non une base en mémoire comme H2, qui ne gère pas le JSONB ni les index partiels
de la même façon. Conséquence : **Docker doit tourner** pour lancer les tests.

## 14.5 Tester un WebSocket

Les tests WebSocket démarrent le serveur sur un port aléatoire et s'y connectent avec un vrai client :

```java
// websocket/PhantasmonWebSocketIntegrationTest.java (raccourci)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "phantasmon.logging.enabled=false")
class PhantasmonWebSocketIntegrationTest {
    @LocalServerPort private int port;

    private static class RecordingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();      // les messages reçus, dans l'ordre
        protected void handleTextMessage(WebSocketSession session, TextMessage message) { received.add(message.getPayload()); }
    }

    @Test
    void heartbeatReceivesAck() throws Exception {
        … // joueur + jeton
        WebSocketSession session = new StandardWebSocketClient()
                .execute(handler, "ws://localhost:" + port + "/ws?token=" + token).get(5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage("""
                {"type":"Heartbeat","data":{}}
                """));
        assertThat(handler.received.poll(5, TimeUnit.SECONDS)).contains("HeartbeatAck");   // attendre au plus 5 s
    }
}
```

Le serveur répond de façon **asynchrone** : on ne peut pas lire la réponse « tout de suite ». La file bloquante
(`poll` avec délai) attend l'arrivée du message sans figer le test indéfiniment.

## 14.6 Lancer et lire les résultats

```bash
./gradlew test
./gradlew test --tests "com.mystaria.phantasmon_backend.trade.TradeControllerTest"
```

Rapport : `build/reports/tests/test/index.html`. La CI GitHub (`.github/workflows/build.yml`) relance toute la suite
à chaque push.

## 14.7 Pièges rencontrés

| Piège | Solution |
|---|---|
| Chaque test Spring créait un fichier dans `log/` | `@SpringBootTest(properties = "phantasmon.logging.enabled=false")` |
| Un `src/test/resources/application.properties` **remplace** celui de `main` (pas de fusion) et casse `JWT_SECRET` | Ne pas en créer ; surcharger par `properties = …` |
| `jsonPath(...).doesNotExist()` sur une projection filtrée échoue à tort | Vérifier les valeurs attendues plutôt que l'absence |

## À retenir

- Test d'abord, vu en échec, puis code.
- Tests unitaires sans Spring quand les dépendances sont injectées ; tests d'intégration avec l'application entière.
- Testcontainers = vraie base, jetable ; Docker requis.
- Pour l'asynchrone, attendre avec un délai borné.
