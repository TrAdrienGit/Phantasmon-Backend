# CAD Phantasmon — Partie 4 : Plan de développement

Ce plan séquence le développement en phases. Principe directeur : **le backend d'abord**, parce qu'il est source de vérité, plus facile à couvrir en TDD (logique pure, pas de dépendance Minecraft), et que le client ne peut de toute façon rien afficher sans lui. Le combat (le point le plus incertain de l'architecture, CAD Partie 2 §9) est volontairement traité en dernier.

Chaque phase liste : objectif, livrables, definition of done (DoD).

---

## Phase 0 — Fondations (avant code métier)

**Objectif** : lever les prérequis identifiés avant d'écrire la première feature.

- Repos créés (`phantasmon-client`, `phantasmon-backend`, `phantasmon-docs`), `.gitignore` en place.
- Licence GPL 3.0 déposée dans chaque repo (`LICENSE`).
- Audit rapide de l'API publique Cobblemon (`CobblemonEvents` et équivalents) pour identifier ce qui est stable vs interne — notes à garder dans `phantasmon-docs`.
- Squelette Spring Boot généré (auth, pokemon, trade, battle, presence, websocket, version, admin, common — voir `CONTEXT_CURSOR_BACKEND.md`).
- Squelette Fabric généré via le template officiel (1.21.1, client-only).
- Docker Compose PostgreSQL local fonctionnel.
- CI de base sur les deux repos (voir livrables joints à ce message).
- OpenAPI de base généré (voir livrable joint).

**DoD** : les deux projets buildent, la CI passe au vert sur un commit vide, la base de données locale démarre.

---

## Phase 1 — Backend : identité & Pokémon (cœur du système)

**Objectif** : rendre le backend capable de gérer un joueur et ses Pokémon, en autonomie, testé.

- `players` : création à la première connexion, `last_username`, `last_seen_at`.
- Flux d'auth Mojang (`joinServer`/`hasJoined`) → JWT (§3 Partie 2).
- `pokemon` CRUD complet (colonnes + JSONB hybride, §5 Partie 2).
- `PokemonLegalityService` (IVs/EVs, §B Partie 3) — TDD dès le premier test.
- Idempotence sur `POST /pokemon` (`idempotency_keys`, §12 Partie 2).
- `cobblemon_data_version` posé sur chaque Pokémon (§9 Partie 2), sans logique de migration encore.
- Pagination PC (`GET /pokemon?box=n`).
- Codes d'erreur structurés (`ERROR_*`) dès cette phase — pas de texte brut, jamais (§L Partie 3).

**DoD** : un joueur peut s'authentifier, créer/lire/modifier/supprimer un Pokémon légal, la légalité est rejetée si violée, une requête dupliquée ne recrée rien. Couverture de tests élevée (backend = zone TDD forte).

---

## Phase 2 — Backend : présence & WebSocket

**Objectif** : poser l'infrastructure temps réel, sans encore de logique Ghost Entity dessus.

- `PlayerPresence` en mémoire, groupement `server_fingerprint` + `dimension` (§4 Partie 2).
- Connexion WebSocket authentifiée par JWT.
- `Heartbeat`/`HeartbeatAck`.
- TTL de nettoyage des sessions orphelines (§F Partie 3).
- `GET /version` + handshake de compatibilité client/backend (§E Partie 3).

**DoD** : deux clients de test (simulateurs, pas encore le vrai mod) rejoignant le même `server_fingerprint` se voient correctement regroupés ; un heartbeat manqué déclenche le nettoyage attendu.

---

## Phase 3 — Backend : Trade

**Objectif** : premier flux "métier complet" au-dessus des fondations (bon test de la robustesse de Phase 1-2).

- Table `trades`, endpoints `propose/accept/cancel` (§D Partie 3).
- Transaction atomique d'échange d'ownership.
- Événements WebSocket associés (`TradeProposed`, `TradeAccepted`, `TradeCancelled`).

**DoD** : un trade accepté échange bien les deux `owner_uuid` en une seule transaction ; un trade annulé ne modifie rien ; un trade sur un Pokémon déjà transféré échoue proprement.

---

## Phase 4 — Backend : Battle (structure seulement, pas l'arbitrage)

**Objectif** : poser la donnée et les endpoints de session de combat, sans encore le modèle "client hôte" (qui nécessite le client, Phase 8).

- `battle_sessions`, `POST /battles`, `POST /battles/{uuid}/result`.
- Garde-fous de cohérence de résultat (§9.2 Partie 2) — logique isolée et testable indépendamment du client.
- Restauration du `GhostPokemon` après combat (§19 CAD Partie 1).

**DoD** : une session de combat se crée, accepte un résultat plausible, rejette un résultat incohérent (test unitaire des garde-fous).

---

## Phase 5 — Client : squelette + auth + connexion

**Objectif** : premier lien réel entre le mod et le backend.

- Écran/commande de connexion déclenchant le flux Mojang (`joinServer`) côté client.
- Stockage du JWT en mémoire, refresh.
- Appel `GET /version` avant toute chose (§E Partie 3).
- i18n posée dès ce moment (`lang/fr_fr.json`, `lang/en_us.json`) — pas de chaîne en dur dès le premier écran.

**DoD** : le joueur se connecte, le client détecte une incompatibilité de version si on la force manuellement en test, les deux langues fonctionnent sur les premiers textes.

---

## Phase 6 — Client : Pokémon (PC, équipe, création/édition)

**Objectif** : rendre le système utilisable sans encore le volet visuel Ghost Entity.

- UI de création/édition de Pokémon, appelant le backend (Phase 1).
- Affichage des erreurs de légalité traduites depuis les codes `ERROR_*`.
- PC en pagination (consommation de `GET /pokemon?box=n`).
- Import Showdown : **la table de correspondance Showdown ↔ identifiants Cobblemon (espèces/formes/attaques/objets) se construit à cette phase**, pas avant — c'était un point ouvert du CAD, il se résout concrètement ici, au contact du vrai import.

**DoD** : un joueur crée, édite, consulte son PC entièrement depuis le mod, sans jamais passer par un appel API brut manuel.

---

## Phase 7 — Client : Ghost Entity (rendu, spawn/despawn, mouvement)

**Objectif** : la fonctionnalité la plus visible du mod.

- Rendu client-only réutilisant modèles/animations Cobblemon (§7 Partie 2).
- Spawn/despawn/mouvement pilotés par les événements WebSocket de la Phase 2.
- Cycle de vie complet (déconnexion, mort, changement de dimension — §8 Partie 2).

**DoD** : deux joueurs Ghost sur le même serveur se voient mutuellement envoyer/rappeler leurs Pokémon, un joueur sans l'addon ne voit rien.

---

## Phase 8 — Client : Trade UI

**Objectif** : brancher l'UI sur le backend de la Phase 3.

- Proposition/acceptation/annulation depuis le jeu.
- Notifications visuelles/i18n des événements de trade.

**DoD** : un trade complet se fait de bout en bout entre deux clients réels.

---

## Phase 9 — Client + Backend : Combat Ghost

**Objectif** : la phase la plus complexe, volontairement en dernier — tout le reste du système doit déjà être stable.

- Implémentation du modèle "client hôte + garde-fous backend" (§9 Partie 2).
- Alternance de l'hôte entre les deux joueurs (§9.2 Partie 3).
- Restauration post-combat déjà posée en Phase 4, à brancher ici.

**DoD** : un combat Ghost vs Ghost se déroule, se termine (victoire/défaite/draw), les Pokémon retrouvent leur état de base ensuite.

---

## Phase 10 — Durcissement & publication

**Objectif** : passage en conditions réelles.

- Logs complets vérifiés en conditions de charge légère (§17 Partie 2).
- Stratégie de backup PostgreSQL effectivement en place (§I Partie 3), pas juste documentée.
- Publication Modrinth + CurseForge (§J Partie 3).
- Vérification finale de la compatibilité de licence (Phase 0, point laissé ouvert).

**DoD** : le mod est publiquement installable, le backend tourne en environnement réel avec sauvegardes vérifiées (test de restauration effectué au moins une fois).

---

## Ce que ce plan laisse volontairement de côté

- Aucune tâche liée à un mod serveur Minecraft (il n'y en a pas, rappel constant du CAD).
- Aucune tâche de scalabilité multi-instance backend (hors scope, §H Partie 3).
- Pas de sprint dédié aux tests client-side impossibles à automatiser (rendu, interactions Minecraft) — couverts par QA manuelle, pas par du TDD forcé là où ça n'a pas de sens (confirmé).
