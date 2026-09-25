# Phantasmon Backend — Lancer le backend en console

Ce document explique comment démarrer le backend Phantasmon localement, en développement comme en paquet
autonome (jar). Il ne couvre pas l'hébergement final (choix d'infrastructure non tranché — voir
`CAD_Ghost_Pokemon_Partie_3_Complements.md` §I/§J).

---

## 1. Prérequis

- **Java 21** (JDK) — c'est la seule version supportée pour compiler/exécuter ce projet (contrairement au
  mod client qui a besoin d'un JDK 25 pour son outillage de build, voir la documentation du repo Client).
- **PostgreSQL** joignable (instance native, service Windows, conteneur Docker manuel, etc.) avec :
  - un rôle applicatif (ex. `phantasmon_agent`) ;
  - une base créée (ex. `db_phantasmon`) appartenant à ce rôle.
  - Les migrations Flyway (`src/main/resources/db/migration/`) créent le schéma automatiquement au
    premier démarrage — il n'y a rien à exécuter manuellement en SQL.
- **Docker** n'est nécessaire **que** pour la suite de tests (`./gradlew test`, via Testcontainers). Il
  n'est **pas** requis pour simplement lancer l'application.

---

## 2. Configuration — variables d'environnement

| Variable | Rôle | Exemple |
|---|---|---|
| `BDD_HOST` | Hôte PostgreSQL | `localhost` |
| `BDD_PORT` | Port PostgreSQL | `5432` |
| `BDD_NAME` | Nom de la base | `db_phantasmon` |
| `BDD_USER` | Rôle PostgreSQL applicatif | `phantasmon_agent` |
| `BDD_PASSWORD` | Mot de passe de ce rôle | *(secret)* |
| `JWT_SECRET` | Clé de signature HMAC-SHA256 des JWT | *(secret, ex. sortie de `openssl rand -base64 64`)* |
| `LOGGING_ENABLED` | Active/désactive le logging fichier (voir §9) | `true` (défaut) |

`JWT_SECRET` est **obligatoire** : sans elle, le démarrage échoue immédiatement avec une erreur du type
`Could not resolve placeholder 'phantasmon.jwt.secret'`. `LOGGING_ENABLED` est optionnelle (défaut `true`).

### 2.1 En développement local — fichier `.env`

Un fichier `.env` à la racine du projet (copié depuis `.env.template`, jamais commité — voir
`.gitignore`) est chargé automatiquement par `DotenvEnvironmentPostProcessor` **tant que l'application est
lancée depuis ce répertoire** (`./gradlew bootRun`, ou `java -jar` exécuté depuis la racine du projet).

```bash
cp .env.template .env
# puis éditer .env avec de vraies valeurs
```

### 2.2 Hors du répertoire du projet / déploiement réel

Le fichier `.env` n'est qu'une commodité de développement. Dans tout autre contexte (jar copié ailleurs,
conteneur, serveur distant), il faut positionner de vraies variables d'environnement système — le
`.env` n'est alors pas nécessaire.

---

## 3. Lancer en mode développement

```bash
./gradlew bootRun
```

Recharge le code à chaud (Spring Boot DevTools est présent). Les migrations Flyway s'appliquent au
démarrage — les logs affichent `Migrating schema "public" to version "N - ..."` pour chacune.

---

## 4. Lancer un jar packagé

```bash
./gradlew bootJar
java -jar build/libs/phantasmon-backend-<version>.jar
```

⚠️ Le répertoire `build/libs/` contient **deux** jars : utilisez celui **sans** le suffixe `-plain`
(ex. `phantasmon-backend-0.0.1-SNAPSHOT.jar`). Le fichier `-plain.jar` ne contient que les classes du
projet, sans ses dépendances — il n'est pas exécutable seul.

Exemple avec variables d'environnement passées explicitement (PowerShell) :

```powershell
$env:BDD_HOST = "localhost"
$env:BDD_PORT = "5432"
$env:BDD_NAME = "db_phantasmon"
$env:BDD_USER = "phantasmon_agent"
$env:BDD_PASSWORD = "..."
$env:JWT_SECRET = "..."
java -jar phantasmon-backend-0.0.1-SNAPSHOT.jar
```

Ce mode a été vérifié : le jar packagé démarre correctement, se connecte à PostgreSQL et répond sur
`/health`, **sans** fichier `.env` présent, en s'appuyant uniquement sur ces variables d'environnement.

---

## 5. Vérifier que le backend tourne

```bash
curl http://localhost:8080/health
```

Réponse attendue :

```json
{ "status": "UP", "database": "UP" }
```

Voir `Documentation/PHANTASMON_API_REFERENCE.md` pour le détail de tous les endpoints disponibles.

---

## 6. Arrêter le backend

- `bootRun` : `Ctrl+C` dans le terminal, puis éventuellement `./gradlew --stop` pour arrêter le daemon
  Gradle sous-jacent.
- Jar packagé : `Ctrl+C`, ou terminer le processus Java (`taskkill /IM java.exe /F` sous Windows si
  nécessaire, en ciblant le bon PID sur une machine où plusieurs JVM tournent).

---

## 7. Dépannage courant

| Symptôme | Cause probable |
|---|---|
| `Could not resolve placeholder 'phantasmon.jwt.secret'` | `JWT_SECRET` non défini dans l'environnement |
| Échec de connexion PostgreSQL au démarrage | `BDD_HOST`/`BDD_PORT` incorrects, service PostgreSQL arrêté, pare-feu |
| `FATAL: authentification par mot de passe échouée` | `BDD_USER`/`BDD_PASSWORD` incorrects, ou rôle/BDD inexistants côté PostgreSQL |
| Port 8080 déjà utilisé | Une autre instance tourne déjà ; changer via `server.port` (propriété Spring) |
| `/health` répond `503` | Le backend tourne mais ne peut pas joindre PostgreSQL (vérifier les identifiants et la disponibilité du serveur) |
| Aucun fichier dans `log/` | `LOGGING_ENABLED=false` (comportement voulu), ou dossier `log/` absent/sans droits d'écriture |

---

## 8. Sécurité

`JWT_SECRET` et `BDD_PASSWORD` sont de vrais secrets : ne jamais les committer, ne jamais les afficher
dans des logs partagés. Voir `.gitignore` (le `.env` y est explicitement exclu, `.env.template` reste
suivi par git).

---

## 9. Logs fichier

Chaque session du backend (chaque démarrage) crée **un seul fichier** dans `log/` à la racine du projet,
nommé :

```
Log-Phantasmon-Backend_<AAAA-MM-JJ>_<HH-MM-SS>.txt
```

Un redémarrage crée un nouveau fichier — les anciens ne sont jamais réutilisés ni écrasés. Le fichier
contient tout ce que la console affiche (démarrage, migrations Flyway, chaque appel REST via
`RequestLoggingFilter` avec méthode/chemin/statut/durée, événements métier importants comme
l'authentification ou la création de joueur) — le même niveau `INFO` que la console.

### 9.1 Activer/désactiver

Contrôlé par `LOGGING_ENABLED` dans `.env` (ou la variable d'environnement système équivalente) :

```
LOGGING_ENABLED=true   # comportement par défaut si absent
LOGGING_ENABLED=false  # aucun fichier créé ; la console continue de logger normalement
```

### 9.2 Rétention — plafond de 5 Go

Le dossier `log/` est plafonné à **5 Go au total**. Une vérification a lieu au démarrage puis toutes les
heures (`LogRetentionService`) : si le dossier dépasse le plafond, les fichiers de session les **plus
anciens** sont supprimés en premier jusqu'à repasser sous la limite. Seuls les fichiers nommés
`Log-Phantasmon-Backend_*.txt` sont concernés — tout autre fichier présent dans `log/` est ignoré par
sécurité.

### 9.3 Détails techniques

Contrairement à une configuration Logback classique par XML, ce projet pilote le nom de fichier et le
toggle via un `EnvironmentPostProcessor` Java (`SessionLogFileEnvironmentPostProcessor`) qui positionne la
propriété standard `logging.file.name` de Spring Boot avant que la journalisation ne démarre — c'est ce
mécanisme natif de Spring Boot qui décide d'attacher ou non un appender fichier. Un essai avec un
`logback-spring.xml` personnalisé (`<if>` conditionnel) s'est révélé peu fiable à cause du double passage
d'initialisation de Logback avec Spring (`<springProperty>` non résolu assez tôt) — voir la mémoire du
projet pour le détail si cette zone doit être retouchée un jour.
