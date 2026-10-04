# Bugs connus et TODO

> **Document miroir** : identique dans `Phantasmon-Backend/Documentation/project/` et
> `Phantasmon-Client/Documentation/project/`. Toute modification doit être reportée dans les deux.
>
> Mis à jour le 2026-10-03. Une entrée résolue n'est pas supprimée : elle passe dans §5 avec la date de résolution.

Identifiants : `BUG-n` (comportement incorrect), `TODO-n` (action à faire), `DEBT-n` (dette technique),
`LIM-n` (limite connue, assumée pour l'instant). Priorité : **haute** (bloque une publication), **moyenne**,
**basse**.

## 1. Bugs connus

| ID | Priorité | Dépôt | Description | Piste |
|---|---|---|---|---|
| — | — | — | Aucun bug connu ouvert (BUG-1 à BUG-5 corrigés le 2026-10-03, voir §5). | — |

## 2. TODO

| ID | Priorité | Dépôt | Action |
|---|---|---|---|
| TODO-2 | haute | Client | Retirer `/phantasmon debug fingerprint` quand un vrai serveur dédié remplacera les tests « Ouvrir au LAN » (décision D-18). |
| TODO-3 | haute | Client | Remplacer les métadonnées factices : `homepage` / `sources` de `fabric.mod.json` (`github.com/your-account/…`), lien de mise à jour `https://modrinth.com/mod/phantasmon` (`AuthService`). |
| TODO-7 | moyenne | Machine serveur | Rétablir l'accès SSH (`production-server`) : les déploiements du client retombent sur l'instance locale « Cobblemon 2 ». |
| TODO-13 | moyenne | Client | Les Ghost doivent être connus d'office des joueurs : dans Cobblemon, un Pokémon non scanné au Pokédex affiche « ???? » à la place de son nom. |
| TODO-15 | haute | Les deux | Faire un audit de sécurité (client et backend). |
| TODO-16 | haute | Machine qui héberge la base | Planifier `scripts/backup-database.ps1` (tâche Windows quotidienne, `guides/deployment.md` §3.2) vers un autre disque. |

## 3. Dette technique

| ID | Dépôt | Description |
|---|---|---|
| DEBT-1 | Les deux | Les clés de `pokemon.data` `heldItem`, `teraType`, `friendship` sont en camelCase alors que le reste de l'API est en snake_case (les clés de `Map` échappent aux stratégies de nommage Jackson/Gson). Fonctionne ; tout renommage exige une migration des données existantes. |
| DEBT-2 | Backend | Une requête refusée par la validation Bean (400) renvoie le format d'erreur de Spring, pas un `error_code` structuré. |
| DEBT-3 | Backend | Pas de révocation des refresh tokens (décision D-16). |
| DEBT-4 | Backend | Idempotence non protégée contre deux requêtes identiques **simultanées** (vérifier puis enregistrer). |
| DEBT-5 | Client | `cobblemon_data_version` est enregistré (`1.8.1`, constante) mais jamais comparé à la version locale de Cobblemon. |

## 4. Limites connues (assumées)

| ID | Description |
|---|---|
| LIM-1 | Un client hôte modifié peut fausser le résultat d'un combat (CAD Partie 2 §9.2, décision D-05). |
| LIM-2 | Sur un client pur, seules les action effects des jars de mods sont chargées, pas celles des datapacks du monde. |
| LIM-3 | Cobblemon 1.8.1 ne contient ni Méga-Gemmes, ni Cristaux Z, ni Energy Booster ; le sélecteur d'objets les proposera automatiquement si Cobblemon les ajoute. |
| LIM-4 | Le Ghost n'a pas de pathfinding, traverse son propriétaire, et peut apparaître à des positions légèrement différentes selon les clients. |
| LIM-5 | Instance backend unique : présence, échanges en direct et combats en cours sont en mémoire et perdus au redémarrage. |
| LIM-6 | Derrière un proxy (Velocity/BungeeCord), deux serveurs partageant la même adresse seraient regroupés (empreinte de serveur). |
| LIM-7 | L'échange asynchrone par commandes exige l'UUID Mojang de l'autre joueur (l'échange en direct évite ce problème). |
| LIM-8 | Pas d'archivage WAL : la restauration revient à la dernière sauvegarde (jusqu'à 24 h de pertes avec une sauvegarde quotidienne). |

## 5. Résolu

| ID | Date | Résolution |
|---|---|---|
| — | 2026-10-03 | Licence : **GPL 3.0** retenue par Adrien. Documentation et README alignés. |
| BUG-1 | 2026-10-03 | `fabric.mod.json` : licence passée de `CC0-1.0` à `GPL-3.0-only`. |
| BUG-2 | 2026-10-03 | `fabric.mod.json` : `"cobblemon": ">=1.8.1"` ajouté dans `depends` (Fabric Loader affiche un message clair si Cobblemon manque). |
| BUG-3 | 2026-10-03 | Indicateur `[Ghost]` : le Ghost sorti porte le surnom `[Ghost] <surnom ou espèce>`, affiché par l'étiquette native de Cobblemon (avec le niveau) quand on le regarde. `GhostEntitySpawn` transporte `nickname`. |
| BUG-4 | 2026-10-03 | Confirmé par test. Le balayage TTL ne retire plus la présence lui-même (`PresenceService.findExpired`) : `PhantasmonWebSocketHandler.expire` quitte le groupe comme un `LeaveServerGroup` (le Ghost disparaît chez les autres) puis ferme la session. Test `PresenceTtlSweepIntegrationTest`. |
| BUG-5 | 2026-10-03 | Confirmé par test, cause différente du soupçon : pas d'échange partiel (le `@Transactional` de `transferOwnership` rendait la transaction rollback-only), mais une erreur 500 (`UnexpectedRollbackException`) au lieu de `ERROR_POKEMON_PC_FULL`. `TradeService.requirePcRoom` vérifie la place des deux côtés avant tout transfert ; l'échange reste `PENDING`. Test `acceptWithTheInitiatorsPcFullFailsCleanlyAndChangesNothing`. |
| TODO-1 | 2026-10-03 | URL du backend configurable : `backend_url` dans `config/phantasmon.json` (créé au premier lancement, défaut = machine de dev, lu au lancement). Test `BackendUrlFileTest`. |
| TODO-4 | 2026-10-03 | Version unique **0.1.0** : client (`gradle.properties`), backend (`build.gradle`, jar `phantasmon-backend-0.1.0.jar`), `phantasmon.version.current` / `min-supported`. 1.0.0 est réservé à la première publication. |
| TODO-5 | 2026-10-03 | Sauvegardes : `scripts/backup-database.ps1` (base native ou conteneur Docker, détection automatique ; rotation 14 jours / hebdomadaire 92 jours) et `scripts/test-restore.ps1` (restauration dans un conteneur jetable), testés sur les deux sources. Reste la planification (TODO-16) ; WAL non fait (LIM-8). |
| TODO-6 | 2026-10-03 | PostgreSQL natif de la machine de dev : `listen_addresses = 'localhost'` (fait par Adrien, copie `postgresql.conf.bak-2026-10-03`). Vérifié : écoute sur `127.0.0.1` et `::1` seulement, port 5432 injoignable via Tailscale, backend `/health` UP. |
| TODO-8 | 2026-10-03 | Commentaires de code et de configuration pointent vers les nouveaux documents (19 références, sections vérifiées). Seule exception, volontaire : `V7__trades_pokemon_history_without_fk.sql` garde `PHANTASMON_DB_SCHEMA.md §6` (migration appliquée, somme de contrôle Flyway) ; c'est `reference/database-schema.md` §6. |
| TODO-9 | 2026-10-03 | Javadocs corrigées : `ClientCommonPacketListenerImplMixin` (premier des 7 Mixins), `PhantasmonKeybinds` (4 touches : P, O, G, B), `BackendHealthPinger` (URL de `config/phantasmon.json`), `RequestLoggingFilter` / `LogRetentionService` (`SessionLogFileEnvironmentPostProcessor`, pas de `logback-spring.xml`), `PhantasmonWebSocketHandler` (messages `Battle*` routés vers `LiveBattleService`). En plus : `&` brut dans `PokemonUpdateRequest` qui faisait échouer `./gradlew javadoc`. |
| TODO-10 | 2026-10-03 | 20 fichiers supprimés de `textures/gui/sprites/pc/` (10 sprites nine-slice de l'ancien PC + leurs `.mcmeta`), aucune référence dans le code ; seul `star.png` reste (`PhantasmonCanvasScreen.SPRITE_STAR`). Récupérables par git. |
| TODO-11 | 2026-10-03 | Export Showdown côté client : boutons EXPORTER du PC (équipe) et de l'éditeur (Pokémon affiché), `/phantasmon pokemon export [uuid]` ; noms anglais de Cobblemon ; `Hidden Power [Type]` géré à l'export et à l'import. Aller-retour couvert par `ShowdownExporterTest`. |
| TODO-12 | 2026-10-04 | Formes spéciales en combat : `BattleVisuals` écrit les aspects reçus dans `PokemonEntity.ASPECTS` (comme les Ghost dans le monde) ; `GhostBattlePokemonFactory` force les aspects de forme en dernier, avec chromatique et sexe. |
| TODO-14 | 2026-10-04 | Au démarrage d'un combat Ghost, le backend rappelle les Ghost des deux joueurs (`GhostEntityDespawn` à leur groupe) ; jusqu'à la fin, `SendOutGhost` répond `ERROR_GHOST_IN_BATTLE` (vérifié sous le verrou du combat). Rappel factorisé dans `GhostRecall` (aussi utilisé par `RecallGhost` et l'échange en direct). Tests `startingABattleRecallsBothPlayersGhosts`, `noGhostCanBeSentOutWhileTheBattleLasts`. |
