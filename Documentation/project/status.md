# État du projet

> **Document miroir** : identique dans `Phantasmon-Backend/Documentation/project/` et
> `Phantasmon-Client/Documentation/project/`. Toute modification doit être reportée dans les deux.
>
> **État au 2026-11-25.** À mettre à jour à chaque fin de phase, fonctionnalité livrée ou limite découverte.

## 1. Avancement par phase

Plan de référence : [`specifications/cad-4-plan-developpement.md`](../specifications/cad-4-plan-developpement.md).

| Phase | Périmètre | Statut | Validation |
|---|---|---|---|
| 0 | Fondations : dépôts, squelettes, CI, OpenAPI, PostgreSQL local | ✅ Terminée | `docker-compose.yml` ajouté le 2026-10-03 ; licence Apache 2.0 depuis le 2026-10-30 (GPL 3.0 avant) |
| 1 | Backend : joueurs, auth Mojang → JWT, CRUD Pokémon, légalité, idempotence, PC | ✅ Terminée | Légalité limitée aux IV/EV ([D-02](../architecture/decisions.md#d-02--légalité-vérifiée-par-le-backend-limitée-aux-ivev)) |
| 2 | Backend : présence, WebSocket, heartbeat/TTL, `GET /version` | ✅ Terminée | Tests d'intégration WebSocket réels |
| 3 | Backend : échanges REST atomiques | ✅ Terminée | |
| 4 | Backend : sessions de combat et garde-fous | ✅ Terminée | |
| 5 | Client : connexion, JWT en mémoire, handshake de version, i18n | ✅ Terminée | Validée en jeu (2026-09-26) |
| 6 | Client : PC, équipe, édition, import Showdown | ✅ Terminée | PC graphique et éditeur validés (2026-10-02) ; export Showdown ajouté (2026-10-03) |
| 7 | Client : rendu des Ghost, sortie/rappel, cycle de vie | ✅ Terminée | Validée à deux comptes (2026-09-29) |
| 8 | Client : interface d'échange | ✅ Terminée | Échange en direct validé à deux comptes (2026-10-02) |
| 9 | Client + backend : combat Ghost (client hôte) | ✅ Terminée (2026-10-06) | Cœur validé à deux comptes, hôte LAN et hôte client pur (2026-10-03) ; puis lobby, formats Smogon, intros, caméra, musique, Méga / Z / Téra, rôle admin, tous validés en jeu par Adrien |
| 10 | Durcissement et publication | ⏳ Non commencée | Voir §3 |

Adrien a confirmé le fonctionnement en jeu des phases 1 à 8 le 2026-10-03, et déclaré la phase 9 terminée le
2026-10-06. Les TODO restants sont mis en suspens (voir `known-issues.md`) : la plupart concernent la machine de
production (phase 10), qu'Adrien ne monte pas pour l'instant (il reste sur sa machine de développement).

**2026-10-07 — Core déclaré terminé par Adrien.** Seul du polish (UI, VFX, SFX, animations) continue sur `dev`.
Le projet passe à **Phantasmon Network** (couche inter-serveurs), développé directement sur `dev`
([D-27](../architecture/decisions.md#d-27--phantasmon-network-développé-sur-dev)) selon
[`specifications/network-cahier-des-charges.md`](../specifications/network-cahier-des-charges.md).
**Jalon 1 (« Hub social minimal ») et jalon 2 (« Interactions » : échange et combat entre serveurs) terminés et
validés en jeu le 2026-10-07.**

**2026-10-30 — Une seule branche.** Depuis le découpage en mods (Showtime, Events, Trahison), chaque dépôt vit sur `main` uniquement ; `dev` a été fusionnée puis supprimée.

**Découpage en mods (depuis le 2026-10-29).** La mise en scène des combats est le mod **Cobblemon Battle Showtime**
(D-37, D-40), dont Phantasmon dépend (`>=1.0.0 <2.0.0`). **Phantasmon Events** est un add-on qui dépend de Phantasmon
(`>=0.1.0 <0.2.0`). **Séparé le 2026-11-10 (D-41, TODO-35)** : le backend Phantasmon ne connaît Events que par
ses points d'extension (`addon`) et charge le module `Phantasmon-Events/backend` depuis son dossier `addons/` ;
sans lui, Phantasmon tourne seul. Les IA des PNJ sont dans le mod Events.
**Depuis le 2026-11-10, les tests se font sans Events** (Adrien) : backend déployé avec `--no-events`, clients
Cobblemon Academy (MystAria_, TheMashen) sur notre serveur Minecraft ; les hubs miroirs restent en base, masqués par
le cœur tant que le module n'est pas chargé.

**Novembre 2026.** Modération du chat du hub (TODO-31), sol du hub (TODO-33), import d'équipe complète (Méga et
Primo importés sous leur forme de base), vrais objets tenus, talents importés et amitié dans les combats Ghost,
compatibilité avec Battle Extras (3 mixins), musique du lobby jouée par Showtime. Validés en jeu par Adrien : vocal du hub
(D-36), terrain visible sans regarder (D-33, à revoir), blocs fantômes des hubs miroirs (D-38), debug de l'IA des PNJ.

**Du 2026-11-10 au 2026-11-24, tout validé en jeu par Adrien.**

- Menu Phantasmon (touche **H**) refait dans le style de Showtime : hubs, musique, réglages (adresse du backend),
  gestionnaire des hubs pour tous les joueurs ; PC, éditeur, échange et lobby refaits dans le même style (V2).
- PC : boîtes vrac ou équipes (TODO-36), recherche et boîtes nommées (TODO-38), fenêtre d'import (collage,
  vérification en direct, aperçu du modèle), sélection multiple, équipe entière dans une ligne, barres de défilement
  à la souris, aide (2026-11-21).
- Apparences et récompenses gagnées (TODO-37) : filtres, coques lumineuses, auras, taille ; intros et ambiances
  réservées.
- Lobby de combat : choix de l'intro, panneau de scène (préréglages, stades, ambiances de Showtime), choix du terrain
  (League Reborn, par les extensions Showdown), format, intro et ambiance verrouillés pour les matchs de tournoi et
  les PNJ d'événement.
- Compatibilité LeagueWay (touche du PC sur **U**), objets tenus virtuels ouverts aux add-ons (`VirtualItems`).
- Spectateurs (2026-11-22) : la caméra filme du côté de l'hôte, un combat rejoint en cours montre ses Pokémon déjà
  sortis, le bouton Retour de Cobblemon termine bien le spectacle ; les combats vus de loin gardent l'ambiance de
  leur terrain.
- Pokémon téracristallisés d'office (2026-11-24) : case dans l'éditeur, téracristallisation à la première entrée,
  plus d'autre téracristallisation pour l'équipe, contrôle dans le lobby. Backend redéployé avec ce contrôle le
  2026-11-25.

| Étape Network | Périmètre | Statut |
|---|---|---|
| N0 | Cahier des charges, décisions D-27 à D-29 | ✅ Validé par Adrien (2026-10-07) |
| N1 | Backend : Hub Anchors (V11, REST, quotas, droits) | ✅ Terminée (2026-10-07) : `hub/`, `V11`, 8 tests d'intégration ; docs de référence à jour |
| N2 | Backend : `HubService` (capacité 50, `HubJoin` / `HubMove`, chat, TTL) | ✅ Terminée (2026-10-07) : 7 tests WebSocket réels (2 empreintes, capacité, anti-doublon, chat, sorties) |
| N3 | Client : commandes et affichage des Anchors, consentement, transformation de coordonnées, chat | ✅ Terminée, validée en jeu par Adrien (2026-10-07) |
| N4 | Client : avatars des joueurs distants | ✅ Validée en jeu par Adrien (2026-10-07), avec skin complet, cape et collisions |
| N5 | Les deux : Ghost dans le Hub ; validation du jalon 1 en jeu | ✅ Terminée, validée en jeu par Adrien (2026-10-07) — **jalon 1 terminé** |
| N6 | Client : roue Ghost sur un avatar (jalon 2) | ✅ Terminée, validée en jeu par Adrien (2026-10-07) |
| N7 | Client : avatar comme adversaire (combat, caméra, lobby, intro) | ✅ Terminée, validée en jeu par Adrien (2026-10-07) |
| N8 | Les deux : validation du jalon 2 (échange et combat entre deux serveurs) | ✅ Validée par Adrien (2026-10-07) — **jalon 2 terminé** |

## 2. Fonctionnalités disponibles

| Domaine | Fonctionnalité | Accès joueur |
|---|---|---|
| Connexion | Auto-login à l'entrée dans un monde si le backend répond `UP` ; refresh automatique | `/phantasmon login` en secours |
| PC | 16 boîtes × 30 cases, équipe de 6, boîtes vrac ou équipes, boîtes nommées, recherche, glisser-déposer (déplacer ou échanger, sélection multiple), fiche complète, suppression avec confirmation | Touche **P** (**U** avec LeagueWay) |
| Création | Import Showdown (plusieurs Pokémon à la fois) dans une fenêtre de collage, vérifié en direct, avec aperçu du modèle | Bouton IMPORTER du PC |
| Édition | Surnom, niveau, sexe, chromatique, talent, objet, nature, Téracristal, téracristallisé d'office, IV/EV, 4 attaques, aperçu en direct | Bouton ÉDITER du PC |
| Ghost | Sortie/rappel du Pokémon en emplacement 1, suivi fluide, balade, animations de Poké Ball, formes, sexe et chromatique | Overlay Ghost (O, puis haut / bas, R) |
| Échange en direct | Invitation, choix des offres, double confirmation, échange atomique | Roue Cobblemon (**R**) uniquement |
| Échange asynchrone | Offre par UUID, acceptation ou annulation plus tard | Retiré du client (TODO-22) ; API REST conservée |
| Combat | Combat Ghost contre Ghost avec l'interface de Cobblemon, lobby (format Smogon, intro, scène, terrain), mise en scène de Cobblemon Battle Showtime, chrono optionnel, abandon | Roue Cobblemon (**R**) uniquement |
| Spectateurs | Regarder un combat Ghost en cours, comme dans Cobblemon (D-31), aussi entre serveurs via le Global Hub ; rejoint en cours, il montre les Pokémon déjà sortis | Roue Cobblemon (**R**) → « Regarder le combat Ghost » |
| Construction du Hub | Chaque Anchor bâtit chez les clients du serveur le schematic de son hub en blocs client, incassables (D-34) | Automatique (`hub_schematics/hub_<nom>/`) |
| Plusieurs hubs | Créés / supprimés / rechargés par les admins, espaces séparés, un Anchor par hub et par joueur, sans chevauchement (D-35) | Gestionnaire des hubs (touche **H**) |
| Vocal du hub | Les membres d'un hub s'entendent en 3D depuis les avatars, avec Simple Voice Chat (facultatif, connecté sur le serveur de chacun) (D-36) ; validé en jeu | Touches de Simple Voice Chat |
| Modération du chat du hub | Rendre muet pour une durée, rétablir, verrouiller le chat, annonces (TODO-31) | `/phantasmon admin hub mute / unmute / lock` |
| Terrain visible alentour | Les joueurs du même serveur et, si un joueur y est, du Global Hub voient les Pokémon d'un combat sans le regarder (D-33) ; validé en jeu, à revoir | Automatique |
| Combat solo (admin) | Combat contre un miroir de son équipe, joué par l'IA de Cobblemon, regardable, non stocké (D-32) | `/phantasmon admin battle solo` |
| Avatars du hub : compatibilités | Icônes de Simple Voice Chat à côté du pseudo, pose du mod Crawl, empilement de Player Ladder, emotes d'Emotecraft (tous facultatifs) | 2026-11-10 |
| Gestionnaire des hubs | Joueurs : hubs (taille, construction, date, ancres), leurs ancres, pose avec aperçu (verte / rouge, boutons [Accepter] / [Annuler] du chat), suppression. Admins : en plus renommer, envoyer, copier depuis la zone en jeu ou retirer le schematic, relire, supprimer, créer, toutes les ancres de tous les serveurs ; onglets ajoutables par les add-ons. Remplace les commandes de gestion des hubs et des ancres | Touche **H** |
| API pour add-ons | `PhantasmonApi` : session, requêtes au backend, messages WebSocket, équipe Cobblemon, roues d'interaction sur d'autres entités, combat contre un PNJ, onglets du gestionnaire des hubs | Utilisée par Phantasmon Events |
| Apparences et récompenses | Filtres, coques lumineuses, auras, taille, intros et ambiances réservées, donnés par un admin (TODO-37) | Page Récompenses du menu |
| Langues | Français et anglais | Langue du jeu |

## 3. Non implémenté (prévu par le CAD)

| Élément | Référence CAD | Commentaire |
|---|---|---|
| Limitation de débit par joueur | Partie 2 §13 | WebSocket limité (40 messages/s par connexion, SEC-5) ; rien côté REST (D-20) |
| Sauvegardes hors serveur | Partie 3 §I, Phase 10 | Backend en production sur Ubuntu (2026-10-19) avec sauvegarde quotidienne locale ; copie hors serveur à faire (TODO-16) ; pas d'archivage WAL (LIM-8) |
| Publication Modrinth / CurseForge | Partie 3 §J, Phase 10 | Page de publication et liste de contrôle prêtes (`Documentation/publication/`, 2026-11-18) ; en attendant, le lien de mise à jour pointe sur les versions GitHub (TODO-3) |

## 4. Bugs connus, TODO et dette technique

Suivis dans [`known-issues.md`](known-issues.md) (identifiants `BUG-n`, `TODO-n`, `DEBT-n`, `LIM-n`).

## 5. Pistes envisagées (non engagées)

- Pathfinding Cobblemon pour les Ghost (actuellement : déplacement direct avec saut d'un bloc).
- Écran de connexion dédié ; autres paramètres (chrono par défaut…). L'adresse du backend se règle dans l'écran de paramètres (2026-11-10).
- Équipes multiples nommées (D-03).
- Hébergement définitif du backend (VPS ou autre) et stratégie de sauvegarde associée.

## 6. Indicateurs

| | Backend | Client |
|---|---|---|
| Tests automatisés | 231 tests (JUnit 5 + Testcontainers, 2026-11-25) ; module Events : 517 (avec le jeu de trahison) | 77 tests (logique pure) |
| Migrations Flyway | V1 à V27 (V18-V19 et V22-V24 passés dans le module Events, qui a ses propres V1 à V14, D-41) | — |
| Mixins | — | 24 (dont 1 accesseur, 3 pour Battle Extras et 3 pour les apparences) ; ceux de la mise en scène sont dans Cobblemon Battle Showtime (D-37) |
| Langues | — | `fr_fr`, `en_us` (596 clés) |
