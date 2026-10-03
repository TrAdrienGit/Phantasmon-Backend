# Déployer le backend

> L'hébergement définitif n'est pas encore choisi (CAD Partie 3 §I, Phase 10). Ce guide décrit la procédure
> générique et l'infrastructure de test actuelle. Briefing destiné à un agent IA travaillant sur la machine
> serveur : [`agents/server-machine-briefing.md`](../agents/server-machine-briefing.md).

## 1. Infrastructure actuelle (2026-10-03)

| Machine | Adresse Tailscale | Rôle actuel |
|---|---|---|
| Machine de développement (Windows) | `100.116.43.32` | Fait tourner **le** backend utilisé par les tests (port 8080) et PostgreSQL natif ; client Minecraft `MystAria_` |
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

## 3. Sauvegardes (à mettre en place)

Le backend est l'unique source de vérité : une perte de base est une perte définitive pour tous les joueurs.
Recommandation du CAD (Partie 3 §I) :

| Élément | Cible |
|---|---|
| Sauvegarde complète (`pg_dump -Fc`) | Quotidienne, conservée 14 jours |
| Sauvegarde hebdomadaire | Conservée 3 mois |
| Archivage WAL (restauration à un instant donné) | Continu |
| Test de restauration | Périodique, au moins une fois avant la publication (critère de fin de la Phase 10) |

Aucune de ces sauvegardes n'est automatisée à ce jour.

## 4. Liste de contrôle avant une mise en service réelle

- [ ] Points de priorité haute de [`project/known-issues.md`](../project/known-issues.md) traités.
- [ ] `JWT_SECRET` fort et unique, jamais réutilisé depuis le développement.
- [ ] PostgreSQL limité à `localhost`.
- [ ] Sauvegardes automatisées et restauration testée.
- [ ] HTTPS/WSS devant le backend si exposé publiquement.
- [ ] URL du backend configurable dans le client (aujourd'hui en dur).
- [ ] `phantasmon.version.current` / `min-supported` alignés sur la version publiée du client.
- [ ] Commande `/phantasmon debug fingerprint` retirée du client.
