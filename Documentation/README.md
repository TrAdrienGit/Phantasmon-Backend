# Documentation — Phantasmon Backend

Service Spring Boot, source de vérité de Phantasmon (joueurs, Ghost Pokémon, échanges, combats), consommé
exclusivement par le mod client [Phantasmon-Client](https://github.com/TrAdrienGit/Phantasmon-Client).

**Dernière révision complète : 2026-10-03.**

## Par où commencer

| Je veux… | Lire |
|---|---|
| Comprendre le projet en 5 minutes | [`architecture/system-overview.md`](architecture/system-overview.md) |
| Lancer le backend sur ma machine | [`guides/running.md`](guides/running.md) |
| Appeler l'API | [`reference/rest-api.md`](reference/rest-api.md), [`reference/websocket-protocol.md`](reference/websocket-protocol.md) |
| Savoir où en est le projet | [`project/status.md`](project/status.md), [`project/known-issues.md`](project/known-issues.md) |
| Coder sur ce dépôt (humain ou agent IA) | [`agents/backend-agent-context.md`](agents/backend-agent-context.md) |

## Organisation

```text
Documentation/
├── README.md                       ce fichier
├── architecture/
│   ├── system-overview.md          vue d'ensemble du système (miroir)
│   ├── backend-architecture.md     paquets, requêtes, WebSocket, état mémoire, transactions, logs
│   └── decisions.md                journal des décisions et écarts au CAD (miroir)
├── reference/
│   ├── rest-api.md                 API REST implémentée, avec exemples
│   ├── websocket-protocol.md       messages WebSocket (présence, Ghost, échange, combat)
│   ├── error-codes.md              catalogue des codes d'erreur et de leurs traductions client
│   ├── database-schema.md          schéma PostgreSQL, diagramme, historique des migrations
│   ├── configuration.md            variables d'environnement et propriétés
│   └── openapi.yaml                contrat OpenAPI 3 des routes REST
├── guides/
│   ├── running.md                  lancer en développement, jar, PostgreSQL Docker, dépannage
│   ├── testing.md                  stratégie de test, Testcontainers, CI
│   └── deployment.md               déploiement, infrastructure actuelle, sauvegardes
├── specifications/                 cahier des charges, Parties 1 à 4, annotées (miroir)
├── project/
│   ├── status.md                   avancement, non implémenté (miroir)
│   └── known-issues.md             bugs connus, TODO, dette technique, limites (miroir)
├── agents/
│   ├── backend-agent-context.md    règles pour les agents IA qui codent ici
│   └── server-machine-briefing.md  briefing d'un agent IA sur la machine serveur
└── research/
    └── cobblemon-custom-pokemon.md note sur le format des données Cobblemon (miroir)
```

**Miroir** : le document existe à l'identique dans le dépôt Client. Une modification doit être reportée dans
les deux dépôts : `architecture/system-overview.md`, `architecture/decisions.md`, `project/status.md`, `project/known-issues.md`,
`specifications/*`, `research/cobblemon-custom-pokemon.md`.

## Conventions

- **Langue** : documentation en français ; noms de fichiers en anglais, en minuscules avec tirets.
- **Format** : Markdown (GitHub Flavored), diagrammes en Mermaid ; OpenAPI en YAML.
- **Source de vérité** : la documentation décrit le code **tel qu'il est**. En cas de doute, le code et les tests
  font foi, puis [`architecture/decisions.md`](architecture/decisions.md), puis les
  [`specifications/`](specifications/README.md).
- **Dates** absolues (`AAAA-MM-JJ`), jamais « hier » ou « la semaine dernière ».
- **Secrets** : jamais de mot de passe, jeton ou contenu de `.env` dans la documentation.

## Maintenance (dans le même changement que le code)

| Changement | Documents à mettre à jour |
|---|---|
| Endpoint REST | `reference/rest-api.md`, `reference/openapi.yaml` |
| Message WebSocket | `reference/websocket-protocol.md` |
| Code d'erreur | `reference/error-codes.md` (+ traductions côté client) |
| Migration Flyway | `reference/database-schema.md` (tableaux, diagramme, historique) |
| Variable / propriété | `reference/configuration.md`, `guides/running.md` |
| Écart au CAD, choix structurant | `architecture/decisions.md` (miroir) |
| Fonctionnalité terminée, limite découverte | `project/status.md` (miroir) |
| Bug découvert, TODO, dette technique | `project/known-issues.md` (miroir) |

## Documentation du client

Le dépôt [Phantasmon-Client](https://github.com/TrAdrienGit/Phantasmon-Client) documente le mod : compilation,
installation, guide du joueur, commandes et touches, architecture du client (Ghost, écrans, moteur de combat,
Mixins) et historique de développement détaillé.
