# Héberger le backend sur Oracle Cloud (Always Free)

Préparé le 2026-11-26 sur la branche `migration-oracle` ; **pas encore fait**. But : sortir le backend de `server-linux` (ton
réseau, ton IP) vers une machine Oracle gratuite, avec sa propre IP publique et un nom de domaine en HTTPS.

Le backend reste exactement le même (Java 21, PostgreSQL 18, add-on Events) : on réutilise `scripts/deploy-ubuntu.sh`
et `scripts/ubuntu/setup-server.sh` ([`deploying-ubuntu.md`](deploying-ubuntu.md)). Seules différences : Caddy publie le
HTTPS à la place de Tailscale Funnel, et l'utilisateur SSH est `ubuntu` (avec `sudo`) au lieu de `root`.

## 1. Ce qu'il faut savoir avant

- **Offre** : machine ARM « Ampere A1 » Always Free. Depuis le 15 juin 2026, des articles disent que les comptes gratuits
  sont limités à **2 cœurs / 12 Go** (avant : 4 / 24), les comptes « Pay As You Go » gardant 4 / 24 sans frais. À
  vérifier dans la console. Le backend utilise environ 650 Mo : les deux suffisent largement.
- **Inscription** : carte bancaire (vérification, pas de débit) et vraie identité. Le compte est à ton nom ; rien de
  public ne le montre. Ce que les joueurs voient : l'IP d'Oracle et le nom de domaine.
- **Machine inactive** : Oracle peut récupérer une instance gratuite presque inactive pendant 7 jours. Passer le compte en
  « Pay As You Go » (en restant dans les limites gratuites) évite ce risque.
- **Capacité** : les A1 manquent parfois dans une région ; réessayer plus tard, ou une autre zone (« AD »). La région
  d'origine (Paris ou Marseille) ne se change plus après l'inscription.

## 2. Dans la console Oracle

1. **Réseau** : *Networking → Virtual cloud networks → Start VCN Wizard → Create VCN with Internet Connectivity*.
2. **Ports** : dans la *Default Security List* du sous-réseau public, ajouter deux *Ingress Rules* : source `0.0.0.0/0`,
   TCP, ports `80` et `443`. (22 y est déjà.)
3. **Instance** : *Compute → Instances → Create* :
   - image **Canonical Ubuntu 26.04** (même version que `server-linux`) ; sinon 24.04, le script installe alors PostgreSQL 18 depuis le dépôt officiel de PostgreSQL ;
   - shape **VM.Standard.A1.Flex**, 2 OCPU / 12 Go (ou ce que la console propose en gratuit) ;
   - sous-réseau public, *Assign a public IPv4 address* ;
   - clé SSH : ta clé publique (`~/.ssh/id_ed25519.pub`) ;
   - volume de démarrage 50 Go (200 Go gratuits au total).

   « Out of capacity » : Oracle n'a plus de machine A1 libre. `scripts/oracle/launch-instance.py` réessaie toutes les
   5 minutes (2 OCPU / 12 Go puis 1 / 6) et s'arrête dès que l'instance existe. Il faut le SDK (`pip install oci`) et une
   clé d'API dans `~/.oci/config` (*My profile → API keys → Add API key*, clé privée enregistrée dans `~/.oci/`).
4. **IP fixe** : l'IP publique « éphémère » ne change pas tant que l'instance existe, même après un redémarrage. Pour une
   IP qui survit à la suppression de l'instance : *Networking → IP Management → Reserved Public IPs*, puis l'attacher à
   la carte réseau de l'instance (*Attached VNICs → IPv4 Addresses → Edit*). Vérifier dans la console qu'elle est
   gratuite avant de la créer.

## 3. Un nom de domaine

Caddy a besoin d'un nom pour le certificat HTTPS (Let's Encrypt). Au choix :
- **gratuit** : un sous-domaine DuckDNS (`<nom>.duckdns.org`), qu'on fait pointer vers l'IP de l'instance ;
- **ton domaine** : un enregistrement `A` vers l'IP de l'instance.

Choisir un nom sans lien avec ton identité (pseudonyme MystAria au plus).

## 4. Préparer la machine (une fois)

Depuis Git Bash, à la racine de `Phantasmon-Backend` :

```bash
scp scripts/oracle/prepare-vm.sh ubuntu@<ip>:
ssh ubuntu@<ip> sudo bash prepare-vm.sh --domain <nom> --email <adresse pour Let's Encrypt, optionnelle>
```

Le script ([`scripts/oracle/prepare-vm.sh`](../../scripts/oracle/prepare-vm.sh)) installe Java 21, PostgreSQL 18 et Caddy,
ouvre 80 et 443 dans l'`iptables` de l'image Oracle (qui bloque tout sauf SSH), active les mises à jour de sécurité
automatiques et fail2ban, et écrit `/etc/phantasmon/public-proxy` pour que le déploiement n'active pas Tailscale Funnel.

Optionnel : installer Tailscale sur la machine pour l'administrer comme `server-linux`, puis fermer le port 22 public.

## 5. Migrer les données de server-linux

```bash
bash scripts/oracle/migrate-from-server-linux.sh ubuntu@<ip>
```

Le script ([`scripts/oracle/migrate-from-server-linux.sh`](../../scripts/oracle/migrate-from-server-linux.sh)) :
1. **arrête et désactive** le backend de `server-linux`, pour que rien ne soit écrit après la copie ;
2. fait un `pg_dump` de sa base et une archive de `hub_schematics/` (constructions, terrains des hubs miroirs, salles et
   exports du jeu de trahison), `admins.txt`, `showdown-data/` et `backups/` ;
3. copie tout sur la machine Oracle et y déploie avec ce dump (`deploy-ubuntu.sh --restore-dump`) ;
4. vérifie `/health`.

Le dump et l'archive contiennent les données réelles des joueurs : ils passent par un dossier temporaire privé, supprimé à
la fin.

Ensuite, les déploiements habituels vers Oracle :

```bash
PHANTASMON_DEPLOY_HOST=ubuntu@<ip> bash scripts/deploy-ubuntu.sh
```

## 6. Après la bascule

- **Clients** : dans l'écran de réglages de Phantasmon, adresse du backend `https://<nom>` (le client n'a plus d'adresse
  par défaut depuis le 2026-11-25).
- **Serveur Minecraft** (copie des hubs miroirs, `mirror-server` d'Events) : son `backend_url` doit pointer vers
  `https://<nom>`.
- **Page de téléchargements** : `phantasmon.downloads.share-base` pointe toujours vers le Nextcloud de `server-linux`.
  Les liens marchent tant que `server-linux` est allumé ; sinon, mettre `PHANTASMON_DOWNLOADS_SHARE_BASE` dans le `.env`.
- **Tailscale Funnel** de `server-linux` : `tailscale funnel reset` quand la bascule est validée.
- **Sauvegardes** : le `pg_dump` quotidien reste sur la machine Oracle (`/opt/phantasmon/backups`). En garder une copie
  ailleurs (par exemple une copie quotidienne vers `server-linux`) est à mettre en place.

## 7. Revenir en arrière

Rien n'est supprimé sur `server-linux` : `ssh root@100.101.1.79 systemctl enable --now phantasmon-backend`, puis remettre
l'ancienne adresse dans les clients. Les données écrites entre-temps sur Oracle ne sont pas recopiées.
