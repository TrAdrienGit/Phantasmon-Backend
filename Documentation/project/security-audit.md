# Audit de sécurité (2026-10-04)

> TODO-15. Lecture du code des deux dépôts (backend : authentification, contrôleurs REST, WebSocket, services
> d'échange et de combat ; client : authentification, relais de combat, configuration). Aucune correction faite
> pendant l'audit : chaque point est suivi dans [`known-issues.md`](known-issues.md) (`SEC-n`) en attendant la
> décision d'Adrien. Document identique dans les deux dépôts.
>
> Non couvert : analyse automatique des dépendances (CVE), tests d'intrusion réels, machine serveur (SSH
> injoignable).

## 1. Résumé

| ID | Gravité | Dépôt | Sujet |
|---|---|---|---|
| SEC-1 | **Haute** | Les deux | Usurpation de compte : le `serverId` de l'authentification est choisi par le client |
| SEC-2 | **Haute** | Client | L'invité exécute n'importe quel paquet Cobblemon relayé par l'hôte d'un combat |
| SEC-3 | Moyenne | Backend | Anciennes routes REST de combat : combat créé sans accord, résultat déclaré par n'importe quel participant |
| SEC-4 | Moyenne | Backend | Une présence sans empreinte ou sans dimension casse le regroupement pour tous les joueurs |
| SEC-5 | Moyenne | Backend | Tailles non bornées (données Pokémon, surnom, chaînes WebSocket) et aucune limite de débit |
| SEC-6 | Moyenne | Backend | Coordonnées des joueurs diffusées à quiconque rejoint leur groupe |
| SEC-7 | Basse | Backend | Clé d'idempotence non liée au joueur ni à la route |
| SEC-8 | Basse | Backend | Une deuxième connexion WebSocket du même joueur est défaite par la fermeture de la première |
| SEC-9 | Basse | Backend | Données `ivs` / `evs` mal typées : erreur 500 au lieu d'un refus propre |

## 2. Détail

### SEC-1 — Usurpation de compte via le `serverId` (haute)

- **Constat** : le client tire un `serverId` au hasard, appelle `joinServer` chez Mojang, puis l'envoie à
  `POST /auth/session` ; le backend vérifie seulement que Mojang confirme `hasJoined(username, serverId)`. Le backend
  n'a jamais émis ce `serverId`.
- **Attaque** : quand un joueur rejoint **n'importe quel** serveur Minecraft en ligne, ce serveur reçoit et vérifie
  exactement ce type de preuve (`username` + hachage de session). Son opérateur, ou un plugin, peut la rejouer
  aussitôt auprès de `POST /auth/session` et obtenir un jeton Phantasmon au nom du joueur : lecture, suppression,
  échange de tous ses Ghost. Le paramètre `ip` transmis à Mojang n'est pas une protection fiable : l'authentification
  fonctionne aujourd'hui via Tailscale, où l'adresse vue par le backend n'est pas celle vue par Mojang.
- **Correction proposée** : défi émis par le backend. `POST /auth/challenge` renvoie un nonce aléatoire (usage
  unique, ~30 s) ; le client l'utilise comme `serverId` ; `POST /auth/session` refuse tout `serverId` qui n'est pas
  un défi émis, non expiré, non consommé. Un serveur tiers ne connaît jamais nos défis.
- À confirmer en jeu par un test contrôlé si on le souhaite ; la correction ne dépend pas de ce test.

### SEC-2 — L'invité exécute tout paquet relayé par l'hôte (haute)

- **Constat** : `LiveBattleController.onRelayedPacket` décode et remet à Cobblemon (`CobblemonPackets.dispatchLocally`)
  **tout** paquet client de Cobblemon envoyé par l'hôte, sans liste blanche.
- **Attaque** : un hôte au client modifié peut envoyer à l'invité des paquets sans rapport avec le combat
  (synchronisation de l'équipe, du PC, des données joueur, ouverture d'interfaces, etc.) et altérer son client
  pendant la session. Le décodage de données forgées est aussi une surface de plantage.
- **Correction proposée** : n'accepter que les identifiants `cobblemon:battle_*` (tous les paquets que le moteur de
  combat envoie à un acteur) et `phantasmon:action_effect` ; journaliser et ignorer le reste.
- Sens inverse déjà sain : l'hôte n'applique que des `BattleSelectActionsPacket`, attribués à l'adversaire connu
  de la session.

### SEC-3 — Anciennes routes REST de combat (moyenne)

- **Constat** : `POST /battles` crée une session contre n'importe quel joueur sans son accord ;
  `POST /battles/{uuid}/result` accepte le vainqueur déclaré par **n'importe quel** participant, y compris l'invité
  d'un combat en direct (qui contourne alors la règle « seul l'hôte déclare »).
- **Effet** : faux résultats en base (victoires fabriquées, combats en direct clos par le perdant). Sans effet de jeu
  aujourd'hui, mais tout futur classement s'appuierait dessus.
- **Correction proposée** : retirer ces deux routes (le client ne les utilise pas ; le combat en direct les
  remplace), ou au minimum refuser `result` sur une session hébergée.

### SEC-4 — Regroupement cassé par une présence incomplète (moyenne)

- **Constat** : `JoinServerGroup` et `PositionUpdate` acceptent `server_fingerprint` / `dimension` absents (`null`).
  `PresenceService.groupMembers` appelle ensuite `presence.serverFingerprint().equals(...)` sur toutes les présences.
- **Effet** : un seul client modifié envoie une présence sans empreinte ; chaque calcul de groupe des autres joueurs
  lève une exception : plus aucun Ghost n'apparaît, ne bouge ni ne disparaît chez personne.
- **Correction proposée** : refuser (`ERROR_WS_MALFORMED_MESSAGE`) une empreinte ou une dimension absente, vide ou
  trop longue ; comparaisons tolérantes au `null` en défense.

### SEC-5 — Tailles non bornées et aucune limite de débit (moyenne)

- **Constat** : `data` d'un Pokémon (JSON libre, jusqu'à la taille de requête acceptée par Tomcat), surnom sans
  longueur maximale (diffusé aux autres joueurs dans `GhostEntitySpawn` et affiché au-dessus du Ghost), chaînes
  WebSocket sans limite ; messages WebSocket jusqu'à 1 Mio sans limite de fréquence (D-20).
- **Effet** : remplissage de la base (480 Pokémon par joueur), surcharge réseau du backend et des autres clients,
  étiquettes géantes au-dessus des Ghost.
- **Correction proposée** : bornes côté backend (taille de `data`, surnom ≤ 32 caractères, longueur des chaînes
  WebSocket) et limite de messages par joueur et par seconde.

### SEC-6 — Coordonnées des joueurs exposées (moyenne, conception)

- **Constat** : l'empreinte d'un serveur est le SHA-256 de son adresse, calculable par n'importe qui. Un joueur
  authentifié peut rejoindre le groupe de n'importe quel serveur et recevoir chaque seconde la position de tous
  les joueurs qui ont un Ghost sorti (`GhostEntitySpawn` / `GhostEntityMove`), où qu'ils soient.
- **Effet** : localisation des joueurs (bases sur un serveur survie), sans être connecté au serveur.
- **Pistes** (à décider, aucune n'est parfaite car les positions viennent des clients) : n'envoyer la position qu'aux
  membres qui se déclarent à proximité ; arrondir les positions envoyées ; documenter comme limite connue.

### SEC-7 — Idempotence non cloisonnée (basse)

- **Constat** : `IdempotencyService` retrouve une réponse par `request_uuid` seul, sans vérifier le joueur ni la
  route enregistrés.
- **Effet** : qui connaîtrait le `request_uuid` d'un autre joueur recevrait sa réponse (UUID aléatoires : peu
  exploitable) ; une réutilisation sur une autre route renvoie une réponse du mauvais type.
- **Correction proposée** : ne réutiliser la réponse que si joueur et route correspondent, sinon `409`.

### SEC-8 — Double connexion WebSocket (basse)

- **Constat** : une nouvelle connexion du même joueur remplace l'ancienne dans `SessionRegistry` ; quand l'ancienne
  se ferme, `afterConnectionClosed` désinscrit la **nouvelle** et retire sa présence.
- **Effet** : le joueur reste connecté mais ne reçoit plus rien (Ghost, échanges, combats) jusqu'à reconnexion.
- **Correction proposée** : ne désinscrire / quitter le groupe que si la session fermée est celle enregistrée.

### SEC-9 — Erreur 500 sur `ivs` / `evs` mal typés (basse)

- **Constat** : `PokemonLegalityService` fait un cast en `Map` et un `Integer.parseInt` sans garde.
- **Effet** : une requête forgée donne une erreur 500 (et une trace dans le log) au lieu d'une erreur métier.
- **Correction proposée** : `ERROR_LEGALITY_INVALID_STATS` (422) sur tout type inattendu.

## 3. Points vérifiés sans problème

- Routes REST : propriété toujours tirée du JWT ; `requireSelf` / vérification du propriétaire sur les Pokémon,
  échanges et combats ; jeton de rafraîchissement refusé comme jeton d'accès et inversement.
- Jeton WebSocket en paramètre d'URL : jamais journalisé (`RequestLoggingFilter` n'écrit que le chemin).
- Échange en direct : propriété et présence dans l'équipe revérifiées en base au moment d'exécuter.
- Hôte de combat : n'applique que les choix de combat de l'adversaire de la session.
- Secrets : `.env` ignoré par git, `JWT_SECRET` d'au moins 256 bits imposé par la bibliothèque JWT.
- PostgreSQL limité à `localhost` (TODO-6) ; conteneur publié sur `127.0.0.1` seulement.

## 4. Déjà connus et assumés

- Pas de révocation des jetons de rafraîchissement (DEBT-3, D-16).
- Pas de limitation de débit (D-20) — repris dans SEC-5.
- Un hôte de combat modifié peut fausser le combat (LIM-1, D-05).
- Trafic HTTP / WS en clair : acceptable via Tailscale (chiffré) ; HTTPS / WSS obligatoire avant toute exposition
  publique (`guides/deployment.md`).
- `setAllowedOrigins("*")` sur `/ws` : sans conséquence, le jeton n'est jamais présent dans un navigateur.
