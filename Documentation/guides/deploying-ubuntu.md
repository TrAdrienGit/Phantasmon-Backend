# Déployer le backend sur Ubuntu

Le backend tourne sur le serveur Ubuntu `server-linux` (nœud Tailscale `100.101.1.79`), publié en HTTPS par
**Tailscale Funnel** : **https://server-linux.tail192969.ts.net**. Mis en place le 2026-10-19.

## 1. Ce qui est installé

| Élément | Où | Notes |
|---|---|---|
| Java 21 | paquet `openjdk-21-jre-headless` | |
| PostgreSQL 18 | paquet `postgresql`, service `postgresql` | Rôle et base pris du `.env` (`BDD_USER`, `BDD_PASSWORD`, `BDD_NAME`) |
| Utilisateur système | `phantasmon` | Sans shell ; propriétaire de `/opt/phantasmon` |
| Application | `/opt/phantasmon/` | `phantasmon-backend.jar`, `.env` (600), `hub_schematics/`, `admins.txt`, `log/`, `showdown-data/` |
| Service | `phantasmon-backend` (`/etc/systemd/system/phantasmon-backend.service`) | Démarre au boot, relancé en cas d'échec ; `BDD_HOST=localhost`, `BDD_PORT=5432`, écoute sur `127.0.0.1:8080` seulement |
| Sauvegarde | timer `phantasmon-backup.timer` (4 h, rattrapé au démarrage) → `/opt/phantasmon/backup-database.sh` | `pg_dump -Fc` dans `backups/daily/` (14 jours), copie du dimanche dans `backups/weekly/` (92 jours) ; dossier à `postgres` |
| Publication | `tailscale funnel --bg 8080` | HTTPS public (WebSocket compris) vers `127.0.0.1:8080` ; aucun port ouvert sur le réseau local |

Fichiers sources : `scripts/deploy-ubuntu.sh` (lancé depuis la machine de dev), `scripts/ubuntu/setup-server.sh`
(exécuté sur le serveur, idempotent) et `scripts/ubuntu/phantasmon-backend.service`.

## 2. Déployer

Depuis Git Bash, à la racine de `Phantasmon-Backend` (SSH par Tailscale, en `root`) :

```bash
bash scripts/deploy-ubuntu.sh
```

Compile le jar, envoie jar, `.env`, `hub_schematics/` (et `admins.txt` la première fois seulement), installe ce qui
manque, redémarre le service et attend `/health`. La base du serveur n'est pas touchée.

```bash
bash scripts/deploy-ubuntu.sh --migrate-db
```

Idem, mais **remplace la base du serveur** par un dump frais de la base locale (`scripts/backup-database.ps1`) ;
demande confirmation (`--yes` pour ne pas la demander). Autre cible : variable `PHANTASMON_DEPLOY_HOST`.

Le premier déploiement a utilisé `--migrate-db` : joueurs, Pokémon, Anchors et hubs de la machine de dev ont été
repris tels quels.

## 3. Exploiter

| Besoin | Commande |
|---|---|
| Logs en direct | `ssh root@100.101.1.79 journalctl -u phantasmon-backend -f` (aussi `/opt/phantasmon/log/`) |
| État | `ssh root@100.101.1.79 systemctl status phantasmon-backend` |
| Redémarrer | `ssh root@100.101.1.79 systemctl restart phantasmon-backend` (ou `/phantasmon admin reboot`) |
| Santé publique | `curl https://server-linux.tail192969.ts.net/health` |
| Nouveau schematic de hub | le déposer dans `/opt/phantasmon/hub_schematics/hub_<nom>/` (propriétaire `phantasmon`) puis `/phantasmon admin hub reload <nom>` |
| Admins | `/opt/phantasmon/admins.txt` (relu à chaud) |
| Sauvegarde maintenant | `ssh root@100.101.1.79 systemctl start phantasmon-backup` ; prochaine : `systemctl list-timers phantasmon-backup.timer` |
| Restaurer un dump | `systemctl stop phantasmon-backend`, puis `sudo -u postgres dropdb db_phantasmon && sudo -u postgres createdb -O <BDD_USER> db_phantasmon && sudo -u postgres pg_restore --no-owner --role=<BDD_USER> -d db_phantasmon < <dump>`, puis `systemctl start phantasmon-backend` |

## 4. Clients

Chaque joueur met l'adresse publique dans `config/phantasmon.json` de son instance :

```json
{ "backend_url": "https://server-linux.tail192969.ts.net" }
```

## 5. Points d'attention

- **Funnel** a dû être autorisé une fois dans la console Tailscale (lien affiché par `tailscale funnel`). Si le
  déploiement l'affiche à nouveau, l'autorisation a été retirée.
- Les fichiers `*.sh` et `*.service` doivent garder des fins de ligne LF (`.gitattributes`) : bash refuse un script
  en CRLF (`set: pipefail: invalid option name`).
- Les sauvegardes restent **sur le même disque** que la base : une copie ailleurs (autre machine, cloud) protégerait
  aussi d'une panne du serveur.
- Deux backends en parallèle (Windows et Ubuntu) font diverger deux bases : n'en garder qu'un seul en service.
