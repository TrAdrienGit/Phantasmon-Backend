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
| BUG-1 | moyenne | Client | `fabric.mod.json` déclare `"license": "CC0-1.0"` alors que la licence du projet est la **GPL 3.0** (fichiers `LICENSE`). | Remplacer par `"GPL-3.0-only"` (ou `GPL-3.0-or-later` selon l'intention). |
| BUG-2 | basse | Client | Cobblemon n'est pas déclaré dans `depends` de `fabric.mod.json` : sans Cobblemon, le jeu plante au lieu d'afficher un message clair de Fabric Loader. | Ajouter `"cobblemon": ">=1.8.1"` dans `depends`. |
| BUG-3 | basse | Client | L'indicateur `[Ghost]` (CAD Partie 1 §5, §22.1) n'est pas affiché au-dessus des Ghost sortis. | Nom personnalisé visible sur l'entité, traduit. |

## 2. TODO

| ID | Priorité | Dépôt | Action |
|---|---|---|---|
| TODO-1 | haute | Client | Rendre l'URL du backend configurable (aujourd'hui `BackendConfig.BASE_URL = http://100.116.43.32:8080`, IP Tailscale de la machine de dev). |
| TODO-2 | haute | Client | Retirer `/phantasmon debug fingerprint` quand un vrai serveur dédié remplacera les tests « Ouvrir au LAN » (décision D-18). |
| TODO-3 | haute | Client | Remplacer les métadonnées factices : `homepage` / `sources` de `fabric.mod.json` (`github.com/your-account/…`), lien de mise à jour `https://modrinth.com/mod/phantasmon` (`AuthService`). |
| TODO-4 | haute | Les deux | Aligner les numéros de version : client `1.0.0` (`gradle.properties`), backend `phantasmon.version.current` / `min-supported` = `0.1.0`, jar backend `0.0.1-SNAPSHOT`. |
| TODO-5 | haute | Backend | Mettre en place les sauvegardes PostgreSQL et tester une restauration (CAD Partie 3 §I, Phase 10). |
| TODO-6 | haute | Machine de dev | Restreindre le PostgreSQL natif à `localhost` (il écoute sur `0.0.0.0:5432`). |
| TODO-7 | moyenne | Machine serveur | Rétablir l'accès SSH (`production-server`) : les déploiements du client retombent sur l'instance locale « Cobblemon 2 ». |
| TODO-8 | moyenne | Les deux | Mettre à jour les commentaires de code qui citent les anciens noms de documentation (`PHANTASMON_DB_SCHEMA.md`, `PHANTASMON_API_REFERENCE.md`, `PHANTASMON_BACKEND_RUNNING.md`, `CONTEXT_CURSOR_BACKEND.md`, `phantasmon-backend-openapi.yaml`, `SERVER_AGENT_BRIEFING.md`, `Documentation/ecran_echange/`). Fichiers : backend `JwtService`, `BattleSession`, `CreateBattleRequest`, `ApiException`, `IdempotencyKey`, `Player`, `PlayerService`, `Pokemon`, `PokemonLegalityService`, `PlayerPresence`, `LiveTradeService`, `ProposeTradeRequest`, `application.properties`, `docker-compose.yml`, `.env.template` ; client `PhantasmonCanvasScreen`, `scripts/deploy-to-prod-server.sh` (local). **Ne jamais modifier `V7__trades_pokemon_history_without_fk.sql`** (migration appliquée : la somme de contrôle Flyway casserait). Correspondance des noms : `project/development-journal.md` (dépôt Client). |
| TODO-9 | basse | Client | Corriger les javadocs obsolètes : `ClientCommonPacketListenerImplMixin` (« seul Mixin du mod », il y en a 7), `PhantasmonKeybinds` (« trois touches », il y en a 4), `BackendHealthPinger` (« backend local en dur »). |
| TODO-10 | basse | Client | Supprimer les textures inutilisées `textures/gui/sprites/pc/*` (toutes sauf `star.png`) depuis la refonte du PC du 2026-10-02. |
| TODO-11 | basse | Les deux | Implémenter l'export Showdown vers le presse-papiers (CAD Partie 1 §11). |

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

## 5. Résolu

| ID | Date | Résolution |
|---|---|---|
| — | 2026-10-03 | Licence : **GPL 3.0** retenue par Adrien. Documentation et README alignés ; reste `fabric.mod.json` (BUG-1). |
