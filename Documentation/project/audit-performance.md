# Audit de performance du backend (2026-11-26)

Branche `migration-oracle` (Phantasmon-Backend et Phantasmon-Events). Objectif : moins de RAM, moins de latence, avant de
passer sur une petite machine Oracle ([`../guides/deploying-oracle.md`](../guides/deploying-oracle.md)).

## Mesures de départ (server-linux, 2026-11-26)

- Processus Java : **643 Mo** de RSS, 52 threads, lancé avec `-Xms256m -Xmx2g`.
- Journaux : **307 Mo** dans `log/` ; dans le fichier du jour, **100 %** des lignes sont `GET /server/mirror/<hub>/feed`
  (le serveur Minecraft lit le flux de chaque hub miroir chaque seconde, 3 hubs).
- Un hub miroir chargé garde tout son terrain en mémoire **jusqu'à l'arrêt** : la Ligue Onyx (329×329 blocs) fait
  5 975 sections complètes, soit environ **49 Mo**.
- Toutes les écritures WebSocket passaient par un verrou par session : l'émetteur attendait que le message soit
  vraiment parti. Un joueur sur une mauvaise connexion bloquait donc le thread qui lui écrivait, et
  `LiveBattleService` envoie sous son verrou global : un combat ralenti ralentissait tous les autres.

## Ce qui est fait sur la branche

| # | Changement | Gain attendu | Où |
|---|---|---|---|
| 1 | Chaque session WebSocket est enveloppée dans un `ConcurrentWebSocketSessionDecorator` : un seul thread envoie, les autres mettent en file et repartent. Un client bloqué plus de 10 s ou avec 8 Mo en attente est déconnecté (son client se reconnecte). | Latence : plus de blocage en chaîne derrière un client lent (combats, hubs, voix). | `websocket/SessionRegistry` |
| 2 | Envoi d'un même message à plusieurs joueurs : sérialisé **une fois** (`sendAll`) au lieu d'une fois par destinataire. | CPU et déchets mémoire divisés par le nombre de joueurs sur les relais fréquents : mouvements dans les hubs, état vocal, spectateurs, fantômes, hubs miroirs. | `HubService`, `LiveBattleService`, `PhantasmonWebSocketHandler`, Events `MirrorService` |
| 3 | Message entrant lu en une passe (`readValue`) au lieu de `readTree` puis `treeToValue`. | Chaque message WebSocket n'est plus analysé deux fois. | `PhantasmonWebSocketHandler` |
| 4 | Terrain d'un hub miroir **retiré de la mémoire** après 10 min sans usage (enregistré, personne ne copie ni ne regarde la zone) ; relu depuis `mirror.bin` à la demande. La sauvegarde toutes les 10 s ne fait plus de requête SQL pour les terrains déjà enregistrés. Un échec d'écriture garde le terrain « à enregistrer » (avant : perdu jusqu'à la prochaine modification). | Environ 50 Mo par grand hub miroir rendus hors usage ; 3 requêtes SQL de moins toutes les 10 s. | Events `MirrorService` |
| 5 | Les sondages réussis et rapides (`/server/mirror/*/feed`, `/health`) sont journalisés en DEBUG ; un échec ou une réponse de plus de 500 ms reste en INFO. | Journaux réduits d'environ 99 % ; moins d'écritures disque. | `logging/RequestLoggingFilter` |
| 6 | Tomcat : 64 threads au plus (200 par défaut). PostgreSQL : 8 connexions au plus, 2 au repos (10 par défaut). Hibernate : cache des plans de requêtes à 256 (2 048), listes `IN` arrondies à une puissance de deux, écritures groupées par 32. | Moins de piles de threads et de processus PostgreSQL ; cache plus petit. | `application.properties` |
| 7 | Compression HTTP des réponses JSON et des pages web de plus de 2 Ko (les fichiers de construction, déjà en gzip, sont exclus). | Catalogue des hubs et pages ligue et tournois plus rapides hors réseau local. | `application.properties` |
| 8 | JVM : tas de 1 Go au plus (avant 2 Go), départ à 128 Mo ; le tas inutilisé est rendu au système après une minute d'inactivité (`G1PeriodicGCInterval`) ; chaînes en double fusionnées ; piles de threads de 512 Ko ; arrêt sur `OutOfMemoryError` pour que systemd relance. Réglable par serveur avec `systemctl edit` (`JAVA_OPTS`). | RSS au repos nettement plus bas qu'aujourd'hui ; plafond maîtrisé. | `scripts/ubuntu/phantasmon-backend.service` |
| 9 | En-têtes `X-Forwarded-*` du proxy pris en compte (Caddy ou Funnel). | Adresses et schéma corrects derrière le proxy. | `scripts/ubuntu/phantasmon-backend.service` |

Les gains ne sont **pas encore mesurés en production** : ils le seront au premier déploiement (RSS avec
`grep VmRSS /proc/<pid>/status`, taille des journaux).

## Proposé, pas fait (à décider)

1. **Index par groupe dans `PresenceService` et par hub dans `HubService`.** Chaque mouvement parcourt tous les joueurs
   connectés (`groupMembers`, `visibleTo`) : O(n) par mouvement, O(n²) au total. Invisible à 20 joueurs, sensible
   au-delà d'une centaine.
2. **Verrou par combat dans `LiveBattleService`.** Toutes ses méthodes sont `synchronized` sur le service : deux combats
   sans lien s'attendent, et `refreshAllViewers` prend ce verrou chaque seconde. Avec le point 1 de la section
   précédente, ce verrou ne bloque plus sur le réseau, mais reste unique.
3. **Flux des hubs miroirs.** Le serveur Minecraft fait 3 requêtes par seconde, chacune avec une recherche du hub en base
   par son nom. Après la migration, ces requêtes traversent Internet : un cache du hub par nom, ou un passage du flux en
   WebSocket ou en long polling, réduirait la latence et la charge.
4. **Java 25 à l'exécution.** Le code reste en Java 21, mais une JVM 25 apporte les en-têtes d'objets compacts
   (`-XX:+UseCompactObjectHeaders`, environ 10 à 20 % de tas en moins) et les threads virtuels sans le problème des blocs
   `synchronized` de Java 21.
5. **Démarrage plus rapide** avec une archive CDS (Spring Boot sait la produire) ; à tester avec le chargement des
   add-ons par `PropertiesLauncher`.
6. **Rétention des journaux** : 5 Go aujourd'hui (`phantasmon.logging.max-total-bytes`) ; 1 Go suffirait sur la machine
   Oracle.
7. **Sauvegardes hors de la machine** : copie quotidienne des dumps vers `server-linux` ou un stockage objet Oracle.
