# Briefing — agent IA sur la machine serveur

> Destiné à un agent IA (Claude Code ou équivalent) qui démarre sans mémoire sur la machine
> « production-server ». Ce texte doit suffire pour comprendre le projet et sa mission sur cette machine.
> Remplace l'ancien `SERVER_AGENT_BRIEFING.md`. Mis à jour le 2026-10-03.

## 1. Le projet en bref

Phantasmon compte deux dépôts GitHub d'Adrien :

- **`Phantasmon-Client`** : mod Fabric **client uniquement** (Minecraft 1.21.1 + Cobblemon 1.8.1, Java). Aucun code
  Phantasmon ne tourne sur un serveur Minecraft ; un joueur sans le mod ne voit rien.
- **`Phantasmon-Backend`** : service Spring Boot 4.1.1 (Java 21, PostgreSQL), sans aucune dépendance Minecraft.
  Seule source de vérité (propriété, légalité, échanges, résultats de combat).

Les joueurs créent, éditent, font sortir, échangent et font combattre des « Ghost Pokémon ». Les clients ne se
parlent jamais directement : tout passe par le backend (REST + WebSocket). Les combats sont exécutés par le moteur
de Cobblemon sur le client de l'un des deux joueurs (l'hôte) ; le backend relaie et applique des garde-fous.

Documentation : `Phantasmon-Backend/Documentation/README.md` et `Phantasmon-Client/Documentation/README.md`.

## 2. Règles non négociables

1. **Ne jamais commiter ni pousser** sans demande explicite d'Adrien. Un `git pull` pour déployer est normal.
2. **Ne jamais afficher ni commiter le `.env`** ou une variable secrète (`JWT_SECRET`, `BDD_PASSWORD`).
3. Le backend s'exécute avec **Java 21** (le JDK 25 ne sert qu'à compiler le mod client).
4. Schéma : migrations Flyway uniquement, jamais `ddl-auto: update`, jamais modifier une migration appliquée.
5. Erreurs d'API toujours structurées (`{"error_code": "ERROR_…", "details": {…}}`).
6. Ne pas écrire de code métier sur cette machine sauf demande explicite.

## 3. Cette machine

Ancien PC portable réinstallé, **Windows 10** (choix d'Adrien), i7-10750H, 16 Go de RAM. Utilisateur Windows `Gwen`.
Joignable par Tailscale (`100.106.248.73`) et par SSH via l'alias `production-server` depuis la machine de dev.

Double rôle prévu :

1. **Machine de test multijoueur** : un second client Minecraft (compte `TheMashen`, instance CurseForge
   « Cobblemon Academy 2.0 - Copie ») pour tester à deux comptes avec la machine de dev (compte `MystAria_`).
2. **Hébergement « prod » provisoire** du backend et de PostgreSQL, en attendant un hébergement définitif.

## 4. État actuel (2026-10-03)

| Élément | État |
|---|---|
| Tailscale entre les deux machines | En place |
| SSH (OpenSSH) depuis la machine de dev | En place, mais **injoignable** lors des derniers déploiements (le script de déploiement du client retombe sur une instance locale) |
| Dépôts clonés | Oui |
| Backend installé comme service NSSM | Oui, **mais pas utilisé** : les deux clients pointent vers le backend de la machine de dev (`100.116.43.32:8080`) pour partager les mêmes données |
| Client de test | Mis à jour par `scripts/deploy-to-prod-server.sh` (dépôt client, script local non versionné) dans `C:\Users\Gwen\curseforge\minecraft\Instances\Cobblemon Academy 2.0 - Copie\mods` |
| Tests à deux comptes | Faits en « Ouvrir au LAN » (pas de serveur dédié) ; voir le guide de déploiement du client |

## 5. Missions possibles

- Rétablir l'accès SSH depuis la machine de dev (service `sshd`, pare-feu, Tailscale).
- Faire tourner le backend **en production** sur cette machine si Adrien le décide : suivre
  [`guides/deployment.md`](../guides/deployment.md) (jar packagé, variables d'environnement système, service
  NSSM, PostgreSQL limité à `localhost`, pare-feu minimal). Les clients devront alors viser la nouvelle
  URL (`backend_url` dans `config/phantasmon.json` de chaque instance, sans recompilation).
- Mettre en place les sauvegardes PostgreSQL (même guide, §3).

Ne pas trancher seul l'hébergement définitif ni la méthode de déploiement automatisé : présenter les options à
Adrien.
