# CAD Ghost Pokémon — Partie 2 : Architecture technique

## 0. Principe directeur

Ce document formalise les choix techniques validés en échange avec le porteur du projet. Le principe central qui structure toute l'architecture est le suivant :

> **Aucune brique Ghost n'est installée sur le serveur Minecraft/Cobblemon.** Le serveur reste vanilla + Cobblemon, sans rien connaître du système Ghost. Seuls les clients (joueurs) installent l'addon Ghost, et communiquent **directement** avec un backend externe, indépendant de tout serveur Minecraft particulier.

On est donc sur un modèle **peer-to-peer via backend** :

```text
Client Alice (Ghost)  ──┐
                         ├──►  Ghost Backend  ◄──── Source de vérité
Client Bob (Ghost)    ──┘         (Spring Boot + PostgreSQL)

Serveur Minecraft/Cobblemon
   → ne sait rien de Ghost, ne contient aucun code Ghost
```

Ce choix a des implications profondes sur la synchronisation des entités, l'authentification et les combats, détaillées plus bas. Chaque section technique le rappelle quand c'est pertinent.

---

## 1. Stack technique retenue

| Composant | Choix |
|---|---|
| Minecraft | 1.21.1 |
| Cobblemon | 1.8.1 (le mod devra être maintenu à jour au fil des versions Cobblemon) |
| Loader | Fabric |
| Langage mod | Java |
| Build system mod | Gradle |
| Backend | Java 21 + Spring Boot 3.x |
| Base de données | PostgreSQL (colonnes classiques + JSONB, modèle hybride) |
| Communication | REST + WebSocket |
| Auth client ↔ backend | Vérification de session Mojang + JWT |

**Note sur la maintenance Cobblemon** : comme convenu, chaque montée de version Cobblemon pourra casser des identifiants (espèces, attaques, formes). Le mécanisme de `cobblemon_data_version` (section 9) est donc conservé et devient un pilier, pas une option.

---

## 2. Composants du système

### 2.1 Ghost Client (mod Fabric, **client-only**)

- S'installe uniquement côté joueur, par-dessus Cobblemon.
- Ne nécessite **aucune** entité, bloc ou registre côté serveur logique Minecraft.
- Responsabilités :
  - UI (création/édition/PC/équipe/import Showdown) ;
  - communication REST + WebSocket avec le Ghost Backend ;
  - rendu des Ghost Pokémon en tant qu'**entités purement clientèles** (aucune existence côté serveur Minecraft, voir §7) ;
  - remontée périodique de son contexte (position, monde, dimension, serveur courant) au backend.

### 2.2 Ghost Backend (Spring Boot)

- Tourne sur une infrastructure indépendante, découplée de tout serveur Minecraft.
- Source de vérité unique pour :
  - les données Pokémon (persistantes) ;
  - l'identité et les droits des joueurs ;
  - les sessions de jeu Ghost (qui est connecté, sur quel serveur, où) ;
  - les sessions de combat.
- Expose une API REST + un endpoint WebSocket.
- Stocke tout dans PostgreSQL.

### 2.3 Absence volontaire d'un composant serveur Minecraft

Point à garder en tête pour toute la suite : tout ce qui, dans une architecture classique, serait arbitré par un mod serveur (autorité de visibilité réseau, anti-triche par contrôle serveur, exécution du moteur de combat Cobblemon) doit être repensé ici en mode **backend-arbitré + clients semi-autonomes**. Cela introduit des limitations assumées, explicitées en section 12 (Combat).

---

## 3. Authentification client ↔ backend

Sans serveur Minecraft intermédiaire, c'est le **client** qui doit prouver au backend qu'il représente bien le joueur dont il prétend porter l'UUID. On ne peut pas faire confiance à une simple déclaration du client ("je suis tel UUID").

### 3.1 Mécanisme retenu : vérification de session Mojang/Microsoft

On réutilise le mécanisme standard d'authentification Minecraft (celui utilisé par n'importe quel vrai serveur pour vérifier un joueur en ligne), sans avoir besoin d'un serveur Minecraft réel :

```text
1. Ghost Client génère un serverId aléatoire
2. Ghost Client appelle le joinServer de la session Minecraft du joueur
   (le même mécanisme que le client utilise pour rejoindre un vrai serveur)
3. Ghost Client envoie au Ghost Backend :
      - uuid
      - serverId utilisé
4. Ghost Backend interroge l'API de session Mojang (hasJoined)
      avec username + serverId + IP cliente
5. Si Mojang confirme → le joueur contrôle bien ce compte
6. Ghost Backend émet un token de session (JWT), lié à l'uuid,
   avec expiration courte + refresh
```

- Ce flux ne nécessite aucun vrai serveur Minecraft : c'est exactement la même primitive que celle utilisée nativement par le client pour se connecter à n'importe quel serveur en ligne.
- Le token JWT est ensuite utilisé pour toutes les requêtes REST et pour l'établissement de la connexion WebSocket.
- Le backend ne fait plus confiance à un `server_id/secret` (obsolète dans ce modèle, remplacé entièrement par ce flux client).

### 3.2 Ce que ça implique

- Fonctionne uniquement pour les comptes Minecraft premium (online-mode). Les comptes offline/cracked ne pourront pas être authentifiés de façon fiable — **à assumer explicitement comme prérequis du projet**.
- Le token JWT devient la seule preuve d'identité acceptée par le backend pour toute opération sensible.
- Rotation : token court (ex. 15–30 min) + refresh token stocké côté client, invalidable côté backend en cas de compromission.

---

## 4. Sessions de jeu & regroupement des joueurs

Puisque le backend ne sait pas nativement "quel serveur Minecraft" héberge quels joueurs, il faut un mécanisme explicite pour regrouper les joueurs qui doivent se voir mutuellement.

```text
Ghost Client
   ↓ à la connexion sur un serveur Minecraft
   envoie au backend :
      - server_fingerprint (hash de l'adresse IP:port du serveur rejoint)
      - dimension
      - position (x, y, z) — mise à jour périodique via WebSocket
```

Le backend maintient une table en mémoire (`PlayerPresence`) des joueurs Ghost actuellement connectés, groupés par `server_fingerprint` + `dimension`, et ne diffuse les événements de spawn/déplacement/despawn qu'aux joueurs du même groupe.

Cela reproduit fonctionnellement la notion de "même serveur" sans jamais avoir besoin d'installer quoi que ce soit dessus.

**Limite connue** : deux serveurs différents ayant la même IP publique (ex. reverse proxy / BungeeCord multi-instances sur le même port) pourraient être vus comme un seul groupe. Si le projet utilise un proxy (Velocity/BungeeCord), il faudra affiner le fingerprint (ex. inclure le nom du serveur transmis par le proxy, si accessible côté client — sinon accepter la limite en V1).

---

## 5. Base de données — PostgreSQL

### 5.1 Modèle hybride retenu

Colonnes classiques pour tout ce qui est interrogé, filtré, indexé ou impliqué dans des contraintes d'intégrité. JSONB pour tout ce qui est spécifique/variable (stats calculées, IVs/EVs, objets, historique).

```sql
CREATE TABLE pokemon (
    uuid                UUID PRIMARY KEY,
    owner_uuid          UUID NOT NULL REFERENCES players(uuid),
    species             VARCHAR(64) NOT NULL,
    form                VARCHAR(64),
    level               SMALLINT NOT NULL,
    nature              VARCHAR(32) NOT NULL,
    ability             VARCHAR(64) NOT NULL,
    is_shiny            BOOLEAN NOT NULL DEFAULT FALSE,
    box_id              SMALLINT,             -- position PC
    box_slot            SMALLINT,
    team_slot           SMALLINT,              -- NULL si pas en équipe active
    cobblemon_data_version VARCHAR(32) NOT NULL,
    data                JSONB NOT NULL,        -- IVs, EVs, moves, held_item, stats dérivées, historique...
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pokemon_owner ON pokemon(owner_uuid);
CREATE INDEX idx_pokemon_data_gin ON pokemon USING GIN (data);
```

```sql
CREATE TABLE players (
    uuid            UUID PRIMARY KEY,       -- UUID Minecraft
    last_username   VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ
);
```

```sql
CREATE TABLE battle_sessions (
    uuid            UUID PRIMARY KEY,
    player_a        UUID NOT NULL REFERENCES players(uuid),
    player_b        UUID NOT NULL REFERENCES players(uuid),
    team_a          JSONB NOT NULL,   -- snapshot des pokemon_uuid impliqués
    team_b          JSONB NOT NULL,
    status          VARCHAR(16) NOT NULL, -- PENDING, ACTIVE, FINISHED, ABORTED
    result          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
```

```sql
CREATE TABLE idempotency_keys (
    request_uuid    UUID PRIMARY KEY,
    player_uuid     UUID NOT NULL,
    endpoint        VARCHAR(64) NOT NULL,
    response_snapshot JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 5.2 Contenu du champ `data` (JSONB) pour un Pokémon

```json
{
  "ivs": { "hp": 31, "atk": 12, "def": 20, "spa": 31, "spd": 25, "spe": 18 },
  "evs": { "hp": 0, "atk": 252, "def": 0, "spa": 0, "spd": 4, "spe": 252 },
  "moves": ["avalanche", "aqua_tail", "megahorn", "protect"],
  "held_item": "leftovers",
  "friendship": 120,
  "origin": { "obtained_at": "2026-09-01T12:00:00Z", "method": "trade" }
}
```

---

## 6. Références Cobblemon (pas de duplication)

Confirmé : le backend ne stocke **que des identifiants** (espèce, forme, capacité, attaques, objets) sous forme de chaînes stables (les identifiants internes Cobblemon), jamais les données de jeu elles-mêmes (stats de base, animations, effets). Le mod résout ces identifiants localement à l'aide des données Cobblemon présentes chez le joueur.

### 6.1 `cobblemon_data_version`

Chaque Pokémon stocke la version Cobblemon avec laquelle il a été créé/modifié pour la dernière fois (`cobblemon_data_version` en colonne, voir §5.1).

```text
Lecture d'un Pokémon
   ↓
Client compare cobblemon_data_version stockée vs sa version Cobblemon locale
   ↓
Si différentes
   → tentative de résolution directe (identifiants inchangés dans la majorité des cas)
   → si échec de résolution (espèce/attaque/forme introuvable)
       → fallback affiché au joueur : "donnée non reconnue avec cette version de Cobblemon"
       → le Pokémon reste intact en base, rien n'est perdu ni auto-corrigé silencieusement
```

Aucune migration automatique agressive en V1 : on log l'incompatibilité et on bloque proprement l'affichage/usage du Pokémon concerné plutôt que de risquer une corruption de données.

---

## 7. Ghost Entity — rendu 100% client

Conséquence directe de l'absence de mod serveur : un Ghost Pokémon **n'existe jamais** en tant qu'entité Minecraft réelle (aucun ID d'entité serveur, aucun packet vanilla d'entité). C'est un objet purement visuel et local à chaque client Ghost.

```text
Alice envoie son Ghost Pokémon
   ↓
Ghost Client Alice → Backend : SendOutGhost(pokemon_uuid, position, dimension)
   ↓
Backend diffuse l'événement (WebSocket) à tous les clients Ghost
   du même groupe (server_fingerprint + dimension, voir §4)
   ↓
Chaque Ghost Client concerné (dont Bob, s'il a l'addon) :
   → instancie localement une entité "fantôme" côté client uniquement
     (aucune existence serveur, gérée par le moteur de rendu du client)
   → réutilise le modèle/l'animation Cobblemon pour le rendu
   → positionne/anime l'entité à partir des mises à jour reçues via WebSocket
```

- Bob **sans** Ghost ne reçoit tout simplement jamais ces événements WebSocket : il ne peut matériellement rien voir. C'est le backend qui filtre la diffusion, pas un mécanisme réseau Minecraft.
- Le rendu vise, comme demandé, une réutilisation maximale des modèles et animations Cobblemon (accès aux mêmes renderers), pas une reconstruction de zéro.
- Le déplacement de l'entité fantôme suit le joueur propriétaire (comme un Pokémon "out" classique côté rendu), avec interpolation client pour lisser les mises à jour réseau (celles-ci arrivant par intervalles, pas frame par frame).

### 7.1 Limites assumées

- Pas de collision physique réelle avec le monde côté serveur (puisqu'aucune entité serveur n'existe). Les interactions (combat, capture, etc.) doivent donc être pilotées par des actions explicites du joueur (clic, commande, touche), pas par des mécaniques de collision Minecraft standard.
- Le rendu peut légèrement diverger entre deux clients en cas de perte de paquets WebSocket ponctuelle (aucune correction serveur "autoritaire" au sens vanilla). Acceptable pour un système cosmétique/gameplay non compétitif au sens strict Minecraft.

---

## 8. Cycle de vie de la Ghost Entity

```text
Envoi
Client → Backend : SendOutGhost
Backend → Clients du groupe : GhostEntitySpawn

Rappel volontaire
Client → Backend : RecallGhost
Backend → Clients du groupe : GhostEntityDespawn

Déconnexion / changement de dimension / téléportation / sortie du groupe
Client détecte l'événement localement
   → envoie RecallGhost automatique au backend
   → Backend diffuse GhostEntityDespawn
   → données persistantes inchangées en base

Mort du joueur
   → le Ghost Pokémon n'est pas perdu (conforme à la demande)
   → comportement par défaut : despawn automatique + Pokémon intact en base
```

---

## 9. Combat Ghost — point ouvert assumé

C'est la conséquence la plus lourde du choix "pas de mod serveur". Sans mod serveur, on ne peut plus déléguer le calcul du combat au moteur Cobblemon serveur comme prévu initialement (§18 de la Partie 1 supposait un `Cobblemon Battle` serveur accessible).

### 9.1 Option retenue pour la V1

**Client hôte autoritaire + validation best-effort du backend** :

```text
Ghost A (joueur initiateur) et Ghost B acceptent le combat
   ↓
Backend crée une BattleSession (uuid, team_a, team_b, status=ACTIVE)
   ↓
Le client de l'un des deux joueurs (désigné "hôte", ex. l'initiateur)
fait tourner localement la logique de combat Cobblemon
(même moteur que celui utilisé normalement côté serveur, mais exécuté client-side)
   ↓
Chaque action de combat est :
   - calculée par le client hôte
   - envoyée en WebSocket aux deux clients + au backend pour état/audit
   - le client non-hôte rejoue localement l'action reçue pour affichage cohérent
   ↓
Fin de combat
   → résultat envoyé au backend → BattlePokemon jeté, GhostPokemon restauré (§19 Partie 1)
```

### 9.2 Limitation assumée et à valider explicitement

Ce modèle fait du client hôte une autorité de fait sur le déroulement du combat — un joueur malveillant contrôlant son propre client pourrait théoriquement fausser un résultat de combat qu'il héberge. En V1, on l'accepte comme limitation connue (le système Ghost n'étant pas positionné comme compétitif/classé au sens strict). Des garde-fous minimaux seront tout de même mis en place :

- le backend vérifie la cohérence globale du résultat (ex. pas plus de dégâts qu'un coup critique max théorique, pas de Pokémon invalide dans l'équipe envoyée) ;
- alternance de l'hôte entre les deux joueurs sur les combats successifs (limite l'exploitation répétée par un seul joueur) ;
- possibilité prévue pour une V2 : combat arbitré côté backend avec un vrai moteur (réimplémentation ou intégration d'un moteur de règles Pokémon côté serveur, type Showdown), si le besoin de fiabilité compétitive apparaît.

*Ce point est explicitement signalé pour validation — c'est un compromis structurel lié à la contrainte "pas de mod serveur", pas un oubli.*

---

## 10. API REST

```http
# Authentification
POST   /auth/session            # échange preuve Mojang → JWT

# Pokémon
GET    /players/{uuid}/pokemon
GET    /players/{uuid}/pc?box={n}
POST   /pokemon                 # idempotent via request_uuid
PATCH  /pokemon/{uuid}
DELETE /pokemon/{uuid}
POST   /pokemon/{uuid}/clone
POST   /pokemon/import-showdown
GET    /pokemon/{uuid}/export

# Équipe
GET    /players/{uuid}/team
PUT    /players/{uuid}/team

# Battle
POST   /battles                 # crée une BattleSession
GET    /battles/{uuid}
POST   /battles/{uuid}/result

# Admin
GET    /admin/pokemon/{uuid}
POST   /admin/pokemon/{uuid}/inspect
POST   /admin/players/{uuid}/audit

# Santé
GET    /health
```

Toutes les routes (hors `/auth/session` et `/health`) exigent un JWT valide. Toute opération sensible (modification, suppression, échange, combat, duplication) est revérifiée côté backend contre l'ownership réel, jamais déduite de ce que déclare le client (conforme à §24 Partie 1).

---

## 11. WebSocket — canaux et événements

```text
Connexion : wss://backend/ws?token={jwt}

C2S (client → backend)
├── JoinServerGroup(server_fingerprint, dimension)
├── LeaveServerGroup
├── PositionUpdate(x, y, z, dimension)
├── SendOutGhost(pokemon_uuid)
├── RecallGhost(pokemon_uuid)
├── BattleAction(battle_uuid, action)
└── Heartbeat

S2C (backend → client)
├── GhostEntitySpawn(player_uuid, pokemon_uuid, position)
├── GhostEntityMove(player_uuid, pokemon_uuid, position)
├── GhostEntityDespawn(player_uuid, pokemon_uuid)
├── BattleState(battle_uuid, state)
├── BattleEnded(battle_uuid, result)
├── Error(code, message)
└── HeartbeatAck
```

---

## 12. Anti-duplication / idempotence

Reprise du principe de la Partie 1, adapté au contexte sans serveur intermédiaire : chaque opération de création/modification sensible envoyée par le client REST porte un `request_uuid`. Le backend vérifie dans `idempotency_keys` avant traitement :

```text
Requête entrante (request_uuid = X)
   ↓
Existe déjà en base pour ce joueur/endpoint ?
   ↓ oui                          ↓ non
Renvoie la réponse             Traite la requête
originale stockée              Stocke la réponse sous request_uuid = X
(pas de re-traitement)
```

---

## 13. Sécurité

- Toute requête sensible passe par le JWT émis en §3, jamais par une déclaration brute du client.
- Le backend est la seule source de vérité sur l'ownership : `Ce pokemon_uuid appartient-il à ce player_uuid authentifié ?` est vérifié systématiquement avant modification/suppression/échange/combat.
- Rate limiting par joueur sur les endpoints sensibles (création, échange, combat) pour limiter l'abus depuis un client modifié.
- Le client ne peut jamais appeler directement les routes `/admin/*` : elles exigent un rôle admin porté par le JWT, attribué manuellement côté backend (pas d'auto-élévation possible depuis le jeu).

---

## 14. Cache

Cache mémoire côté **Ghost Client**, pas côté serveur Minecraft (puisqu'il n'existe pas) :

```text
Backend (source de vérité)
   ↓
Cache local au client, tant que le joueur est connecté
   ↓
Gameplay (lecture rapide, écriture toujours vers le backend)
```

Le cache n'est pas une priorité de la V1 (confirmé) : chaque lecture peut interroger le backend directement au départ, l'optimisation cache viendra si la latence perçue le justifie.

---

## 15. Ghost PC — pagination

```http
GET /players/{uuid}/pc?box=1
GET /players/{uuid}/pc?box=2
```

Chargement à la demande, jamais les 576 emplacements en une fois, conforme à la Partie 1.

---

## 16. Administration

```text
Admin (client avec droits) → Backend : /admin/pokemon/{uuid}/inspect
   ↓
Backend vérifie le rôle admin porté par le JWT
   ↓
Réponse : données complètes du Pokémon
```

Aucune route admin n'est accessible sans JWT à rôle élevé. Aucune logique admin ne transite par un quelconque composant serveur Minecraft (il n'y en a pas).

---

## 17. Logs

Confirmé : logging maximal en V1 (au-delà du strict minimum technique prévu en Partie 1 §30), couvrant :

- toutes les opérations CRUD Pokémon (avec request_uuid, player_uuid, horodatage) ;
- tous les événements de connexion/déconnexion au groupe de session ;
- tous les combats (actions, résultats) ;
- toutes les erreurs techniques (timeout, résolution Cobblemon, incompatibilité de version, packet invalide) ;
- toutes les actions admin.

Niveau de détail à affiner en Partie 3 (rotation, rétention, format structuré JSON pour exploitation).

---

## 18. Heartbeat / disponibilité backend

```text
Ghost Client ⇄ Ghost Backend : ping périodique (WebSocket Heartbeat / HeartbeatAck)

Plusieurs échecs consécutifs
   ↓
Backend jugé indisponible
   ↓
Client : "Le système Ghost est temporairement indisponible."
   (jamais d'exception brute affichée au joueur)

Combat actif au moment de la perte
   ↓
Combat interrompu → résultat = Draw (conforme Partie 1 §22)
```

---

## 19. Stratégie de développement — Test-Driven Development

Conformément à la demande, le développement suit un cycle TDD strict :

```text
Pour chaque feature :
   1. Écriture du test (backend et/ou mod) avant le code
   2. Implémentation minimale pour faire passer le test
   3. Refactor
   4. Commit
```

À chaque commit/ajout de prompt, la suite de tests complète est rejouée pour garantir une couverture maximale et détecter toute régression immédiatement.

### 19.1 Catégories de tests (reprises et adaptées de la Partie 1 §32)

**Backend** : création, modification, suppression, clone, ownership, équipes, UUID, transactions, concurrence, idempotence.

**Ghost Client** : rendu de l'entité fantôme, réception WebSocket, interpolation de mouvement, gestion de la perte de connexion, UI (création/édition/PC/import Showdown).

**Auth** : flux de vérification Mojang, émission/expiration/refresh JWT, rejet d'un token invalide ou expiré.

**Groupement de session** : regroupement correct par `server_fingerprint` + `dimension`, isolation entre groupes distincts, sortie de groupe à la déconnexion.

**Cobblemon (résolution de données)** : résolution d'identifiants espèces/attaques/formes/objets, détection d'incompatibilité de version, comportement de fallback propre.

**Battle** : Ghost vs Ghost, victoire/défaite/draw, déconnexion en cours de combat, backend indisponible en cours de combat, reset HP/PP/statut après combat, cohérence des résultats vérifiée côté backend (garde-fous §9.2).

**Compatibilité de visibilité** :

```text
Client Ghost + Client Ghost → visibilité mutuelle correcte
Client Ghost + Client normal → le client normal ne reçoit rien
Deux groupes de serveurs différents → aucune fuite d'événements entre groupes
```

---

## 20. Points restant ouverts pour la Partie 3

1. **Combat Ghost** : valider définitivement le modèle "client hôte + garde-fous" (§9) ou explorer une alternative pour la V1.
2. **Fingerprint de groupement serveur** derrière un proxy (Velocity/BungeeCord) — à affiner si le projet cible ce cas.
3. **Détail exact des DTOs REST/WebSocket** (schémas JSON complets par endpoint).
4. **Stratégie de déploiement du backend** (hébergement, CI/CD, environnements staging/prod).
5. **Politique de rétention/rotation des logs.**

La Partie 3 pourra se concentrer sur la spécification détaillée des contrats API (DTOs, codes d'erreur) et le plan de déploiement.
