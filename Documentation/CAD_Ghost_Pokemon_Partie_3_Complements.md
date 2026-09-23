# CAD Ghost Pokémon — Partie 3 : Compléments d'architecture

Cette partie répond aux trous identifiés après relecture des Parties 1 et 2, et complète l'architecture en conséquence. Elle ne remet pas en cause les choix déjà validés (peer-to-peer via backend, PostgreSQL, REST+WebSocket, pas de mod serveur) : elle les précise là où ils étaient encore flous.

---

## A. Portée gameplay — système fermé, sans interaction avec le monde Cobblemon réel

Confirmé : le Ghost Pokémon est un système **entièrement séparé** du gameplay Cobblemon normal.

- Aucune XP, aucune évolution automatique, aucun breeding/daycare.
- Aucun combat contre un Pokémon sauvage réel (cohérent avec l'absence d'entité serveur, Partie 2 §7).
- Toute évolution de niveau, de forme ou de stats d'un Ghost Pokémon passe **exclusivement** par une action manuelle du joueur (édition via UI/commande).

### A.1 Conséquence sur l'architecture

Ce point **simplifie** fortement plusieurs zones d'incertitude de la Partie 2 :

- Le combat Ghost (Partie 2 §9) reste un mini-jeu autonome entre deux Ghost Pokémon, sans jamais avoir à s'interfacer avec le moteur de combat "réel" de Cobblemon côté serveur. Le modèle "client hôte + garde-fous" reste donc adapté sans complexité supplémentaire.
- Pas besoin de capter d'événements Cobblemon serveur (capture, combat, changement d'XP) : le Ghost Backend n'a jamais besoin d'observer le serveur Minecraft, ce qui confirme et renforce le choix "pas de mod serveur".

### A.2 Passerelle Ghost → réel, uniquement via commande OP

Seule passerelle prévue : un administrateur (OP) peut matérialiser un Ghost Pokémon en un **vrai** Pokémon Cobblemon en utilisant directement les commandes de spawn natives de Cobblemon, côté serveur, à sa discrétion. Ce n'est **pas** une fonctionnalité du système Ghost lui-même — c'est un usage manuel, hors API, hors backend, qui ne nécessite aucun développement dans le mod ou le backend. On se contente donc de le documenter comme procédure admin, sans l'intégrer techniquement.

**Pas de conversion inverse** (réel → Ghost) prévue.

---

## B. Légalité des Pokémon créés

Règles de validation retenues, alignées sur les règles réelles des jeux Pokémon :

- **IVs** : plafonnés à 31 par statistique (0–31 par stat, 6 stats).
- **EVs** : répartition "comme un vrai Pokémon", c'est-à-dire :
  - maximum 252 par statistique ;
  - maximum 510 au total sur les 6 statistiques.

### B.1 Implémentation

Un service de validation métier (`PokemonLegalityService`) est ajouté côté **backend**, appelé systématiquement à la création et à la modification (`POST /pokemon`, `PATCH /pokemon/{uuid}`, `POST /pokemon/import-showdown`) :

```text
Requête de création/modification
   ↓
PokemonLegalityService.validate(data)
   ├── IVs : chaque stat ∈ [0, 31]
   ├── EVs : chaque stat ∈ [0, 252], somme ≤ 510
   ├── moveset : cohérent avec l'espèce/forme (résolu via données Cobblemon locales au moment de la création)
   └── ability : doit faire partie des abilities possibles pour l'espèce/forme
   ↓
Invalide → 422 avec détail des règles violées
Valide → persistance
```

La validation se fait côté backend (autorité), pas seulement côté client, pour empêcher un client modifié d'envoyer des données illégales directement à l'API.

---

## C. Accès à la création — ouvert à tous, sans quota

Confirmé : tout joueur possédant l'addon a accès complet à la création et l'édition de Ghost Pokémon, sans limite de quantité, sans cooldown, sans coût.

**Conséquence** : pas de logique de quota/économie à développer en V1. Ce point simplifie `POST /pokemon` (Partie 2 §10) — aucune vérification de plafond nécessaire, uniquement la validation de légalité (§B) et l'ownership.

*Point de vigilance pour plus tard (hors scope V1, à garder en tête)* : sans quota, le volume de lignes dans `pokemon` (Partie 2 §5.1) peut croître sans limite par joueur. Ce n'est pas un problème à l'échelle attendue du projet, mais si le nombre de joueurs devient important, un monitoring de croissance de la table sera utile (aucune action requise maintenant).

---

## D. Échange (trade) entre joueurs

Spécification du flux, atomique, avec proposition/acceptation, annulation possible sans timeout.

### D.1 Modèle de données

```sql
CREATE TABLE trades (
    uuid            UUID PRIMARY KEY,
    initiator_uuid  UUID NOT NULL REFERENCES players(uuid),
    recipient_uuid  UUID NOT NULL REFERENCES players(uuid),
    offered_pokemon UUID NOT NULL REFERENCES pokemon(uuid),
    requested_pokemon UUID NOT NULL REFERENCES pokemon(uuid),
    status          VARCHAR(16) NOT NULL, -- PENDING, ACCEPTED, CANCELLED, COMPLETED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ
);
```

### D.2 Flux

```text
Initiateur → Backend : POST /trades
   { recipient_uuid, offered_pokemon_uuid, requested_pokemon_uuid }
   ↓
Backend vérifie l'ownership des deux Pokémon (offered = initiateur, requested = destinataire)
   ↓
Trade créé, status = PENDING
   ↓ notification WebSocket au destinataire

Destinataire → Backend : POST /trades/{uuid}/accept
   ↓
Backend, dans une transaction unique :
   - reprend l'ownership des deux Pokémon
   - échange owner_uuid des deux lignes `pokemon`
   - status trade = COMPLETED
   ↓
Si l'un des deux Pokémon n'appartient plus au joueur attendu (déjà échangé/supprimé entre-temps)
   → transaction annulée intégralement, trade status = CANCELLED, erreur renvoyée

Annulation (par l'initiateur OU le destinataire, tant que PENDING)
   → Backend : POST /trades/{uuid}/cancel
   → status = CANCELLED
   → pas de timeout automatique : le trade reste PENDING indéfiniment tant que personne n'agit
```

L'atomicité est garantie par une transaction PostgreSQL unique sur l'étape d'acceptation (soit les deux `owner_uuid` changent ensemble, soit rien ne change).

### D.3 Endpoints ajoutés

```http
POST   /trades
POST   /trades/{uuid}/accept
POST   /trades/{uuid}/cancel
GET    /trades/{uuid}
GET    /players/{uuid}/trades          # trades en cours pour un joueur
```

### D.4 Événements WebSocket ajoutés

```text
S2C
├── TradeProposed(trade_uuid, initiator, offered, requested)
├── TradeAccepted(trade_uuid)
└── TradeCancelled(trade_uuid)
```

---

## E. Handshake de version Client ↔ Backend

Ajouté dès la V1, sur le modèle du versionnement déjà prévu pour les données Cobblemon (Partie 2 §6).

```text
Connexion WebSocket (ou premier appel REST de la session)
   ↓
Client envoie : client_version (ex. "1.4.2")
   ↓
Backend compare à sa table de compatibilité (min_supported_version, current_version)
   ↓
client_version < min_supported_version
   → connexion refusée, message clair : "Merci de mettre à jour l'addon Ghost."
client_version compatible mais < current_version
   → connexion acceptée, avertissement non bloquant affiché au joueur
client_version compatible
   → connexion normale
```

Le backend expose ces informations via un endpoint dédié :

```http
GET /version
   → { "current_version": "1.4.2", "min_supported_version": "1.3.0" }
```

Appelé par le client avant même l'authentification (§3 Partie 2), pour éviter d'engager un flux d'auth inutile sur un client trop ancien.

---

## F. Nettoyage des sessions orphelines (crash client)

Ajout d'un TTL de présence côté backend pour compléter le Heartbeat déjà prévu (Partie 2 §18).

```text
PlayerPresence (en mémoire backend)
   ├── player_uuid
   ├── server_fingerprint
   ├── dimension
   ├── position
   ├── active_ghost_pokemon (liste des Ghost Pokémon actuellement "sortis")
   └── last_heartbeat_at

Tâche planifiée (ex. toutes les 10s)
   ↓
Pour chaque PlayerPresence :
   si (now - last_heartbeat_at) > TTL (ex. 30s)
      → considérer le joueur déconnecté brutalement
      → diffuser GhostEntityDespawn pour chacun de ses active_ghost_pokemon
      → supprimer la PlayerPresence
```

Cela couvre le crash client, la perte réseau brutale, ou la fermeture forcée du jeu — cas non couverts par le `RecallGhost` propre (Partie 2 §8), qui suppose un client encore capable d'envoyer un message.

---

## G. Validation de cohérence des positions — écartée

Confirmé : pas de validation de plausibilité des positions envoyées par le client. La triche visuelle sur un système purement cosmétique (§A : aucune interaction avec le gameplay réel, aucun enjeu compétitif matériel) n'a pas d'intérêt à exploiter, donc pas de coût de développement/calcul à engager là-dessus. Le backend continue de relayer telles quelles les positions reçues (Partie 2 §4, §11), sans couche de vérification supplémentaire.

---

## H. Scalabilité multi-instance backend — hors scope

Confirmé hors scope pour le moment. L'architecture reste sur une **instance unique** du Ghost Backend, avec état de présence (`PlayerPresence`) et routage WebSocket en mémoire locale (Partie 2 §4), sans Redis ni bus de messages partagé.

*Point à garder en tête si le besoin apparaît plus tard* : le jour où une deuxième instance backend serait nécessaire, il faudra externaliser `PlayerPresence` et le routage WebSocket (ex. Redis pub/sub) — mais aucune action n'est requise dans l'implémentation actuelle. Le code du service de présence sera simplement écrit de façon à limiter le couplage direct à l'état mémoire local, pour ne pas fermer la porte à cette évolution.

---

## I. Sauvegarde PostgreSQL

Confirmé : tout est persisté en base, donc une stratégie de sauvegarde est nécessaire dès la V1 (le backend étant l'unique source de vérité, une perte de base = perte définitive et irréversible des données de tous les joueurs).

### I.1 Recommandation

```text
Sauvegarde complète (pg_dump ou snapshot infra) : quotidienne
WAL archiving (Point-In-Time Recovery) : activé en continu
Rétention : 
   - sauvegardes quotidiennes conservées 14 jours
   - une sauvegarde hebdomadaire conservée 3 mois
Test de restauration : à effectuer périodiquement (pas juste sauvegarder — vérifier que ça restaure)
```

Le choix de l'infrastructure exacte (managed PostgreSQL avec backup automatique type RDS/Cloud SQL, vs auto-hébergé avec cron + `pg_dump`) dépendra de l'hébergement retenu pour le backend — à préciser quand ce choix d'hébergement sera fait.

---

## J. Distribution du Ghost Client

Confirmé : publication sur **Modrinth** et **CurseForge**, les deux plateformes standards de l'écosystème Fabric/Cobblemon.

### J.1 Conséquence — mises à jour

Pas d'auto-updater interne prévu en V1 : les joueurs mettent à jour via leur launcher (Modrinth App, CurseForge/Overwolf, ou gestionnaire de modpack) comme pour n'importe quel mod Fabric classique. Cela s'articule bien avec le handshake de version (§E) : un client obsolète est détecté et invité à mettre à jour via les canaux standards, plutôt que via un mécanisme de mise à jour propriétaire.

---

## K. Flux d'authentification Mojang — risque noté et accepté

Confirmé comme accepté. Le flux `joinServer`/`hasJoined` (Partie 2 §3) reste la méthode retenue. À garder en tête sans blocage : ce flux utilise une primitive Mojang en dehors de son usage prévu (rejoindre un vrai serveur) ; il n'y a pas d'action à ce stade, simplement une vigilance si Mojang faisait évoluer ses règles d'usage de cette API à l'avenir.

---

## L. Internationalisation — FR et EN obligatoires dès la V1

Ajout à l'architecture du Ghost Client : toutes les chaînes affichées (UI, messages d'erreur, notifications de trade, avertissements de version) passent par un système de traduction standard Fabric (`lang/fr_fr.json`, `lang/en_us.json`), aucune chaîne en dur dans le code.

### L.1 Conséquence côté backend

Les messages d'erreur renvoyés par l'API (ex. §E "mise à jour requise", §B "IVs invalides") doivent être renvoyés sous forme de **codes** (ex. `ERROR_LEGALITY_IV_OUT_OF_RANGE`) et non de texte brut, pour que le client puisse les traduire localement plutôt que d'afficher du texte serveur non traduit.

```json
{
  "error_code": "ERROR_LEGALITY_EV_TOTAL_EXCEEDED",
  "details": { "total": 528, "max": 510 }
}
```

---

## Synthèse des ajouts à l'architecture (Partie 2 → mise à jour)

| Ajout | Composant impacté |
|---|---|
| `PokemonLegalityService` | Backend |
| Table `trades` + endpoints + événements WS | Backend + Ghost Client |
| Endpoint `GET /version` + handshake | Backend + Ghost Client |
| TTL de nettoyage `PlayerPresence` | Backend |
| Stratégie de backup PostgreSQL | Infrastructure |
| Système i18n (`fr_fr` / `en_us`) + codes d'erreur | Ghost Client + Backend |
| Documentation procédure admin (spawn réel via commande Cobblemon) | Documentation uniquement, aucun code |

Aucun de ces ajouts ne remet en cause les fondations posées en Partie 2 (peer-to-peer via backend, pas de mod serveur, PostgreSQL, REST+WebSocket) — ils les complètent.

---

## Points encore ouverts après cette partie

Pour rester honnête sur ce qui reste à trancher plus tard (hors scope immédiat, donc non bloquant) :

1. Choix d'hébergement définitif du backend (conditionne le détail exact de la stratégie de backup, §I).
2. Détail complet des DTOs REST/WebSocket (déjà noté en fin de Partie 2).
3. Éventuelle externalisation de `PlayerPresence` si le besoin multi-instance apparaît un jour (§H).
