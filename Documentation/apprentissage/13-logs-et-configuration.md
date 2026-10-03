# 13. Logs, configuration et environnement

## 13.1 Pourquoi journaliser

Un serveur tourne sans écran. Quand un joueur dit « mon Ghost n'apparaît pas », les **logs** sont la seule trace de
ce qui s'est passé. Dans ce projet, ils ont permis de trouver plusieurs bugs (chapitre 15) grâce à des lignes comme :

```text
POST /pokemon -> 201 (34 ms)
WebSocket connected: player b1a4…
Player b1a4… joined group fingerprint=… dimension=minecraft:overworld
Player b1a4… sent out Ghost 9f8e… (samurott) — broadcasting to 1 group member(s) + self
```

## 13.2 SLF4J et Logback

On écrit dans les logs via **SLF4J** (une interface) ; **Logback** (l'implémentation fournie par Spring Boot) décide
où et comment écrire. Avec Lombok, `@Slf4j` crée le champ `log` :

```java
log.info("Pokemon {} ({}) created for owner {}", request.species(), pokemon.getUuid(), ownerUuid);
log.warn("Mojang verification failed for username={}", request.username());
log.error("Live trade session {} failed at completion", session.uuid(), ex);   // dernier argument : la pile d'erreur
```

Les `{}` sont remplacés par les arguments (plus efficace et plus sûr que la concaténation). Niveaux : `debug` <
`info` < `warn` < `error`. Le projet affiche `info` et plus.

Règle : jamais de secret (jeton, mot de passe) dans un log.

## 13.3 Un fichier par démarrage

Chaque démarrage écrit dans son propre fichier `log/Log-Phantasmon-Backend_<date>_<heure>.txt`. Plutôt qu'un
fichier de configuration Logback, le projet fixe la propriété standard `logging.file.name` très tôt, dans un
`EnvironmentPostProcessor` (voir chapitre 5) :

```java
// logging/SessionLogFileEnvironmentPostProcessor.java (raccourci)
boolean enabled = environment.getProperty("phantasmon.logging.enabled", Boolean.class,
        environment.getProperty("LOGGING_ENABLED", Boolean.class, true));
if (!enabled) return;                                  // pas de propriété → Spring n'écrit que dans la console
String fileName = directory + "/Log-Phantasmon-Backend_" + SESSION_TIMESTAMP.format(LocalDateTime.now()) + ".txt";
environment.getPropertySources().addLast(new MapPropertySource("phantasmonSessionLogFile",
        Map.of("logging.file.name", fileName, …)));
```

Spring Boot n'ajoute une sortie fichier **que si** `logging.file.name` est défini : l'interrupteur
`LOGGING_ENABLED` est obtenu gratuitement. Une première tentative avec un `logback-spring.xml` conditionnel a échoué :
les propriétés Spring n'y sont pas encore résolues assez tôt (chapitre 15).

## 13.4 Limiter la taille : la rétention

```java
// logging/LogRetentionService.java (raccourci)
@EventListener(ApplicationReadyEvent.class) public void onStartup() { enforceRetention(); }
@Scheduled(fixedRate = 60 * 60 * 1000L)        public void scheduledCheck() { enforceRetention(); }

void enforceRetention() {
    // liste les fichiers Log-Phantasmon-Backend_*.txt, du plus ancien au plus récent,
    // supprime les plus anciens tant que le total dépasse 5 Gio
}
```

Seuls les fichiers au nom attendu sont supprimés : on ne touche jamais à un fichier inconnu.

## 13.5 Les variables d'environnement

La configuration qui change selon la machine (adresse de la base, secrets) vient de **variables d'environnement**,
lues par `application.properties` (`${BDD_HOST:localhost}`). Avantages : le même jar tourne partout, et aucun
secret n'est dans le code.

| Variable | Rôle |
|---|---|
| `BDD_HOST`, `BDD_PORT`, `BDD_NAME`, `BDD_USER`, `BDD_PASSWORD` | Connexion PostgreSQL |
| `JWT_SECRET` | Clé de signature des jetons |
| `LOGGING_ENABLED` | Fichier de log oui / non |

En développement, définir ces variables à chaque session serait pénible : un fichier `.env` (ignoré par git) est lu
au démarrage (`DotenvEnvironmentPostProcessor`). `.env.template` (commité) liste les clés sans les valeurs. En
production, on définit de vraies variables : le jar a été testé lancé **sans** `.env`.

## 13.6 PostgreSQL avec Docker Compose

**Docker** exécute des programmes dans des **conteneurs** isolés, à partir d'**images** prêtes à l'emploi
(`postgres:18`). **Docker Compose** décrit un ou plusieurs conteneurs dans un fichier YAML :

```yaml
# docker-compose.yml (raccourci)
services:
  postgres:
    image: postgres:18
    environment:
      POSTGRES_USER: ${BDD_USER:?BDD_USER missing from .env}     # lit le même .env que le backend
      POSTGRES_PASSWORD: ${BDD_PASSWORD:?BDD_PASSWORD missing from .env}
      POSTGRES_DB: ${BDD_NAME:-db_phantasmon}
    ports:
      - "127.0.0.1:${BDD_DOCKER_PORT:-5433}:5432"               # accessible seulement depuis cette machine
    volumes:
      - phantasmon-pgdata:/var/lib/postgresql                    # les données survivent à l'arrêt du conteneur
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
volumes:
  phantasmon-pgdata:
```

```bash
docker compose up -d     # démarre en arrière-plan
docker compose ps        # état ("healthy")
docker compose down      # arrête (données conservées dans le volume)
```

Points de sécurité : le port est publié sur `127.0.0.1` (pas sur le réseau), et sur 5433 pour ne pas entrer en
conflit avec une installation native sur 5432.

## À retenir

- Des logs précis (qui, quoi, combien) sont l'outil principal de diagnostic d'un serveur.
- SLF4J pour écrire, Logback pour la destination ; jamais de secret dans un log.
- Configuration par variables d'environnement ; `.env` seulement pour le confort en développement.
- Docker Compose donne une base locale reproductible, isolée et limitée à la machine.
