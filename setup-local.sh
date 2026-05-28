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
COMPOSE_OVERRIDE_FILE="docker-compose.override.yaml"
CERTS_DIR="./nginx/certs"
CERT_FILE="${CERTS_DIR}/agentk.crt"
KEY_FILE="${CERTS_DIR}/agentk.key"
CERT_DAYS=365
DEFAULT_CERT_CN="agentk.local"
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

is_ip_address_local() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

strip_scheme_local() {
    echo "$1" | sed -E 's|^https?://||' | sed 's|/.*||'
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

    # Forca o secret padrao que bate com o realm importado (config/keycloak/realm-agentk.json).
    upsert_env "OAUTH2_PROXY_CLIENT_SECRET" "$DEFAULT_CLIENT_SECRET"

    log_ok "Credenciais do Keycloak atualizadas no .env"
}

# ---------------------------------------------------------------------------
# Domínio customizado (opcional) — permite acessar via DuckDNS etc. mesmo local
# ---------------------------------------------------------------------------
configure_custom_domain() {
    local current_domain
    current_domain="$(strip_scheme_local "${CUSTOM_DOMAIN:-}")"

    echo ""
    echo -e "${BOLD}Domínio personalizado (opcional)${NC}"
    echo -e "  Se tiver um domínio apontando para este host (DuckDNS, Cloudflare, etc.),"
    echo -e "  informe aqui. Deixe em branco para usar somente agentk.local."
    echo -e "  Exemplo: agentk-guardrail.duckdns.org"
    local input_domain
    read -r -p "Domínio [${current_domain:-nenhum}]: " input_domain || true
    [[ -n "${input_domain:-}" ]] && current_domain="$(strip_scheme_local "$input_domain")"

    CUSTOM_DOMAIN="${current_domain:-}"
    upsert_env "CUSTOM_DOMAIN" "$CUSTOM_DOMAIN"

    if [[ -n "$CUSTOM_DOMAIN" ]]; then
        log_ok "Domínio configurado: ${CUSTOM_DOMAIN}"
    else
        log_info "Nenhum domínio configurado. Usando agentk.local."
    fi
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

# ---------------------------------------------------------------------------
# Gera docker-compose.override.yaml quando um domínio customizado está definido
# ---------------------------------------------------------------------------
generate_compose_override() {
    if [[ -z "${CUSTOM_DOMAIN:-}" ]]; then
        # Sem domínio: remove override anterior se existir para não conflitar
        if [[ -f "$COMPOSE_OVERRIDE_FILE" ]]; then
            rm -f "$COMPOSE_OVERRIDE_FILE"
            log_info "Override anterior removido (sem domínio customizado)."
        fi
        return 0
    fi

    log_info "Gerando ${COMPOSE_OVERRIDE_FILE} para domínio ${CUSTOM_DOMAIN}..."

    local trusted_ip1="${OAUTH2_PROXY_TRUSTED_PROXY_IP_1:-172.16.0.0/12}"
    local trusted_ip2="${OAUTH2_PROXY_TRUSTED_PROXY_IP_2:-10.0.0.0/8}"
    local trusted_ip3="${OAUTH2_PROXY_TRUSTED_PROXY_IP_3:-192.168.0.0/16}"
    local client_id="${OAUTH2_PROXY_CLIENT_ID:-oauth2-proxy}"
    local client_secret="${OAUTH2_PROXY_CLIENT_SECRET:-oauth2-proxy-secret}"
    local cookie_secret="${OAUTH2_PROXY_COOKIE_SECRET:-agentktccsecretkey1234567890abcd}"

    cat > "$COMPOSE_OVERRIDE_FILE" <<OVERRIDE
# =============================================================================
# docker-compose.override.yaml — gerado automaticamente por setup-local.sh
# Substitui referências ao hostname agentk.local pelo domínio customizado.
# NÃO edite manualmente; execute setup-local.sh novamente para regenerar.
# Domínio configurado: ${CUSTOM_DOMAIN}
# Gerado em: $(date -u '+%Y-%m-%dT%H:%M:%SZ')
# =============================================================================

services:

  keycloak:
    environment:
      - KC_BOOTSTRAP_ADMIN_USERNAME=\${KEYCLOAK_ADMIN:-admin}
      - KC_BOOTSTRAP_ADMIN_PASSWORD=\${KEYCLOAK_ADMIN_PASSWORD:-admin}
      - KC_DB=dev-file
      - KC_HOSTNAME=${CUSTOM_DOMAIN}
      - KC_HOSTNAME_STRICT=false
      - KC_HTTP_RELATIVE_PATH=/keycloak
      - KC_PROXY_HEADERS=xforwarded
      - KC_HTTP_ENABLED=true
      - KC_HEALTH_ENABLED=true
      - KC_IMPORT=/tmp/realm-agentk.json
      - GOOGLE_CLIENT_ID=\${GOOGLE_CLIENT_ID:-CHANGE_ME}
      - GOOGLE_CLIENT_SECRET=\${GOOGLE_CLIENT_SECRET:-CHANGE_ME}

  oauth2-proxy:
    command:
      - --http-address=0.0.0.0:4180
      - --upstream=http://agentk-client:8501
      - --provider=oidc
      - --client-id=${client_id}
      - --client-secret=${client_secret}
      - --oidc-issuer-url=https://${CUSTOM_DOMAIN}/keycloak/realms/agentk
      - --skip-oidc-discovery=true
      - --login-url=https://${CUSTOM_DOMAIN}/keycloak/realms/agentk/protocol/openid-connect/auth
      - --redeem-url=http://keycloak:8080/keycloak/realms/agentk/protocol/openid-connect/token
      - --oidc-jwks-url=http://keycloak:8080/keycloak/realms/agentk/protocol/openid-connect/certs
      - --redirect-url=https://${CUSTOM_DOMAIN}/oauth2/callback
      - --reverse-proxy=true
      - --trusted-proxy-ip=${trusted_ip1}
      - --trusted-proxy-ip=${trusted_ip2}
      - --trusted-proxy-ip=${trusted_ip3}
      - --cookie-secret=${cookie_secret}
      - --cookie-secure=true
      - --cookie-samesite=none
      - --session-cookie-minimal=true
      - --insecure-oidc-allow-unverified-email=true
      - --skip-auth-route=GET=^/favicon\.ico\$
      - --email-domain=*
      - --skip-provider-button=true
      - --oidc-extra-audience=${client_id}
      - --whitelist-domain=${CUSTOM_DOMAIN}
      - --backend-logout-url=http://keycloak:8080/keycloak/realms/agentk/protocol/openid-connect/logout
OVERRIDE

    log_ok "${COMPOSE_OVERRIDE_FILE} gerado para ${CUSTOM_DOMAIN}."
}

ensure_ssl_certificate() {
    mkdir -p "$CERTS_DIR"

    # CN: domínio customizado tem precedência sobre agentk.local
    local CERT_CN="${CUSTOM_DOMAIN:-${DEFAULT_CERT_CN}}"

    if [[ -f "$CERT_FILE" && -f "$KEY_FILE" ]]; then
        local existing_cn
        existing_cn="$(openssl x509 -noout -subject -in "$CERT_FILE" 2>/dev/null | sed 's/.*CN\s*=\s*//' | tr -d ' ')"
        if [[ "$existing_cn" == "$CERT_CN" ]]; then
            log_info "Certificado SSL ja existe para ${CERT_CN}."
            return 0
        else
            log_warn "Certificado existente (CN=${existing_cn}) difere de ${CERT_CN}. Regenerando..."
            rm -f "$CERT_FILE" "$KEY_FILE"
        fi
    fi

    log_info "Gerando certificado SSL para ${CERT_CN}..."

    local san_cfg
    san_cfg="$(mktemp)"

    # SAN: inclui domínio (se configurado), agentk.local, IP local e loopback
    local san_entries=""
    local dns_idx=1 ip_idx=1
    if [[ -n "${CUSTOM_DOMAIN:-}" ]]; then
        san_entries+="DNS.${dns_idx} = ${CUSTOM_DOMAIN}\n"; (( dns_idx++ )) || true
    fi
    san_entries+="DNS.${dns_idx} = ${DEFAULT_CERT_CN}\n"; (( dns_idx++ )) || true
    san_entries+="DNS.${dns_idx} = localhost\n"
    san_entries+="IP.${ip_idx}  = ${AGENTK_HOST_IP}\n"; (( ip_idx++ )) || true
    san_entries+="IP.${ip_idx}  = 127.0.0.1"

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
$(echo -e "$san_entries")
CFG

    openssl req -x509 -nodes -days "$CERT_DAYS" -newkey rsa:2048 \
        -keyout "$KEY_FILE" \
        -out "$CERT_FILE" \
        -config "$san_cfg" \
        -extensions v3_req >/dev/null 2>&1

    rm -f "$san_cfg"
    log_ok "Certificado SSL gerado."
}

# ---------------------------------------------------------------------------
# Gera gateway-keystore.p12 via openssl (sem necessidade de Java/keytool)
# ---------------------------------------------------------------------------
ensure_gateway_keystore() {
    local keystore="./gateway-keystore.p12"
    local ks_pass="${KEYSTORE_PASSWORD:-gateway-secret}"

    if [[ -f "$keystore" ]]; then
        log_info "gateway-keystore.p12 ja existe."
        return 0
    fi

    log_info "Gerando gateway-keystore.p12..."

    local tmp_key tmp_crt
    tmp_key="$(mktemp)"
    tmp_crt="$(mktemp)"

    openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
        -keyout "$tmp_key" \
        -out    "$tmp_crt" \
        -subj "/CN=gateway/OU=dev/O=agentk/L=local/ST=local/C=BR" \
        >/dev/null 2>&1

    openssl pkcs12 -export \
        -inkey "$tmp_key" \
        -in    "$tmp_crt" \
        -name  gateway \
        -out   "$keystore" \
        -passout "pass:${ks_pass}" \
        >/dev/null 2>&1

    rm -f "$tmp_key" "$tmp_crt"
    upsert_env "KEYSTORE_PASSWORD" "$ks_pass"
    log_ok "gateway-keystore.p12 gerado."
}

ensure_logs_dir() {
    mkdir -p "./Agentk-Sugest/logs"
    chmod 777 "./Agentk-Sugest/logs" || true
}

start_stack() {
    log_info "Subindo todos os servicos..."
    docker compose up -d --build
    log_ok "Stack iniciada."

    # Recarrega a configuração do nginx para forçar re-resolução de DNS.
    # Necessário quando o nginx sobe antes do Keycloak/oauth2-proxy estar prontos.
    log_info "Recarregando nginx (flush DNS)..."
    sleep 3
    docker exec nginx nginx -s reload 2>/dev/null && log_ok "nginx recarregado." || log_warn "nginx reload falhou (ignorado)."
}

print_summary() {
    local primary_host="${CUSTOM_DOMAIN:-agentk.local}"
    echo ""
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo -e "${GREEN}|                    STACK AGENTK PRONTA                      |${NC}"
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo ""
    echo -e "Aplicacao:      ${BOLD}https://${primary_host}/${NC}"
    echo -e "Keycloak Admin: ${BOLD}https://${primary_host}/keycloak/admin/${NC}"
    echo ""
    if [[ -z "${CUSTOM_DOMAIN:-}" ]]; then
        echo -e "Se agentk.local nao resolver no seu PC, adicione no /etc/hosts:"
        echo -e "${BOLD}${AGENTK_HOST_IP} agentk.local${NC}"
    else
        echo -e "Acesso alternativo por agentk.local (requer /etc/hosts):"
        echo -e "  ${BOLD}${AGENTK_HOST_IP} agentk.local${NC}"
        echo -e "  Arquivo de override gerado: ${BOLD}${COMPOSE_OVERRIDE_FILE}${NC}"
    fi
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
    configure_custom_domain
    load_env
    ensure_runtime_env
    load_env
    ensure_ssl_certificate
    generate_compose_override
    ensure_gateway_keystore
    ensure_logs_dir
    start_stack
    print_summary
}

main "$@"
