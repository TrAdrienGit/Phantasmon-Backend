# État du projet

> **Document miroir** : identique dans `Phantasmon-Backend/Documentation/project/` et
> `Phantasmon-Client/Documentation/project/`. Toute modification doit être reportée dans les deux.
>
> **État au 2026-10-03.** À mettre à jour à chaque fin de phase, fonctionnalité livrée ou limite découverte.

## 1. Avancement par phase

Plan de référence : [`specifications/cad-4-plan-developpement.md`](../specifications/cad-4-plan-developpement.md).

| Phase | Périmètre | Statut | Validation |
|---|---|---|---|
| 0 | Fondations : dépôts, squelettes, CI, OpenAPI, PostgreSQL local | ✅ Terminée | `docker-compose.yml` ajouté le 2026-10-03 ; licence GPL 3.0 |
| 1 | Backend : joueurs, auth Mojang → JWT, CRUD Pokémon, légalité, idempotence, PC | ✅ Terminée | Légalité limitée aux IV/EV ([D-02](../architecture/decisions.md#d-02--légalité-vérifiée-par-le-backend-limitée-aux-ivev)) |
| 2 | Backend : présence, WebSocket, heartbeat/TTL, `GET /version` | ✅ Terminée | Tests d'intégration WebSocket réels |
| 3 | Backend : échanges REST atomiques | ✅ Terminée | |
| 4 | Backend : sessions de combat et garde-fous | ✅ Terminée | |
| 5 | Client : connexion, JWT en mémoire, handshake de version, i18n | ✅ Terminée | Validée en jeu (2026-09-26) |
| 6 | Client : PC, équipe, édition, import Showdown | ✅ Terminée | PC graphique et éditeur validés (2026-10-02) ; export Showdown ajouté (2026-10-03) |
| 7 | Client : rendu des Ghost, sortie/rappel, cycle de vie | ✅ Terminée | Validée à deux comptes (2026-09-29) |
| 8 | Client : interface d'échange | ✅ Terminée | Échange en direct validé à deux comptes (2026-10-02) |
| 9 | Client + backend : combat Ghost (client hôte) | ✅ Cœur terminé | Validé à deux comptes, hôte LAN et hôte client pur, animations d'attaque comprises (2026-10-03) |
| 10 | Durcissement et publication | ⏳ Non commencée | Voir §3 |

Adrien a confirmé le fonctionnement en jeu des phases 1 à 8 le 2026-10-03.

## 2. Fonctionnalités disponibles

| Domaine | Fonctionnalité | Accès joueur |
|---|---|---|
| Connexion | Auto-login à l'entrée dans un monde si le backend répond `UP` ; refresh automatique | `/phantasmon login` en secours |
| PC | 16 boîtes × 30 cases, équipe de 6, glisser-déposer (déplacer ou échanger), fiche complète, suppression avec confirmation | Touche **P**, `/phantasmon pc` |
| Création | Import Showdown depuis le presse-papiers (plusieurs Pokémon à la fois) | Bouton IMPORTER, `/phantasmon pokemon import` |
| Édition | Surnom, niveau, sexe, chromatique, talent, objet, nature, Téracristal, IV/EV, 4 attaques, aperçu en direct | Bouton ÉDITER du PC |
| Ghost | Sortie/rappel du Pokémon en emplacement 1, suivi fluide, balade, animations de Poké Ball, formes, sexe et chromatique | Touche **H**, `/phantasmon sendout` |
| Échange en direct | Invitation, choix des offres, double confirmation, échange atomique | Touche **G**, roue Cobblemon (**R**), `/phantasmon trade invite` |
| Échange asynchrone | Offre par UUID, acceptation ou annulation plus tard | `/phantasmon trade propose\|accept\|cancel\|view\|list` |
| Combat | Combat Ghost contre Ghost avec l'interface de Cobblemon, chrono optionnel, abandon | Touche **B**, roue Cobblemon (**R**), `/phantasmon battle invite` |
| Langues | Français et anglais | Langue du jeu |

## 3. Non implémenté (prévu par le CAD)

| Élément | Référence CAD | Commentaire |
|---|---|---|
| Fonctions d'administration (`/admin/*`, rôle admin, inspection) | Partie 1 §37-38, Partie 2 §16 | [D-20](../architecture/decisions.md#d-20--fonctions-dadministration-et-limitation-de-débit-reportées) |
| Limitation de débit par joueur | Partie 2 §13 | WebSocket limité (40 messages/s par connexion, SEC-5) ; rien côté REST (D-20) |
| Sauvegardes PostgreSQL planifiées | Partie 3 §I, Phase 10 | Scripts de sauvegarde et de test de restauration prêts et testés ; planification à faire (TODO-16) ; pas d'archivage WAL (LIM-8) |
| Publication Modrinth / CurseForge | Partie 3 §J, Phase 10 | Lien de téléchargement factice dans le client (TODO-3) |

## 4. Bugs connus, TODO et dette technique

Suivis dans [`known-issues.md`](known-issues.md) (identifiants `BUG-n`, `TODO-n`, `DEBT-n`, `LIM-n`).

## 5. Pistes envisagées (non engagées)

- Pathfinding Cobblemon pour les Ghost (actuellement : déplacement direct avec saut d'un bloc).
- Écran de connexion dédié et paramètres utilisateur (URL du backend, chrono par défaut…).
- Équipes multiples nommées (D-03).
- Hébergement définitif du backend (VPS ou autre) et stratégie de sauvegarde associée.

## 6. Indicateurs

| | Backend | Client |
|---|---|---|
| Tests automatisés | ~127 méthodes `@Test` (JUnit 5 + Testcontainers) | 40 méthodes `@Test` (logique pure) |
| Migrations Flyway | V1 à V8 | — |
| Mixins | — | 7 |
| Langues | — | `fr_fr`, `en_us` (~224 clés) |
