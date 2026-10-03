# Contexte pour les agents IA — Phantasmon Backend

> À lire avant toute génération de code sur ce dépôt. Remplace l'ancien `CONTEXT_CURSOR_BACKEND.md`.
> Mis à jour le 2026-10-03.

## 1. Rôle du service

Le backend est la **source de vérité absolue** de Phantasmon. Il ne dépend d'aucun serveur Minecraft ni de
Fabric : c'est un service Spring Boot autonome qui ne parle qu'au mod client (REST + un WebSocket par client).
Aucun code Phantasmon ne tourne sur un serveur Minecraft, et il n'y en aura jamais.

Lire d'abord : [`architecture/system-overview.md`](../architecture/system-overview.md), puis
[`architecture/backend-architecture.md`](../architecture/backend-architecture.md).

## 2. Pile

Java 21 · Spring Boot **4.1.1** · PostgreSQL + Flyway · jjwt · Jackson 3 · Gradle · JUnit 5 + Testcontainers.

## 3. Règles non négociables

1. **Ne jamais faire confiance au client.** Le joueur vient du JWT, jamais du corps ou du chemin. La propriété est
   revérifiée en base avant toute modification, suppression, échange, sortie de Ghost ou action de combat.
2. **Légalité imposée par le service**, pas seulement par des annotations : `PokemonLegalityService` à la création
   et à chaque `PATCH` avec `data` (IV ∈ [0, 31], EV ∈ [0, 252], total ≤ 510).
3. **Idempotence** des `POST` sensibles (`/pokemon`, `/trades`, `/battles`) via `request_uuid` et `IdempotencyService`.
4. **Erreurs structurées** : `throw new ApiException(status, "ERROR_…", details)`, jamais de texte libre. Tout
   nouveau code va dans [`reference/error-codes.md`](../reference/error-codes.md) et doit être traduit côté client.
5. **Transactions atomiques** pour tout changement de propriétaire ou de place.
6. **Identifiants Cobblemon uniquement** dans `pokemon` (jamais de stats, modèles, animations).
7. **État éphémère en mémoire** (présence, échanges en direct, combats en cours) : pas de table SQL, pas de Redis.
   Instance unique assumée.
8. **Pas de contrôle de plausibilité des positions** (anti-triche positionnel écarté par le CAD).
9. **Pas de moteur de combat côté backend** : le combat tourne sur le client hôte ; le backend relaie et applique
   des garde-fous.
10. **TDD strict** : test d'abord ; intégration sur PostgreSQL réel (Testcontainers), jamais H2.
11. **Flyway uniquement**, jamais `ddl-auto: update`, jamais modifier une migration appliquée.
12. **Snake_case** global (Jackson) : pas de `@JsonProperty` par champ.

## 4. Pièges connus de cette pile

- Spring Boot 4 sépare certaines auto-configurations en modules : sans `spring-boot-flyway`,
  `spring-boot-restclient` ou `spring-boot-webmvc-test`, la fonctionnalité est **silencieusement** inactive.
- Jackson 3 : `ObjectMapper` est dans `tools.jackson.databind`, plus `com.fasterxml.jackson.databind`.
- `Map.of(...)` refuse les valeurs `null` : utiliser une `HashMap` pour les charges utiles WebSocket.
- `PresenceService.groupMembers` exclut l'appelant : utiliser `broadcastToGroupAndSelf` quand le joueur doit
  recevoir son propre événement.
- Échange de places : libérer et flusher l'ancienne place avant de la réattribuer (index uniques).
- Écritures WebSocket concurrentes : toujours passer par `SessionRegistry.sendTo` (synchronisé).
- Logs : ne pas remplacer le mécanisme `EnvironmentPostProcessor` par un `logback-spring.xml` conditionnel.

## 5. Documentation à tenir à jour dans le même changement

| Quand | Mettre à jour |
|---|---|
| Endpoint REST ajouté, modifié ou retiré | `reference/rest-api.md`, `reference/openapi.yaml` |
| Message WebSocket ajouté, modifié ou retiré | `reference/websocket-protocol.md` |
| Nouveau code d'erreur | `reference/error-codes.md` (+ traduction côté client) |
| Nouvelle migration Flyway | `reference/database-schema.md` (tableaux, diagramme, historique) |
| Nouvelle variable ou propriété | `reference/configuration.md`, `guides/running.md` |
| Décision qui s'écarte du CAD | `architecture/decisions.md` (miroir dans le dépôt client) |
| Fin de fonctionnalité ou nouvelle limite | `project/status.md` (miroir dans le dépôt client) |
| Bug découvert, TODO, dette technique | `project/known-issues.md` (miroir dans le dépôt client) |

## 6. Hors périmètre sans demande explicite

- Toute intégration à un mod serveur Minecraft.
- Un moteur de combat Pokémon côté backend.
- Quotas, coûts ou délais de création de Pokémon (accès libre confirmé).
- Scalabilité multi-instance (Redis, bus de messages).
- Commits et pushs : Adrien gère git lui-même.
- Afficher ou commiter le contenu du `.env`.

## 7. Hiérarchie des sources en cas de contradiction

1. Le code et les tests (comportement réel).
2. Ce document et `architecture/decisions.md`.
3. `specifications/cad-4-plan-developpement.md`.
4. `specifications/cad-3-complements.md` (prime sur la Partie 1, notamment sur la légalité).
5. `specifications/cad-2-architecture-technique.md` (architecture de référence ; les schémas « Ghost Server
   Addon » de la Partie 1 sont obsolètes).
6. `reference/database-schema.md` pour le SQL, `reference/openapi.yaml` pour les contrats HTTP.
7. `specifications/cad-1-specifications-fonctionnelles.md` pour l'intention produit uniquement.
