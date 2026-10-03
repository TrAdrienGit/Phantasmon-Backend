# Apprentissage — construire un backend comme celui de Phantasmon

Ce parcours explique **tout le backend Phantasmon** à un développeur qui sait programmer mais découvre Java
« côté serveur », Spring Boot, les bases de données, le réseau et la sécurité. À la fin, vous devez être capable
de comprendre chaque fichier du dépôt et de construire un service similaire.

Le mod Minecraft (côté joueur) a son propre parcours dans le dépôt Client :
`Phantasmon-Client/Documentation/apprentissage/`. Commencez par celui-ci : le client n'a de sens que si l'on sait
à qui il parle.

## Ce que le parcours suppose

- Vous savez ce qu'est une variable, une fonction, une boucle, une classe, un objet : ce n'est pas réexpliqué.
- Vous connaissez peu Java : ses particularités utilisées ici sont expliquées au chapitre 2.
- Vous n'avez jamais utilisé Spring, Gradle, PostgreSQL, WebSocket ni JWT.

## Comment lire

Les chapitres se lisent **dans l'ordre** : chacun s'appuie sur les précédents. Chaque chapitre :

1. explique le concept général (ce qu'on trouverait dans n'importe quel projet) ;
2. montre **le vrai code** de Phantasmon, avec le chemin du fichier pour l'ouvrir à côté ;
3. se termine par « À retenir ».

Les extraits sont parfois raccourcis (imports et commentaires retirés, `…` pour une partie omise). Le code complet
fait foi. Les chemins sont relatifs à `src/main/java/com/mystaria/phantasmon_backend/` sauf mention contraire.

## Sommaire

| N° | Chapitre | Vous apprendrez |
|---|---|---|
| 1 | [Vue d'ensemble](01-vue-d-ensemble.md) | Ce que fait le système, pourquoi un backend, comment lire le dépôt |
| 2 | [Le Java utile pour ce projet](02-java-utile.md) | Records, annotations, génériques, `Optional`, lambdas, streams, exceptions, concurrence, Lombok |
| 3 | [Réseau : HTTP, REST, JSON](03-reseau-http-rest-json.md) | Requêtes, réponses, codes de statut, en-têtes, conception d'une API |
| 4 | [Gradle](04-gradle.md) | Construire, tester, lancer ; lire `build.gradle` |
| 5 | [Les bases de Spring Boot](05-spring-boot-bases.md) | Conteneur, beans, injection, configuration, démarrage |
| 6 | [Le pipeline d'une requête REST](06-pipeline-requete-rest.md) | Filtres, sécurité, contrôleur, validation, JSON, erreurs |
| 7 | [La base de données](07-base-de-donnees.md) | PostgreSQL, JSONB, Flyway, JPA, dépôts, transactions |
| 8 | [Sécurité et authentification](08-securite-authentification.md) | Preuve Mojang, JWT, Spring Security, propriété, secrets |
| 9 | [Le domaine Pokémon](09-domaine-pokemon.md) | Un service métier complet : création, légalité, idempotence, déplacements |
| 10 | [WebSocket et présence](10-websocket-et-presence.md) | Temps réel, handshake, dispatch, diffusion, heartbeat, TTL |
| 11 | [Les échanges](11-echanges.md) | Machine à états en mémoire, transaction finale, concurrence |
| 12 | [Les combats](12-combats.md) | Relais, désignation de l'hôte, chrono, fin de combat |
| 13 | [Logs, configuration, environnement](13-logs-et-configuration.md) | Journalisation, `.env`, variables, Docker Compose |
| 14 | [Les tests](14-tests.md) | TDD, JUnit, MockMvc, Testcontainers, tests WebSocket |
| 15 | [Études de cas : bugs réels](15-etudes-de-cas.md) | Comment on a trouvé et corrigé de vrais bugs |
| 16 | [Glossaire](16-glossaire.md) | Tous les termes en un endroit |

## Pour aller plus loin

La documentation de référence (contrats exacts, schéma, configuration) est dans les dossiers voisins :
`../reference/`, `../architecture/`, `../guides/`. Ce parcours explique **pourquoi et comment** ; la référence dit
**exactement quoi**.
