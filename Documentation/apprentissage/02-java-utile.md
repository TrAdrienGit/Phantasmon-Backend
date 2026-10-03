# 2. Le Java utile pour ce projet

Ce chapitre n'enseigne pas la programmation : il présente les particularités de Java que vous croiserez dans le
code, avec un exemple tiré du projet à chaque fois. Version utilisée : **Java 21**.

## 2.1 Paquets et fichiers

Chaque fichier commence par `package com.mystaria.phantasmon_backend.pokemon;` : le paquet est l'« adresse » de la
classe, et doit correspondre au dossier. Une classe publique = un fichier du même nom. `import` permet d'utiliser
une classe d'un autre paquet sans écrire son nom complet.

Le nom de paquet suit la convention « domaine inversé » (`com.mystaria`) pour éviter les collisions avec d'autres
bibliothèques. Le tiret est interdit dans un nom de paquet, d'où `phantasmon_backend`.

## 2.2 Les records : des objets de données

Un `record` déclare une classe **immuable** dont les champs, le constructeur, les accesseurs, `equals`,
`hashCode` et `toString` sont générés automatiquement.

```java
// common/ErrorResponse.java
public record ErrorResponse(String errorCode, Map<String, Object> details) {
}
```

On écrit `new ErrorResponse("ERROR_X", Map.of())`, puis `response.errorCode()` (pas de `get`). Le projet utilise
des records pour **tout ce qui transite sur le réseau** (requêtes, réponses, messages WebSocket) : ce sont de
simples sacs de données qu'on ne modifie pas.

Un record peut contenir des méthodes :

```java
// auth/MojangProfile.java
public record MojangProfile(String id, String name) {
    public UUID uuid() {
        // Mojang renvoie l'UUID sans tirets : on les remet.
        String dashed = id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }
}
```

## 2.3 Les annotations

Une annotation (`@Quelquechose`) est une **étiquette** posée sur une classe, une méthode, un champ ou un paramètre.
Elle ne fait rien par elle-même : c'est un outil (le compilateur, Spring, Hibernate, Lombok…) qui la lit et agit.

```java
@RestController                 // Spring : cette classe répond à des requêtes HTTP
public class HealthController {
    @GetMapping("/health")      // Spring : cette méthode répond à GET /health
    public ResponseEntity<…> health() { … }
}
```

Presque toute la « magie » de Spring passe par des annotations. Quand vous voyez une annotation inconnue, la
question à se poser est : **quel outil la lit, et que fait-il ?**

## 2.4 Les génériques

`List<UUID>` est une liste d'UUID, `Map<String, Object>` une table clé → valeur. Le type entre chevrons est vérifié
à la compilation. Une méthode peut aussi être générique :

```java
// common/IdempotencyService.java
public <T> T executeIdempotent(UUID requestUuid, UUID playerUuid, String endpoint,
                               Supplier<T> action, Class<T> responseType) { … }
```

`<T>` signifie « fonctionne pour n'importe quel type T » : la même méthode sert pour un Pokémon, un échange ou un
combat.

## 2.5 `Optional` : « peut-être une valeur »

Au lieu de renvoyer `null` quand rien n'est trouvé, beaucoup de méthodes renvoient un `Optional<T>` :

```java
// pokemon/PokemonService.java
Pokemon pokemon = pokemonRepository.findById(pokemonUuid)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_POKEMON_NOT_FOUND", Map.of("uuid", pokemonUuid)));
```

| Méthode | Effet |
|---|---|
| `orElseThrow(…)` | Valeur, ou lève l'exception fournie |
| `orElse(x)` / `orElseGet(() -> x)` | Valeur, ou une valeur de repli |
| `map(f)` | Transforme la valeur si elle existe |
| `filter(cond)` | Garde la valeur seulement si la condition est vraie |
| `ifPresent(f)` | Exécute `f` seulement si la valeur existe |

## 2.6 Lambdas et références de méthode

Une lambda est une fonction anonyme passée en paramètre : `x -> x * 2`, `() -> doSomething()`. Une référence de
méthode est un raccourci : `PokemonResponse::from` équivaut à `p -> PokemonResponse.from(p)`.

Les interfaces fonctionnelles standard que vous verrez : `Supplier<T>` (ne prend rien, rend un T), `Runnable` (ne
prend rien, ne rend rien), `Function<A, B>`, `Consumer<T>`.

```java
// pokemon/PokemonService.java — swapOrMove reçoit « l'action qui pose la nouvelle place » en paramètre
swapOrMove(pokemon, occupant, () -> {
    pokemon.setTeamSlot(teamSlot);
    pokemon.setBoxId(null);
    pokemon.setBoxSlot(null);
});
```

## 2.7 Les streams : transformer des collections

```java
// pokemon/PokemonService.java
return pokemonRepository.findByOwnerUuid(ownerUuid).stream()   // flux d'entités Pokemon
        .map(PokemonResponse::from)                            // chacune convertie en réponse JSON
        .toList();                                             // rassemblées en liste
```

Les opérations courantes : `filter`, `map`, `collect(Collectors.toSet())`, `toList()`, `anyMatch`.

## 2.8 Exceptions

Une exception interrompt l'exécution jusqu'à ce qu'un `catch` la récupère. Deux familles :

- **vérifiées** (`IOException`) : le compilateur oblige à les déclarer (`throws`) ou à les attraper ;
- **non vérifiées** (`RuntimeException` et ses sous-classes) : libres.

Le projet crée sa propre exception non vérifiée pour toutes les erreurs métier :

```java
// common/ApiException.java (raccourci)
public class ApiException extends RuntimeException {
    private final HttpStatus status;          // ex. 404
    private final String errorCode;           // ex. "ERROR_POKEMON_NOT_FOUND"
    private final Map<String, Object> details;
    …
}
```

Un service la lève ; un gestionnaire central la transforme en réponse JSON (chapitre 6). Le reste du code n'a
jamais à fabriquer de réponse d'erreur à la main.

## 2.9 Syntaxes modernes à reconnaître

```java
var builder = Jwts.builder();                           // type déduit par le compilateur

switch (incoming.type()) {                              // switch « flèche », sans break
    case "Heartbeat" -> { … }
    case "RecallGhost" -> handleRecallGhost(playerUuid);
    default -> send(session, WsMessage.error(…));
}

if (value instanceof Number number) {                   // test de type + variable typée en une fois
    return number.intValue();
}

String json = """
        { "species": "pikachu" }
        """;                                            // bloc de texte multiligne (utilisé dans les tests)
```

## 2.10 Dates et identifiants

| Type | Usage |
|---|---|
| `UUID` | Identifiant unique de 128 bits (joueurs, Pokémon, échanges…). `UUID.randomUUID()` en génère un. |
| `Instant` | Un instant précis (UTC). `Instant.now()`. |
| `Duration` | Une durée. `Duration.ofSeconds(30)`, ou `PT30S` en texte (format ISO-8601 utilisé dans la configuration). |
| `Clock` | Une horloge injectable : en test, on la remplace par une horloge qu'on avance à la main. |

## 2.11 Concurrence : plusieurs requêtes en même temps

Un serveur traite plusieurs requêtes **en parallèle**, chacune sur son propre thread. Si deux threads modifient la
même donnée en mémoire au même moment, le résultat peut être faux. Outils utilisés :

| Outil | Où | Effet |
|---|---|---|
| `ConcurrentHashMap` | `PresenceService`, `SessionRegistry` | Une `Map` utilisable par plusieurs threads à la fois |
| `synchronized` sur une méthode | `LiveTradeService`, `LiveBattleService` | Un seul thread à la fois dans les méthodes de l'objet |
| `synchronized (objet) { … }` | `SessionRegistry.sendTo` | Un seul thread à la fois écrit sur **cette** session |
| Objets immuables (records) | `PlayerPresence` | On remplace l'objet entier au lieu de le modifier : pas d'état à moitié mis à jour |

La base de données gère sa propre concurrence par les **transactions** (chapitre 7).

## 2.12 Lombok : moins de code répétitif

Lombok est un outil qui **génère du code à la compilation** à partir d'annotations :

| Annotation | Génère |
|---|---|
| `@Getter` / `@Setter` | Les méthodes `getX()` / `setX()` |
| `@NoArgsConstructor` | Un constructeur sans argument (exigé par JPA) |
| `@Slf4j` | Un champ `log` pour écrire dans les journaux : `log.info("…")` |

```java
@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Pokemon {
    @Id private UUID uuid;
    @Setter private String species;   // setSpecies(...) généré pour ce champ seulement
    …
}
```

## À retenir

- Records pour les données réseau, annotations lues par des outils, `Optional` au lieu de `null`.
- Lambdas et streams pour passer du comportement et transformer des listes.
- Une seule exception métier (`ApiException`) transformée en JSON à un seul endroit.
- Plusieurs threads en parallèle : collections concurrentes, `synchronized`, objets immuables.
