# CAD — Cahier des Charges Fonctionnel
## Ghost Pokémon — Partie 1 : Spécifications fonctionnelles

**Version :** 1.0  
**Statut :** Spécification fonctionnelle V1  
**Projet :** Addon Ghost Pokémon pour Cobblemon  
**Document :** Partie 1 — Cahier des charges fonctionnel

---

# 1. Vision du projet

Le projet consiste à créer un addon pour Cobblemon permettant aux joueurs équipés de l'addon de créer, stocker, modifier et utiliser des **Ghost Pokémon**.

Les Ghost Pokémon sont des Pokémon virtuels indépendants des données Pokémon normales de Cobblemon.

Ils sont gérés par un système dédié, avec un **backend externe comme source de vérité**, tout en utilisant autant que possible les systèmes natifs de Cobblemon pour les fonctionnalités de gameplay, notamment les combats.

L'objectif est de fournir une expérience aussi proche que possible d'un Pokémon Cobblemon classique, tout en conservant une séparation stricte entre :

- les Pokémon normaux du serveur ;
- les Ghost Pokémon ;
- les données persistantes du serveur Cobblemon ;
- les données persistantes du système Ghost.

---

# 2. Philosophie générale

Le système Ghost doit fonctionner comme une **couche indépendante au-dessus de Cobblemon**.

Architecture logique :

```text
Joueur
   ↓
Client Minecraft
Cobblemon + Ghost Addon
   ↓
Serveur Minecraft
Cobblemon
Ghost Server Addon
Ghost Layer / Adapter
   ↓ API
Backend Ghost
   ↓
Base de données
```

Le principe fondamental est :

> Le backend Ghost est la source de vérité des Ghost Pokémon.

Le serveur Minecraft ne doit pas devenir la base de données principale des Ghost Pokémon.

Le système doit utiliser Cobblemon au maximum lorsque cela est techniquement possible et utiliser une couche d'adaptation/proxy lorsque les fonctionnalités nécessaires ne sont pas directement accessibles.

---

# 3. Ghost Pokémon

## 3.1 Définition

Un Ghost Pokémon est un Pokémon virtuel appartenant au système Ghost.

Il possède ses propres données et son propre UUID.

Il n'est pas enregistré comme Pokémon normal dans les données persistantes de Cobblemon.

Chaque Ghost Pokémon possède au minimum :

- un UUID ;
- un propriétaire ;
- une espèce ;
- une forme éventuelle ;
- un surnom ;
- un niveau ;
- une nature ;
- une capacité ;
- des IV ;
- des EV ;
- des attaques ;
- un objet tenu éventuel ;
- un type Téra éventuel ;
- son état shiny ;
- son genre éventuel ;
- les autres informations nécessaires à son affichage et à son utilisation en combat.

---

# 4. Identité et propriété

Chaque Ghost Pokémon possède :

```text
UUID Pokémon
UUID propriétaire
```

Le UUID du Pokémon est unique.

Le propriétaire peut changer lors d'un échange.

Le changement de propriétaire ne doit pas modifier l'identité interne du Pokémon.

---

# 5. Indicateur Ghost

Les Ghost Pokémon doivent être clairement identifiables.

Un indicateur `[Ghost]` doit être affiché avec le Pokémon.

Exemple :

```text
[Ghost] Bichou
```

L'indicateur doit permettre de différencier un Ghost Pokémon d'un Pokémon Cobblemon normal.

Malgré cet indicateur, l'expérience visuelle et fonctionnelle doit rester aussi proche que possible d'un Pokémon normal.

---

# 6. Espèces et formes

Le système doit permettre de créer :

- les Pokémon standards ;
- les légendaires ;
- les fabuleux ;
- les formes alternatives ;
- les formes régionales ;
- les formes spéciales ;
- les variantes disponibles dans Cobblemon.

Le système ne doit pas limiter artificiellement les espèces disponibles.

---

# 7. Personnalisation des Pokémon

Le joueur doit pouvoir modifier librement les caractéristiques d'un Ghost Pokémon.

La personnalisation doit notamment permettre de modifier :

- espèce ;
- forme ;
- surnom ;
- niveau ;
- genre ;
- shiny ;
- nature ;
- capacité ;
- attaques ;
- IV ;
- EV ;
- objet tenu ;
- type Téra ;
- autres données pertinentes exposées par le modèle Ghost.

## 7.1 Combinaisons impossibles

La V1 n'impose pas de validation compétitive stricte.

Les combinaisons normalement impossibles peuvent donc être créées.

Exemples possibles en V1 :

- capacité normalement indisponible ;
- attaque normalement incompatible ;
- combinaison d'éléments inhabituelle ;
- valeurs non conformes aux règles classiques.

L'objectif est de réduire la complexité de la première version.

Une validation compétitive de type Smogon pourra être ajoutée ultérieurement.

---

# 8. Format Showdown

Le format Pokémon Showdown / Smogon est utilisé comme référence pour l'import et l'export des Pokémon.

Exemple :

```text
Bichou (Samurott-Hisui) @ Assault Vest
Ability: Torrent
Shiny: Yes
Tera Type: Grass
EVs: 144 Atk / 64 Def / 136 SpD
Timid Nature
- Avalanche
- Aqua Tail
- Body Slam
- Dark Pulse
```

Ce format constitue le format d'échange avec le joueur.

En interne, le système utilise un modèle de données Ghost canonique.

---

# 9. Création des Pokémon

Tout joueur disposant de l'addon peut créer des Ghost Pokémon.

Aucune permission individuelle particulière n'est nécessaire.

Le système ne doit pas nécessiter :

- OP ;
- permission serveur ;
- grade spécial ;
- permission administrateur.

La création doit être disponible pour tous les joueurs équipés de l'addon.

---

# 10. Import d'équipe

L'import d'équipes Showdown doit être supporté.

La V1 doit au minimum permettre un import par commande.

Le joueur peut fournir un bloc Showdown contenant un ou plusieurs Pokémon.

Le système doit :

1. parser le texte ;
2. créer les Ghost Pokémon correspondants ;
3. leur attribuer des UUID uniques ;
4. les enregistrer dans le backend ;
5. les placer dans le Ghost PC / une équipe selon le fonctionnement défini.

Une interface graphique pourra être ajoutée ultérieurement.

---

# 11. Export

Les Ghost Pokémon doivent pouvoir être exportés au format Showdown.

L'export doit générer un texte compatible avec le format Showdown.

Le joueur doit pouvoir récupérer directement le texte dans son presse-papiers.

L'objectif est de permettre :

- le partage d'équipe ;
- la sauvegarde externe ;
- la modification via Showdown ;
- la réimportation.

---

# 12. Ghost PC

Les Ghost Pokémon possèdent un stockage séparé du PC Cobblemon normal.

Le Ghost PC ne doit pas être mélangé au PC Cobblemon.

## 12.1 Capacité

Le Ghost PC possède :

- 16 boîtes ;
- 36 emplacements par boîte ;
- 6 colonnes × 6 lignes.

Capacité totale :

```text
16 × 36 = 576 Ghost Pokémon
```

Les 576 emplacements correspondent à la capacité de stockage simultanée.

Il n'existe pas de limite globale de création de Ghost Pokémon.

---

# 13. Accès au Ghost PC

Le fonctionnement privilégié est l'utilisation d'un véritable bloc de type PC Pokémon dans le monde.

Le bloc doit ouvrir l'interface du Ghost PC.

Si la création d'un bloc fonctionnel s'avère trop complexe techniquement, une interface accessible par une touche dédiée pourra servir de solution de remplacement.

La séparation avec le PC Cobblemon doit rester claire.

---

# 14. Organisation du Ghost PC

Le Ghost PC sert à :

- stocker les Pokémon ;
- consulter les Pokémon ;
- modifier les Pokémon ;
- créer des équipes ;
- organiser les équipes ;
- cloner des Pokémon ;
- supprimer des Pokémon ;
- exporter des Pokémon ;
- importer des équipes.

---

# 15. Équipes

Le système doit permettre la création de plusieurs équipes.

Une équipe peut contenir :

```text
1 à 6 Pokémon
```

Les équipes incomplètes sont autorisées.

Exemple :

```text
Équipe PvP
[Pokémon 1]
[Pokémon 2]
[Pokémon 3]
[Vide]
[Vide]
[Vide]
```

Le joueur doit pouvoir modifier librement ses équipes.

---

# 16. Stockage des équipes

Une équipe ne doit pas dupliquer les données des Pokémon.

Elle doit stocker des références vers les UUID des Ghost Pokémon.

Exemple :

```text
GhostTeam
├── Slot 1 → UUID Pokémon
├── Slot 2 → UUID Pokémon
├── Slot 3 → UUID Pokémon
├── Slot 4 → UUID Pokémon
├── Slot 5 → UUID Pokémon
└── Slot 6 → UUID Pokémon
```

Le Pokémon présent dans le Ghost PC reste l'entité canonique.

Les équipes ne possèdent que des références.

---

# 17. Équipe active

Le joueur possède une équipe active de six emplacements maximum.

L'équipe active correspond à l'équipe utilisée pour les combats Ghost.

Une équipe active peut être incomplète.

Le système ne doit pas obliger le joueur à avoir six Pokémon pour utiliser une équipe.

---

# 18. Modification des Pokémon

Le joueur doit pouvoir modifier un Ghost Pokémon après sa création.

Les modifications doivent notamment couvrir :

- espèce ;
- forme ;
- surnom ;
- niveau ;
- IV ;
- EV ;
- nature ;
- capacité ;
- attaques ;
- objet ;
- shiny ;
- genre ;
- type Téra ;
- autres paramètres disponibles.

Les modifications sont persistées dans le backend.

---

# 19. Duplication

Un Ghost Pokémon doit pouvoir être cloné.

Le clone possède :

- les mêmes données que l'original ;
- un nouvel UUID ;
- le même propriétaire initial.

Exemple :

```text
Pokémon original
UUID A

↓ Clone

Pokémon original
UUID A

Pokémon cloné
UUID B
```

Les deux Pokémon deviennent ensuite indépendants.

---

# 20. Suppression

La suppression d'un Ghost Pokémon est définitive en V1.

Il n'existe pas de corbeille ni de système de restauration.

La suppression doit également gérer les références existantes dans les équipes afin d'éviter des références invalides.

---

# 21. Présence dans le monde

Les Ghost Pokémon peuvent être envoyés dans le monde.

Ils doivent pouvoir :

- apparaître auprès du joueur ;
- être visibles ;
- suivre leur propriétaire ;
- être rappelés ;
- être présentés aux autres joueurs équipés de l'addon.

Le comportement visuel doit se rapprocher autant que possible d'un Pokémon Cobblemon normal.

---

# 22. Visibilité selon le client

La gestion des entités est spécifique aux Ghost Pokémon.

## 22.1 Joueur équipé de l'addon

Le joueur voit :

- les Ghost Pokémon ;
- leur modèle ;
- leur nom ;
- leur indicateur `[Ghost]` ;
- leur comportement.

## 22.2 Joueur sans addon

Le joueur ne doit rien voir.

Il ne doit pas simplement voir une entité invisible.

Il ne doit y avoir :

- aucune entité Ghost synchronisée ;
- aucun modèle Ghost ;
- aucune interaction possible ;
- aucune présence visuelle.

Principe :

```text
Alice possède l'addon
        ↓
voit le Ghost

Bob n'a pas l'addon
        ↓
aucune entité Ghost n'existe pour son client
```

---

# 23. Restrictions hors combat

Les Ghost Pokémon ne doivent pas être considérés comme des Pokémon Cobblemon normaux.

En V1, ils ne doivent pas être utilisés pour :

- les combats sauvages ;
- les combats classiques avec des joueurs sans addon ;
- les montures ;
- les systèmes Cobblemon secondaires non prévus ;
- les fonctions nécessitant une progression Cobblemon classique.

---

# 24. Niveaux et progression

Les Ghost Pokémon ne possèdent pas de progression classique.

En V1 :

- pas d'XP ;
- pas de gain de niveau ;
- pas d'évolution automatique ;
- pas de progression naturelle ;
- pas d'apprentissage automatique de nouvelles attaques.

Le joueur modifie directement les caractéristiques de son Ghost Pokémon.

---

# 25. État après combat

Les combats Ghost doivent être temporaires.

L'état de combat ne doit pas être enregistré comme état permanent du Pokémon.

Après un combat :

- PV entièrement restaurés ;
- PP restaurés ;
- statuts supprimés ;
- boosts supprimés ;
- autres états temporaires supprimés.

Le Pokémon revient à son état de base.

Exemple :

```text
Avant combat
PV : 100%

Pendant combat
PV : 17%
Stat boosts : +2
Statut : Burn
PP modifiés

Fin du combat
↓
PV : 100%
PP : restaurés
Boosts : 0
Statut : aucun
```

---

# 26. Combats PvP

Le système principal de combat V1 est :

```text
Ghost Pokémon
       VS
Ghost Pokémon
```

Les combats doivent utiliser autant que possible le système de combat Cobblemon existant.

Le projet ne doit pas recréer un moteur Pokémon complet.

---

# 27. Intégration au moteur Cobblemon

Le principe est :

```text
Ghost Pokémon
      ↓
Ghost Adapter
      ↓
Cobblemon Battle System
```

Le Ghost Layer fournit au système Cobblemon les informations nécessaires au combat sans transformer le Ghost Pokémon en Pokémon Cobblemon persistant.

L'objectif est de réutiliser :

- calculs de combat ;
- attaques ;
- capacités ;
- statistiques ;
- effets ;
- logique de tours ;
- interface de combat ;
- autres composants Cobblemon compatibles.

---

# 28. Règles de combat

La V1 utilise les règles normales de combat Cobblemon autant que possible.

Aucune règle Ghost spécifique complexe n'est ajoutée dans un premier temps.

L'objectif à terme est de se rapprocher des règles compétitives de type Showdown / Smogon.

La validation stricte des équipes n'est pas obligatoire en V1.

---

# 29. Demande de combat

Le joueur doit pouvoir proposer un combat Ghost à un autre joueur.

L'intégration idéale consiste à ajouter une option au menu circulaire/interactif de Cobblemon lors de l'interaction avec un autre joueur.

Option :

```text
Combat Ghost
```

Une commande de secours doit également pouvoir permettre :

```text
/ghost battle <joueur>
```

Le joueur ciblé reçoit une demande et peut :

- accepter ;
- refuser.

---

# 30. Ghost contre Ghost

Les combats Ghost contre Ghost sont obligatoires dans le MVP.

Conditions :

- les deux joueurs possèdent l'addon ;
- les deux utilisent des Ghost Pokémon ;
- le backend est disponible.

---

# 31. Ghost contre Pokémon normal

La compatibilité Ghost ↔ Pokémon normal est souhaitée.

Elle n'est cependant pas une obligation absolue du MVP.

Objectif idéal :

```text
Ghost → Pokémon normal
Pokémon normal → Ghost
```

Le système doit utiliser le moteur Cobblemon lorsque possible.

Cette fonctionnalité pourra être finalisée après la stabilisation des combats Ghost ↔ Ghost.

---

# 32. Joueurs sans addon

Un joueur ne possédant pas l'addon ne doit pas pouvoir :

- voir un Ghost Pokémon ;
- interagir avec un Ghost Pokémon ;
- recevoir une demande de combat Ghost ;
- combattre un Ghost Pokémon.

En V1, un combat Ghost contre un joueur sans addon est interdit.

---

# 33. Échanges

Les Ghost Pokémon peuvent être échangés entre joueurs équipés de l'addon.

Conditions :

```text
Joueur A possède addon
Joueur B possède addon
```

Lors d'un échange :

- le propriétaire est modifié ;
- le UUID du Pokémon est conservé ;
- les données du Pokémon sont conservées.

Exemple :

```text
Owner = Alice
UUID = X

Échange

Owner = Bob
UUID = X
```

---

# 34. Séparation avec les Pokémon normaux

Le Ghost PC et le PC Cobblemon sont deux systèmes séparés.

En principe :

```text
PC Cobblemon
    ≠
Ghost PC
```

Un Ghost Pokémon ne peut pas être placé dans le PC normal.

Un Pokémon normal ne peut pas être placé directement dans le Ghost PC.

---

# 35. Conversion Pokémon normal → Ghost

La conversion d'un Pokémon Cobblemon normal en Ghost est prévue.

Cette conversion pourra permettre de transformer les données du Pokémon normal en données Ghost.

Les détails techniques de la conversion devront être définis dans l'architecture technique.

---

# 36. Conversion Ghost → Pokémon normal

La conversion inverse est possible uniquement pour les administrateurs / joueurs OP.

Elle constitue une fonction administrative.

Un joueur normal ne doit pas pouvoir convertir librement un Ghost en Pokémon Cobblemon réel.

---

# 37. Administration

Le système possède une interface d'administration.

Deux formes sont prévues :

- interface graphique ;
- commandes.

L'administration permet notamment :

- inspection ;
- création ;
- modification ;
- suppression ;
- clonage ;
- attribution ;
- gestion des joueurs ;
- gestion des équipes ;
- gestion des Pokémon ;
- actions de maintenance.

---

# 38. Inspection administrateur

Une commande d'inspection est prévue.

Elle doit permettre à un administrateur de consulter les données d'un Ghost Pokémon.

Exemple conceptuel :

```text
/ghost admin inspect <uuid>
```

L'interface pourra afficher :

- propriétaire ;
- espèce ;
- forme ;
- niveau ;
- nature ;
- capacité ;
- attaques ;
- IV ;
- EV ;
- objet ;
- shiny ;
- type Téra ;
- état ;
- informations techniques.

---

# 39. Permissions

Pour les joueurs :

```text
Addon installé
      ↓
accès aux fonctionnalités Ghost
```

Il n'y a pas de système de grades ou de permissions individuelles pour l'utilisation normale.

Les fonctions administratives restent protégées par les permissions serveur / OP.

---

# 40. Backend

Le backend Ghost est obligatoire.

Il constitue la source de vérité.

Il gère notamment :

- Pokémon ;
- propriétaires ;
- équipes ;
- stockage ;
- UUID ;
- données Ghost ;
- validation des requêtes ;
- opérations d'administration ;
- synchronisation.

Le backend est indépendant du serveur Minecraft.

---

# 41. Base de données

Le backend s'appuie sur une base de données persistante.

La base contient les données Ghost.

Le serveur Minecraft ne doit pas devenir la source de vérité.

Principe :

```text
Minecraft Server
       |
       | API
       ↓
Ghost Backend
       |
       ↓
Database
```

---

# 42. Autorité des données

Le backend est l'autorité finale pour les données Ghost.

Le serveur Minecraft peut :

- demander des données ;
- transmettre des modifications ;
- créer une session ;
- demander un Pokémon ;
- demander une équipe ;
- transmettre l'état nécessaire au gameplay.

Mais il ne doit pas devenir la base persistante principale.

---

# 43. Indisponibilité du backend

Si le backend est indisponible :

```text
Ghost System = désactivé
```

Le joueur ne peut plus utiliser les fonctionnalités Ghost.

Les fonctionnalités normales de Cobblemon continuent de fonctionner.

Le serveur ne doit pas être bloqué par l'indisponibilité du backend Ghost.

---

# 44. Perte du backend pendant un combat

Si le backend devient indisponible pendant un combat Ghost :

1. le combat est interrompu ;
2. aucun vainqueur n'est déclaré ;
3. le résultat est considéré comme un match nul / égalité ;
4. l'état permanent des Ghost Pokémon n'est pas modifié ;
5. les états temporaires sont nettoyés.

Principe :

```text
Backend perdu
     ↓
Battle terminated
     ↓
Draw
     ↓
Reset battle state
```

---

# 45. Indépendance du serveur Cobblemon

Le serveur Cobblemon doit rester fonctionnel sans le système Ghost.

Si le backend Ghost tombe :

- les Pokémon normaux continuent de fonctionner ;
- les PC normaux continuent de fonctionner ;
- les combats normaux continuent ;
- le serveur Minecraft ne doit pas dépendre du backend pour son fonctionnement général.

---

# 46. Architecture logique cible

Architecture fonctionnelle :

```text
┌──────────────────────────────┐
│        Minecraft Client      │
│                              │
│ Cobblemon + Ghost Client     │
└──────────────┬───────────────┘
               │
               │ Packets / Network
               ▼
┌──────────────────────────────┐
│      Minecraft Server        │
│                              │
│ Cobblemon                    │
│ Ghost Server Addon           │
│ Ghost Layer / Adapter        │
└──────────────┬───────────────┘
               │
               │ API
               ▼
┌──────────────────────────────┐
│        Ghost Backend         │
│                              │
│ Pokémon                      │
│ Teams                        │
│ Storage                      │
│ Ownership                    │
│ Validation                   │
│ Battle sessions              │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│          Database            │
└──────────────────────────────┘
```

---

# 47. Modèle de données fonctionnel

Le modèle fonctionnel cible est :

```text
GhostPokemon
├── identity
│   ├── uuid
│   ├── owner
│   └── nickname
│
├── species
│   ├── species
│   └── form
│
├── battle
│   ├── level
│   ├── nature
│   ├── ability
│   ├── moves
│   ├── IVs
│   ├── EVs
│   └── teraType
│
├── appearance
│   ├── shiny
│   ├── gender
│   └── ...
│
└── heldItem
```

Les informations exactes seront précisées dans la partie architecture technique.

---

# 48. Séparation des données permanentes et temporaires

Les données permanentes d'un Ghost Pokémon sont séparées de son état de combat.

```text
GhostPokemon
       ↓
Battle Instance
       ↓
État temporaire
├── HP
├── PP
├── statuts
├── boosts
└── autres états de combat
```

L'état temporaire ne doit pas être enregistré comme état permanent du Pokémon.

---

# 49. Flux fonctionnel principal

## 49.1 Création

```text
Joueur
 ↓
Création Ghost
 ↓
Ghost Backend
 ↓
Création UUID
 ↓
Enregistrement
 ↓
Ghost PC
```

## 49.2 Modification

```text
Joueur
 ↓
Modification
 ↓
Serveur Ghost
 ↓
Backend
 ↓
Sauvegarde
```

## 49.3 Combat

```text
Joueur A
 ↓
Demande Ghost Battle
 ↓
Joueur B
 ↓
Acceptation
 ↓
Backend / Battle Session
 ↓
Ghost Adapter
 ↓
Cobblemon Battle System
 ↓
Combat
 ↓
Fin
 ↓
Reset état temporaire
```

---

# 50. Objectif du MVP

Le MVP doit permettre de disposer d'un système Ghost fonctionnel de bout en bout.

Fonctionnalités obligatoires :

- création de Ghost Pokémon ;
- import Showdown ;
- stockage dans le Ghost PC ;
- 16 boîtes ;
- 576 emplacements ;
- création d'équipes ;
- équipes de 1 à 6 Pokémon ;
- équipe active ;
- modification complète ;
- clonage ;
- suppression ;
- UUID ;
- propriétaire ;
- indicateur `[Ghost]` ;
- apparition dans le monde ;
- suivi du joueur ;
- rappel ;
- invisibilité complète pour les joueurs sans addon ;
- stockage backend ;
- backend comme source de vérité ;
- désactivation du système si le backend est indisponible ;
- combats Ghost contre Ghost ;
- utilisation du moteur Cobblemon autant que possible ;
- reset complet de l'état après combat ;
- absence d'XP ;
- absence de progression ;
- absence d'évolution ;
- absence de combat sauvage ;
- absence de combat avec les joueurs sans addon.

---

# 51. Fonctionnalités prévues après le MVP

Les fonctionnalités suivantes sont prévues mais ne sont pas nécessaires pour la première version :

- Ghost ↔ Pokémon normal ;
- Pokémon normal ↔ Ghost plus complet ;
- interface graphique complète ;
- intégration complète au menu circulaire Cobblemon ;
- validation de légalité Showdown / Smogon ;
- règles compétitives avancées ;
- système de tournois ;
- matchmaking ;
- statistiques de matchs ;
- historique des matchs ;
- systèmes avancés d'administration ;
- compatibilité avec davantage de fonctionnalités Cobblemon ;
- intégration plus poussée des objets et systèmes Cobblemon ;
- améliorations de synchronisation et de rendu.

---

# 52. Principes techniques à préserver

Même si les détails seront définis dans la Partie 2 — Architecture technique, les principes suivants sont considérés comme non négociables :

## 52.1 Ne pas recréer Cobblemon

Le projet ne doit pas réimplémenter inutilement :

- les Pokémon ;
- les calculs de combat ;
- les attaques ;
- les capacités ;
- le système de combat ;
- les interfaces déjà fournies par Cobblemon.

Lorsque Cobblemon fournit déjà une fonctionnalité exploitable, elle doit être privilégiée.

## 52.2 Ne pas transformer les Ghost Pokémon en Pokémon persistants Cobblemon

Un Ghost Pokémon ne doit pas devenir un Pokémon Cobblemon normal simplement pour pouvoir être utilisé.

Le système doit utiliser un adaptateur / proxy lorsque nécessaire.

## 52.3 Backend comme source de vérité

Les données Ghost persistantes doivent appartenir au backend.

## 52.4 Aucun Ghost visible pour un client non équipé

Le serveur doit éviter de synchroniser les entités Ghost avec les clients qui ne possèdent pas l'addon.

## 52.5 Séparer permanent et temporaire

Les données du Pokémon et son état de combat doivent rester séparés.

## 52.6 Ne pas casser Cobblemon

Une panne du système Ghost ne doit pas rendre le serveur Cobblemon inutilisable.

---

# 53. Résumé fonctionnel

Le projet peut être résumé comme suit :

```text
                 GHOST SYSTEM
                      │
        ┌─────────────┴─────────────┐
        │                           │
   Ghost Backend              Minecraft Addon
        │                           │
        │                     Ghost Layer
        │                           │
        │                     Cobblemon
        │                           │
        └──────────────┬────────────┘
                       │
                  Joueur équipé
                       │
          ┌────────────┴────────────┐
          │                         │
      Ghost PC                 Ghost Battle
          │                         │
       Teams                  Cobblemon Battle
          │                         │
       Pokémon                Temporary State
```

Le résultat recherché est un système où :

- n'importe quel joueur équipé de l'addon peut créer des Ghost Pokémon ;
- ces Pokémon sont stockés indépendamment de Cobblemon ;
- les données sont persistées dans un backend ;
- les Pokémon peuvent être personnalisés sans restriction compétitive en V1 ;
- les équipes peuvent être créées et importées depuis Showdown ;
- les Ghost Pokémon peuvent être utilisés dans le monde ;
- seuls les clients équipés voient les Ghost Pokémon ;
- les combats utilisent le moteur Cobblemon autant que possible ;
- les Ghost Pokémon ne progressent pas ;
- leur état est réinitialisé après chaque combat ;
- le serveur Cobblemon reste indépendant du système Ghost ;
- le backend reste la source de vérité ;
- le système peut évoluer ultérieurement vers une intégration compétitive et des fonctionnalités plus avancées.

---

# 54. Étape suivante

La Partie 1 définit le **quoi** du projet.

La prochaine étape est la **Partie 2 — Architecture technique**, qui devra définir précisément :

1. version de Minecraft ;
2. version de Cobblemon ;
3. loader utilisé ;
4. structure du mod ;
5. séparation client / serveur ;
6. architecture du backend ;
7. API ;
8. base de données ;
9. modèle technique `GhostPokemon` ;
10. modèle technique `GhostTeam` ;
11. modèle technique `GhostPC` ;
12. authentification serveur ↔ backend ;
13. synchronisation ;
14. packets Minecraft ;
15. gestion des entités invisibles pour les clients non équipés ;
16. Ghost Adapter pour Cobblemon ;
17. intégration au moteur de combat ;
18. parser Showdown ;
19. exporter Showdown ;
20. gestion des erreurs ;
21. sécurité ;
22. gestion des déconnexions ;
23. stratégie de tests.
