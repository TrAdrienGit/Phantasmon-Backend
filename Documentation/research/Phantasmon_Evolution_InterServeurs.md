# Phantasmon — Vision d'évolution inter-serveurs

> Document de réflexion — évolution future de Phantasmon  
> **Statut : idée / roadmap à long terme, non implémentée**

---

## 1. Principe général

Le projet Phantasmon est d'abord développé comme prévu initialement : un addon/mod client permettant notamment l'utilisation de Pokémon « Ghost » dans un système indépendant du serveur Cobblemon.

Une fois cette première version **stable, fonctionnelle, belle et sans bugs bloquants**, le projet pourra être forké afin d'explorer une évolution plus ambitieuse :

> **Transformer Phantasmon en une couche d'interconnexion entre différents serveurs Minecraft, sans nécessiter de mod serveur.**

L'objectif ne serait plus seulement de permettre des combats, mais de créer une **couche virtuelle de présence et d'interaction inter-serveurs**.

---

## 2. Stratégie de développement

### Phase 1 — Phantasmon Core

Priorité absolue :

- terminer le Phantasmon initial ;
- rendre le mod fonctionnel ;
- stabiliser le backend ;
- corriger les bugs ;
- améliorer l'UX/UI ;
- obtenir une version propre et réellement utilisable.

Aucune fonctionnalité de réseau inter-serveurs ne doit détourner l'attention de cette phase.

### Phase 2 — Fork

Une fois la première version considérée comme suffisamment mature :

```text
Phantasmon
    │
    ├── Version stable / référence
    │
    └── Fork expérimental
          └── Phantasmon Network
```

Le fork servira à expérimenter la couche inter-serveurs sans risquer de déstabiliser le projet original.

---

# 3. Concept : Phantasmon comme couche inter-serveurs

Le principe est de permettre à plusieurs joueurs présents sur des serveurs Minecraft différents de partager un espace virtuel commun.

```text
Minecraft Server A ──┐
                     │
Minecraft Server B ──┼──> Phantasmon Backend
                     │
Minecraft Server C ──┘
```

Les serveurs Minecraft ne communiquent jamais directement entre eux.

Toute synchronisation passe par le backend :

```text
Server A
   │
   ▼
Phantasmon Backend
   │
   ▼
Server B
```

**Pas de P2P.**

Le backend est le point central de synchronisation.

---

# 4. Global Hub

Le concept central serait un **Global Hub**.

Le Global Hub n'est pas une dimension Minecraft réelle.

Il s'agit d'un **espace logique partagé**, géré par le backend Phantasmon.

Il contient notamment :

- les joueurs présents ;
- leur position virtuelle ;
- leur rotation ;
- leur identité ;
- leurs états ;
- éventuellement leurs Pokémon ;
- leurs interactions ;
- les sessions auxquelles ils participent.

Conceptuellement :

```text
                    PHANTASMON BACKEND
                 ┌──────────────────────┐
                 │      GLOBAL HUB      │
                 │                      │
                 │  Player A            │
                 │  Player B            │
                 │  Player C            │
                 │                      │
                 │  Services / Sessions │
                 └──────────┬───────────┘
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
          Server A      Server B      Server C
          Hub Anchor    Hub Anchor    Hub Anchor
```

Il n'existe qu'un **seul Global Hub logique**.

---

# 5. Hub Anchor

Chaque serveur peut disposer d'un **Hub Anchor**.

Le Hub Anchor est une zone locale du monde Minecraft qui sert de point d'accès au Global Hub.

Conceptuellement :

```text
Serveur Minecraft
       │
       ▼
  Hub Anchor
       │
       ▼
  Global Hub
```

Un joueur qui entre dans l'Anchor peut être enregistré dans le Global Hub.

Un joueur qui quitte l'Anchor cesse d'être présent dans cet espace partagé.

Exemple conceptuel :

```text
/phantasmon create-hub HUB_social <coords>
```

La syntaxe exacte de la commande reste à définir.

---

# 6. Le Hub n'est pas une reconstruction du serveur

Une première idée envisagée était de reconstruire chez les autres joueurs une partie du serveur hôte.

Cette approche est abandonnée comme modèle principal.

Problèmes :

- le serveur réel possède ses propres blocs ;
- les blocs « fantômes » n'existent pas réellement côté serveur ;
- les collisions ne correspondent pas ;
- le joueur pourrait apparaître en train de marcher sur quelque chose que le serveur considère comme inexistant ;
- cela peut provoquer des corrections de position, du rubber-banding ou des problèmes avec les systèmes anti-cheat.

Le modèle retenu est donc différent :

> **Le Hub est une représentation virtuelle synchronisée, superposée au monde Minecraft local.**

---

# 7. Contrainte fondamentale : client-only

Phantasmon reste un projet **client-only**.

Le fonctionnement fondamental ne doit pas nécessiter :

- de mod serveur ;
- de plugin serveur ;
- de modification du serveur ;
- d'accès aux données internes du serveur ;
- d'API spécifique fournie par le serveur.

Le serveur Minecraft reste maître de :

- la position réelle du joueur ;
- la gravité ;
- les collisions ;
- les blocs ;
- les chunks ;
- les déplacements physiques.

Phantasmon ne peut pas rendre un bloc virtuel réellement solide pour le serveur.

---

# 8. Conséquence sur la physique

Les éléments virtuels du Hub doivent donc être considérés comme principalement **visuels et interactifs**, et non comme des éléments physiques Minecraft.

Pour une première version, il serait préférable de privilégier un espace partagé essentiellement horizontal :

```text
          Global Hub
    ┌───────────────────┐
    │                   │
    │    Player A       │
    │          Player B │
    │                   │
    │       Player C    │
    │                   │
    └───────────────────┘
```

La coordonnée verticale pourrait être limitée ou gérée séparément afin d'éviter les conflits avec la physique réelle du serveur.

La verticalité et les environnements virtuels complexes pourront éventuellement être étudiés plus tard.

---

# 9. Transformation des coordonnées

Chaque Hub Anchor possède un point d'origine local.

Le client peut convertir la position Minecraft locale vers une position dans l'espace virtuel partagé.

Exemple :

```text
globalX = localX - anchorOriginX
globalZ = localZ - anchorOriginZ
```

Ainsi :

```text
Serveur A                         Serveur B

Anchor A                          Anchor B
   │                                 │
   ▼                                 ▼
Local position                    Local position
   │                                 │
   └────────> Global coordinates <────┘
```

Deux joueurs situés sur des serveurs complètement différents peuvent donc partager les mêmes coordonnées virtuelles.

---

# 10. Entités virtuelles

Les joueurs provenant d'autres serveurs ne sont pas de véritables joueurs Minecraft du serveur local.

Phantasmon les représente sous forme d'**entités distantes virtuelles**.

Conceptuellement :

```text
Minecraft Client
       │
       ├── Local Minecraft Player
       │
       ├── Remote Player A
       ├── Remote Player B
       └── Remote Pokémon
```

Ces entités pourraient recevoir des données telles que :

- identité ;
- skin / apparence ;
- position ;
- rotation ;
- animations ;
- état de déplacement ;
- Pokémon actifs ;
- états de combat ;
- interactions ;
- emotes.

Elles sont contrôlées par les données reçues du backend et non par le serveur Minecraft local.

---

# 11. Synchronisation

Flux conceptuel lors de l'entrée dans un Anchor :

```text
Player enters Hub Anchor
        │
        ▼
Client detects entry
        │
        ▼
JOIN_HUB
        │
        ▼
Phantasmon Backend
        │
        ▼
Player registered in Global Hub
        │
        ▼
Relevant remote players sent to client
        │
        ▼
Remote entities created
```

Pendant le déplacement :

```text
Local player moves
        │
        ▼
Client calculates virtual position
        │
        ▼
Position update
        │
        ▼
Backend
        │
        ▼
Other relevant clients
```

Lors de la sortie :

```text
Player leaves Anchor
        │
        ▼
LEAVE_HUB
        │
        ▼
Backend removes presence
        │
        ▼
Remote representation disappears
```

---

# 12. Le système de présence

Le véritable noyau du futur système pourrait être la **présence**.

Phantasmon saurait :

- qui est connecté ;
- dans quel espace virtuel il se trouve ;
- où il se trouve ;
- avec qui il est à proximité ;
- dans quelle session il se trouve ;
- quel état il expose aux autres joueurs.

Le combat devient alors une application de cette synchronisation.

```text
                PRESENCE
                   │
        ┌──────────┼──────────┐
        ▼          ▼          ▼
      Social     Combat    Events
        │          │          │
     Voice     Tournament   Spectator
                 │
              Training
```

---

# 13. Sessions

Une évolution possible consiste à distinguer le **Global Hub** des **sessions**.

Le Global Hub représente l'espace et la présence.

Une session représente une activité particulière.

Exemple :

```text
GLOBAL HUB
│
├── Social Space
│
├── Battle Session #827
│     ├── Alice
│     └── Bob
│
├── Tournament #183
│     ├── Match 1
│     ├── Match 2
│     └── Spectators
│
└── Training Session #91
      ├── Player A
      └── Training target
```

Une session pourrait avoir différents états :

```text
WAITING
READY
ACTIVE
FINISHED
```

Cette architecture permettrait de réutiliser la même infrastructure pour plusieurs fonctionnalités.

---

# 14. Spectateurs

Une possibilité intéressante serait de permettre à des joueurs qui ne participent pas à une activité de simplement l'observer.

Exemple :

```text
Server A ── Alice
Server B ── Bob
Server C ── Charlie
                 │
                 ▼
           Battle Session
                 │
          ┌──────┴──────┐
          ▼             ▼
       Alice           Bob
      PLAYER           PLAYER

      Charlie
     SPECTATOR
```

Le spectateur pourrait recevoir les états nécessaires à la représentation du combat sans participer lui-même.

Cela ouvre notamment la voie aux tournois inter-serveurs.

---

# 15. Types de Hub / services

Le Global Hub pourrait à terme accueillir plusieurs types de services.

Exemples envisagés :

### Social

Espace de rencontre entre joueurs.

### Tournament

Espace permettant d'organiser des compétitions inter-serveurs.

### Training

Espace d'entraînement.

### Battle

Espace dédié aux combats.

### Event

Espace temporaire associé à un événement.

### Voice

Système de voix basé sur la position virtuelle des joueurs.

Les dimensions proposées précédemment sont uniquement des exemples et ne sont pas encore définitives.

---

# 16. Voice / proximité

La synchronisation des positions pourrait également servir à un système de voix de proximité.

Exemple :

```text
Global Hub

Alice ───── 3m ───── Bob ───────── 20m ───────── Charlie
```

Le backend pourrait utiliser la distance virtuelle pour déterminer quels joueurs doivent recevoir le flux vocal ou avec quel niveau de spatialisation.

Cette fonctionnalité est entièrement future et ne fait pas partie du premier développement.

---

# 17. Événements inter-serveurs

Le système pourrait également permettre des événements indépendants du serveur Minecraft.

Exemple :

```text
Server A ─┐
Server B ─┤
Server C ─┼──> PHANTASMON EVENT
Server D ─┤
Server E ─┘
```

Le backend pourrait gérer :

- inscriptions ;
- présence ;
- matchmaking ;
- sessions ;
- spectateurs ;
- combats ;
- statistiques ;
- événements temporaires.

Les joueurs resteraient sur leurs serveurs Minecraft respectifs.

---

# 18. Identité Phantasmon

À plus long terme, le réseau pourrait posséder une identité indépendante du serveur Minecraft.

Conceptuellement :

```text
Minecraft Server A
        │
        ▼
   Player Identity
        │
        ▼
Phantasmon Identity
        │
   ┌────┼────┐
   ▼    ▼    ▼
Server A B   C
```

Cela pourrait éventuellement permettre :

- profil Phantasmon ;
- statistiques ;
- historique de combats ;
- amis ;
- achievements ;
- présence ;
- historique de tournois.

Cette fonctionnalité soulève cependant des questions de sécurité, d'authentification et de confiance et ne doit pas être développée dans le cadre du premier fork sans réflexion dédiée.

---

# 19. Vision architecturale globale

À terme, l'architecture pourrait ressembler à :

```text
                         PHANTASMON
                    ┌─────────────────┐
                    │                 │
                    │   GLOBAL HUB    │
                    │                 │
                    │    Presence     │
                    │    Sessions     │
                    │    Identity     │
                    │    State        │
                    │                 │
                    └────────┬────────┘
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
          ▼                  ▼                  ▼
     Server A           Server B           Server C
     Anchor             Anchor             Anchor
          │                  │                  │
          ▼                  ▼                  ▼
       Client              Client              Client
```

Le backend devient la couche de synchronisation.

Les serveurs Minecraft deviennent les environnements locaux dans lesquels les joueurs existent réellement.

Phantasmon devient la couche virtuelle permettant de les faire interagir.

---

# 20. Principe directeur

Le concept peut être résumé ainsi :

> **Le serveur Minecraft fournit l'environnement local du joueur.**
>
> **Phantasmon fournit l'expérience virtuelle inter-serveurs.**

Ou, plus techniquement :

> **Phantasmon devient une couche de présence, de synchronisation et d'interaction virtuelle située au-dessus de plusieurs serveurs Minecraft indépendants.**

---

# 21. Ce qui doit rester hors du périmètre du premier projet

Afin d'éviter la dispersion, les fonctionnalités suivantes ne doivent pas être ajoutées au Phantasmon Core uniquement parce qu'elles pourraient être utiles au futur réseau :

- Global Hub ;
- Hub Anchor inter-serveurs ;
- voix ;
- réseau social ;
- matchmaking global ;
- événements inter-serveurs ;
- système de spectateurs inter-serveurs ;
- identité réseau complète ;
- reconstruction de mondes distants ;
- physique virtuelle complexe.

Ces éléments appartiennent au futur fork.

---

# 22. Priorité actuelle

La priorité reste :

```text
PHANTASMON CORE
     │
     ├── Fonctionnel
     ├── Stable
     ├── Propre
     ├── Beau
     └── Sans bugs bloquants
              │
              ▼
        VERSION STABLE
              │
              ▼
             FORK
              │
              ▼
     PHANTASMON NETWORK
```

**Ne pas construire aujourd'hui les problèmes de demain.**

Le futur réseau doit être préparé conceptuellement, mais le développement actuel doit rester concentré sur le Phantasmon initial.

---

## 23. Idée centrale à conserver

Le projet n'a pas besoin de devenir immédiatement un « réseau Minecraft ».

Il doit d'abord prouver qu'il est capable de faire fonctionner proprement son concept initial.

Si cette base fonctionne et devient suffisamment stable, le fork pourra alors transformer cette infrastructure en quelque chose de beaucoup plus large :

> **un réseau virtuel inter-serveurs permettant à des joueurs provenant de mondes Minecraft différents de partager une présence et des interactions communes, sans que leurs serveurs aient besoin de communiquer directement ou d'installer Phantasmon côté serveur.**
