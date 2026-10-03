# 4. Gradle : construire le projet

## 4.1 À quoi sert un outil de build

Un projet Java dépend de dizaines de bibliothèques (Spring, Hibernate, le pilote PostgreSQL…) qui dépendent
elles-mêmes d'autres bibliothèques. Les télécharger et les assembler à la main serait impossible. **Gradle** :

- télécharge les dépendances déclarées (depuis **Maven Central**, le grand dépôt public Java) ;
- compile le code ;
- lance les tests ;
- produit un fichier `.jar` (une archive exécutable du programme) ;
- lance l'application en développement.

## 4.2 Le wrapper

On n'installe pas Gradle : le dépôt contient `gradlew` (Linux/macOS/Git Bash) et `gradlew.bat` (Windows), plus
`gradle/wrapper/`. Au premier lancement, le wrapper télécharge **la version exacte** de Gradle prévue pour le
projet. Tout le monde, CI comprise, utilise donc la même version.

## 4.3 Lire `build.gradle`

```groovy
plugins {
    id 'java'                                              // compiler du Java
    id 'org.springframework.boot' version '4.1.1'          // tâches bootRun, bootJar
    id 'io.spring.dependency-management' version '1.1.7'   // versions cohérentes des dépendances Spring
}

group = 'com.mystaria'
version = '0.1.0'                                 // apparaît dans le nom du jar

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }   // compiler pour Java 21
}

repositories { mavenCentral() }                            // où télécharger

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'     // serveur HTTP + REST
    implementation 'org.springframework.boot:spring-boot-starter-websocket'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'   // base de données en objets
    implementation 'org.springframework.boot:spring-boot-starter-validation' // @NotNull, @Min…
    implementation 'org.springframework.boot:spring-boot-flyway'             // migrations (voir piège 4.5)
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    implementation 'org.springframework.boot:spring-boot-restclient'         // appeler Mojang
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'                         // JWT
    runtimeOnly    'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly    'io.jsonwebtoken:jjwt-jackson:0.12.6'
    compileOnly    'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    runtimeOnly    'org.postgresql:postgresql'                                // pilote JDBC
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:testcontainers-postgresql'
    …
}

tasks.named('test') { useJUnitPlatform() }                // tests avec JUnit 5
```

Une dépendance s'écrit `groupe:nom:version`. Les « starters » Spring regroupent plusieurs bibliothèques cohérentes ;
le plugin de gestion des dépendances fournit leurs versions (d'où l'absence de numéro).

### Les portées (configurations)

| Portée | Présente à la compilation | Dans le jar final | Exemple |
|---|---|---|---|
| `implementation` | oui | oui | Spring |
| `runtimeOnly` | non | oui | Pilote PostgreSQL (on n'écrit jamais de code contre lui directement) |
| `compileOnly` | oui | non | Lombok (il génère du code puis disparaît) |
| `annotationProcessor` | outil exécuté pendant la compilation | non | Lombok |
| `developmentOnly` | `bootRun` seulement | non | DevTools (redémarrage à chaud) |
| `testImplementation` | tests seulement | non | Testcontainers |

## 4.4 Les tâches

```bash
./gradlew build            # compile, teste, assemble
./gradlew build -x test    # idem sans les tests
./gradlew test             # les tests seulement
./gradlew bootRun          # lance l'application (développement)
./gradlew bootRun --continuous   # recompile à chaque sauvegarde (avec DevTools : redémarrage automatique)
./gradlew bootJar          # build/libs/phantasmon-backend-0.1.0.jar, exécutable avec java -jar
```

Le dossier `build/` contient tout ce que Gradle produit (classes, jar, rapports de test) ; il n'est jamais commité.

## 4.5 Piège réel : la fonctionnalité qui ne fait rien

Avec Spring Boot 4, certaines intégrations automatiques ont été déplacées dans des modules séparés. Avec seulement
`flyway-core`, **Flyway ne s'exécutait jamais**, sans erreur ni message : le module `spring-boot-flyway` manquait.
Même chose pour `spring-boot-restclient` (pas de `RestClient.Builder` injectable) et `spring-boot-webmvc-test`
(pour les tests MockMvc). Leçon : si une fonctionnalité Spring semble ignorée en silence, vérifier qu'il ne manque pas
un module dans `build.gradle`.

## À retenir

- Gradle télécharge, compile, teste et emballe ; le wrapper fixe sa version.
- `build.gradle` déclare des plugins, des dépôts et des dépendances avec une portée.
- `bootRun` pour développer, `bootJar` pour livrer.
- Une intégration Spring silencieusement inactive = souvent un module manquant.
