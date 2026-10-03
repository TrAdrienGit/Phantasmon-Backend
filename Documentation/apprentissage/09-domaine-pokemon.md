# 9. Le domaine Pokémon : un service métier complet

Fichiers : `pokemon/` (`PokemonController`, `PokemonService`, `PokemonLegalityService`, `PokemonRepository`,
`Pokemon`, records `PokemonCreateRequest` / `PokemonUpdateRequest` / `PokemonResponse`) et
`common/IdempotencyService`.

## 9.1 Les règles du domaine

| Règle | Où elle est appliquée |
|---|---|
| Un Pokémon appartient à un joueur, qui seul peut le modifier | `PokemonService.findOwned` |
| IV entre 0 et 31, EV entre 0 et 252, total EV ≤ 510 | `PokemonLegalityService` |
| PC de 16 boîtes × 30 cases ; équipe de 6 emplacements | Contraintes SQL + `PokemonService` |
| Un Pokémon est **soit** dans le PC, **soit** dans l'équipe | `PokemonService.moveTo…` |
| Déplacer vers une place occupée échange les deux Pokémon | `PokemonService.swapOrMove` |
| Créer deux fois avec le même `request_uuid` ne crée qu'un Pokémon | `IdempotencyService` |
| Impossible de supprimer un Pokémon engagé dans un échange en attente | `PokemonService.delete` |

## 9.2 Création

```java
// pokemon/PokemonService.java
@Transactional
public PokemonResponse create(UUID ownerUuid, PokemonCreateRequest request) {
    legalityService.validate(request.data());                       // 1. règles métier d'abord
    Short boxId = toShort(request.boxId());
    Short boxSlot = toShort(request.boxSlot());
    Short teamSlot = toShort(request.teamSlot());
    if (boxId == null && boxSlot == null && teamSlot == null) {     // 2. pas de place demandée : première libre
        int[] freeSlot = findFreePcSlot(ownerUuid);
        boxId = (short) freeSlot[0];
        boxSlot = (short) freeSlot[1];
    }
    Pokemon pokemon = new Pokemon(UUID.randomUUID(), ownerUuid, …); // 3. l'UUID est généré par le serveur
    try {
        pokemonRepository.saveAndFlush(pokemon);                    // 4. écriture immédiate…
    } catch (DataIntegrityViolationException ex) {                  //    …pour attraper ici la violation d'index unique
        throw new ApiException(HttpStatus.CONFLICT, "ERROR_POKEMON_SLOT_OCCUPIED", Map.of());
    }
    return PokemonResponse.from(pokemon);                           // 5. entité → record JSON
}
```

Points à remarquer : le propriétaire vient du jeton (paramètre `ownerUuid`), l'UUID est généré côté serveur, et la
base est utilisée comme arbitre final de la place libre (`saveAndFlush` + `catch`). Sans le *flush*, l'erreur
d'unicité arriverait plus tard, au commit, hors de ce `try`.

## 9.3 La légalité

```java
// pokemon/PokemonLegalityService.java (raccourci)
public void validate(Map<String, Object> data) {
    Map<String, Object> evs = (Map<String, Object>) data.getOrDefault("evs", Map.of());
    int total = 0;
    for (Map.Entry<String, Object> entry : evs.entrySet()) {
        int value = toInt(entry.getValue());
        if (value < 0 || value > 252) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_EV_OUT_OF_RANGE",
                    Map.of("stat", entry.getKey(), "value", value, "min", 0, "max", 252));
        }
        total += value;
    }
    if (total > 510) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_EV_TOTAL_EXCEEDED",
                Map.of("total", total, "max", 510));
    }
}
```

Pourquoi un service séparé et non des annotations : la règle porte sur le contenu d'un JSON libre, et elle doit
être appelée **explicitement** à la création **et** à chaque modification de `data`. Pourquoi seulement IV/EV : le
backend n'a aucune donnée Cobblemon (il ne sait pas quels talents existent pour une espèce). Cette vérification est
faite par l'interface du client (décision D-02).

## 9.4 L'idempotence

```java
// common/IdempotencyService.java
@Transactional
public <T> T executeIdempotent(UUID requestUuid, UUID playerUuid, String endpoint, Supplier<T> action, Class<T> responseType) {
    return repository.findById(requestUuid)
            .map(existing -> objectMapper.convertValue(existing.getResponseSnapshot(), responseType))   // déjà vu : rejouer
            .orElseGet(() -> {
                T result = action.get();                                                             // première fois : exécuter
                Map<String, Object> snapshot = objectMapper.convertValue(result, Map.class);
                repository.save(new IdempotencyKey(requestUuid, playerUuid, endpoint, snapshot));     // mémoriser la réponse
                return result;
            });
}
```

Le contrôleur passe l'action sous forme de lambda (`() -> pokemonService.create(...)`) : le service d'idempotence
décide s'il faut l'exécuter. Ce mécanisme est générique et sert aussi pour `POST /trades` et `POST /battles`. Il
est dans la même transaction que l'action : si l'action échoue, rien n'est mémorisé.

Limite : deux requêtes identiques **exactement simultanées** pourraient toutes deux ne rien trouver puis s'exécuter
(DEBT-4). Le cas réel visé, une nouvelle tentative après une perte de réponse, est séquentiel.

## 9.5 Déplacer ou échanger de place

Une seule règle pour tous les cas (PC → PC, PC → équipe, équipe → PC, équipe → équipe) : destination vide =
déplacement ; destination occupée = échange.

```java
private void moveToTeamSlot(UUID ownerUuid, Pokemon pokemon, short teamSlot) {
    Optional<Pokemon> occupant = pokemonRepository.findByOwnerUuidAndTeamSlot(ownerUuid, teamSlot)
            .filter(candidate -> !candidate.getUuid().equals(pokemon.getUuid()));   // soi-même n'est pas un occupant
    swapOrMove(pokemon, occupant, () -> {
        pokemon.setTeamSlot(teamSlot);       // nouvelle place : équipe…
        pokemon.setBoxId(null);              // …donc plus de place dans le PC
        pokemon.setBoxSlot(null);
    });
}

private void swapOrMove(Pokemon pokemon, Optional<Pokemon> occupant, Runnable applyNewLocation) {
    occupant.ifPresentOrElse(other -> {
        Short oldTeamSlot = pokemon.getTeamSlot(); Short oldBoxId = pokemon.getBoxId(); Short oldBoxSlot = pokemon.getBoxSlot();
        pokemon.setTeamSlot(null); pokemon.setBoxId(null); pokemon.setBoxSlot(null);
        pokemonRepository.saveAndFlush(pokemon);             // 1. libérer l'ancienne place EN BASE
        other.setTeamSlot(oldTeamSlot); other.setBoxId(oldBoxId); other.setBoxSlot(oldBoxSlot);
        pokemonRepository.saveAndFlush(other);               // 2. l'occupant prend l'ancienne place
        applyNewLocation.run();                              // 3. le Pokémon prend la nouvelle (écrite au commit)
    }, applyNewLocation);                                    // pas d'occupant : simple déplacement
}
```

```mermaid
sequenceDiagram
    participant S as Service
    participant DB as Base (index unique par place)
    Note over S: A en équipe 1, B en équipe 2 ; on déplace A vers 2
    S->>DB: A.place = vide (flush)
    S->>DB: B.place = équipe 1 (flush) — libre, aucun conflit
    S->>DB: A.place = équipe 2 (commit) — libérée par B
```

Pourquoi cet ordre : l'index unique interdit que deux lignes aient la même place **à un instant donné**. Si on
écrivait d'abord « B → équipe 1 » alors que A y est encore en base, la contrainte échouerait. On « vide, puis on
occupe ». Ce bug a été réellement rencontré en écrivant le test (chapitre 15).

L'écran PC du client n'envoie que la destination ; c'est le serveur qui découvre l'occupant. C'est ce qui a permis
de construire le glisser-déposer sans nouvelle logique serveur.

## 9.6 Mise à jour partielle

```java
@Transactional
public PokemonResponse update(UUID ownerUuid, UUID pokemonUuid, PokemonUpdateRequest request) {
    Pokemon pokemon = findOwned(ownerUuid, pokemonUuid);
    if (request.data() != null) { legalityService.validate(request.data()); pokemon.setData(request.data()); }
    if (request.level() != null) pokemon.setLevel(request.level().shortValue());
    … // nature, ability, isShiny
    if (request.teamSlot() != null) moveToTeamSlot(…);
    else if (request.boxId() != null || request.boxSlot() != null) { … moveToPcSlot(…); }
    pokemonRepository.saveAndFlush(pokemon);
    return PokemonResponse.from(pokemon);
}
```

Convention du `PATCH` : `null` = « ne pas toucher ». Exception volontaire : `data` remplace **tout** le JSON ; le
client renvoie donc le `data` complet.

## 9.7 Supprimer, cloner, trouver une place

- `delete` refuse si `isEngagedInPendingTrade` (requête SQL native, pour que `pokemon` ne dépende pas de `trade`).
- `clone` copie tout dans une nouvelle ligne, nouvel UUID, première case libre.
- `findFreePcSlot` charge les places occupées dans un `Set` puis parcourt boîte par boîte, case par case ; s'il ne
  trouve rien : `ERROR_POKEMON_PC_FULL`.

## À retenir

- Le service concentre les règles ; le contrôleur et le dépôt restent simples.
- La base est l'arbitre final (index uniques) ; `saveAndFlush` + `catch` transforme une violation en erreur métier.
- L'idempotence est un enrobage générique qui mémorise la réponse.
- Pour échanger deux valeurs soumises à une contrainte d'unicité : libérer, écrire, puis occuper.
