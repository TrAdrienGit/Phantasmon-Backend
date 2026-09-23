---
source: https://cobblemon.com/guides/custompokemon.html (guide officiel Cobblemon)
consulté le: 2026-09-23
nature: notes reformulées, pas une copie du tutoriel original
---

# Notes — Création d'un Pokémon custom sous Cobblemon

Résumé technique du processus officiel de création d'une espèce custom ("fakemon"), utile comme référence pour comprendre le format d'identifiants que le backend Phantasmon doit référencer (cf. CAD Partie 2 §6 — pas de duplication des données Cobblemon).

## Principe général

Un Pokémon custom Cobblemon repose sur **deux ensembles de fichiers combinés** :

- un **data pack** (`data/`) : toute l'information nécessaire au serveur (stats, évolutions, spawns) ;
- un **resource pack** (`assets/`) : tout ce qui est visuel côté client (modèles, textures, animations).

Pour un usage solo/pack unique, les deux peuvent être combinés dans une seule structure de dossiers, à la racine de laquelle un `pack.mcmeta` doit être présent pour que Minecraft reconnaisse le pack.

## Arborescence des fichiers attendue

| Élément | Chemin |
|---|---|
| Modèle 3D (Bedrock Geometry, exporté depuis Blockbench) | `assets/cobblemon/bedrock/pokemon/models/{espece}/{espece}.geo.json` |
| Texture(s) | `assets/cobblemon/textures/pokemon/{espece}/{espece}.png` |
| Animations | `assets/cobblemon/bedrock/pokemon/animations/{espece}/{espece}.animation.json` |
| Poser (association pose ↔ animation) | `assets/cobblemon/bedrock/pokemon/posers/{espece}.json` |
| Resolver (rendu, variantes/aspects) | `assets/cobblemon/bedrock/pokemon/resolvers/0_{espece}_base.json` |
| Fiche espèce (stats, évolutions, moves...) | `data/cobblemon/species/custom/{espece}.json` |
| Traductions (nom, entrées Pokédex) | `assets/cobblemon/lang/{locale}.json` (ex. `en_us.json`, `fr_fr.json`) |
| Table de spawn | `data/cobblemon/spawn_pool_world/{espece}.json` |
| Déclaration d'un aspect custom (variante) | `data/cobblemon/species_features/{aspect}.json` |

## Modèles, textures, animations

- Les modèles sont créés comme des **Bedrock Entities** dans Blockbench (pas des modèles Java Edition classiques), car ce format supporte mieux l'animation — c'est le standard utilisé par Cobblemon lui-même.
- Au minimum une texture par espèce ; une variante shiny est fortement recommandée par convention, une par variante/forme si plusieurs existent.
- Les animations suivent une nomenclature fixe attendue par le moteur Cobblemon : `ground_idle`, `ground_walk`, `water_idle`, `water_swim`, `air_idle`, `air_fly`, `sleep`, `faint`, `battle_idle`, `cry` — à fournir selon le mode de déplacement de l'espèce (terrestre/aquatique/volant).

## Fiche espèce — champs principaux (data pack)

Regroupés par catégorie, les champs clés que la fiche JSON d'une espèce doit renseigner :

- **Identité** : `name`, `labels`, `pokedex` (clés de traduction), `height`, `weight`, `preEvolution`, `features` (liste d'aspects custom).
- **Stats de combat** : `primaryType`, `secondaryType`, `baseStats` (6 stats), `catchRate`, `maleRatio`, `baseExperienceYield`, `experienceGroup`, `eggCycles`, `eggGroups`, `baseFriendship`, `evYield`.
- **Moveset** : `moves` (avec préfixes indiquant la méthode d'apprentissage : niveau, œuf, CT, tuteur), `abilities` (avec marquage des capacités cachées).
- **Évolutions** : liste `evolutions`, chaque entrée avec un `id`, une `variant` (type de déclencheur : niveau, échange, interaction avec objet...), un `result`, des `requirements` (conditions : niveau minimum, biome, objet tenu...).
- **Propriétés d'entité** : `baseScale`, `hitbox` (dimensions), `drops` (loot table simplifiée), `behaviour` (capacités de déplacement : vol, nage...).

## Variantes / aspects

Un aspect (ex. une forme alternative) se déclare séparément dans `species_features/`, avec un type (`flag` pour un booléen simple) et une valeur par défaut. Le resolver associe ensuite chaque combinaison d'aspects à un modèle/texture spécifique.

## Test en jeu

Une fois le pack posé dans `resourcepacks/` (client) et `datapacks/` (sauvegarde du monde), le spawn manuel se fait via une commande dédiée (`/pokespawn` ou `/spawnpokemon` suivi du nom de l'espèce).

## Pertinence pour Phantasmon

Ce format confirme le principe retenu dans le CAD (Partie 2 §6) : Cobblemon résout une espèce à partir d'un **identifiant textuel simple** (`name`/`species`), pas d'une donnée binaire complexe. Le Ghost Backend n'a donc besoin de stocker que ces identifiants (espèce, aspects/variantes, moves, ability) — la résolution réelle (stats, modèle, animation) reste entièrement déléguée aux données Cobblemon/custom déjà présentes chez le client, exactement comme le fait ce pipeline officiel pour n'importe quelle espèce custom.
