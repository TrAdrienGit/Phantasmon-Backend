# 7. La base de données

## 7.1 PostgreSQL en deux minutes

Une base relationnelle range les données en **tables** (lignes × colonnes typées). On l'interroge en **SQL**.
Notions utilisées dans le projet :

| Notion | Exemple réel |
|---|---|
| Clé primaire (`PRIMARY KEY`) | `pokemon.uuid` : identifie une ligne de façon unique |
| Clé étrangère (`REFERENCES`) | `pokemon.owner_uuid REFERENCES players(uuid)` : un Pokémon doit appartenir à un joueur existant |
| `NOT NULL`, `CHECK` | `level SMALLINT NOT NULL CHECK (level BETWEEN 1 AND 100)` |
| Index | `idx_pokemon_owner` : retrouver vite les Pokémon d'un joueur |
| Index unique partiel | `uq_pokemon_team_slot ON pokemon(owner_uuid, team_slot) WHERE team_slot IS NOT NULL` : deux Pokémon d'un même joueur ne peuvent pas occuper le même emplacement d'équipe |
| `JSONB` | `pokemon.data` : un document JSON stocké dans une colonne |

Les contraintes en base sont un **filet de sécurité** : même si le code Java a un bug, la base refuse un état
incohérent.

### Le modèle hybride colonnes + JSONB

Ce qu'on filtre, indexe ou contraint va en **colonnes** (`owner_uuid`, `species`, `team_slot`…). Le reste, variable
et jamais filtré, va dans `data` (JSONB) : IV, EV, attaques, objet, surnom… On évite ainsi une table par détail
tout en gardant des requêtes rapides sur l'essentiel.

## 7.2 Flyway : versionner le schéma

Le schéma de la base évolue avec le code. **Flyway** applique des scripts SQL numérotés, une seule fois chacun, et
note dans une table (`flyway_schema_history`) lesquels ont été appliqués.

```text
src/main/resources/db/migration/
├── V1__init_players.sql
├── V2__init_pokemon.sql
├── …
├── V6__resize_pokemon_box.sql        ← modifie une contrainte de V2
├── V7__trades_pokemon_history_without_fk.sql
└── V8__battle_sessions_host.sql
```

Règles d'or :

1. **Ne jamais modifier une migration déjà appliquée.** Flyway calcule une somme de contrôle de chaque fichier ;
   si elle change, le démarrage échoue. On corrige toujours par une nouvelle migration (`V6` a réduit les boîtes
   de 36 à 30 cases sans toucher `V2`).
2. Jamais `ddl-auto: update` (Hibernate qui modifierait le schéma tout seul) : `spring.jpa.hibernate.ddl-auto=none`.
3. Une contrainte créée sans nom reçoit un nom automatique ; pour la retrouver, interroger le catalogue plutôt que
   de deviner :

```sql
-- V6__resize_pokemon_box.sql (extrait)
DO $$
DECLARE existing_constraint text;
BEGIN
    SELECT conname INTO existing_constraint FROM pg_constraint
    WHERE conrelid = 'pokemon'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%box_slot%';
    IF existing_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE pokemon DROP CONSTRAINT %I', existing_constraint);
    END IF;
END $$;
ALTER TABLE pokemon ADD CONSTRAINT chk_pokemon_box_slot CHECK (box_slot BETWEEN 1 AND 30);
```

## 7.3 JPA et Hibernate : des lignes devenues objets

**JPA** est la norme Java pour manipuler une base avec des objets ; **Hibernate** est son implémentation. Une classe
annotée `@Entity` correspond à une table :

```java
// pokemon/Pokemon.java (raccourci)
@Entity
@Table(name = "pokemon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)    // JPA exige un constructeur sans argument
public class Pokemon {
    @Id
    private UUID uuid;

    @Column(name = "owner_uuid", nullable = false) @Setter
    private UUID ownerUuid;

    @Column(name = "team_slot") @Setter
    private Short teamSlot;                           // Short (et non short) : peut être NULL

    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) @Setter
    private Map<String, Object> data;                 // colonne JSONB ↔ Map Java

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }   // avant le premier INSERT
    @PreUpdate  void onUpdate() { updatedAt = Instant.now(); }               // avant chaque UPDATE
}
```

L'entité **n'est jamais renvoyée telle quelle** au client : on la convertit en record (`PokemonResponse.from(...)`).
Le format JSON public reste ainsi indépendant de la structure interne.

## 7.4 Les dépôts : des requêtes sans SQL

```java
// pokemon/PokemonRepository.java
public interface PokemonRepository extends JpaRepository<Pokemon, UUID> {
    List<Pokemon> findByOwnerUuid(UUID ownerUuid);
    Optional<Pokemon> findByOwnerUuidAndTeamSlot(UUID ownerUuid, Short teamSlot);
    List<Pokemon> findByOwnerUuidAndTeamSlotIsNotNullOrderByTeamSlot(UUID ownerUuid);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM trades WHERE status = 'PENDING' "
            + "AND (offered_pokemon = :pokemonUuid OR requested_pokemon = :pokemonUuid))", nativeQuery = true)
    boolean isEngagedInPendingTrade(@Param("pokemonUuid") UUID pokemonUuid);
}
```

On écrit **seulement l'interface**. Spring Data génère l'implémentation :

- `JpaRepository` fournit `findById`, `save`, `saveAndFlush`, `delete`, `findAll`… ;
- les **requêtes dérivées** sont déduites du nom de la méthode : `findByOwnerUuidAndTeamSlot` → `WHERE owner_uuid
  = ? AND team_slot = ?` ;
- `@Query` permet d'écrire le SQL soi-même quand le nom deviendrait illisible.

## 7.5 Les transactions

Une **transaction** regroupe plusieurs écritures : soit **toutes** sont validées (commit), soit **aucune**
(rollback). Indispensable pour un échange : on ne veut jamais qu'un seul des deux Pokémon change de propriétaire.

```java
@Transactional                    // tout le corps de la méthode est une transaction
public PokemonResponse create(UUID ownerUuid, PokemonCreateRequest request) { … }

@Transactional(readOnly = true)   // lecture seule : optimisations, aucune écriture possible
public List<PokemonResponse> listForOwner(UUID ownerUuid) { … }
```

Comportements à connaître :

| Comportement | Conséquence |
|---|---|
| Une exception non vérifiée qui sort de la méthode → **rollback** | Lever `ApiException` annule tout ce qui a été écrit |
| `@Transactional(noRollbackFor = ApiException.class)` | Valide quand même : utilisé pour enregistrer le statut `CANCELLED` d'un échange avant de lever l'erreur (et cause suspectée de BUG-5, chapitre 15) |
| Une méthode `@Transactional` appelée depuis une autre **rejoint** la transaction en cours | `TradeService.accept` → `PokemonService.transferOwnership` : un seul commit pour tout |
| Les entités chargées sont **suivies** : modifier un champ suffit, l'UPDATE est fait au commit | Dans `update`, `pokemon.setLevel(...)` est écrit en base sans appel explicite |

### `save` contre `saveAndFlush`

Hibernate accumule les écritures et les envoie à la base au moment le plus tardif (le commit). `saveAndFlush`
force l'envoi **immédiat** de l'ordre SQL (sans valider la transaction). C'est indispensable quand l'ordre des
écritures compte vis-à-vis d'une contrainte. Exemple réel : échanger deux Pokémon de place sans violer l'index
unique de place (chapitre 9.5).

## 7.6 Lancer PostgreSQL

En développement, soit une installation native, soit un conteneur Docker décrit dans `docker-compose.yml`
(chapitre 13). En test, chaque exécution démarre sa propre base jetable (Testcontainers, chapitre 14).

## À retenir

- Colonnes pour ce qu'on filtre et contraint, JSONB pour le reste ; les contraintes SQL sont un filet de sécurité.
- Flyway versionne le schéma ; une migration appliquée ne se modifie jamais.
- Une entité JPA = une ligne ; un dépôt = une interface dont Spring génère les requêtes.
- `@Transactional` : tout ou rien ; une exception non vérifiée annule ; `saveAndFlush` contrôle l'ordre des écritures.
