#!/usr/bin/env bash
# Install from an existing Finora checkout. No remote shell script is executed.
set -Eeuo pipefail
umask 077
finora_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
finora_image=ghcr.io/gadmin2151/finora:latest
finora_init_args=()

usage() {
  cat <<'HELP'
Usage: sudo ./scripts/install-debian.sh [OPTIONS]
  --domain NAME     Configure Caddy HTTPS, e.g. finance.example.com
  --port PORT       Loopback HTTP port (default: 8088)
  --local-ai        Enable Ollama on CPU; models are downloaded in the web UI
  --image IMAGE     Registry tag/digest (default: ghcr.io/gadmin2151/finora:latest)
  --build           Build finora:local from this checkout
  -h, --help        Show this help
Supports Debian 12/13 on amd64/arm64. Options configure the FIRST installation.
Existing .env, passwords, volumes and organizations are preserved on reruns.
HELP
}

while (($#)); do
  case "$1" in
    --domain|--port)
      (($# >= 2)) || { usage >&2; exit 2; }
      finora_init_args+=("$1" "$2"); shift 2 ;;
    --image)
      (($# >= 2)) || { usage >&2; exit 2; }
      finora_image=$2; shift 2 ;;
    --local-ai) finora_init_args+=("--local-ai"); shift ;;
    --build) finora_image=finora:local; shift ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ ${EUID} -eq 0 ]] || { printf 'Run with sudo.\n' >&2; exit 1; }
[[ -r /etc/os-release ]] || { printf 'Debian 12/13 is required.\n' >&2; exit 1; }
# shellcheck source=/dev/null
source /etc/os-release
[[ ${ID:-} == debian && (${VERSION_ID:-} == 12 || ${VERSION_ID:-} == 13) ]] || {
  printf 'Supported systems: Debian 12 or 13.\n' >&2; exit 1;
}
finora_arch=$(dpkg --print-architecture)
[[ $finora_arch == amd64 || $finora_arch == arm64 ]] || {
  printf 'Supported architectures: amd64 and arm64.\n' >&2; exit 1;
}
[[ -d /run/systemd/system ]] || { printf 'Run on a Debian host with systemd.\n' >&2; exit 1; }
trap 'printf "Installation stopped at line %s. Existing data was not removed.\n" "$LINENO" >&2' ERR
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl python3
python3 "$finora_root/scripts/init.py" --image "$finora_image" "${finora_init_args[@]}"

if ! command -v docker >/dev/null 2>&1; then
  for finora_package in docker.io docker-compose docker-doc docker-buildx podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "$finora_package" 2>/dev/null | grep -q '^install ok installed$'; then
      printf 'Conflicting package: %s. Reconcile the existing container runtime before installing Docker CE.\n' "$finora_package" >&2
      exit 1
    fi
  done
  install -m 0755 -d /etc/apt/keyrings
  curl --fail --silent --show-error --location --retry 3 --connect-timeout 15 --max-time 120 \
    https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
  chmod 0644 /etc/apt/keyrings/docker.asc
  cat > /etc/apt/sources.list.d/finora-docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/debian
Suites: ${VERSION_CODENAME}
Components: stable
Architectures: ${finora_arch}
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  chmod 0644 /etc/apt/sources.list.d/finora-docker.sources
  apt-get update
  apt-get install -y --no-install-recommends docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
if ! docker compose version >/dev/null 2>&1; then
  printf 'Docker exists but Compose plugin is missing. Install docker-compose-plugin for your existing Docker distribution.\n' >&2
  exit 1
fi
systemctl enable --now docker
docker info >/dev/null
cd -- "$finora_root"
python3 scripts/deploy.py
printf '\nInitial password: sudo cat %s/.secrets/admin_password\n' "$finora_root"
printf 'Application updates: cd %s && sudo python3 scripts/deploy.py\n' "$finora_root"
