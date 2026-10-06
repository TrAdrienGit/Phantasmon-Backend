# Phantasmon — Intégration future avec Pokémon Showdown

> Document de préparation technique destiné au futur développement par un agent de coding.
> Statut : recherche / conception. À entreprendre après stabilisation du Phantasmon Core.

## 1. Objectif

Permettre à un joueur utilisant Phantasmon dans Minecraft/Cobblemon de combattre un joueur humain utilisant Pokémon Showdown dans son navigateur, sans que le joueur Showdown ait besoin d'installer Phantasmon ni de savoir que son adversaire vient de Minecraft.

Architecture cible :

```text
Minecraft / Cobblemon
        │
        ▼
Phantasmon Client
        │
        ▼
Phantasmon Backend
        │
        │ protocole Showdown
        ▼
Pokémon Showdown
        │
        ▼
Joueur Showdown
```

Le backend Phantasmon agit comme adaptateur/proxy de combat entre les deux environnements.

Le joueur Cobblemon utilise l'interface Phantasmon/Cobblemon. Le joueur Showdown utilise l'interface officielle Showdown.

## 2. Principe fondamental

**Showdown doit rester l'autorité du combat côté Showdown.**

Il ne faut pas reproduire le moteur de combat Showdown dans Phantasmon pour cette fonctionnalité.

```text
Action Cobblemon
      ↓
Phantasmon Client
      ↓
Phantasmon Backend
      ↓
Showdown
      ↓
Simulation officielle
      ↓
Événements de combat
      ↓
Phantasmon Backend
      ↓
Phantasmon Client
```

Exemple : une attaque choisie dans Minecraft devient une décision Showdown (`/choose move ...`), puis le résultat de la simulation Showdown est traduit vers Phantasmon.

## 3. Ce que Showdown fournit réellement

Il faut distinguer trois éléments.

### API Web

Les APIs web publiques servent notamment aux utilisateurs, ladders, replays et données du Pokédex/moves. Elles ne constituent pas une API REST complète pour piloter une partie temps réel.

Source : https://github.com/smogon/pokemon-showdown-client/blob/master/WEB-API.md

### Protocole client/serveur

C'est la partie essentielle du projet. Showdown documente son protocole et indique qu'une connexion WebSocket directe est possible.

Source : https://github.com/smogon/pokemon-showdown/blob/master/PROTOCOL.md

### Protocole de combat

Les messages spécifiques aux combats et les décisions `/choose` sont documentés séparément. Les demandes de décision arrivent notamment sous forme de `|request|JSON`.

Source : https://github.com/smogon/pokemon-showdown/blob/master/sim/SIM-PROTOCOL.md

**Conclusion :** la voie technique à privilégier est le protocole Showdown, pas l'automatisation d'un navigateur.

## 4. Faisabilité et points à valider

### Faisable techniquement

- connexion WebSocket à Showdown ;
- gestion d'une session ;
- challenge d'un joueur ;
- envoi d'équipe ;
- réception des événements de combat ;
- envoi des décisions `/choose` ;
- traduction Cobblemon → Showdown ;
- traduction Showdown → Phantasmon ;
- maintien d'un combat hybride.

### À valider avant développement réel

- politique de Showdown concernant les comptes automatisés ;
- création automatisée de comptes ;
- comportement attendu des bots/proxies ;
- éventuelles limites/rate limits ;
- compatibilité durable du protocole ;
- utilisation d'un compte automatisé sur le serveur public ;
- restrictions éventuelles sur ladder/rated battles.

**Ne pas considérer la création automatique de comptes comme acquise simplement parce que le protocole technique existe.**

## 5. Architecture recommandée

Ne pas mélanger directement la logique Showdown avec le domaine de combat Phantasmon.

```text
Phantasmon Backend
│
├── Battle Domain
│   ├── BattleSession
│   ├── Player
│   ├── Pokemon
│   └── BattleState
│
├── Showdown Integration
│   ├── ShowdownConnection
│   ├── ShowdownProtocolParser
│   ├── ShowdownBattleAdapter
│   ├── ShowdownTeamAdapter
│   └── ShowdownAccountManager
│
└── Client WebSocket
```

Flux :

```text
Phantasmon Battle Domain
          ▲
          │
     Adapter Layer
          │
          ▼
   Showdown Protocol
```

Le domaine Phantasmon ne doit jamais dépendre directement des chaînes brutes du protocole Showdown.

## 6. POC obligatoire avant Minecraft

La première étape ne doit PAS être Minecraft.

Créer un prototype backend capable de :

1. se connecter à Showdown ;
2. gérer le protocole de connexion ;
3. obtenir une session utilisateur ;
4. rejoindre/ouvrir une battle room ;
5. envoyer une équipe valide ;
6. accepter/lancer un challenge ;
7. recevoir `|request|...` ;
8. envoyer `/choose ...` ;
9. parser les messages de combat ;
10. détecter la fin du combat.

Le POC doit fonctionner avec deux utilisateurs Showdown avant d'être connecté à Cobblemon.

Puis réaliser un second POC :

```text
Fake Phantasmon Player
        ↕
Phantasmon Backend
        ↕
Showdown
        ↕
Human Showdown Player
```

Le fake player doit pouvoir envoyer une équipe, choisir une attaque, changer de Pokémon, recevoir les résultats et terminer le combat.

## 7. Connexion et compte Showdown

L'idée initiale est de créer automatiquement un compte Showdown pour chaque joueur Phantasmon.

Cette fonctionnalité doit être traitée comme un sous-projet indépendant.

Ne jamais stocker des identifiants Showdown dans le client Minecraft.

```text
Minecraft Player
       ↓
Phantasmon Account
       ↓
Backend
       ↓
Showdown Account / Session
```

Les secrets éventuels restent côté backend.

Avant d'implémenter la création automatique, rechercher et documenter :

- mécanisme actuel de registration ;
- captcha éventuel ;
- restrictions anti-abus ;
- rate limits ;
- règles concernant les bots ;
- utilisation d'un compte automatisé ;
- possibilité d'utiliser le serveur public.

Alternative à étudier : compte/session proxy dédié au service. Cette solution doit toutefois être validée côté politique Showdown avant adoption.

## 8. Gestion des équipes

Showdown possède plusieurs représentations d'équipe : export lisible, JSON et packed format.

Source : https://github.com/smogon/pokemon-showdown/blob/master/sim/TEAMS.md

Créer un modèle canonique intermédiaire :

```text
Cobblemon/Phantasmon
        ↓
Canonical Team Model
        ↓
Showdown Team Adapter
        ↓
Showdown format
```

Ne pas faire directement `Cobblemon → chaîne Showdown`.

Modèle minimal :

```text
PokemonBattleData
├── species
├── forme
├── level
├── gender
├── shiny
├── ability
├── item
├── nature
├── IVs
├── EVs
├── moves
├── teraType
└── nickname
```

Puis :

```text
PokemonBattleData
       ├── Cobblemon Adapter
       └── Showdown Adapter
```

## 9. Validation de compatibilité

Un Pokémon Cobblemon n'est pas forcément représentable par Showdown.

Avant chaque combat :

```text
Phantasmon Team
      ↓
Compatibility Validator
      ├── compatible → Showdown
      └── incompatible → rejet
```

Le validateur doit détecter notamment :

- espèce inconnue ;
- forme inconnue ;
- move inconnu ;
- ability inconnue ;
- item inconnu ;
- combinaison incompatible ;
- mécanique non supportée ;
- niveau invalide ;
- taille d'équipe invalide.

Pour le premier POC, refuser proprement tout contenu custom plutôt que de produire une approximation silencieuse.

## 10. Gestion des décisions

Le joueur Minecraft doit pouvoir faire les décisions fondamentales d'un combat Singles :

```text
move 1
move 2
move 3
move 4
switch
```

Exemple :

```text
Minecraft UI
    ↓
MOVE_1
    ↓
Backend
    ↓
/choose move 1
```

Inversement, lorsque Showdown envoie :

```text
|request|{...}
```

le backend doit transformer le JSON en état compréhensible par Phantasmon.

Le `rqid`, lorsqu'il est fourni, doit être conservé et renvoyé avec la décision. Il sert à éviter qu'une décision soit appliquée au mauvais état.

Source : https://github.com/smogon/pokemon-showdown/blob/master/sim/SIM-PROTOCOL.md

## 11. Parser le protocole correctement

Le protocole Showdown n'est pas du JSON pur.

Exemple :

```text
>battle-room-id
|init|battle
|player|p1|...
|player|p2|...
|request|{...}
|turn|1
|move|p1a: ...
|damage|p2a: ...
```

Le parser doit être :

- événementiel ;
- tolérant aux messages inconnus ;
- capable de traiter plusieurs lignes ;
- capable de gérer les messages spécifiques à une room ;
- indépendant de l'affichage.

Ne jamais baser la logique sur les phrases affichées à l'utilisateur.

Mauvais :

```text
"Samurott used Aqua Jet!"
```

Bon :

```text
|move|p1a: Samurott|Aqua Jet|...
|damage|p2a: ...|...
```

## 12. Showdown comme autorité

Pendant un combat Showdown, Showdown doit déterminer :

- dégâts ;
- vitesse ;
- statuts ;
- effets ;
- PP ;
- changements de stats ;
- KO ;
- victoire ;
- égalité.

Phantasmon traduit les décisions, reçoit les résultats et met à jour son affichage.

Ne pas recalculer les résultats côté Phantasmon pour ensuite les confronter à Showdown.

## 13. Format initial

Le premier POC doit utiliser un format extrêmement contrôlé :

```text
Gen 9
Singles
6 Pokémon
format standard déterminé
pas de custom rules
pas de doubles
pas de triples
pas de ladder au début
```

Le format exact devra être confirmé au moment de l'implémentation selon les formats disponibles sur le serveur.

Ne pas commencer avec tous les formats.

## 14. Reconnexion et machine à états

Prévoir explicitement une machine à états :

```text
CREATED
   ↓
CONNECTING_SHOWDOWN
   ↓
AUTHENTICATING
   ↓
CHALLENGING
   ↓
WAITING_ACCEPT
   ↓
TEAM_PREVIEW
   ↓
ACTIVE
   ↓
FINISHED
```

États d'erreur :

```text
FAILED
DISCONNECTED
CANCELLED
TIMEOUT
```

Une `BattleSession` devrait conserver au minimum :

```text
Phantasmon connection
Showdown connection
Showdown room
current state
last request
rqid
timestamps
```

La reconnexion doit être conçue dès le départ.

## 15. Développement local

Ne pas développer directement contre le serveur public.

Installer une instance locale de Pokémon Showdown pour contrôler :

- serveur ;
- comptes ;
- formats ;
- logs ;
- connexions ;
- déconnexions ;
- tests automatisés.

Source : https://github.com/smogon/pokemon-showdown/blob/master/server/README.md

Ordre recommandé :

```text
Local Showdown
      ↓
Serveur de test
      ↓
Serveur public
```

Le dépôt Showdown contient également le simulateur et des outils CLI utiles aux tests.

Source : https://github.com/smogon/pokemon-showdown/blob/master/COMMANDLINE.md

## 16. Tests

### Unitaires

Prévoir notamment :

```text
ShowdownProtocolParserTest
ShowdownTeamAdapterTest
ShowdownRequestParserTest
ShowdownChoiceEncoderTest
ShowdownBattleEventMapperTest
```

### Intégration

```text
Phantasmon Backend
        ↓
Local Showdown Server
```

### E2E

```text
Minecraft Client
       ↓
Phantasmon Backend
       ↓
Showdown
       ↓
Showdown Browser Client
```

Tester explicitement :

- attaque ;
- switch ;
- team preview ;
- KO ;
- statut ;
- victoire ;
- abandon ;
- timeout ;
- déconnexion ;
- reconnexion ;
- équipe invalide ;
- Pokémon incompatible.

## 17. Scope du premier POC

### Inclus

- Singles ;
- format Showdown standard déterminé ;
- 6 Pokémon maximum ;
- moves standards ;
- items standards ;
- abilities standards ;
- équipe Phantasmon compatible ;
- challenge humain ;
- attaques ;
- switch ;
- fin de combat ;
- affichage du résultat.

### Exclus

- doubles ;
- triples ;
- ladder ;
- matchmaking automatique ;
- comptes automatisés définitifs ;
- Pokémon custom ;
- moves custom ;
- formes custom ;
- modifications du client Showdown ;
- voice ;
- Global Hub ;
- fonctionnalités sociales.

## 18. Erreurs à ne surtout pas faire

### 1. Développer directement contre le serveur public

Mauvais :

```text
Développement → play.pokemonshowdown.com
```

Préférer :

```text
Développement → Showdown local
```

### 2. Automatiser le navigateur

Ne pas utiliser Selenium/Playwright pour cliquer sur l'interface Showdown.

Utiliser le protocole réseau.

### 3. Parser les textes d'interface

Ne pas construire la logique autour des phrases d'affichage.

Utiliser les événements structurés.

### 4. Refaire le moteur de combat

Le moteur Showdown existe déjà.

### 5. Coupler le parser au domaine Phantasmon

Mauvais :

```text
raw Showdown message
    ↓
Cobblemon action directe
```

Bon :

```text
raw protocol
    ↓
Showdown parser
    ↓
Showdown event
    ↓
Battle domain event
    ↓
Phantasmon
```

### 6. Stocker les mots de passe Showdown dans le client

Jamais.

### 7. Supporter tous les formats dès le début

Un seul format contrôlé.

### 8. Autoriser silencieusement les incompatibilités

Si un Pokémon n'est pas représentable exactement, rejeter l'équipe.

### 9. Supposer que le protocole ne changera jamais

Isoler toute l'intégration derrière un `ShowdownProtocolAdapter` et créer des tests de compatibilité.

### 10. Copier du code du client Showdown sans analyser la licence

Le serveur Showdown est sous MIT, tandis que le client officiel est sous AGPLv3. Le protocole peut être implémenté indépendamment ; ne pas copier du code du client dans Phantasmon sans analyse de licence.

Sources :
https://github.com/smogon/pokemon-showdown
https://github.com/smogon/pokemon-showdown-client

## 19. Risques principaux

| Risque | Niveau | Réponse |
|---|---|---|
| Protocole Showdown évolue | Élevé | Adaptateur isolé + tests |
| Authentification automatisée | Élevé | Valider avant implémentation |
| Création automatique de comptes | Très élevé | Sous-projet séparé |
| Pokémon incompatible | Élevé | Validator |
| Latence | Moyen | State machine + timeouts |
| Déconnexion | Élevé | Reconnexion |
| Formats complexes | Élevé | Scope réduit |
| Parsing incorrect | Élevé | Parser dédié + tests |
| Désynchronisation | Élevé | Showdown comme autorité |
| Licence client | Élevé | Ne pas copier sans analyse |
| Serveur public indisponible | Moyen | Tests locaux |
| Anti-abus / anti-bot | Élevé | Validation préalable |

## 20. Architecture finale recommandée

```text
                    PHANTASMON BACKEND
                           │
             ┌─────────────┴─────────────┐
             │                           │
      Phantasmon Protocol         Showdown Adapter
             │                           │
             ▼                           ▼
     Minecraft Client             Showdown Protocol
                                         │
                                         ▼
                                  Showdown Server
                                         │
                                         ▼
                                   Web Client
```

Le `Showdown Adapter` doit être isolé.

Si Showdown change son protocole, seul ce module doit principalement évoluer.

## 21. Roadmap de développement

```text
1. Lire les sources Showdown
        ↓
2. Installer Showdown localement
        ↓
3. WebSocket Connector
        ↓
4. Authentication/session
        ↓
5. Battle Room parser
        ↓
6. Team Adapter
        ↓
7. Request/Choice Adapter
        ↓
8. Battle Event Adapter
        ↓
9. Fake Phantasmon Player
   contre navigateur Showdown
        ↓
10. Tests erreurs/déconnexions
        ↓
11. Backend Phantasmon réel
        ↓
12. Client Minecraft
        ↓
13. Parcours complet
```

## 22. Critère de réussite du POC

> Un joueur humain utilisant le client web officiel Pokémon Showdown peut combattre un joueur contrôlé depuis Minecraft via Phantasmon, avec Showdown comme moteur de simulation et Phantasmon comme couche de traduction, sans modification du client Showdown.

## 23. Sources primaires

- Pokémon Showdown — dépôt principal : https://github.com/smogon/pokemon-showdown
- Protocole client/serveur : https://github.com/smogon/pokemon-showdown/blob/master/PROTOCOL.md
- Protocole de combat : https://github.com/smogon/pokemon-showdown/blob/master/sim/SIM-PROTOCOL.md
- Architecture : https://github.com/smogon/pokemon-showdown/blob/master/ARCHITECTURE.md
- Équipes : https://github.com/smogon/pokemon-showdown/blob/master/sim/TEAMS.md
- Outils CLI : https://github.com/smogon/pokemon-showdown/blob/master/COMMANDLINE.md
- Serveur local : https://github.com/smogon/pokemon-showdown/blob/master/server/README.md
- API Web : https://github.com/smogon/pokemon-showdown-client/blob/master/WEB-API.md
- Client officiel : https://github.com/smogon/pokemon-showdown-client
