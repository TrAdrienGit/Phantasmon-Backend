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
| D-24 | Échanges et combats uniquement par la roue, PC uniquement par sa touche | Acceptée | 2026-10-05 |
| D-25 | Formats de combat : règles de Pokémon Showdown, vérifiées par le backend | Acceptée | 2026-10-06 |
| D-26 | Rôle administrateur : fichier de pseudos, commandes client, contrôles côté backend | Acceptée | 2026-10-06 |
| D-27 | Phantasmon Network développé sur `dev` | Acceptée | 2026-10-07 |
| D-28 | Hub Anchors créables par tout joueur | Acceptée | 2026-10-07 |
| D-29 | Entrée dans le Global Hub sur consentement explicite | Acceptée | 2026-10-07 |
| D-30 | Hub Anchors partagés au sein d'un serveur, jamais entre serveurs | Acceptée | 2026-10-07 |
| D-31 | Spectateurs d'un combat Ghost | Acceptée | 2026-10-07 |
| D-32 | Combat solo d'un admin contre un miroir de son équipe | Acceptée | 2026-10-07 |
| D-33 | Terrain d'un combat Ghost visible par les joueurs alentour | Acceptée | 2026-10-07 |

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
  regroupés. Commande de test `/phantasmon debug fingerprint <valeur>` (devenue `/phantasmon admin debug fingerprint`, D-26 ; persistée dans
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

## D-24 — Échanges et combats uniquement par la roue, PC uniquement par sa touche

- **Contexte** : TODO-22, demande d'Adrien (2026-10-05) : se libérer des touches et commandes en double.
- **Décision** : plus aucune commande ni touche pour ouvrir le PC, inviter à un échange ou à un combat, ni de
  commande `sendout` / `recall` (touche H et overlay Ghost à la place). Échanges et
  combats démarrent depuis la roue d'interaction de Cobblemon ; le PC s'ouvre avec sa touche (P). Les commandes
  `trade join|decline` et `battle join|decline|timer` restent, uniquement pour les boutons cliquables du chat
  (choix d'Adrien). L'échange asynchrone par commandes est retiré du client ; l'API REST reste.
- **Conséquences** : touches G et B supprimées ; il faut être assez près de l'autre joueur pour ouvrir la roue sur lui.

## D-25 — Formats de combat : règles de Pokémon Showdown, vérifiées par le backend

- **Contexte** : TODO-24 (étude `research/smogon-regulations.md` côté client). Pas d'API Smogon ; Pokémon Showdown
  publie ses formats, tiers et données en JavaScript public (licence MIT).
- **Décision (Adrien)** : dans le lobby, les deux joueurs choisissent ensemble un format parmi 20 : « Libre » (défaut,
  sans règle) puis 8 formats National Dex et 11 formats Gen 9 en simple (OU, Ubers, UU, RU, NU, PU, ZU, LC, Monotype,
  1v1, Anything Goes). Le backend télécharge les données de Showdown à chaque démarrage (mise à jour journalisée,
  copie en cache, copie de secours livrée), résout les règles comme Showdown et vérifie les équipes ; les Pokémon qui
  enfreignent le format sont entourés en rouge et « Prêt » est refusé. Les règles que le moteur applique lui-même
  (Sleep Clause Mod, Terastal Clause…) et le niveau du format (100, 5 en LC ; choix de Claude, Adrien n'ayant pas
  tranché) sont transmis à l'hôte. En 1v1, seul le lead combat.
- **Limites** : sous-ensemble du validateur de Showdown — les movesets ne sont pas revérifiés contre les learnsets,
  les bannissements combinés (« A + B ») sont ignorés.

## D-26 — Rôle administrateur : fichier de pseudos, commandes client, contrôles côté backend

- **Contexte** : TODO-25 (D-20 avait reporté les fonctions d'administration).
- **Décision (Adrien)** : comme les ops d'un serveur Minecraft, un fichier `admins.txt` à côté du backend, un pseudo
  par ligne. Pas de touche, seulement des commandes `/phantasmon admin` : ouvrir et manipuler le PC d'un joueur comme
  le sien, arrêter le combat ou le lobby d'un joueur (match nul), redémarrer le backend, plus les commandes de
  ping et de débogage déplacées ici.
- **Mise en œuvre** : le backend décide (pseudo de dernière connexion ; fichier relu dès qu'il change) et vérifie
  chaque requête ; le client ne fait que montrer les commandes (`GET /admin/me`). Les modifications d'un admin
  passent par les règles normales du service, au nom du propriétaire. Redémarrage dans le même processus (le backend
  est lancé à la main, sans superviseur pour le relancer).
- **Conséquence** : un pseudo est réattribuable après un changement de nom Mojang ; tenir le fichier à jour.

## D-27 — Phantasmon Network développé sur `dev`

- **Contexte** : Adrien déclare le Core terminé (2026-10-07) ; seul du polish UI / VFX / SFX / animations continue.
  La note de recherche `Phantasmon_Evolution_InterServeurs.md` prévoyait un « fork » pour expérimenter Network sans
  déstabiliser le Core.
- **Décision (Adrien)** : ni nouveaux dépôts, ni branche dédiée ; Network est développé directement sur `dev`, dans
  `Phantasmon-Client` et `Phantasmon-Backend`, en même temps que le polish du Core.
- **Conséquences** : pas de fusion ni de report entre branches. Network arrive sur `dev` par étapes ; tant
  qu'aucun Anchor n'existe, il ne change rien au comportement du Core, donc aucun interrupteur n'est prévu. Chaque
  étape doit laisser `dev` compilable et ses tests verts. Cahier des charges :
  [`specifications/network-cahier-des-charges.md`](../specifications/network-cahier-des-charges.md).

## D-28 — Hub Anchors créables par tout joueur

- **Contexte** : sans mod serveur, rien ne permet de vérifier qu'un joueur a le droit de poser un point d'accès au
  Global Hub sur un serveur donné.
- **Décision (Adrien)** : tout joueur authentifié peut créer un Anchor, sans rôle particulier, mais **un seul**.
- **Garde-fous** : un Anchor par joueur, nom unique par serveur, suppression par le créateur ou par un admin
  (D-26), entrée dans le Hub uniquement sur consentement (D-29).
- **Limite assumée** : le backend ne peut pas vérifier que le créateur se trouvait réellement sur ce serveur à cette
  position.

## D-29 — Entrée dans le Global Hub sur consentement explicite

- **Contexte** : conséquence de D-28 ; un Anchor posé par un inconnu ne doit pas suffire à diffuser la présence des
  joueurs qui le traversent.
- **Décision (proposée par Claude, retenue dans le cahier des charges Network)** : entrer dans un Anchor affiche une
  invitation ; le joueur rejoint le Hub seulement s'il accepte, ou s'il a activé l'acceptation automatique pour cet
  Anchor.
- **Conséquence** : aucune position n'est transmise au Hub sans action du joueur ; le Hub ne transmet que des
  coordonnées relatives à l'Anchor, jamais l'adresse du serveur.

## D-30 — Hub Anchors partagés au sein d'un serveur, jamais entre serveurs

- **Contexte** : précision d'Adrien (2026-10-07) après le premier test des avatars. Exemple : quatre joueurs, deux
  serveurs ; J1 et J2 sur S1, J3 et J4 sur S2. Chacun pose son Anchor (J1 pose A1 sur S1, etc.).
- **Décision (Adrien)** :
  - J2 voit l'Anchor de J1 et peut l'utiliser pour entrer dans le Hub : **un serveur partage ses Anchors entre ses
    joueurs** ;
  - J3 et J4 ne voient pas A1 : **deux serveurs ne se partagent jamais leurs Anchors** ; de même, l'Anchor créé par
    J4 sur S2 sert à J3 et J4 ;
  - chaque joueur ne pose qu'un Anchor (D-28).
- **Ce que cela corrige** : la première version (N2) n'envoyait jamais l'avatar d'un joueur aux membres de même
  empreinte et même dimension, en supposant qu'ils se voyaient déjà pour de vrai. Au test du 2026-10-07, les deux
  comptes partageaient l'empreinte `testsession` : aucun avatar. Or deux joueurs d'un même serveur peuvent être dans
  le Hub par deux Anchors différents, loin l'un de l'autre. Une première correction, le même jour, avait rendu les
  Anchors strictement personnels (migration `V12`) : c'était un contresens, annulé par `V13`.
- **Mise en œuvre** :
  - backend : `GET /hub/anchors?server_fingerprint=…&dimension=…` liste les Anchors du serveur ; `HubJoin
    { anchor_uuid }` accepte n'importe quel Anchor du serveur et de la dimension de la présence du joueur
    (`ERROR_HUB_ANCHOR_WRONG_SERVER` sinon) ; nom unique par serveur (`V12` avait retiré les index, `V13` les remet,
    en numérotant d'abord les noms en double) ;
  - visibilité : chaque membre du Hub reçoit **tous** les autres. Un avatar montre où son joueur se tient dans le
    Hub ; le client ne le masque (avec son Ghost) que si le vrai joueur est chargé et se tient dans le cube de
    l'Anchor par lequel ce client est entré : ils sont alors dans le même Anchor et se voient pour de vrai — choix de
    Claude (la règle « à moins de 2 blocs de l'emplacement de l'avatar » du premier jet ratait ce cas, voir le journal
    client §4.96) ;
  - client : l'entité d'un avatar porte un UUID dérivé de celui du joueur, pour ne jamais entrer en conflit avec le
    vrai joueur chargé dans le même monde.
- **Conséquences** : D-28 et D-29 restent valables telles quelles.

## D-31 — Spectateurs d'un combat Ghost

- **Contexte** : Adrien (2026-10-07) veut qu'on puisse regarder un combat « comme dans Cobblemon ». Cobblemon gère
  ses spectateurs côté serveur (`SpectateBattleHandler`) ; un combat Ghost tourne sur le client hôte, sans serveur.
- **Décision** :
  - entrée « Regarder le combat Ghost » dans la roue d'interaction (R sur un joueur ou sur un avatar du Global Hub),
    comme l'entrée « Regarder » de Cobblemon ; le backend vérifie que la cible est en combat Ghost ;
  - le moteur de l'hôte fournit le contenu : son flux spectateur (`PokemonBattle.sendSpectatorUpdate`, intercepté par
    `PokemonBattleSpectatorMixin`) et, pour chaque nouveau spectateur, le même rattrapage que Cobblemon
    (`BattleInitializePacket` sans camp + historique du chat) ; le backend recopie à chaque spectateur
    (`BattleSpectatorPacket`), jamais le relais privé de l'invité ;
  - le spectateur rejoue ces paquets dans l'interface de Cobblemon, qui s'ouvre en mode spectateur ; la scène (Pokémon
    devant chaque dresseur, animations, Méga / Z / Téra) est reconstruite localement comme chez les joueurs ; musique
    de combat du pack ; le bouton Retour de Cobblemon arrête de regarder ;
  - regarder occupe le joueur (ni invitation ni lobby pendant ce temps) ; les deux joueurs voient « X regarde le
    combat ».
- **Choix de Claude** : pas de limite de spectateurs ni de distance au-delà de la portée de la roue (10 blocs, mur
  exclu) ; l'hôte n'envoie le flux spectateur que s'il y a au moins un spectateur.

## D-32 — Combat solo d'un admin contre un miroir de son équipe

- **Contexte** : Adrien (2026-10-07) veut une commande admin pour « lancer un combat contre soi-même ».
- **Décision (interprétation de Claude)** : `/phantasmon admin battle solo` démarre un vrai combat Ghost contre un
  miroir de l'équipe Ghost de l'admin, joué par l'IA aléatoire de Cobblemon sur son client (l'hôte). Sans lobby, format
  Libre, intro normale. C'est un combat en direct pour le backend (`LiveBattle` dont hôte et invité sont l'admin) :
  on peut le regarder, l'arrêter (`admin stopbattle`) ; il n'est **pas** stocké (`battle_sessions` interdit un joueur
  contre lui-même). Le miroir a son propre uuid (dérivé de celui de l'admin) : l'interface de Cobblemon distingue ainsi
  les deux camps.

## D-33 — Terrain d'un combat Ghost visible par les joueurs alentour

- **Contexte** : bug signalé par Adrien (2026-10-07) : dans le Hub, les Pokémon d'un joueur en combat n'apparaissaient
  que chez ceux qui regardaient le combat en spectateur. Dans Cobblemon, les Pokémon d'un combat sont de vraies
  entités que tout joueur proche voit.
- **Décision (choix d'Adrien entre deux options)** :
  - sont **témoins** d'un combat les joueurs du même serveur et de la même dimension que l'hôte ou l'invité, et, si
    l'un des deux est dans le Global Hub, tous les membres du Hub ; ni les deux joueurs ni les spectateurs. Exemple :
    J1 (serveur S1, dans le Hub) combat J3 (serveur S2) ; J2 sur S1 hors Hub et J4 dans le Hub voient le terrain ;
  - les témoins voient la **scène complète** — Pokémon devant leur dresseur (ou son avatar du Hub), sorties, rappels,
    K.O., animations d'attaque, Méga / Primo / Z / Téra — sans écran de combat, caméra, chat ni musique ;
  - le backend tient la liste des témoins (réévaluée chaque seconde) et recopie le flux spectateur de l'hôte
    (`BattleFieldPacket`) ; chaque nouveau témoin reçoit de l'hôte le terrain tel qu'il est.
- **Choix de Claude** : un dresseur absent chez le témoin (autre serveur hors Hub, miroir du combat solo) : ses
  Pokémon se placent à 7 blocs devant l'autre dresseur, face à lui ; si aucun dresseur n'est chargé, les sorties
  attendent qu'un des deux le soit. Les Méga / Z / Téra vus de loin se réduisent à leur apogée (éclair, gerbe,
  changement de modèle, lueur Téra), entendue depuis le Pokémon.
- **Conséquences** : l'hôte envoie son flux spectateur dès qu'il y a un spectateur **ou** un témoin ; plusieurs
  combats peuvent être vus en même temps (une scène par combat, `BattleFieldScenes`).
