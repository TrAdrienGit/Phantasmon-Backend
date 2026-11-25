#!/usr/bin/env bash
# Prepares a fresh Oracle Cloud VM (Always Free, Ampere A1, Ubuntu 26.04 or 24.04, arm64) for the Phantasmon backend.
# Run ONCE on the VM, as root, before the first scripts/deploy-ubuntu.sh to it:
#   scp scripts/oracle/prepare-vm.sh ubuntu@<ip>:
#   ssh ubuntu@<ip> sudo bash prepare-vm.sh --domain <name pointing to the VM> [--email <for Let's Encrypt>]
# Idempotent: running it again changes nothing that is already right.
# What it does and why: Documentation/guides/deploying-oracle.md.
#   - Java 21 and PostgreSQL 18 (the version of server-linux, so its dumps restore as they are);
#   - opens 80 and 443 in the VM's own firewall (Oracle's Ubuntu images reject everything but SSH in iptables, on top
#     of the VCN security list, which is opened in the Oracle console);
#   - Caddy in front of the backend: HTTPS with an automatic Let's Encrypt certificate, WebSocket included;
#   - automatic security updates and fail2ban on SSH;
#   - writes /etc/phantasmon/public-proxy, so the deployment leaves Tailscale Funnel off on this machine.
set -euo pipefail

DOMAIN=""
EMAIL=""
while (($#)); do
	case "$1" in
		--domain) DOMAIN="${2:?--domain needs a name}"; shift ;;
		--email) EMAIL="${2:?--email needs an address}"; shift ;;
		*) echo "Unknown option: $1"; exit 1 ;;
	esac
	shift
done
[[ -n "$DOMAIN" ]] || { echo "Usage: sudo bash prepare-vm.sh --domain <name> [--email <address>]"; exit 1; }
[[ "$(id -u)" == 0 ]] || { echo "Run it as root (sudo)."; exit 1; }

log() { echo "==> [oracle] $*"; }
export DEBIAN_FRONTEND=noninteractive

# ---------------------------------------------------------------- system
log "Updating the system..."
apt-get update -qq
apt-get upgrade -y -qq
apt-get install -y -qq curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https \
	unattended-upgrades fail2ban netfilter-persistent iptables-persistent
timedatectl set-timezone Europe/Paris
systemctl enable --now unattended-upgrades fail2ban >/dev/null

# ---------------------------------------------------------------- Java 21
command -v java >/dev/null 2>&1 || apt-get install -y -qq openjdk-21-jre-headless
java -version 2>&1 | head -1

# ---------------------------------------------------------------- PostgreSQL 18
# Ubuntu 26.04 ships 18; 24.04 ships 16, which cannot read a dump of 18: then the PostgreSQL project's repository.
if [[ -z "$(apt-cache policy postgresql-18 2>/dev/null | awk '/Candidate:/ {print $2}' | grep -v none || true)" ]]; then
	log "Adding the PostgreSQL repository (apt.postgresql.org) for PostgreSQL 18..."
	install -d /usr/share/postgresql-common/pgdg
	curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc
	. /etc/os-release
	echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" \
		> /etc/apt/sources.list.d/pgdg.list
	apt-get update -qq
fi
apt-get install -y -qq postgresql-18 postgresql
systemctl enable --now postgresql >/dev/null

# ---------------------------------------------------------------- firewall (iptables of Oracle's images)
# Their INPUT chain ends with a REJECT: the ACCEPTs go just before it.
for port in 80 443; do
	if ! iptables -C INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT 2>/dev/null; then
		reject="$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" {print $1; exit}')"
		if [[ -n "$reject" ]]; then
			iptables -I INPUT "$reject" -p tcp -m state --state NEW --dport "$port" -j ACCEPT
		else
			iptables -A INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT
		fi
		log "Port $port opened in iptables."
	fi
done
netfilter-persistent save >/dev/null

# ---------------------------------------------------------------- Caddy (HTTPS reverse proxy)
if ! command -v caddy >/dev/null 2>&1; then
	log "Installing Caddy (its official repository)..."
	curl -fsSL 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
	curl -fsSL 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
	apt-get update -qq
	apt-get install -y -qq caddy
fi
GLOBAL=""
[[ -n "$EMAIL" ]] && GLOBAL=$'{\n\temail '"$EMAIL"$'\n}\n\n'
cat > /etc/caddy/Caddyfile <<EOF
${GLOBAL}# Phantasmon backend (scripts/oracle/prepare-vm.sh). The backend listens on 127.0.0.1:8080 only; Caddy gets and
# renews the certificate for ${DOMAIN} by itself, and passes WebSockets (/ws) through as they are.
${DOMAIN} {
	encode zstd gzip
	reverse_proxy 127.0.0.1:8080
	log {
		output file /var/log/caddy/phantasmon.log {
			roll_size 20MiB
			roll_keep 5
		}
	}
}
EOF
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
systemctl enable caddy >/dev/null
systemctl reload-or-restart caddy

mkdir -p /etc/phantasmon
echo "caddy (https://${DOMAIN})" > /etc/phantasmon/public-proxy

log "Ready. Next, from the dev machine: PHANTASMON_DEPLOY_HOST=$(logname 2>/dev/null || echo ubuntu)@<this VM> scripts/deploy-ubuntu.sh"
log "Caddy asks Let's Encrypt for ${DOMAIN} as soon as the name points here and ports 80/443 are open in the VCN."
