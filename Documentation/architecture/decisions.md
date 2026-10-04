# Journal des décisions d'architecture

> **Document miroir** : identique dans `Phantasmon-Backend/Documentation/architecture/` et
> `Phantasmon-Client/Documentation/architecture/`. Toute modification doit être reportée dans les deux.

Ce journal recense les décisions qui **précisent, complètent ou s'écartent** du cahier des charges
([`specifications/`](../specifications/README.md)). Chaque entrée suit le même format : contexte, décision,
conséquences. Une décision n'est jamais supprimée : si elle est remplacée, son statut passe à « Remplacée par D-xx ».

Pour ajouter une décision : prendre le numéro suivant, dater, et mettre à jour l'index ci-dessous.

## Index

| N° | Décision | Statut | Date |
|---|---|---|---|
| D-01 | Aucun mod serveur : architecture « pair-à-pair via backend » | Acceptée | 2026-09-23 |
| D-02 | Légalité vérifiée par le backend limitée aux IV/EV | Acceptée | 2026-09-26 |
| D-03 | Une seule équipe active par joueur, sans équipes nommées | Acceptée | 2026-09-26 |
| D-04 | PC et équipe exclusifs, déplacement-ou-échange uniforme, boîtes 6×5 | Acceptée | 2026-09-27 |
| D-05 | Combat : le client hôte exécute la pile de combat de Cobblemon | Acceptée | 2026-10-03 |
| D-06 | Échange en direct par WebSocket, en plus de l'échange REST asynchrone | Acceptée | 2026-10-02 |
| D-07 | Historique des échanges sans clé étrangère vers `pokemon` (V7) | Acceptée | 2026-10-02 |
| D-08 | `POST /battles` : équipe adverse dérivée côté serveur | Caduque (route retirée, SEC-3) | 2026-09-26 |
| D-09 | Pas d'endpoints `team`, `import-showdown`, `export` côté backend | Acceptée | 2026-09-26 |
| D-10 | Seul un Pokémon de l'équipe peut sortir ; `sendout` = emplacement 1 | Acceptée | 2026-09-29 |
| D-11 | Le mouvement du Ghost suit `PositionUpdate` et est lissé côté client | Acceptée | 2026-10-03 |
| D-12 | Accès au PC par touche et commande, pas par un bloc | Acceptée | 2026-09-27 |
| D-13 | Interfaces graphiques dessinées sur un canevas maison 1600×900 | Acceptée | 2026-10-02 |
| D-14 | Correspondance Showdown → Cobblemon par normalisation algorithmique | Acceptée | 2026-09-26 |
| D-15 | Mixins autorisés, en échec explicite au démarrage | Acceptée | 2026-10-03 |
| D-16 | Pas de révocation des refresh tokens en V1 | Acceptée | 2026-09-25 |
| D-17 | Spring Boot 4.1 au lieu de 3.x | Acceptée | 2026-09-23 |
| D-18 | Empreinte de serveur = SHA-256 de l'adresse ; surcharge de test | Acceptée | 2026-09-29 |
| D-19 | `PATCH /pokemon` remplace `data` en entier | Acceptée | 2026-09-26 |
| D-20 | Fonctions d'administration et limitation de débit reportées | Acceptée, débit WebSocket ajouté (SEC-5) | 2026-09-26 |
| D-21 | Positions des joueurs visibles par tout leur groupe : limite assumée | Acceptée | 2026-10-04 |
| D-22 | Ghost contre Pokémon normal = copie de l'équipe Cobblemon d'un joueur | Acceptée | 2026-10-04 |
| D-23 | Lobby de combat (aperçu d'équipe, lead caché) avant chaque combat en direct | Acceptée | 2026-10-04 |

---

## D-01 — Aucun mod serveur : architecture « pair-à-pair via backend »

- **Contexte** : la Partie 1 du CAD décrivait un « Ghost Server Addon » installé sur le serveur Minecraft.
- **Décision** : aucun code Phantasmon sur le serveur Minecraft. Chaque client parle directement au backend
  (REST + WebSocket), qui est la seule autorité. Validée dans la Partie 2 du CAD.
- **Conséquences** : les Ghost sont des entités locales à chaque client ; le regroupement des joueurs se fait
  par empreinte de serveur (D-18) ; le combat doit être exécuté par un client (D-05). Les schémas d'architecture
  de la Partie 1 (§2, §27, §41, §42, §46, §49, §53) sont obsolètes.

## D-02 — Légalité vérifiée par le backend limitée aux IV/EV

- **Contexte** : la Partie 3 §B demande aussi la cohérence attaques/talent avec l'espèce. Le backend n'a aucune
  donnée Cobblemon (principe de non-duplication).
- **Décision** : `PokemonLegalityService` vérifie IV ∈ [0, 31], EV ∈ [0, 252] et total EV ≤ 510. La cohérence
  avec l'espèce est assurée côté client par l'éditeur : seuls les talents de l'espèce, les objets de combat
  (tag `#cobblemon:held/is_held_item`) et des attaques distinctes sont proposés.
- **Conséquences** : un client modifié peut enregistrer une combinaison incohérente. Le moteur Cobblemon la
  joue telle quelle en combat. Accepté pour la V1 (le système n'est pas classé).

## D-03 — Une seule équipe active par joueur, sans équipes nommées

- **Contexte** : la Partie 1 §15 évoque « plusieurs équipes » ; la Partie 2, l'OpenAPI et le schéma ne modélisent
  qu'une équipe active.
- **Décision** : l'équipe est portée par `pokemon.team_slot` (1 à 6). Pas de table `teams`.
- **Conséquences** : des équipes multiples nommées nécessiteraient une nouvelle table et une migration. Point à
  reconfirmer si le besoin réapparaît.

## D-04 — PC et équipe exclusifs, déplacement-ou-échange uniforme, boîtes 6×5

- **Contexte** : la première version permettait à un Pokémon d'être à la fois dans le PC et dans l'équipe.
- **Décision** : un Pokémon a **soit** `box_id`/`box_slot`, **soit** `team_slot`. `PATCH /pokemon/{uuid}` avec
  une destination (`team_slot` ou `box_id`+`box_slot`) déplace le Pokémon ; si la destination est occupée, les
  deux Pokémon **échangent** leur place, quelle que soit la combinaison (PC↔PC, PC↔équipe, équipe↔équipe).
  Boîtes réduites de 6×6 à **6×5** (30 cases, 480 au total), migration `V6`.
- **Conséquences** : l'échange respecte l'index unique en libérant d'abord l'ancienne place (flush), puis en
  occupant la nouvelle. L'écran PC et ses glisser-déposer n'ont besoin d'aucune logique serveur supplémentaire.

## D-05 — Combat : le client hôte exécute la pile de combat de Cobblemon

- **Contexte** : sans mod serveur, le moteur de combat Cobblemon (serveur) ne peut pas être utilisé normalement
  (CAD Partie 2 §9).
- **Décision** : l'un des deux clients (l'**hôte**) fait tourner la pile serveur de Cobblemon elle-même
  (`PokemonBattle`, Showdown via GraalJS, interpréteur) sur un thread dédié. L'interface affichée est celle de
  Cobblemon. Le backend relaie les paquets Cobblemon encodés (`BattlePacket`) et les choix de l'invité
  (`BattleChoice`), désigne l'hôte en **alternance** entre deux mêmes joueurs (`battle_sessions.host_uuid`, V8),
  et enregistre le résultat. Chrono optionnel de 90 s, activable une fois par l'un ou l'autre joueur.
- **Écart avec le CAD** : une déconnexion termine le combat en `ABORTED` sans vainqueur (le CAD parle de « match
  nul ») ; les messages `BattleAction`/`BattleState` prévus sont remplacés par le relais de paquets.
- **Conséquences** : un client hôte modifié peut fausser un résultat (limitation assumée, Partie 2 §9.2). Le
  client a besoin de Mixins pour intercepter les choix et les animations (D-15).

## D-06 — Échange en direct par WebSocket, en plus de l'échange REST asynchrone

- **Contexte** : l'échange par commandes (UUID du joueur + deux UUID de Pokémon) a été jugé « très pénible » au
  premier test à deux comptes (2026-09-29).
- **Décision** : ajout d'un échange en direct (écran dédié). Négociation en mémoire (`LiveTradeService`), seule
  l'exécution finale est transactionnelle. Seuls les Pokémon de l'**équipe active** sont échangeables, et chacun
  prend **l'emplacement d'équipe** de celui qu'il remplace. Tout changement d'offre remet « prêt » à faux pour les
  deux. L'échange REST asynchrone (CAD Partie 3 §D) reste disponible.
- **Conséquences** : chaque échange en direct crée une ligne `trades` `COMPLETED` (historique commun aux deux modes).

## D-07 — Historique des échanges sans clé étrangère vers `pokemon` (V7)

- **Contexte** : les FK `trades.offered_pokemon`/`requested_pokemon → pokemon` (`ON DELETE RESTRICT`)
  empêchaient de supprimer tout Pokémon ayant déjà été échangé (erreur 500).
- **Décision** : migration `V7` qui retire ces deux FK et indexe les colonnes. La seule protection utile (ne pas
  supprimer un Pokémon engagé dans un échange `PENDING`) est appliquée par le code :
  `ERROR_POKEMON_IN_PENDING_TRADE` (409).
- **Conséquences** : les lignes terminées sont un historique, comme `battle_sessions.team_a`/`team_b`.

## D-08 — `POST /battles` : équipe adverse dérivée côté serveur

- **Contexte** : la requête ne contient qu'une équipe, mais `battle_sessions` exige `team_a` et `team_b`.
- **Décision** : `team` est toujours l'équipe de l'appelant (propriété revérifiée). L'équipe adverse est lue en
  base (Pokémon de l'adversaire avec `team_slot` non nul). La session démarre `ACTIVE`.
- **Conséquences** : aucun joueur ne peut imposer l'équipe de l'autre. Cet endpoint REST n'est pas utilisé par le
  combat en direct (D-05), qui crée sa propre ligne `battle_sessions`.
- **Caduque (2026-10-04)** : `POST /battles` et `POST /battles/{uuid}/result` ont été retirés (audit de sécurité,
  SEC-3) : ils permettaient d'ouvrir un combat sans l'accord de l'adversaire et de déclarer le vainqueur depuis
  n'importe quel participant.

## D-09 — Pas d'endpoints `team`, `import-showdown`, `export` côté backend

- **Décision** :
  - `GET/PUT /players/{uuid}/team` : inutile, le client filtre `team_slot` dans `GET /players/{uuid}/pokemon` et
    déplace par `PATCH`.
  - `POST /pokemon/import-showdown` : l'analyse du format Showdown et la conversion des identifiants se font côté
    client (qui a les données Cobblemon), puis `POST /pokemon` classique.
  - `GET /pokemon/{uuid}/export` : remplacé par un export **côté client** (2026-10-03, TODO-11) : le client a déjà
    les Pokémon et les noms anglais de Cobblemon (que le backend n'a pas) ; voir `architecture/showdown-import.md`
    (dépôt Client).
- **Conséquences** : l'OpenAPI ne décrit que l'existant ; les éléments manquants sont suivis dans `project/status.md`.

## D-10 — Seul un Pokémon de l'équipe peut sortir ; `sendout` = emplacement 1

- **Décision** : `SendOutGhost` refuse un Pokémon du PC (`ERROR_POKEMON_NOT_IN_TEAM`). Côté client,
  `/phantasmon sendout` et la touche **O** basculent sortie/rappel du Pokémon en **emplacement 1** de l'équipe
  (plus d'UUID à saisir).

## D-11 — Le mouvement du Ghost suit `PositionUpdate` et est lissé côté client

- **Décision** : pas de message « déplacement de Ghost » dédié. La position du propriétaire (envoyée chaque
  seconde) déclenche `GhostEntityMove`. Chaque client fait suivre le Ghost en douceur, à partir de l'entité
  joueur locale quand elle est chargée (sans latence), sinon de la dernière position reçue. Après 5 s
  d'immobilité du propriétaire, le Ghost se promène dans un rayon de 10 blocs. Le Ghost ne pousse pas et n'est
  pas poussé (`noPhysics`).
- **Conséquences** : aucune autorité serveur sur la position (CAD Partie 3 §G). Deux clients peuvent voir le
  Ghost à des positions légèrement différentes.

## D-12 — Accès au PC par touche et commande, pas par un bloc

- **Contexte** : CAD Partie 1 §13 privilégie un bloc PC dans le monde, avec repli sur une touche.
- **Décision** : repli retenu, car un bloc nécessiterait du contenu serveur (D-01). Touche **P** ou `/phantasmon pc`.

## D-13 — Interfaces graphiques dessinées sur un canevas maison 1600×900

- **Décision** : PC, éditeur et échange héritent de `PhantasmonCanvasScreen` : mise en page dans l'espace
  1600×900 px de la maquette, mise à l'échelle unique (80 % de la fenêtre), textures générées depuis le CSS de
  la maquette, aucun widget vanilla. Couleurs de type : palette officielle des jeux actuels.
- **Conséquences** : rendu fidèle à la maquette et cohérent entre écrans ; champs de saisie et listes déroulantes
  réimplémentés à la main.

## D-14 — Correspondance Showdown → Cobblemon par normalisation algorithmique

- **Contexte** : CAD Partie 4, phase 6 : « la table de correspondance se construit à cette phase ».
- **Décision** : pas de table figée. Espèces, formes et attaques : minuscules sans séparateurs (`Body Slam` →
  `bodyslam`) ; talents et objets : minuscules avec `_` (`Assault Vest` → `assault_vest`) ; courte liste
  d'exceptions pour les espèces à tiret (Ho-Oh, Porygon-Z, Nidoran-M/F, Jangmo-o…). Règles vérifiées sur les
  fichiers de données de Cobblemon.

## D-15 — Mixins autorisés, en échec explicite au démarrage

- **Décision** : 9 Mixins côté client (interception des choix de combat, exécution de Showdown sans serveur,
  animations d'attaque, roue d'interaction, nom des Ghost toujours affiché, Ghost jamais pris pour des Pokémon sauvages). `defaultRequire = 1` : si une cible change après une mise à jour de
  Cobblemon ou de Minecraft, le jeu refuse de démarrer avec une erreur Mixin explicite, plutôt que de se
  comporter faussement en silence.
- **Conséquences** : chaque mise à jour de Cobblemon impose de revalider la liste des Mixins
  (`Phantasmon-Client/Documentation/architecture/mixins.md`).

## D-16 — Pas de révocation des refresh tokens en V1

- **Décision** : `POST /auth/refresh` émet un nouveau couple de jetons, mais l'ancien refresh token reste valide
  jusqu'à son expiration (7 jours). Une vraie révocation demanderait un stockage côté backend.

## D-17 — Spring Boot 4.1 au lieu de 3.x

- **Contexte** : le CAD indiquait Spring Boot 3.x ; le projet a été généré en 4.1.1.
- **Conséquences connues** : certaines auto-configurations sont dans des modules séparés à déclarer
  explicitement (`spring-boot-flyway`, `spring-boot-restclient`, `spring-boot-webmvc-test`). Sans eux, la
  fonctionnalité est **silencieusement** inactive. Jackson 3 : `ObjectMapper` est dans `tools.jackson.databind`.

## D-18 — Empreinte de serveur = SHA-256 de l'adresse ; surcharge de test

- **Décision** : `server_fingerprint` = SHA-256 de l'adresse saisie pour rejoindre le serveur, ou
  `"singleplayer"` pour un monde local.
- **Conséquences** : lors d'un test « Ouvrir au LAN », l'hôte (`singleplayer`) et l'invité (hash) ne sont pas
  regroupés. Commande de test `/phantasmon debug fingerprint <valeur>` (persistée dans
  `config/phantasmon-fingerprint-override.txt`) ; à retirer quand un vrai serveur dédié sera utilisé. Derrière un
  proxy (Velocity/BungeeCord), deux serveurs partageant la même adresse seraient fusionnés (limite CAD Partie 2 §4).

## D-19 — `PATCH /pokemon` remplace `data` en entier

- **Décision** : si `data` est fourni, il **remplace** tout le JSONB (pas de fusion champ par champ). L'éditeur
  client renvoie donc le `data` complet en conservant les clés qu'il ne gère pas.

## D-20 — Fonctions d'administration et limitation de débit reportées

- **Décision** : les routes `/admin/*`, le rôle admin dans le JWT (CAD Partie 2 §13/§16) et la limitation de débit
  par joueur ne sont pas implémentés. La conversion Ghost → Pokémon réel reste une procédure manuelle d'OP avec les
  commandes de Cobblemon (CAD Partie 3 §A.2), sans code.
- **Mise à jour (2026-10-04, SEC-5)** : une limite de débit existe désormais sur le WebSocket (40 messages par
  seconde et par connexion, rafales jusqu'à 200, `ERROR_WS_RATE_LIMITED`). Rien côté REST, pas de rôle admin.

## D-21 — Positions des joueurs visibles par tout leur groupe : limite assumée

- **Contexte** : audit de sécurité, SEC-6. L'empreinte d'un serveur est le SHA-256 de son adresse (D-18),
  calculable par n'importe qui. Un joueur authentifié peut donc rejoindre le groupe d'un serveur sans y être
  connecté et recevoir chaque seconde la position des joueurs qui ont un Ghost sorti (`GhostEntitySpawn` /
  `GhostEntityMove`).
- **Options écartées** : n'envoyer la position qu'aux membres qui se déclarent proches (les positions viennent des
  clients, donc contournable) ; arrondir les positions (Ghost moins bien placé).
- **Décision (Adrien)** : limite **assumée et documentée** (LIM-9). Phantasmon vise des serveurs entre joueurs de
  confiance ; un joueur qui ne veut pas être localisable ne sort pas de Ghost.
- **Conséquences** : à revoir avant toute ouverture à des serveurs publics.

## D-22 — Ghost contre Pokémon normal = copie de l'équipe Cobblemon d'un joueur

- **Contexte** : la Partie 1 §31 souhaite des combats Ghost ↔ Pokémon normal ; la Partie 3 §A (prioritaire) exclut tout
  combat contre un Pokémon sauvage réel et garde le système Ghost fermé. Sans mod serveur, un vrai Pokémon (entité et
  combats côté serveur) ne peut pas entrer dans un combat Ghost.
- **Options écartées** : Ghost contre Pokémon sauvage réel (impossible sans mod serveur, exclu par la Partie 3) ;
  entraînement solo seul.
- **Décision (Adrien)** : en combat en direct, chaque joueur choisit ses Ghost **ou une copie** de sa vraie équipe
  Cobblemon, lue sur son client. Le combat se joue sur des copies jetables : aucune XP, aucun dégât gardé, rien n'est
  écrit côté serveur — la Partie 3 reste respectée. Même protocole, même moteur, même hôte.
- **Conséquences** : la copie est validée comme un Ghost (bornes, légalité) mais vient du client : un client modifié
  pourrait annoncer une équipe qu'il n'a pas (LIM-10, dans la lignée de LIM-1).

## D-23 — Lobby de combat (aperçu d'équipe, lead caché) avant chaque combat en direct

- **Contexte** : demande d'Adrien (2026-10-04), reprise du « Team Preview » de Showdown. Jusque-là, accepter une
  invitation lançait le combat aussitôt, avec l'équipe choisie dans le chat.
- **Décision** : une invitation acceptée ouvre un lobby (même direction artistique que l'écran d'échange). Chaque
  joueur voit son équipe complète et seulement le **modèle et le nom** des Pokémon adverses (ni niveau, ni objet, ni
  capacités, ni IV/EV — filtrés par le backend, pas par l'écran), choisit Ghost ou équipe Cobblemon, choisit son lead
  (jamais transmis à l'adversaire) et se déclare prêt. Règles choisies par Claude et annoncées à Adrien : changer
  d'équipe retire le « prêt » de l'adversaire ; être prêt verrouille équipe et lead ; le timer du lobby (150 s)
  donne le premier Pokémon comme lead à qui n'est pas prêt, puis active d'office le chrono de combat (90 s). Les
  Ghost sont rappelés dès l'ouverture du lobby.
- **Conséquences** : nouveaux messages `BattleLobby*` ; `BattleSessionStarted` part quand les deux sont prêts, équipes
  réordonnées lead en premier. L'animation de lancement et la musique viendront plus tard.
