# Déployer le backend

> L'hébergement définitif n'est pas encore choisi (CAD Partie 3 §I, Phase 10). Ce guide décrit la procédure
> générique et l'infrastructure de test actuelle. Briefing destiné à un agent IA travaillant sur la machine
> serveur : [`agents/server-machine-briefing.md`](../agents/server-machine-briefing.md).

## 1. Infrastructure actuelle (2026-10-03)

| Machine | Adresse Tailscale | Rôle actuel |
|---|---|---|
| Machine de développement (Windows) | `100.116.43.32` | Fait tourner **le** backend utilisé par les tests (port 8080, depuis les dépôts sur D:, **disque externe USB** qui s'est déconnecté le 2026-10-04 : voir TODO-17) et PostgreSQL natif (limité à `localhost` depuis le 2026-10-03) ; client Minecraft `MystAria_` |
| « production-server » (ancien portable, Windows 10, i7-10750H, 16 Go) | `100.106.248.73` | Second client de test (`TheMashen`). Possède les dépôts et un backend installé comme service NSSM, **volontairement non utilisé** : les deux clients doivent partager un seul backend. Joignable en SSH par l'alias `production-server` (actuellement injoignable). |

Le client vise `backend_url` de `config/phantasmon.json` (défaut `http://100.116.43.32:8080`). Changer de backend :
modifier ce fichier dans chaque instance, puis relancer le jeu ; aucune recompilation.

## 2. Procédure de déploiement

### 2.1 Installer

- JDK **21**.
- PostgreSQL : rôle applicatif dédié et base dédiée (voir [`running.md`](running.md) §1), **écoutant uniquement sur
  `localhost`** (`listen_addresses = 'localhost'` dans `postgresql.conf`).
- Récupérer le dépôt (`git pull` est autorisé ; ne jamais commiter ni pousser depuis la machine serveur).

### 2.2 Construire et lancer

```bash
./gradlew bootJar
java -jar build/libs/phantasmon-backend-0.1.0.jar
```

- Jamais `bootRun` en production (mode développement).
- Variables d'environnement **système** (pas de `.env`) : `BDD_*`, `JWT_SECRET` (valeur aléatoire forte, propre à
  cette machine), `LOGGING_ENABLED`.
- Les migrations Flyway s'appliquent automatiquement au démarrage.

### 2.3 Démarrage automatique (Windows)

Installer le jar comme service, par exemple avec NSSM :

```powershell
nssm install PhantasmonBackend "C:\Program Files\Java\jdk-21\bin\java.exe" "-jar C:\phantasmon\phantasmon-backend.jar"
nssm set PhantasmonBackend AppDirectory C:\phantasmon
nssm set PhantasmonBackend AppEnvironmentExtra BDD_USER=... BDD_PASSWORD=... JWT_SECRET=...
nssm start PhantasmonBackend
```

`AppDirectory` détermine où est créé le dossier `log/`. Après chaque nouvelle version : arrêter le service,
remplacer le jar, redémarrer.

### 2.4 Réseau

| Port | Exposition |
|---|---|
| 8080 (backend) | Accessible aux clients (Tailscale, ou redirection de port + DNS dynamique pour un accès public) |
| 5432 (PostgreSQL) | **Jamais** exposé : `localhost` uniquement |
| 25565 (Minecraft) | Seulement si la machine héberge aussi un serveur de jeu |

Le pare-feu Windows n'ouvre que les ports strictement nécessaires. Pour un accès public, prévoir HTTPS/WSS (proxy
inverse) : le trafic actuel est en HTTP/WS clair.

### 2.5 Vérifier

```bash
curl http://<hôte>:8080/health
```

## 3. Sauvegardes

Le backend est l'unique source de vérité : une perte de base est une perte définitive pour tous les joueurs.
Recommandation du CAD (Partie 3 §I) et état :

| Élément | Cible | État |
|---|---|---|
| Sauvegarde complète (`pg_dump -Fc`) | Quotidienne, conservée 14 jours | Script prêt (`scripts/backup-database.ps1`), à planifier (§3.2) |
| Sauvegarde hebdomadaire | Conservée 3 mois | Même script : le dump du dimanche est copié dans `weekly\`, conservé 92 jours |
| Archivage WAL (restauration à un instant donné) | Continu | Non fait (LIM-8) |
| Test de restauration | Périodique, au moins une fois avant la publication | `scripts/test-restore.ps1`, réussi le 2026-10-03 (base native et base Docker) |

### 3.1 Sauvegarder

```powershell
powershell -ExecutionPolicy Bypass -File scripts\backup-database.ps1
```

- **Source détectée automatiquement** : si le conteneur `phantasmon-postgres` (`docker-compose.yml`) tourne,
  `pg_dump` s'exécute **dans** le conteneur (aucun outil PostgreSQL requis sur la machine) ; sinon le
  `pg_dump.exe` local (PATH, ou `C:\Program Files\PostgreSQL\<version>\bin`) vise `BDD_HOST:BDD_PORT`.
  Forcer avec `-Source docker` ou `-Source native`.
- **Identifiants** : variables d'environnement, sinon `.env` (`BDD_USER`, `BDD_PASSWORD`, `BDD_NAME`, `BDD_HOST`,
  `BDD_PORT`). Le mode Docker n'en a pas besoin (connexion locale au conteneur). Rien de secret n'est affiché.
- **Destination** : `backups\daily\phantasmon_<AAAA-MM-JJ_HH-MM-SS>.dump` (`-BackupDir` pour changer ; en
  production, préférer un autre disque ou un dossier synchronisé ailleurs). Écrit d'abord en `.partial`, renommé
  seulement si `pg_dump` réussit. Code de sortie 1 en cas d'échec.
- `backups/` et `*.dump` sont dans `.gitignore` : **ne jamais commiter un dump**, il contient les données réelles des
  joueurs.

### 3.2 Planifier (tâche Windows, à faire sur la machine qui héberge la base)

```powershell
$script = 'D:\Main_user_data\Documents\GitHub\Phantasmon-Backend\scripts\backup-database.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`""
$trigger = New-ScheduledTaskTrigger -Daily -At 4am
Register-ScheduledTask -TaskName 'Phantasmon - sauvegarde base' -Action $action -Trigger $trigger
```

La tâche tourne sous le compte qui l'enregistre, quand il est connecté (Docker Desktop ne tourne de toute façon
que dans une session ouverte). Vérifier dans le Planificateur de tâches que « Dernier résultat » vaut `0x0`.

### 3.3 Tester une restauration

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-restore.ps1            # dernier dump de backups\daily
powershell -ExecutionPolicy Bypass -File scripts\test-restore.ps1 -DumpFile <chemin>
```

Restaure le dump dans un conteneur `postgres:18` **jetable** (aucun port publié, supprimé à la fin) : la vraie base
n'est jamais touchée. Vérifie l'historique Flyway (aucune migration en échec) et la présence des tables `players`,
`pokemon`, `trades`, `battle_sessions`, et affiche leur nombre de lignes. Nécessite Docker. À refaire au moins
avant chaque mise en service et après une montée de version de PostgreSQL.

### 3.4 Restaurer pour de vrai (perte de données)

**Remplace tout le contenu de la base.** Arrêter le backend d'abord, puis :

```powershell
# Base native (PGPASSWORD = mot de passe de BDD_USER)
& 'C:\Program Files\PostgreSQL\18\bin\pg_restore.exe' -h localhost -p 5432 -U <user> -d <db> --clean --if-exists --no-owner <dump>

# Base Docker
docker cp <dump> phantasmon-postgres:/tmp/restore.dump
docker exec phantasmon-postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner /tmp/restore.dump'
```

Redémarrer le backend : Flyway constate que la base est déjà à jour. Les présences, échanges en direct et combats
en cours ne sont pas en base (LIM-5) : rien à restaurer pour eux.

## 4. Liste de contrôle avant une mise en service réelle

- [ ] Points de priorité haute de [`project/known-issues.md`](../project/known-issues.md) traités.
- [ ] `JWT_SECRET` fort et unique, jamais réutilisé depuis le développement.
- [ ] PostgreSQL limité à `localhost`.
- [ ] Sauvegarde planifiée (§3.2) sur la machine qui héberge la base, vers un autre disque ; restauration testée (§3.3).
- [ ] HTTPS/WSS devant le backend si exposé publiquement.
- [ ] `phantasmon.version.current` / `min-supported` alignés sur la version publiée du client.
- [ ] Commande `/phantasmon debug fingerprint` retirée du client.
