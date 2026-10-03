# Configuration du backend

> Source : `src/main/resources/application.properties`, `.env.template`, `docker-compose.yml`.
> Vérifié le 2026-10-03. Toute nouvelle propriété ou variable met à jour ce fichier et
> [`guides/running.md`](../guides/running.md).

## 1. Variables d'environnement

Lues depuis l'environnement système, ou depuis un fichier `.env` à la racine du projet en développement
(copié de `.env.template`, jamais commité).

| Variable | Obligatoire | Défaut | Rôle |
|---|---|---|---|
| `BDD_HOST` | non | `localhost` | Hôte PostgreSQL |
| `BDD_PORT` | non | `5432` | Port PostgreSQL (`5433` pour le conteneur Docker) |
| `BDD_NAME` | non | `db_phantasmon` | Nom de la base |
| `BDD_USER` | **oui** | — | Rôle PostgreSQL applicatif |
| `BDD_PASSWORD` | **oui** | — | Mot de passe du rôle |
| `JWT_SECRET` | **oui** | — | Clé HMAC-SHA256 des JWT. Générer par exemple avec `openssl rand -base64 64`. Sans elle, le démarrage échoue. |
| `LOGGING_ENABLED` | non | `true` | Un fichier de log par démarrage dans `log/` |
| `BDD_DOCKER_PORT` | non | `5433` | Utilisée **uniquement** par `docker-compose.yml` (port publié sur `127.0.0.1`) |

`JWT_SECRET` et `BDD_PASSWORD` sont des secrets : ne jamais les commiter, les afficher ou les journaliser.

## 2. Propriétés applicatives

| Propriété | Valeur | Rôle |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://${BDD_HOST}:${BDD_PORT}/${BDD_NAME}` | Connexion |
| `spring.jpa.hibernate.ddl-auto` | `none` | Le schéma est géré uniquement par Flyway |
| `spring.jpa.open-in-view` | `false` | |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | |
| `spring.jackson.property-naming-strategy` | `SNAKE_CASE` | Noms de champs JSON de toute l'API |
| `phantasmon.jwt.secret` | `${JWT_SECRET}` | |
| `phantasmon.jwt.access-ttl` | `PT20M` | Durée de vie de l'access token (`expires_in` = 1200) |
| `phantasmon.jwt.refresh-ttl` | `P7D` | Durée de vie du refresh token |
| `phantasmon.logging.enabled` | `${LOGGING_ENABLED:true}` | |
| `phantasmon.logging.directory` | `log` | Relatif au répertoire de lancement |
| `phantasmon.logging.max-total-bytes` | `5368709120` (5 Gio) | Plafond du dossier de logs |
| `phantasmon.version.current` | `0.1.0` | Renvoyée par `GET /version` |
| `phantasmon.version.min-supported` | `0.1.0` | En dessous, le client refuse de se connecter |
| `phantasmon.presence.ttl` | `PT30S` | Délai sans heartbeat avant retrait de la présence |
| `phantasmon.presence.sweep-interval-ms` | `10000` | Période du balayage TTL |
| `phantasmon.trade.invite-ttl` | `PT60S` | Expiration d'une invitation d'échange |
| `phantasmon.battle.invite-ttl` | `PT60S` | Expiration d'une invitation de combat |

Constantes du code (non configurables) : chrono de combat 90 s (`LiveBattleService.TIMER_SECONDS`), taille
maximale d'un message WebSocket 1 Mio, 16 boîtes × 30 cases, équipe de 6, rétention des logs vérifiée toutes les
heures. Le port HTTP est celui de Spring Boot par défaut (8080), modifiable avec `server.port`.

## 3. Surcharger une propriété

Toute propriété peut être surchargée au lancement, sans modifier le fichier :

```bash
java -jar build/libs/phantasmon-backend-0.1.0.jar --phantasmon.jwt.access-ttl=PT3M --server.port=8081
```

Exemples utiles pour les tests manuels : raccourcir `phantasmon.jwt.access-ttl` (garder au moins `PT3M`, le client
vérifie toutes les 60 s avec une marge de 30 s) pour voir le refresh automatique ; monter
`phantasmon.version.min-supported` au-dessus de la version du client pour tester le refus de version.

## 4. Fichiers de configuration

| Fichier | Suivi par git | Rôle |
|---|---|---|
| `.env.template` | oui | Modèle des variables, sans valeurs secrètes |
| `.env` | **non** | Valeurs locales, chargé par `DotenvEnvironmentPostProcessor` si présent dans le répertoire de lancement |
| `docker-compose.yml` | oui | PostgreSQL 18 en conteneur, lit le même `.env` |
| `src/main/resources/application.properties` | oui | Propriétés ci-dessus |
| `.github/workflows/build.yml` | oui | CI : JDK 21 Temurin, build, tests avec `JWT_SECRET` factice, jar en artefact |
