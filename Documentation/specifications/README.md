# Spécifications (cahier des charges)

> **Dossier miroir** : identique dans `Phantasmon-Backend/Documentation/specifications/` et
> `Phantasmon-Client/Documentation/specifications/`. Toute modification doit être reportée dans les deux.

Le cahier des charges (CAD) décrit l'**intention** du projet. Il a été rédigé avant le développement et n'est pas
réécrit au fil de l'eau : chaque écart d'implémentation est signalé par une note datée
(« **Note d'implémentation** ») à l'endroit concerné, et justifié dans
[`architecture/decisions.md`](../architecture/decisions.md).

| Document | Contenu | Statut |
|---|---|---|
| [Partie 1 — Spécifications fonctionnelles](cad-1-specifications-fonctionnelles.md) | Le « quoi » : Ghost Pokémon, PC, équipes, Showdown, monde, combats, échanges | Intention produit valide ; **architecture obsolète** (mod serveur) |
| [Partie 2 — Architecture technique](cad-2-architecture-technique.md) | Pas de mod serveur, auth Mojang, présence, rendu client, combat « client hôte », API | Principes valides ; détails à lire avec les notes |
| [Partie 3 — Compléments](cad-3-complements.md) | Légalité, échanges, handshake de version, TTL, sauvegardes, distribution, i18n | Valide ; prime sur la Partie 1 |
| [Partie 4 — Plan de développement](cad-4-plan-developpement.md) | Phases 0 à 10 et critères de fin | Phases 0 à 9 terminées, phase 10 à faire |
| [Phantasmon Network](network-cahier-des-charges.md) | Couche inter-serveurs : Hub Anchors, Global Hub, avatars distants ; jalons et étapes N0 à N5 | Jalon 1 (Hub social minimal) terminé le 2026-10-07 ; jalons suivants esquissés |

## Ordre de priorité en cas de contradiction

1. Le code et ses tests (comportement réel), puis `architecture/decisions.md`.
2. Partie 4 (plan), puis Partie 3 (compléments), puis Partie 2 (architecture).
3. Partie 1, **pour l'intention fonctionnelle uniquement** (jamais pour l'architecture serveur).

Pour les contrats techniques, la référence est la documentation du dépôt Backend : `reference/rest-api.md`,
`reference/websocket-protocol.md`, `reference/database-schema.md`.
