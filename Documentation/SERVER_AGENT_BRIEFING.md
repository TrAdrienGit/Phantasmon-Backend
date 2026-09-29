# Briefing — Agent IA sur la machine serveur Phantasmon

Ce document est destiné à un agent IA (Claude Code ou équivalent) qui démarre à froid sur une
machine différente de celle utilisée pour le développement. Il n'a aucune mémoire de la session
de développement précédente : ce texte doit suffire à lui donner le contexte du projet et sa
mission sur cette machine spécifiquement.

---

## 1. Qu'est-ce que Phantasmon

Phantasmon est un projet en deux dépôts séparés :

- **`Phantasmon-Client`** — mod Fabric **client uniquement**, Minecraft 1.21.1 + Cobblemon 1.8.1,
  Java. Aucun code Phantasmon ne tourne jamais côté serveur Minecraft/Cobblemon. Un joueur sans
  le mod ne voit rien de spécial.
- **`Phantasmon-Backend`** — service Spring Boot 4.1.1 indépendant, Java 21, PostgreSQL, **aucune**
  dépendance Minecraft/Fabric. C'est la seule source de vérité (ownership, légalité des stats,
  atomicité des échanges, résultats de combat).

Le principe de jeu : les joueurs créent/éditent/échangent/combattent des "Ghost Pokémon" — des
Pokémon virtuels indépendants des données normales de Cobblemon. Le client parle au backend en
REST (CRUD) + WebSocket (`wss://.../ws?token={jwt}`) ; **les clients ne se parlent jamais
directement entre eux**, tout transite par le backend.

Modèle de combat (compromis d'architecture assumé, pas un oubli) : comme il n'y a pas de mod
serveur, les combats Ghost vs Ghost sont exécutés par le **moteur de combat de Cobblemon
lui-même**, tournant localement chez l'un des deux joueurs ("host client") — le backend ne fait
que des garde-fous de plausibilité sur le résultat rapporté, plus une alternance de l'hôte entre
combats successifs pour limiter la triche.

**Où trouver les repos** : `Phantasmon-Client` et `Phantasmon-Backend` sur le GitHub d'Adrien
(à cloner sur cette machine si ce n'est pas déjà fait). Le dépôt canonique de spécifications est
`Phantasmon-Backend/Documentation/` (voir en particulier `PHANTASMON_API_REFERENCE.md`,
`PHANTASMON_DB_SCHEMA.md`, `PHANTASMON_BACKEND_RUNNING.md`) et
`Phantasmon-Client/Documentation/PHANTASMON_CLIENT_BUILDING.md` côté client.

---

## 2. Règles non négociables (héritées, à respecter aussi sur cette machine)

1. **Ne jamais committer ni pusher sans qu'Adrien le demande explicitement.** Il gère tout son git
   lui-même — y compris sur cette machine serveur. Si le déploiement nécessite un `git pull`, c'est
   normal ; ne jamais `git push`/`git commit` de ton propre chef.
2. **Ne jamais committer ni afficher le contenu du fichier `.env`** (ou toute variable comme
   `JWT_SECRET`, `BDD_PASSWORD`) — ce sont de vrais secrets. `.env.template` est suivi par git,
   `.env` ne l'est jamais.
3. Le backend attend Java **21** (pas 25 — c'est uniquement le mod client qui a besoin de JDK 25
   pour son outillage Loom/Gradle, sans rapport avec le Java exécuté par le backend).
4. `Phantasmon-Backend` : jamais `ddl-auto: update`, uniquement des migrations Flyway
   (`src/main/resources/db/migration/V{n}__description.sql`), jamais modifier une migration déjà
   appliquée.
5. Toute erreur API est structurée (`{"error_code": "ERROR_...", "details": {...}}`), jamais du
   texte brut — si du code est modifié ici, respecter cette convention.

---

## 3. Contexte de cette machine spécifique

Ancien PC portable reformaté proprement (Windows 10 conservé, pas de passage à Linux — décision
d'Adrien). Specs : i7-10750H, 16 Go RAM, RTX 2060 6 Go VRAM (le GPU ne sert à rien ici, aucun
calcul serveur n'en a besoin — CPU/RAM sont les facteurs limitants).

**Double rôle voulu pour cette machine** :
1. **Serveur de test multijoueur** — héberger un serveur Minecraft + le backend, pour qu'Adrien
   puisse tester le mod à plusieurs (lui + un 2e compte Minecraft qu'il vient d'acquérir), ce qu'il
   ne pouvait pas faire avant faute de second compte.
2. **Serveur de "prod" pour le moment** — cette même machine sert aussi d'hébergement réel du
   backend/base de données, en attendant une décision d'hébergement plus définitive (VPS ou autre,
   non tranchée — voir `CAD_Ghost_Pokemon_Partie_3_Complements.md` §I/§J côté backend).

---

## 4. Mission de l'agent sur cette machine

### 4.1 Installation de base
- JDK 21 (pas 25 — voir règle 3 ci-dessus).
- PostgreSQL natif (même approche que la machine de dev d'Adrien : un rôle applicatif dédié +
  une base dédiée, migrations Flyway appliquées automatiquement au premier démarrage du backend).
- Le launcher Minecraft officiel (pour lancer un client de test depuis cette machine si besoin).
- Cloner les deux dépôts.

### 4.2 Faire tourner le backend "en prod", pas en mode dev
- Utiliser le **jar packagé** (`./gradlew bootJar` puis `java -jar build/libs/phantasmon-backend-<version>.jar`
  avec de vraies variables d'environnement système), **jamais** `./gradlew bootRun` (mode dev,
  pas fait pour tourner en continu/sans surveillance).
- Le faire démarrer automatiquement au boot de la machine (Planificateur de tâches Windows, ou un
  vrai service Windows via NSSM) — sinon tout s'arrête à la moindre déconnexion/redémarrage.
  Demander à Adrien sa préférence entre ces deux approches avant de choisir.
- Voir `Phantasmon-Backend/Documentation/PHANTASMON_BACKEND_RUNNING.md` pour le détail complet
  des variables d'environnement requises et la procédure de lancement en jar.

### 4.3 Réseau
- Redirection de port sur la box internet : port du backend (8080 par défaut) et port du serveur
  Minecraft (25565) si accès depuis l'extérieur du LAN est voulu.
- IP publique résidentielle probablement dynamique → mettre en place un DNS dynamique gratuit
  (DuckDNS, No-IP ou équivalent) pour que l'URL du backend utilisée par le mod client reste stable.
- PostgreSQL ne doit écouter que sur `localhost` — seul le backend local doit y accéder, jamais
  exposé directement sur internet.
- Pare-feu Windows : n'ouvrir que les ports strictement nécessaires.

### 4.4 Sécurité
- `JWT_SECRET` doit être une vraie valeur aléatoire forte (pas une valeur de dev/exemple) —
  générable via `openssl rand -base64 64`.
- Ne jamais logger ni afficher de secret, même dans les logs de session du backend (voir la
  section logging de `PHANTASMON_BACKEND_RUNNING.md`).

### 4.5 Tunnel machine de dev ↔ machine serveur (à décider avec Adrien, pas encore tranché)
Adrien veut pouvoir pousser des mises à jour depuis sa machine de dev vers cette machine serveur.
**Aucune méthode n'est encore choisie** — à discuter avec lui avant d'implémenter quoi que ce soit.
Options à présenter, sans en imposer une :
- **Tailscale** (ou équivalent de VPN maillé) — le plus simple à mettre en place, chiffré,
  fonctionne même derrière un NAT/IP dynamique sans configuration de redirection de port
  supplémentaire pour l'accès entre les deux machines elles-mêmes.
- **OpenSSH** (inclus nativement dans Windows 10/11) — accès distant classique, suffisant pour un
  `git pull` + relance manuelle ou scriptée du service.
- **Déploiement par `git push`** vers un dépôt bare sur cette machine, avec un hook côté serveur
  qui rebuild et relance le service automatiquement — plus élaboré, à envisager seulement si le
  flux manuel devient pénible.

Ne pas choisir unilatéralement : poser la question à Adrien (trade-offs : simplicité de mise en
place vs automatisation du déploiement).

### 4.6 Test multijoueur
Une fois le backend et un serveur Minecraft (ou juste "Ouvrir au LAN" depuis un monde solo) actifs
sur cette machine, Adrien pourra se connecter avec son compte principal + son nouveau second
compte Minecraft pour enfin tester en conditions réelles : visibilité mutuelle des Ghost Pokémon,
échanges (`trade`), et plus tard les combats (Phase 9, pas encore implémentée).

---

## 5. Ce qui n'est PAS dans le périmètre de cette machine

- Écrire du code métier (features du mod/backend) — ça reste le travail de la session de
  développement principale, pas de cet agent serveur, sauf si Adrien demande explicitement une
  correction ici.
- Décider seul de l'hébergement définitif long terme — c'est encore une question ouverte,
  cette machine n'est qu'une solution "pour le moment".
