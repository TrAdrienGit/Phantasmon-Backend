# Lancer le backend

> Guide de développement local. Déploiement sur une machine dédiée : [`deployment.md`](deployment.md).
> Toutes les variables et propriétés : [`reference/configuration.md`](../reference/configuration.md).

## 1. Prérequis

| Outil | Version | Pour quoi |
|---|---|---|
| JDK | **21** | Compiler et exécuter (le JDK 25 n'est requis que pour le mod client) |
| PostgreSQL | 18 conseillé | Base de données : installation native **ou** conteneur Docker (§5) |
| Docker | récent | Lancer la suite de tests (Testcontainers) ; facultatif pour simplement exécuter |

La base doit exister avec un rôle applicatif qui en est propriétaire (ex. rôle `phantasmon_agent`, base
`db_phantasmon`). Le schéma est créé par Flyway au premier démarrage : aucun SQL à exécuter à la main.

```sql
-- Exemple de préparation (en tant que superutilisateur PostgreSQL)
CREATE ROLE phantasmon_agent LOGIN PASSWORD '<mot de passe>';
CREATE DATABASE db_phantasmon OWNER phantasmon_agent;
```

## 2. Configurer

```bash
cp .env.template .env
# puis renseigner BDD_USER, BDD_PASSWORD et JWT_SECRET (au minimum)
```

Le `.env` est chargé automatiquement **si le backend est lancé depuis la racine du projet**. Il n'est jamais
commité (`.gitignore`).

**Construction du Global Hub** (D-34) : le dossier `hub_schematics/hub_global/` (relatif au dossier de lancement, ou
`PHANTASMON_HUB_SCHEMATICS_DIR`) doit contenir **un seul** fichier `.schem` (WorldEdit / Sponge) ou `.litematic`
(Litematica) de 21 × 21 × 21 blocs. Le dépôt fournit une arène par défaut (`phantasmon_default_hub.schem`, générée
par `scripts/generate_hub_schematics.py`) : la **remplacer** par la vraie construction, ne pas l'ajouter à côté.
Le centre de la couche du bas du schematic se place sous les pieds du joueur qui pose l'Anchor ; le +Z du schematic est
l'avant de l'Anchor. Le changement est pris en compte au redémarrage du backend ; les clients le téléchargent seuls.

## 3. Lancer en développement

```bash
./gradlew bootRun --continuous
```

- `--continuous` fait recompiler Gradle à chaque sauvegarde ; Spring Boot DevTools redémarre alors le contexte en
  quelques secondes. Sans ce drapeau, les modifications ne sont jamais prises en compte.
- Les migrations Flyway s'appliquent au démarrage (`Migrating schema "public" to version "N - …"`).
- Arrêt : `Ctrl+C`, puis `./gradlew --stop` pour arrêter le démon Gradle si besoin.

Sous PowerShell : `.\gradlew.bat bootRun --continuous`.

## 4. Lancer le jar packagé

```bash
./gradlew bootJar
java -jar build/libs/phantasmon-backend-0.1.0.jar
```

Utiliser le jar **sans** suffixe `-plain` (le `-plain.jar` ne contient pas les dépendances). Hors du répertoire du
projet, aucun `.env` n'est lu : passer de vraies variables d'environnement.

```powershell
$env:BDD_USER = "phantasmon_agent"
$env:BDD_PASSWORD = "..."
$env:JWT_SECRET = "..."
java -jar phantasmon-backend-0.1.0.jar
```

Ce mode a été vérifié sans `.env` présent, avec uniquement des variables d'environnement.

## 5. PostgreSQL en conteneur (facultatif)

`docker-compose.yml` lance PostgreSQL 18 avec les identifiants du même `.env`.

```bash
docker compose up -d      # démarre (base vide au premier lancement)
docker compose ps         # doit afficher "healthy"
docker compose down       # arrête, données conservées (volume phantasmon-pgdata)
docker compose down -v    # arrête ET supprime les données
```

- Publié uniquement sur `127.0.0.1:${BDD_DOCKER_PORT:-5433}` : pas de collision avec un PostgreSQL natif sur 5432,
  et la base n'est pas joignable depuis le réseau.
- Pour que le backend l'utilise : `BDD_PORT=5433` dans le `.env` (revenir à `5432` pour l'installation native).
- Copier les données de l'installation native vers le conteneur (facultatif, ne modifie pas la base native ;
  démarrer le conteneur **avant** le backend) :

```bash
# PGPASSWORD = mot de passe de BDD_USER ; <user>/<db> = BDD_USER / BDD_NAME
pg_dump -h localhost -p 5432 -U <user> -Fc <db> > phantasmon.dump
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --clean --if-exists' < phantasmon.dump
```

Ne jamais commiter un fichier `.dump` : il contient les données réelles des joueurs.

Sauvegardes régulières (base native ou conteneur, détection automatique) : `scripts/backup-database.ps1`, voir
[`deployment.md`](deployment.md) §3.

## 6. Vérifier

```bash
curl http://localhost:8080/health
# {"status":"UP","database":"UP"}
curl http://localhost:8080/version
# {"current_version":"0.1.0","min_supported_version":"0.1.0"}
```

Pour un essai complet, utiliser le mod client (la connexion exige un vrai compte Minecraft).

## 7. Logs

Chaque démarrage crée `log/Log-Phantasmon-Backend_<AAAA-MM-JJ>_<HH-MM-SS>.txt` (même contenu que la console).
Le dossier est plafonné à 5 Gio (les plus anciens fichiers sont supprimés). `LOGGING_ENABLED=false` désactive le
fichier, pas la console.

## 8. Dépannage

| Symptôme | Cause probable |
|---|---|
| `Could not resolve placeholder 'phantasmon.jwt.secret'` | `JWT_SECRET` absent de l'environnement / du `.env` |
| `Could not resolve placeholder 'BDD_USER'` (ou `BDD_PASSWORD`) | Variable obligatoire absente |
| Échec de connexion PostgreSQL au démarrage | `BDD_HOST` / `BDD_PORT` incorrects, service arrêté, pare-feu |
| `FATAL: authentification par mot de passe échouée` | Identifiants incorrects, ou rôle / base inexistants |
| `Port 8080 was already in use` | Une autre instance tourne déjà ; l'arrêter ou lancer avec `--server.port=8081` |
| `/health` répond 503 | Le backend tourne mais ne joint pas PostgreSQL |
| Modifications non prises en compte en `bootRun` | Lancé sans `--continuous` |
| Aucune migration appliquée, aucun message Flyway | Dépendance `spring-boot-flyway` retirée de `build.gradle` (module séparé en Spring Boot 4) |
| `Global Hub schematic folder … must hold exactly one .schem or .litematic file` | `hub_schematics/hub_global/` vide ou avec plusieurs schematics : n'en garder qu'un |
| `Global Hub schematic … is AxBxC blocks; a Hub anchor is 21x21x21` | Le schematic n'a pas la taille d'un Anchor (`phantasmon.hub.anchor-size`) |
| `Global Hub schematic folder missing or unreadable` | Backend lancé hors de la racine du projet, ou `PHANTASMON_HUB_SCHEMATICS_DIR` erroné |
| Aucun fichier dans `log/` | `LOGGING_ENABLED=false`, ou dossier sans droits d'écriture |
| Le client reçoit `ERROR_TRADE_PARTNER_UNAVAILABLE` alors que l'autre joueur est en jeu | L'autre client n'a pas de session WebSocket : non connecté, ou pointe vers un autre backend |
