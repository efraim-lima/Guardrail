#!/bin/bash

set -euo pipefail

# -----------------------------------------------------------------------------
# setup.sh (simplificado)
# Objetivo: subir toda a stack Docker com o minimo de configuracao.
# Unica configuracao obrigatoria: usuario e senha admin do Keycloak.
# -----------------------------------------------------------------------------

BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERRO]${NC} $1"; }

ENV_FILE=".env"
CERTS_DIR="./nginx/certs"
CERT_FILE="${CERTS_DIR}/agentk.crt"
KEY_FILE="${CERTS_DIR}/agentk.key"
CERT_DAYS=365
CERT_CN="agentk.local"
DEFAULT_CLIENT_SECRET="oauth2-proxy-secret"

require_tools() {
    if ! command -v docker >/dev/null 2>&1; then
        log_error "Docker nao encontrado. Instale o Docker antes de continuar."
        exit 1
    fi
    if ! docker compose version >/dev/null 2>&1; then
        log_error "Plugin docker compose nao encontrado."
        exit 1
    fi
    if ! command -v openssl >/dev/null 2>&1; then
        log_error "openssl nao encontrado. Instale antes de continuar."
        exit 1
    fi
}

resolve_host_ip() {
    local detected_ip=""

    if command -v ip >/dev/null 2>&1; then
        detected_ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}')"
    fi

    if [[ -z "$detected_ip" ]] && command -v hostname >/dev/null 2>&1; then
        detected_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi

    echo "${detected_ip:-127.0.0.1}"
}

ensure_env_file() {
    if [[ ! -f "$ENV_FILE" ]]; then
        if [[ -f "env.example" ]]; then
            cp env.example "$ENV_FILE"
            log_ok ".env criado a partir de env.example"
        else
            touch "$ENV_FILE"
            log_warn ".env vazio criado"
        fi
    fi
}

upsert_env() {
    local key="$1"
    local value="$2"

    grep -vE "^${key}=" "$ENV_FILE" > "${ENV_FILE}.tmp" || true
    echo "${key}=${value}" >> "${ENV_FILE}.tmp"
    mv "${ENV_FILE}.tmp" "$ENV_FILE"
}

load_env() {
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE" 2>/dev/null || true
    set +a
}

configure_keycloak_credentials() {
    local current_user="${KEYCLOAK_ADMIN:-admin}"
    local current_pass="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
    local input_user
    local input_pass

    echo ""
    echo -e "${BOLD}Configuracao do admin Keycloak${NC}"
    echo -e "Pressione ENTER para manter os valores atuais."
    echo ""

    read -r -p "Usuario admin [${current_user}]: " input_user || true
    read -r -p "Senha admin [${current_pass}]: " input_pass || true

    if [[ -n "${input_user:-}" ]]; then
        current_user="$input_user"
    fi
    if [[ -n "${input_pass:-}" ]]; then
        current_pass="$input_pass"
    fi

    upsert_env "KEYCLOAK_ADMIN" "$current_user"
    upsert_env "KEYCLOAK_ADMIN_PASSWORD" "$current_pass"

    # Mantem secret padrao para eliminar configuracao adicional.
    upsert_env "OAUTH2_PROXY_CLIENT_SECRET" "${OAUTH2_PROXY_CLIENT_SECRET:-$DEFAULT_CLIENT_SECRET}"

    log_ok "Credenciais do Keycloak atualizadas no .env"
}

ensure_runtime_env() {
    local detected_ip
    detected_ip="$(resolve_host_ip)"

    upsert_env "AGENTK_HOST_IP" "$detected_ip"
    upsert_env "HOST_BIND_IP" "${HOST_BIND_IP:-0.0.0.0}"
    upsert_env "OAUTH2_PROXY_TRUSTED_PROXY_IP_1" "${OAUTH2_PROXY_TRUSTED_PROXY_IP_1:-172.16.0.0/12}"
    upsert_env "OAUTH2_PROXY_TRUSTED_PROXY_IP_2" "${OAUTH2_PROXY_TRUSTED_PROXY_IP_2:-10.0.0.0/8}"
    upsert_env "OAUTH2_PROXY_TRUSTED_PROXY_IP_3" "${OAUTH2_PROXY_TRUSTED_PROXY_IP_3:-192.168.0.0/16}"

    export AGENTK_HOST_IP="$detected_ip"
    log_ok "IP detectado: ${detected_ip}"
}

ensure_ssl_certificate() {
    mkdir -p "$CERTS_DIR"

    if [[ -f "$CERT_FILE" && -f "$KEY_FILE" ]]; then
        log_info "Certificado SSL ja existe."
        return 0
    fi

    log_info "Gerando certificado SSL para ${CERT_CN}..."

    local san_cfg
    san_cfg="$(mktemp)"

    cat > "$san_cfg" <<CFG
[req]
distinguished_name = req_distinguished_name
x509_extensions    = v3_req
prompt             = no

[req_distinguished_name]
CN = ${CERT_CN}

[v3_req]
subjectAltName = @alt_names
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[alt_names]
DNS.1 = ${CERT_CN}
DNS.2 = localhost
IP.1  = ${AGENTK_HOST_IP}
IP.2  = 127.0.0.1
CFG

    openssl req -x509 -nodes -days "$CERT_DAYS" -newkey rsa:2048 \
        -keyout "$KEY_FILE" \
        -out "$CERT_FILE" \
        -config "$san_cfg" \
        -extensions v3_req >/dev/null 2>&1

    rm -f "$san_cfg"
    log_ok "Certificado SSL gerado."
}

ensure_logs_dir() {
    mkdir -p "./Agentk-Sugest/logs"
    chmod 777 "./Agentk-Sugest/logs" || true
}

start_stack() {
    log_info "Subindo todos os servicos..."
    docker compose up -d --build
    log_ok "Stack iniciada."
}

print_summary() {
    echo ""
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo -e "${GREEN}|                    STACK AGENTK PRONTA                      |${NC}"
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo ""
    echo -e "Aplicacao:      ${BOLD}https://agentk.local/${NC}"
    echo -e "Keycloak Admin: ${BOLD}https://agentk.local/keycloak/admin/${NC}"
    echo ""
    echo -e "Se agentk.local nao resolver no seu PC, adicione no /etc/hosts:"
    echo -e "${BOLD}${AGENTK_HOST_IP} agentk.local${NC}"
    echo ""
    echo -e "Credenciais admin Keycloak:"
    echo -e "Usuario: ${BOLD}${KEYCLOAK_ADMIN:-admin}${NC}"
    echo -e "Senha:   ${BOLD}${KEYCLOAK_ADMIN_PASSWORD:-admin}${NC}"
    echo ""
    echo -e "Parar tudo: ${BOLD}docker compose down${NC}"
    echo ""
}

main() {
    require_tools
    ensure_env_file
    load_env
    configure_keycloak_credentials
    load_env
    ensure_runtime_env
    load_env
    ensure_ssl_certificate
    ensure_logs_dir
    start_stack
    print_summary
}

main "$@"
