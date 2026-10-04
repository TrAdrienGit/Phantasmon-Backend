# 3. Réseau : HTTP, REST, JSON

## 3.1 Client et serveur

Un **serveur** est un programme qui écoute sur un **port** (8080 pour le backend) et attend des connexions. Un
**client** s'y connecte avec une adresse (`http://100.116.43.32:8080`) : protocole, machine, port. Le mod
Minecraft est le client ; le backend est le serveur.

## 3.2 Une requête et une réponse HTTP

HTTP est un échange de **texte** : le client envoie une requête, le serveur renvoie une réponse, puis c'est fini.

```http
POST /pokemon HTTP/1.1
Host: 100.116.43.32:8080
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json

{"request_uuid":"0f3c…","species":"pikachu","level":50, …}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{"uuid":"9f8e…","owner_uuid":"b1a4…","species":"pikachu","box_id":1,"box_slot":1, …}
```

| Partie | Rôle |
|---|---|
| **Méthode** | L'intention : `GET` (lire), `POST` (créer / déclencher), `PATCH` (modifier en partie), `PUT` (remplacer), `DELETE` (supprimer) |
| **Chemin** | La ressource visée : `/pokemon`, `/pokemon/{uuid}`, `/players/{uuid}/pokemon` |
| **Paramètres de requête** | Après `?` : `/players/…/pc?box=3` |
| **En-têtes** | Métadonnées : `Authorization` (qui je suis), `Content-Type` (format du corps) |
| **Corps** | Les données, ici en JSON |
| **Code de statut** | Le résultat, en un nombre |

## 3.3 Les codes de statut utilisés

| Code | Sens | Exemple dans le projet |
|---|---|---|
| 200 OK | Réussi | `GET /health`, `PATCH /pokemon/{uuid}` |
| 201 Created | Ressource créée | `POST /pokemon` |
| 204 No Content | Réussi, rien à renvoyer | `DELETE /pokemon/{uuid}` |
| 400 Bad Request | Requête mal formée | Champ obligatoire manquant |
| 401 Unauthorized | Identité non prouvée | Preuve Mojang refusée, jeton invalide |
| 403 Forbidden | Identité connue mais action interdite | Modifier le Pokémon d'un autre |
| 404 Not Found | Ressource inexistante | Pokémon inconnu |
| 409 Conflict | Contradiction avec l'état actuel | Case déjà occupée, PC plein |
| 422 Unprocessable Content | Données comprises mais refusées par les règles | IV hors bornes |
| 503 Service Unavailable | Serveur incapable de répondre correctement | Base de données injoignable (`/health`) |

Règle simple : 4xx = la faute du client, 5xx = la faute du serveur.

## 3.4 JSON

JSON est le format texte des données : objets `{ "clé": valeur }`, tableaux `[ … ]`, chaînes, nombres, booléens,
`null`. Côté Java, une bibliothèque (Jackson côté backend, Gson côté client) convertit automatiquement un objet en
JSON (**sérialisation**) et inversement (**désérialisation**).

Convention du projet : noms de champs en **snake_case** (`owner_uuid`), alors que Java utilise le camelCase
(`ownerUuid`). Une seule ligne de configuration fait la conversion partout :

```properties
# src/main/resources/application.properties
spring.jackson.property-naming-strategy=SNAKE_CASE
```

Piège : cette règle s'applique aux champs des objets, **pas aux clés d'une `Map`**. Les clés du champ libre `data`
d'un Pokémon sont écrites telles quelles par le client. Deux clés y sont longtemps restées en camelCase (`heldItem`,
`teraType`) ; la migration V9 les a renommées (`held_item`, `tera_type`) — d'où l'intérêt de fixer la casse dès le
début.

## 3.5 REST : une façon d'organiser une API

REST n'est pas une technologie mais une convention : on expose des **ressources** (Pokémon, échanges, combats)
sous des chemins, et on agit dessus avec les méthodes HTTP.

| Action | Requête |
|---|---|
| Créer un Pokémon | `POST /pokemon` |
| Lister mes Pokémon | `GET /players/{moi}/pokemon` |
| Modifier un Pokémon | `PATCH /pokemon/{uuid}` |
| Supprimer | `DELETE /pokemon/{uuid}` |
| Une action qui n'est pas du CRUD | `POST /pokemon/{uuid}/clone`, `POST /trades/{uuid}/accept` |

Le serveur est **sans état** entre deux requêtes : chaque requête porte tout ce qu'il faut pour être comprise
(notamment le jeton d'identité). C'est ce qui permet de redémarrer le serveur sans « déconnecter » personne côté
REST.

## 3.6 Des erreurs lisibles par une machine

Le client traduit les messages dans la langue du joueur. Le serveur ne renvoie donc jamais de phrase, mais un
**code** stable et des détails :

```json
{ "error_code": "ERROR_LEGALITY_EV_TOTAL_EXCEEDED", "details": { "total": 528, "max": 510 } }
```

Avantages : traduction côté client, tests précis (`jsonPath("$.error_code").value(…)`), contrat stable même si le
texte affiché change.

## 3.7 Idempotence : survivre aux nouvelles tentatives

Le réseau n'est pas fiable : le client envoie `POST /pokemon`, le serveur crée le Pokémon, mais la réponse se perd.
Le client réessaie… et crée un **deuxième** Pokémon. Pour l'éviter, le client joint un identifiant unique de
requête (`request_uuid`). Le serveur mémorise la réponse associée : s'il revoit le même `request_uuid`, il renvoie
la même réponse **sans refaire l'action**. Une opération qu'on peut répéter sans effet supplémentaire est dite
**idempotente**. Implémentation : chapitre 9.

## 3.8 Tester à la main

```bash
curl http://localhost:8080/health
curl -i -X POST http://localhost:8080/auth/refresh -H "Content-Type: application/json" -d '{"refresh_token":"x"}'
```

`-i` affiche le statut et les en-têtes de la réponse, `-X` choisit la méthode, `-H` ajoute un en-tête, `-d` envoie
un corps.

## À retenir

- HTTP = requête (méthode, chemin, en-têtes, corps) puis réponse (statut, en-têtes, corps).
- Les codes de statut disent le résultat ; le corps JSON dit le détail, sous forme de code d'erreur.
- REST organise l'API en ressources ; le serveur reste sans état entre deux requêtes.
- `request_uuid` rend la création idempotente face aux nouvelles tentatives.
