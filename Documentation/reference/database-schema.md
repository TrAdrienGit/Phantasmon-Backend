# Schéma de la base de données

> **Document vivant** : reflète le schéma **après application de toutes les migrations** Flyway
> (`src/main/resources/db/migration/`, V1 à V8). Toute nouvelle migration met à jour ce fichier, y compris le
> diagramme, dans le même changement. Vérifié contre les migrations le 2026-10-03.

## 1. Règles

1. **Flyway uniquement** : `spring.jpa.hibernate.ddl-auto=none`. Une migration appliquée n'est **jamais**
   modifiée ; toute évolution passe par une nouvelle migration `V{n}__description.sql`.
2. **Modèle hybride** : colonnes classiques pour ce qui est filtré, indexé ou contraint ; JSONB pour le reste.
3. **Identifiants Cobblemon uniquement** : jamais de statistiques de base ni de données de rendu.
4. **Pas de table pour l'état éphémère** : présence, échanges en direct et combats en cours vivent en mémoire (§8).
5. **Tests sur un vrai PostgreSQL** (Testcontainers), jamais H2 : le JSONB ne s'y comporte pas pareil.
6. Contraintes sans nom dans une ancienne migration : les retrouver dynamiquement via `pg_constraint` plutôt que
   de deviner le nom généré par PostgreSQL (méthode de `V6` et `V7`).

## 2. Diagramme

```mermaid
erDiagram
    PLAYERS ||--o{ POKEMON : "possède"
    PLAYERS ||--o{ TRADES : "initie"
    PLAYERS ||--o{ TRADES : "reçoit"
    PLAYERS ||--o{ BATTLE_SESSIONS : "joue (a / b)"
    PLAYERS |o--o{ BATTLE_SESSIONS : "héberge (V8)"
    POKEMON |o..o{ TRADES : "offert / demandé (historique, sans FK depuis V7)"
    PLAYERS ||--o{ HUB_ANCHORS : "crée (un par hub, V14)"
    HUBS ||--o{ HUB_ANCHORS : "mène à (V14)"
    PLAYERS |o--o{ HUBS : "crée (admin, V14)"

    PLAYERS {
        uuid uuid PK
        varchar last_username
        timestamptz created_at
        timestamptz last_seen_at
    }
    POKEMON {
        uuid uuid PK
        uuid owner_uuid FK
        varchar species
        varchar form
        smallint level
        varchar nature
        varchar ability
        boolean is_shiny
        smallint box_id
        smallint box_slot
        smallint team_slot
        varchar cobblemon_data_version
        jsonb data
        timestamptz created_at
        timestamptz updated_at
    }
    TRADES {
        uuid uuid PK
        uuid initiator_uuid FK
        uuid recipient_uuid FK
        uuid offered_pokemon "sans FK (V7)"
        uuid requested_pokemon "sans FK (V7)"
        varchar status
        timestamptz created_at
        timestamptz resolved_at
    }
    BATTLE_SESSIONS {
        uuid uuid PK
        uuid player_a FK
        uuid player_b FK
        uuid host_uuid FK "nullable (V8)"
        jsonb team_a
        jsonb team_b
        varchar status
        jsonb result
        timestamptz created_at
        timestamptz finished_at
    }
    HUBS {
        uuid uuid PK
        varchar name "unique, casse ignorée"
        smallint size_x
        smallint size_y
        smallint size_z
        uuid created_by FK "nullable"
        timestamptz created_at
    }
    HUB_ANCHORS {
        uuid uuid PK
        uuid owner_uuid FK
        uuid hub_uuid FK "unique avec owner_uuid (V14)"
        varchar name
        varchar server_fingerprint
        varchar dimension
        double origin_x
        double origin_y
        double origin_z
        smallint yaw
        timestamptz created_at
    }
    IDEMPOTENCY_KEYS {
        uuid request_uuid PK
        uuid player_uuid "sans FK"
        varchar endpoint
        jsonb response_snapshot
        timestamptz created_at
    }
```

7 tables : `players`, `pokemon`, `trades`, `battle_sessions`, `idempotency_keys`, `hubs`, `hub_anchors` (plus la table
technique `flyway_schema_history`). Pas de table `teams` (l'équipe est `pokemon.team_slot`), pas de table de présence.

## 3. `players`

Identité minimale d'un joueur. Créée à la première connexion réussie (`POST /auth/session`), mise à jour à
chaque connexion et à chaque refresh.

| Colonne | Type | Contraintes | Notes |
|---|---|---|---|
| `uuid` | UUID | PK | UUID Mojang (jamais généré par le backend) |
| `last_username` | VARCHAR(16) | NOT NULL | Pseudo au moment de la dernière connexion |
| `created_at` | TIMESTAMPTZ | NOT NULL, défaut `now()` | |
| `last_seen_at` | TIMESTAMPTZ | | Dernière connexion ou refresh |

## 4. `pokemon`

| Colonne | Type | Contraintes | Notes |
|---|---|---|---|
| `uuid` | UUID | PK | Ne change jamais, y compris lors d'un échange |
| `owner_uuid` | UUID | NOT NULL, FK → `players` `ON DELETE RESTRICT`, indexé | Change lors d'un échange |
| `species` | VARCHAR(64) | NOT NULL | Identifiant Cobblemon (`samurott`, `mimejr`…) |
| `form` | VARCHAR(64) | | `NULL` = forme de base (`hisui`, `fairy`…) |
| `level` | SMALLINT | NOT NULL, 1-100 | |
| `nature` | VARCHAR(32) | NOT NULL | `timid`, `adamant`… |
| `ability` | VARCHAR(64) | NOT NULL | `torrent`, `flash_fire`… (cohérence avec l'espèce non vérifiée en base) |
| `is_shiny` | BOOLEAN | NOT NULL, défaut `false` | |
| `box_id` | SMALLINT | 1-16 | Boîte du PC |
| `box_slot` | SMALLINT | 1-30 (`chk_pokemon_box_slot`, V6) | Case dans la boîte (6 colonnes × 5 lignes) |
| `team_slot` | SMALLINT | 1-6 | Emplacement dans l'équipe active |
| `cobblemon_data_version` | VARCHAR(32) | NOT NULL | Version de Cobblemon à la création (ex. `1.8.1`) |
| `data` | JSONB | NOT NULL, index GIN | Voir §4.2 |
| `created_at`, `updated_at` | TIMESTAMPTZ | NOT NULL, défaut `now()` | `updated_at` géré par l'application |

### 4.1 Contraintes et index

| Nom | Définition | But |
|---|---|---|
| `chk_pokemon_box_coherent` | `box_id` et `box_slot` tous deux nuls ou tous deux renseignés | Pas de case à moitié définie |
| `chk_pokemon_box_slot` | `box_slot BETWEEN 1 AND 30` | Boîtes 6×5 (V6) |
| `uq_pokemon_pc_slot` | Unique `(owner_uuid, box_id, box_slot)` si renseignés | Une case = un Pokémon |
| `uq_pokemon_team_slot` | Unique `(owner_uuid, team_slot)` si renseigné | Un emplacement d'équipe = un Pokémon |
| `idx_pokemon_owner` | `owner_uuid` | Listes par joueur |
| `idx_pokemon_data_gin` | GIN sur `data` | Requêtes futures sur le JSONB |

L'exclusivité PC / équipe (jamais les deux à la fois) est garantie par le service (`PokemonService`), pas par une
contrainte SQL. Lors d'un échange de places, l'ancienne place est libérée et flushée avant d'être réattribuée,
pour ne jamais violer les index uniques.

### 4.2 Contenu de `data`

```json
{
  "nickname": "Bichou",
  "gender": "M",
  "tera_type": "grass",
  "ivs": { "hp": 31, "atk": 31, "def": 31, "spa": 31, "spd": 31, "spe": 31 },
  "evs": { "hp": 0, "atk": 144, "def": 64, "spa": 0, "spd": 136, "spe": 0 },
  "moves": ["avalanche", "aquatail", "bodyslam", "darkpulse"],
  "held_item": "assault_vest"
}
```

| Clé | Écrite par | Règle |
|---|---|---|
| `ivs` | Import, éditeur | Chaque valeur ∈ [0, 31] (vérifié par le backend) |
| `evs` | Import, éditeur | Chaque valeur ∈ [0, 252], total ≤ 510 (vérifié par le backend) |
| `moves` | Import, éditeur | Jusqu'à 4 identifiants d'attaque, sans doublon (garanti par l'éditeur) |
| `held_item` | Import, éditeur | Identifiant d'objet Cobblemon (chemin du registre, ex. `choice_band`) |
| `nickname` | Import, éditeur | Texte libre |
| `gender` | Import, éditeur | `"M"` ou `"F"` ; absente = aléatoire (convention Showdown) |
| `tera_type` | Import, éditeur | Identifiant de type ; absente = type primaire de l'espèce |
| `friendship` | Import (ligne `Happiness:`) | Entier ; non affiché ni éditable |

**Casse des clés** : tout en snake_case, comme le reste de l'API. Les stratégies de nommage (Jackson côté backend,
Gson côté client) ne s'appliquent pas aux clés d'une `Map` : c'est le client qui les écrit ainsi. `heldItem` et
`teraType` (camelCase) ont été renommées en `held_item` et `tera_type` par la migration V9 (DEBT-1) ; tout
nouveau renommage exige lui aussi une migration des données existantes.

`nickname`, `gender` et `tera_type` sont en JSONB car ni filtrés ni indexés ; s'ils devaient l'être, une
migration additive les passerait en colonnes. `PATCH /pokemon/{uuid}` **remplace** `data` en entier.

## 5. `trades`

Historique de tous les échanges, asynchrones (REST) et en direct (WebSocket, ligne créée directement en `COMPLETED`).

| Colonne | Type | Contraintes | Notes |
|---|---|---|---|
| `uuid` | UUID | PK | |
| `initiator_uuid` | UUID | NOT NULL, FK → `players`, indexé | |
| `recipient_uuid` | UUID | NOT NULL, FK → `players`, indexé | |
| `offered_pokemon` | UUID | NOT NULL, indexé, **sans FK** (V7) | Appartenait à l'initiateur au moment de l'offre |
| `requested_pokemon` | UUID | NOT NULL, indexé, **sans FK** (V7) | Appartenait au destinataire au moment de l'offre |
| `status` | VARCHAR(16) | NOT NULL, défaut `PENDING`, ∈ {`PENDING`, `ACCEPTED`, `CANCELLED`, `COMPLETED`} | `ACCEPTED` n'est jamais produit |
| `created_at` | TIMESTAMPTZ | NOT NULL, défaut `now()` | |
| `resolved_at` | TIMESTAMPTZ | | Renseigné à l'acceptation ou l'annulation |

Contraintes et index : `chk_trade_not_self` (`initiator_uuid <> recipient_uuid`), `idx_trades_initiator`,
`idx_trades_recipient`, `idx_trades_status` (partiel, `PENDING`), `idx_trades_offered_pokemon`,
`idx_trades_requested_pokemon`.

**Pourquoi sans FK vers `pokemon`** : les FK `ON DELETE RESTRICT` d'origine (V3) empêchaient de supprimer tout
Pokémon ayant déjà été échangé. La seule protection utile (pas de suppression d'un Pokémon engagé dans un échange
`PENDING`) est faite par l'application (`ERROR_POKEMON_IN_PENDING_TRADE`).

## 6. `battle_sessions`

| Colonne | Type | Contraintes | Notes |
|---|---|---|---|
| `uuid` | UUID | PK | |
| `player_a`, `player_b` | UUID | NOT NULL, FK → `players`, indexés | Combat en direct : `player_a` = hôte. Anciennes lignes créées par `POST /battles` (route retirée le 2026-10-04) : `player_a` = appelant. |
| `host_uuid` | UUID | FK → `players`, indexé (V8) | Client qui a exécuté le moteur ; `NULL` pour les anciennes lignes de `POST /battles` (route retirée). Sert à l'alternance de l'hôte. |
| `team_a`, `team_b` | JSONB | NOT NULL | Instantané des UUID de Pokémon engagés, sans FK (historique) |
| `status` | VARCHAR(16) | NOT NULL, défaut `PENDING`, ∈ {`PENDING`, `ACTIVE`, `FINISHED`, `ABORTED`} | Les sessions sont créées `ACTIVE` |
| `result` | JSONB | | REST : `{winner_uuid, log}`. Direct : `{winner_uuid, reason}` (`FINISHED`, `FORFEIT`, `PARTNER_DISCONNECTED`, `BACKEND_LOST` = nul car le backend s'est arrêté ou a planté) |
| `created_at` | TIMESTAMPTZ | NOT NULL, défaut `now()` | |
| `finished_at` | TIMESTAMPTZ | | |

Contrainte : `chk_battle_not_self` (`player_a <> player_b`). Index : `idx_battle_player_a`, `idx_battle_player_b`,
`idx_battle_host`.

## 7. `idempotency_keys`

Journal technique anti-doublon des `POST` sensibles.

| Colonne | Type | Contraintes | Notes |
|---|---|---|---|
| `request_uuid` | UUID | PK | Généré par le client |
| `player_uuid` | UUID | NOT NULL, **sans FK** | Volontaire : un journal technique ne doit pas dépendre du cycle de vie des joueurs |
| `endpoint` | VARCHAR(64) | NOT NULL | `POST /pokemon`, `POST /trades` (`POST /battles` dans d'anciennes lignes). Comparé, avec `player_uuid`, à chaque réutilisation (SEC-7) |
| `response_snapshot` | JSONB | | Réponse d'origine, rejouée telle quelle |
| `created_at` | TIMESTAMPTZ | NOT NULL, défaut `now()` | Aucune purge en V1 |

Index : `idx_idempotency_player_endpoint` (`player_uuid`, `endpoint`).

## 7 bis. `hub_anchors` (Phantasmon Network, V11)

Points d'accès au Global Hub posés par les joueurs, partagés par tous les joueurs de leur serveur (D-30)
([`network-cahier-des-charges.md`](../specifications/network-cahier-des-charges.md) §5.2, D-28).

| Colonne | Type | Contraintes | Notes |
|---|---|---|---|
| `uuid` | UUID | PK | Généré par le backend |
| `owner_uuid` | UUID | NOT NULL, FK `players` `ON DELETE RESTRICT` | Un Anchor par joueur **et par hub** (`uq_hub_anchor_owner_hub` sur `owner_uuid`, `hub_uuid`, V14 ; un seul en tout avant) |
| `hub_uuid` | UUID | NOT NULL, FK `hubs` `ON DELETE CASCADE` | Le hub où mène l'Anchor (V14 ; les Anchors existants → hub `global`) ; index `idx_hub_anchor_hub` |
| `name` | VARCHAR(32) | NOT NULL | Unique par serveur, casse ignorée (`uq_hub_anchor_server_name` sur `server_fingerprint`, `lower(name)`, recréé par V13) |
| `server_fingerprint` | VARCHAR(128) | NOT NULL | Empreinte du serveur (D-18), jamais l'adresse |
| `dimension` | VARCHAR(128) | NOT NULL | |
| `origin_x`, `origin_y`, `origin_z` | DOUBLE PRECISION | NOT NULL | Pieds du créateur ; centre de la zone en `x` / `z`, base en `y` |
| `yaw` | SMALLINT | NOT NULL, ∈ {0, 90, 180, 270} | Arrondi au quart de tour |
| `created_at` | TIMESTAMPTZ | NOT NULL, défaut `now()` | |

Index : `idx_hub_anchor_server_dimension` (`server_fingerprint`, `dimension`) : un Anchor est partagé par les joueurs
de son serveur (D-30), qui le listent par empreinte et dimension. La taille de la zone n'est pas stockée ici : c'est
celle de son hub (`hubs.size_*`). Deux Anchors d'un même serveur et d'une même dimension ne se chevauchent jamais
(contrôle applicatif, `HubBox`, D-35).

## 7 ter. `hubs` (Phantasmon Network, V14)

Les hubs créés par les admins (D-35) : chacun est un espace séparé, avec sa taille et sa construction
(`hub_schematics/hub_<name>/`, hors base).

| Colonne | Type | Contraintes | Notes |
|---|---|---|---|
| `uuid` | UUID | PK | `6c0b5d2e-3b1a-4f0e-9a51-000000000001` pour `global` (créé par V14) |
| `name` | VARCHAR(32) | NOT NULL, unique casse ignorée (`uq_hub_name` sur `lower(name)`) | Minuscules, chiffres, `_` (contrôle applicatif) : c'est aussi un nom de dossier |
| `size_x`, `size_y`, `size_z` | SMALLINT | NOT NULL, de 3 à 64 | Largeur (en travers de l'Anchor), hauteur, longueur (vers l'avant de l'Anchor) |
| `created_by` | UUID | FK `players` `ON DELETE SET NULL` | L'admin créateur ; `NULL` pour `global` |
| `created_at` | TIMESTAMPTZ | NOT NULL, défaut `now()` | |

Supprimer un hub supprime ses Anchors (`ON DELETE CASCADE`, et le service les retire d'abord pour sortir leurs
membres du hub).

## 8. État hors base

| Structure | Service | Contenu |
|---|---|---|
| `PlayerPresence` | `PresenceService` | `player_uuid`, `server_fingerprint`, `dimension`, `position`, `last_heartbeat_at`, `active_ghost_pokemon_uuid` (un seul Ghost sorti à la fois) |
| Sessions d'échange en direct | `LiveTradeService` | Participants, offres, drapeaux « prêt », invitations |
| Combats en cours | `LiveBattleService` | Hôte, invité, chrono, invitations |
| Membres du Global Hub | `HubService` | Anchor d'entrée, empreinte et dimension, dernier état d'avatar (coordonnées Hub), dernier message de chat |

Tout est perdu au redémarrage du backend (comportement voulu) et n'est pas partagé entre instances.

## 9. Historique des migrations

| Version | Fichier | Date | Contenu |
|---|---|---|---|
| V1 | `V1__init_players.sql` | 2026-09-25 | Table `players` |
| V2 | `V2__init_pokemon.sql` | 2026-09-25 | Table `pokemon`, index et contraintes (cases 1-36) |
| V3 | `V3__init_trades.sql` | 2026-09-25 | Table `trades` (avec FK vers `pokemon`) |
| V4 | `V4__init_battle_sessions.sql` | 2026-09-25 | Table `battle_sessions` |
| V5 | `V5__init_idempotency_keys.sql` | 2026-09-25 | Table `idempotency_keys` |
| V6 | `V6__resize_pokemon_box.sql` | 2026-09-27 | Boîtes 6×5 : contrainte `box_slot` 1-30 (`chk_pokemon_box_slot`), ancienne contrainte retrouvée via `pg_constraint` |
| V7 | `V7__trades_pokemon_history_without_fk.sql` | 2026-10-02 | Retrait des FK `trades → pokemon`, index sur `offered_pokemon` / `requested_pokemon` |
| V8 | `V8__battle_sessions_host.sql` | 2026-10-03 | Colonne `battle_sessions.host_uuid` et son index |
| V9 | `V9__pokemon_data_snake_case_keys.sql` | 2026-10-04 | Clés de `pokemon.data` en snake_case : `heldItem` → `held_item`, `teraType` → `tera_type`, aussi dans les réponses mémorisées de `idempotency_keys` (DEBT-1). Rejouable ; aucune autre donnée modifiée (vérifié sur une copie des données réelles : 25 et 10 Pokémon concernés) |
| V10 | `V10__pokemon_hidden_power_single_id.sql` | 2026-10-04 | Dans `data.moves`, les identifiants de variantes `hiddenpower<type>` (importés avant la correction de l'import) deviennent `hiddenpower`, seule capacité Puissance Cachée de Cobblemon (type tiré des IV). Ordre et autres capacités conservés ; rejouable. 1 Pokémon concerné dans les données réelles (`hiddenpowerice`) |
| V11 | `V11__init_hub_anchors.sql` | 2026-10-07 | Phantasmon Network (N1) : table `hub_anchors`, un Anchor par joueur, nom unique par serveur |
| V12 | `V12__hub_anchors_personal.sql` | 2026-10-07 | Contresens sur D-30 (Anchors rendus personnels) : retrait de `uq_hub_anchor_server_name` et de `idx_hub_anchor_server_dimension`. Déjà appliquée sur la base de dev, donc conservée ; annulée par V13 |
| V13 | `V13__hub_anchors_shared_per_server.sql` | 2026-10-07 | D-30 corrigée (Anchors partagés par serveur) : noms en double numérotés (« Nom 2 »…), puis `uq_hub_anchor_server_name` et `idx_hub_anchor_server_dimension` recréés |
| V14 | `V14__init_hubs.sql` | 2026-10-08 | Plusieurs hubs (D-35) : table `hubs` (hub `global` 21 × 21 × 21 créé), `hub_anchors.hub_uuid` (Anchors existants → `global`), un Anchor par joueur et par hub (`uq_hub_anchor_owner_hub` remplace `uq_hub_anchor_owner`) |

## 10. Décisions de schéma

| Sujet | Décision |
|---|---|
| Contraintes `CHECK` sur les statuts, index uniques PC/équipe, `chk_*_not_self` | Ajoutés au SQL illustratif du CAD : empêchent des états incohérents à faible coût |
| `box_id` / `box_slot` nullables | Un Pokémon de l'équipe n'a pas de case PC ; à la création sans emplacement, le service attribue la première case libre |
| Pas de FK sur `idempotency_keys.player_uuid` | Journal technique indépendant des joueurs |
| Instantanés JSONB sans FK (`team_a`, `team_b`, colonnes Pokémon de `trades`) | Historique qui doit survivre à la modification ou suppression des Pokémon |
| Une seule équipe active | Décision D-03 ([`architecture/decisions.md`](../architecture/decisions.md)) |
