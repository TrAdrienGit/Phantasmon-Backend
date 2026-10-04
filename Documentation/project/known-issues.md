# Bugs connus et TODO

> **Document miroir** : identique dans `Phantasmon-Backend/Documentation/project/` et
> `Phantasmon-Client/Documentation/project/`. Toute modification doit être reportée dans les deux.
>
> Mis à jour le 2026-10-03. Une entrée résolue n'est pas supprimée : elle passe dans §5 avec la date de résolution.

Identifiants : `BUG-n` (comportement incorrect), `SEC-n` (sécurité, voir [`security-audit.md`](security-audit.md)), `TODO-n` (action à faire), `DEBT-n` (dette technique),
`LIM-n` (limite connue, assumée pour l'instant). Priorité : **haute** (bloque une publication), **moyenne**,
**basse**.

## 1. Bugs connus

| ID | Priorité | Dépôt | Description | Piste |
|---|---|---|---|---|
| — | — | — | Aucun bug connu ouvert (BUG-1 à BUG-5 et SEC-1 à SEC-9 traités, voir §5 ; SEC-6 devenu LIM-9). | — |

## 2. TODO

| ID | Priorité | Dépôt | Action |
|---|---|---|---|
| TODO-2 | haute | Client | Retirer `/phantasmon debug fingerprint` quand un vrai serveur dédié remplacera les tests « Ouvrir au LAN » (décision D-18). |
| TODO-3 | haute | Client | Remplacer les métadonnées factices : `homepage` / `sources` de `fabric.mod.json` (`github.com/your-account/…`), lien de mise à jour `https://modrinth.com/mod/phantasmon` (`AuthService`). |
| TODO-7 | moyenne | Machine serveur | Rétablir l'accès SSH (`production-server`) : les déploiements du client retombent sur l'instance locale « Cobblemon 2 ». |
| TODO-16 | haute | Machine qui héberge la base | Planifier `scripts/backup-database.ps1` (tâche Windows quotidienne, `guides/deployment.md` §3.2) vers un autre disque. **Reporté par Adrien (2026-10-04).** |
| TODO-17 | haute | Machine de dev | Le disque D: (« URBAN 1TB », USB) qui porte les dépôts, le backend et ses logs s'est déconnecté le 2026-10-04 (erreurs `disk` 51 / `Ntfs` 50, 140, remontages 15h36 et 16h31) : backend tué en plein combat, pics de lag. Vérifier câble / port / mise en veille USB ; faire tourner le backend (et viser les sauvegardes de TODO-16) sur un disque interne. **Reporté par Adrien (2026-10-04).** |

## 3. Dette technique

| ID | Dépôt | Description |
|---|---|---|
| DEBT-3 | Backend | Pas de révocation des refresh tokens (décision D-16). |

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
| LIM-9 | Positions visibles par tout le groupe (SEC-6, décision D-21) : quiconque connaît l'adresse d'un serveur peut rejoindre son groupe et recevoir chaque seconde la position des joueurs ayant un Ghost sorti. Assumé pour des serveurs entre joueurs de confiance. |

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
| TODO-13 | 2026-10-04 | Les Ghost sont connus d'office : `PokemonRendererMixin` fait afficher `[Ghost] <nom>` au-dessus des entités Phantasmon (Ghost dans le monde et Pokémon de combat Ghost, suivis par `PhantasmonEntities`) même si l'espèce n'est pas au Pokédex du joueur. Nom lu sur le `Pokemon` et non via `getName()` (que le mod catchindicator du modpack remplace par « ??? »). Niveau de l'étiquette synchronisé (`LABEL_LEVEL`, affichait « N. 1 »). Les vrais Pokémon et le Pokédex du joueur ne sont pas touchés. |
| TODO-15 | 2026-10-04 | Audit de sécurité fait : [`security-audit.md`](security-audit.md). 9 points : 8 corrigés le même jour, SEC-6 assumé comme limite (LIM-9, D-21) ; détail ci-dessous. |
| SEC-1 | 2026-10-04 | Corrigé le 2026-10-04 : `POST /auth/challenge` (`AuthChallengeService`, 128 bits, 60 s, usage unique, 10 000 en attente au plus) ; `/auth/session` refuse tout autre `server_id` (`ERROR_AUTH_INVALID_CHALLENGE`) sans interroger Mojang ; le client demande le défi avant `joinServer`. Tests `AuthChallengeServiceTest`, `AuthControllerTest`. |
| SEC-2 | 2026-10-04 | Corrigé le 2026-10-04 : `RelayedPacketPolicy` (client) n'accepte que `cobblemon:battle_*` et `phantasmon:action_effect`, vérifié avant décodage. Test `RelayedPacketPolicyTest`. |
| SEC-3 | 2026-10-04 | Corrigé le 2026-10-04 : `POST /battles` et `POST /battles/{uuid}/result` retirés (seul `GET /battles/{uuid}` reste). Test `BattleControllerTest`. |
| SEC-4 | 2026-10-04 | Corrigé le 2026-10-04 : `JoinServerGroup` / `PositionUpdate` refusent une empreinte ou une dimension absente, vide ou > 128 caractères (`ERROR_WS_MALFORMED_MESSAGE`) ; comparaison des groupes tolérante au `null`. Tests WebSocket. |
| SEC-5 | 2026-10-04 | Corrigé le 2026-10-04 : `data` ≤ 16 Kio, surnom ≤ 20 caractères, chaînes de la requête bornées (`@Size`) ; 40 messages WebSocket par seconde par connexion, rafales jusqu'à 200 (`MessageRateLimiter`, `ERROR_WS_RATE_LIMITED`). |
| SEC-7 | 2026-10-04 | Corrigé le 2026-10-04 : réponse rejouée seulement pour le même joueur et la même route, sinon `409 ERROR_IDEMPOTENCY_KEY_REUSED`. Test `IdempotencyServiceTest`. |
| SEC-8 | 2026-10-04 | Corrigé le 2026-10-04 : une nouvelle connexion ferme la précédente ; la fermeture d'une connexion remplacée ne touche plus à rien ; l'expiration TTL fait elle-même le nettoyage complet. Test WebSocket. |
| SEC-9 | 2026-10-04 | Corrigé le 2026-10-04 : `ERROR_LEGALITY_INVALID_DATA` (422) sur `ivs` / `evs` / `nickname` mal typés. Test `PokemonLegalityServiceTest`. |
| SEC-6 | 2026-10-04 | Limite assumée par Adrien (option « documenter ») : décision D-21, suivie en LIM-9. |
| — | 2026-10-04 | Logs du 2026-10-04 (déconnexion en combat, pas de reconnexion) : le client rouvre seul le WebSocket perdu (2 s → 30 s, `/phantasmon login` pour forcer) ; `JoinServerGroup` envoyé seulement une fois la socket ouverte (il pouvait être jeté et jamais renvoyé) ; le backend ne crée plus d'utilisateur en mémoire (mot de passe généré écrit dans le log à chaque démarrage). Cause de la coupure : disque USB (TODO-17). |
| DEBT-2 | 2026-10-04 | Erreurs de format au format structuré : `400 ERROR_VALIDATION_FAILED` (`details.fields`) et `400 ERROR_MALFORMED_REQUEST` (`ApiExceptionHandler`). Tests `AuthControllerTest`, `PokemonControllerTest`. |
| DEBT-4 | 2026-10-04 | Idempotence sûre en concurrence : la clé est réservée par `INSERT … ON CONFLICT DO NOTHING` avant l'action, dans la même transaction ; un doublon simultané attend puis rejoue la réponse ; une action en échec libère la clé. Test `IdempotencyConcurrencyTest`. |
| DEBT-5 | 2026-10-04 | Version réelle de Cobblemon enregistrée à la création et à l'édition (`PATCH` accepte `cobblemon_data_version`) ; Pokémon non reconnu signalé au joueur et exclu de la sortie et des combats, données intactes (`PokemonRecognition`). Tests `CobblemonDataVersionTest`, `PokemonControllerTest`. |
| DEBT-1 | 2026-10-04 | Clés de `pokemon.data` en snake_case : migration Flyway V9 (`heldItem` → `held_item`, `teraType` → `tera_type`, aussi dans les réponses d'idempotence), client mis à jour en même temps. Test `SnakeCaseDataKeysMigrationTest` ; validée sur une copie des données réelles (25 / 10 Pokémon, reste des données identique, rejouable). |
| — | 2026-10-04 | Puissance Cachée (retour d'Adrien) : Cobblemon n'a qu'une capacité `hiddenpower` (les variantes de Showdown partagent cet identifiant, l'entrée du registre garde le type Eau). Badges de la fiche et de l'éditeur calculés depuis les IV ; migration V10 : variantes stockées (`hiddenpowerice`…) → `hiddenpower`, ce qui rend ces Pokémon de nouveau reconnus et utilisables en combat. Test `HiddenPowerMigrationTest`. |
| — | 2026-10-04 | Combat qui ne démarrait pas quand l'hôte est un client pur (invité LAN) avec un Pokémon portant un objet : `swapHeldItem` publiait un événement qui exige un serveur ; l'objet est désormais posé directement. |
| — | 2026-10-04 | Match nul si le backend est perdu (CAD Partie 1 §44) : arrêt du backend → combats conclus en nul `BACKEND_LOST` et annoncés avant la fermeture des connexions ; plantage → sessions `ACTIVE` conclues en nul au redémarrage ; client : messages « match nul, aucun vainqueur ». Tests dans `LiveBattleWebSocketIntegrationTest`. |
