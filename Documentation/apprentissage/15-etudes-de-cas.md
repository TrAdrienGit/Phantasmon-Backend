# 15. Études de cas : bugs réels

Chaque cas suit le même plan : **symptôme**, **enquête**, **cause**, **correction**, **leçon**. Ils viennent tous de
l'historique réel du projet (septembre-octobre 2026). Les deux derniers sont des bugs **suspectés** à la relecture du
code, présentés comme des analyses.

---

## Cas 1 — Les migrations qui ne s'exécutaient pas

- **Symptôme** : les tables n'existent pas en base. Aucune erreur, aucun message de Flyway dans les logs.
- **Enquête** : `flyway-core` et `flyway-database-postgresql` sont bien dans `build.gradle`. Aucune trace de Flyway
  au démarrage, même pas « no migration necessary ».
- **Cause** : Spring Boot 4 a sorti l'auto-configuration de Flyway dans un module séparé,
  `spring-boot-flyway`. Sans lui, la bibliothèque Flyway est présente mais personne ne l'appelle.
- **Correction** : `implementation 'org.springframework.boot:spring-boot-flyway'`. Même problème rencontré ensuite
  avec `spring-boot-restclient` (appel à Mojang).
- **Leçon** : une auto-configuration absente ne produit pas d'erreur. Quand une fonctionnalité Spring est muette,
  vérifier la présence du module d'intégration, et chercher dans les logs le message qu'elle **devrait** produire.

## Cas 2 — `NullPointerException` dans un message WebSocket

- **Symptôme** : en écrivant le test « sortir un Ghost juste après la connexion », le serveur plante.
- **Cause** : `Map.of("position", position, …)` avec `position == null` (le joueur n'a encore envoyé aucune
  position). `Map.of` **refuse** les valeurs `null`.
- **Correction** : construire une `HashMap` explicitement (`ghostSpawnData`), qui accepte `null`.
- **Leçon** : les fabriques immuables de Java (`Map.of`, `List.of`) rejettent `null`. Le TDD a trouvé le bug avant
  la mise en production.

## Cas 3 — « La commande ne fait rien » : le joueur ne voit pas son propre Ghost

- **Symptôme** : en test solo, `/phantasmon sendout` ne produit rien, ni Ghost ni message.
- **Enquête** : le serveur diffuse bien `GhostEntitySpawn`… aux autres membres du groupe.
- **Cause** : `PresenceService.groupMembers(joueur)` renvoie **les autres** joueurs du groupe. C'était correct pour
  son usage d'origine (« qui d'autre est ici ? »), mais réutilisé pour diffuser les Ghost, il excluait le
  propriétaire.
- **Correction** : `broadcastToGroupAndSelf` (groupe + soi), utilisé partout où le propriétaire doit aussi voir.
- **Leçon** : quand on réutilise une méthode existante pour un nouvel usage, vérifier ce qu'elle **exclut**, pas
  seulement ce qu'elle renvoie. Et un test solo qui montre « rien » ne distingue pas un bug d'un oubli de diffusion.

## Cas 4 — « broadcasting to 0 group member(s) » : le groupe jamais rejoint

- **Symptôme** : toujours pas de Ghost. Le log du serveur montre `WebSocket connected`, puis `sent out Ghost …
  broadcasting to 0 group member(s) + self`, mais **jamais** `joined group`.
- **Enquête** : l'absence d'une ligne de log est un indice aussi fort que sa présence. `JoinServerGroup` n'arrive
  donc jamais. Côté serveur, `PresenceService.sendOutGhost` et `updatePosition` utilisent `computeIfPresent` : sans
  présence, ils **ne font rien, en silence**. Le Ghost part donc avec une position nulle, que le client ignore.
- **Cause** (côté client) : une même variable servait à deux usages (détecter un changement de dimension, et savoir
  si `JoinServerGroup` avait été envoyé). Mise à jour 20 fois par seconde par le premier usage, elle n'était jamais
  vide quand le second la testait.
- **Correction** : un drapeau dédié côté client. Voir le parcours du dépôt Client.
- **Leçons** : des logs aux étapes clés (« joined group ») rendent le diagnostic immédiat ; un `computeIfPresent`
  silencieux peut masquer une erreur en amont ; ce bug n'était détectable par aucun test serveur, car les tests
  envoient toujours `JoinServerGroup` eux-mêmes.

## Cas 5 — Les deux joueurs dans des groupes différents

- **Symptôme** : premier test à deux comptes, en « Ouvrir au LAN ». Chacun voit son Ghost, pas celui de l'autre.
  Log : `broadcasting to 0 group member(s)`.
- **Cause** : l'empreinte du serveur Minecraft est calculée par le client : `"singleplayer"` pour celui qui héberge
  la partie LAN, un hash de l'adresse pour celui qui la rejoint. Les deux valeurs ne peuvent pas coïncider.
- **Correction** : pas de changement serveur ; une commande de test côté client pour forcer la même empreinte. Sur
  un vrai serveur dédié, tous les joueurs calculent la même valeur.
- **Leçon** : un environnement de test différent de la production peut produire des bugs qui n'existent pas en
  production. Les identifier comme tels évite de « corriger » ce qui n'est pas cassé.

## Cas 6 — Échanger deux Pokémon de place viole l'index unique

- **Symptôme** : en écrivant le test « déplacer un membre d'équipe sur un emplacement occupé », erreur de contrainte
  `uq_pokemon_team_slot`.
- **Cause** : l'ordre naïf « l'occupant prend l'ancienne place de A, puis A prend la nouvelle » écrit l'occupant à
  une place que **A occupe encore en base** (son changement n'est pas encore envoyé).
- **Correction** : vider la place de A et la flusher, **puis** donner l'ancienne place à l'occupant, **puis**
  donner la nouvelle à A (chapitre 9.5).
- **Leçon** : une contrainte d'unicité est vérifiée à chaque instruction SQL, pas seulement à la fin. Pour échanger
  deux valeurs uniques : libérer, écrire, occuper.

## Cas 7 — Impossible de supprimer un Pokémon déjà échangé

- **Symptôme** : `DELETE /pokemon/{uuid}` renvoie une erreur 500 pour un Pokémon échangé il y a longtemps.
- **Cause** : la table `trades` avait des clés étrangères `ON DELETE RESTRICT` vers `pokemon`, pensées pour protéger
  les échanges **en attente**. Elles bloquaient aussi la suppression après un échange terminé, et chaque échange en
  direct crée une ligne `COMPLETED`.
- **Correction** : migration `V7` qui retire ces deux clés étrangères (retrouvées dynamiquement dans
  `pg_constraint`), et une vérification applicative ciblée : `ERROR_POKEMON_IN_PENDING_TRADE` seulement pour un
  échange `PENDING`.
- **Leçon** : une contrainte de base exprime une règle **permanente**. Une règle qui dépend d'un état (« seulement
  si en attente ») appartient au code. Et une historique doit survivre à la suppression de ce qu'elle référence.

## Cas 8 — `TEXT_PARTIAL_WRITING`

- **Symptôme** : pendant un échange en direct, une erreur `IllegalStateException: TEXT_PARTIAL_WRITING` coupe une
  connexion.
- **Cause** : les messages d'Alice et de Bob sont traités sur deux threads ; tous deux envoient au même moment une
  mise à jour à la même session. Une session WebSocket du serveur web ne supporte pas deux écritures simultanées.
- **Correction** : toutes les écritures passent par `SessionRegistry.sendTo`, qui synchronise sur la session.
- **Leçon** : un objet partagé entre threads doit être protégé, y compris une connexion réseau.

## Cas 9 — Des messages trop gros pour le WebSocket

- **Situation** : en construisant le relais de combat, on transporte des paquets Cobblemon encodés.
- **Risque identifié** : un paquet d'équipe (six Pokémon complets) dépasse la taille maximale par défaut d'un
  message WebSocket du serveur web (8 Kio) ; au-delà, le serveur ferme la connexion.
- **Correction** : `session.setTextMessageSizeLimit(1024 * 1024)` dans `afterConnectionEstablished`.
- **Leçon** : connaître les limites par défaut des composants (taille de message, délais) avant d'y faire passer
  des données d'une autre nature.

## Cas 10 — Le fichier de log qui ignore l'interrupteur

- **Symptôme** : un `logback-spring.xml` avec une condition sur `LOGGING_ENABLED` se comporte de façon imprévisible.
- **Cause** : Logback est initialisé en deux passes ; les propriétés Spring (`<springProperty>`) ne sont pas encore
  disponibles lors de l'évaluation de la condition.
- **Correction** : supprimer le XML et fixer `logging.file.name` dans un `EnvironmentPostProcessor` (chapitre 13).
- **Leçon** : préférer le mécanisme prévu par le framework (une propriété standard) à une configuration qui dépend
  d'un ordre d'initialisation interne.

## Cas 11 — Tous les tests cassés par un fichier de configuration de test

- **Symptôme** : après l'ajout de `src/test/resources/application.properties` (pour désactiver les logs en test),
  plus aucun test ne démarre : `Could not resolve placeholder 'phantasmon.jwt.secret'`.
- **Cause** : un fichier de même nom sur le classpath de test **remplace** celui de `main` au lieu de le compléter.
  Toutes les propriétés de `main` disparaissent.
- **Correction** : supprimer ce fichier ; `@SpringBootTest(properties = "phantasmon.logging.enabled=false")`.
- **Leçon** : comprendre comment les fichiers de ressources sont résolus avant d'en ajouter un.

## Cas 12 — Une donnée qui n'arrive jamais au bon client

- **Symptôme** : côté client, impossible d'afficher le Ghost d'un autre joueur : on ne connaît que son UUID.
- **Cause** (conception) : toutes les routes REST ne donnent accès qu'à **ses propres** Pokémon (sécurité). Le
  message `GhostEntitySpawn` était donc la seule source possible, et il ne contenait pas l'espèce.
- **Correction** : ajouter `species`, `form`, `is_shiny`, `level` (puis `gender`) au message.
- **Leçon** : en construisant client et serveur séparément, se demander pour chaque donnée affichée **par quel
  chemin elle arrive**, et si les règles de sécurité permettent ce chemin.

---

## Analyse A — Le Ghost d'un joueur expiré (bug suspecté BUG-4)

Lisez ensemble `PresenceTtlSweeper.sweep`, `PresenceService.cleanupExpired` et
`PhantasmonWebSocketHandler.afterConnectionClosed` :

1. `cleanupExpired()` **retire** les présences expirées de la `Map` et renvoie leurs UUID.
2. `sweep()` ferme ensuite la session de chacun.
3. La fermeture déclenche `afterConnectionClosed` → `leaveAndDespawnGhost`, qui commence par
   `groupMembers(joueur)` puis `leave(joueur)`.
4. Mais la présence a **déjà** été retirée à l'étape 1 : `groupMembers` renvoie une liste vide et `leave` ne renvoie
   rien. Aucun `GhostEntityDespawn` n'est envoyé aux autres joueurs.

L'ancienne référence d'API affirmait que le TTL « déclenche aussi le despawn » : c'était vrai dans l'intention, pas
dans l'ordre des opérations. Un test d'intégration (« un joueur qui cesse ses heartbeats voit son
Ghost disparaître chez les autres ») le confirmerait.

## Analyse B — L'échange à moitié fait (bug suspecté BUG-5)

Dans `TradeService.accept`, annoté `@Transactional(noRollbackFor = ApiException.class)` :

1. `transferOwnership(offert → destinataire)` réussit et place le Pokémon dans le PC du destinataire.
2. `transferOwnership(demandé → initiateur)` appelle `findFreePcSlot` ; si le PC de l'initiateur est plein, une
   `ApiException` (`ERROR_POKEMON_PC_FULL`) est levée.
3. Comme toute `ApiException` est exclue du rollback, la transaction est **validée** : le premier transfert est
   gardé, le second n'a pas eu lieu, l'échange reste `PENDING`.

L'annotation visait un seul cas (`ERROR_TRADE_OWNERSHIP_CHANGED`, pour conserver le statut `CANCELLED`). Pistes :
vérifier la place libre des deux côtés **avant** tout transfert, ou enregistrer le `CANCELLED` dans une transaction
séparée et laisser le rollback par défaut.

## Ce que ces cas ont en commun

- La majorité des bugs étaient **silencieux** : pas d'exception, juste « rien ne se passe ». Les logs des étapes
  clés et les tests d'intégration sont les meilleurs détecteurs.
- Plusieurs bugs venaient d'un **ordre d'opérations** (flush, retrait avant diffusion, initialisation en deux passes).
- Plusieurs venaient de la **réutilisation** d'un composant hors de son intention d'origine.
