#!/bin/bash

set -euo pipefail

# -----------------------------------------------------------------------------
# setup2.sh
# Objetivo: subir a stack Docker em instâncias VPS na nuvem (AWS, Oracle, GCP, etc.).
# Diferença do setup.sh: usa IP público/domínio real em vez de agentk.local,
# gera docker-compose.override.yaml para adaptar oauth2-proxy e Keycloak,
# e não exige edição de /etc/hosts no cliente.
# -----------------------------------------------------------------------------

BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error(){ echo -e "${RED}[ERRO]${NC} $1"; }

ENV_FILE=".env"
COMPOSE_OVERRIDE_FILE="docker-compose.override.yaml"
CERTS_DIR="./nginx/certs"
CERT_FILE="${CERTS_DIR}/agentk.crt"
KEY_FILE="${CERTS_DIR}/agentk.key"
CERT_DAYS=365
DEFAULT_CLIENT_SECRET="oauth2-proxy-secret"

# ---------------------------------------------------------------------------
# Pré-requisitos
# ---------------------------------------------------------------------------
require_tools() {
    local missing=0

    for tool in docker openssl curl; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            log_error "Ferramenta ausente: ${tool}. Instale antes de continuar."
            missing=1
        fi
    done

    if ! docker compose version >/dev/null 2>&1; then
        log_error "Plugin 'docker compose' não encontrado."
        missing=1
    fi

    [[ "$missing" -eq 1 ]] && exit 1
}

# ---------------------------------------------------------------------------
# Detecta o IP público da instância via IMDS (AWS/Oracle) ou serviço externo
# ---------------------------------------------------------------------------
resolve_public_ip() {
    local ip=""

    # AWS IMDSv1 — disponível apenas em instâncias EC2
    if ip="$(curl -sf --max-time 2 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null)" && [[ -n "$ip" ]]; then
        echo "$ip"
        return 0
    fi

    # Oracle Cloud IMDSv2
    if ip="$(curl -sf --max-time 2 -H "Authorization: Bearer Oracle" \
        http://169.254.169.254/opc/v2/instance/metadata/publicIp 2>/dev/null)" && [[ -n "$ip" ]]; then
        echo "$ip"
        return 0
    fi

    # Fallback genérico — qualquer nuvem com saída para internet
    for svc in "https://api.ipify.org" "https://checkip.amazonaws.com" "https://ifconfig.me"; do
        if ip="$(curl -sf --max-time 4 "$svc" 2>/dev/null)" && [[ -n "$ip" ]]; then
            echo "${ip}" | tr -d '[:space:]'
            return 0
        fi
    done

    echo ""
}

# ---------------------------------------------------------------------------
# Helpers .env
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# Credenciais Keycloak
# ---------------------------------------------------------------------------
configure_keycloak_credentials() {
    local current_user="${KEYCLOAK_ADMIN:-admin}"
    local current_pass="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
    local input_user input_pass

    echo ""
    echo -e "${BOLD}Configuração do admin Keycloak${NC}"
    echo -e "Pressione ENTER para manter os valores atuais."
    echo ""

    read -r -p "Usuário admin [${current_user}]: " input_user || true
    read -r -p "Senha admin   [${current_pass}]: " input_pass || true

    [[ -n "${input_user:-}" ]] && current_user="$input_user"
    [[ -n "${input_pass:-}" ]] && current_pass="$input_pass"

    upsert_env "KEYCLOAK_ADMIN"          "$current_user"
    upsert_env "KEYCLOAK_ADMIN_PASSWORD" "$current_pass"
    upsert_env "OAUTH2_PROXY_CLIENT_SECRET" "$DEFAULT_CLIENT_SECRET"

    log_ok "Credenciais do Keycloak atualizadas."
}

# ---------------------------------------------------------------------------
# Detecta / solicita o hostname público da VPS (IP ou domínio)
# ---------------------------------------------------------------------------
is_ip_address() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

configure_vps_hostname() {
    local detected_ip
    detected_ip="$(resolve_public_ip)"

    local current_hostname="${VPS_HOSTNAME:-${detected_ip}}"

    echo ""
    echo -e "${BOLD}Hostname público da VPS${NC}"
    echo -e "Use o IP público da instância ou um domínio registrado (ex: meuservidor.com)."
    echo -e "Pressione ENTER para aceitar o valor detectado/atual."
    echo ""

    if [[ -n "$detected_ip" ]]; then
        log_info "IP público detectado: ${detected_ip}"
    else
        log_warn "Não foi possível detectar o IP público automaticamente."
        current_hostname="${VPS_HOSTNAME:-}"
    fi

    local input_hostname
    read -r -p "Hostname [${current_hostname:-<obrigatório>}]: " input_hostname || true

    if [[ -n "${input_hostname:-}" ]]; then
        current_hostname="$input_hostname"
    fi

    if [[ -z "${current_hostname:-}" ]]; then
        log_error "Hostname não informado. Abortando."
        exit 1
    fi

    VPS_HOSTNAME="$current_hostname"
    upsert_env "VPS_HOSTNAME" "$VPS_HOSTNAME"
    log_ok "Hostname configurado: ${VPS_HOSTNAME}"
}

# ---------------------------------------------------------------------------
# Variáveis de rede adicionais no .env
# ---------------------------------------------------------------------------
ensure_runtime_env() {
    upsert_env "HOST_BIND_IP"                    "${HOST_BIND_IP:-0.0.0.0}"
    upsert_env "OAUTH2_PROXY_TRUSTED_PROXY_IP_1" "${OAUTH2_PROXY_TRUSTED_PROXY_IP_1:-172.16.0.0/12}"
    upsert_env "OAUTH2_PROXY_TRUSTED_PROXY_IP_2" "${OAUTH2_PROXY_TRUSTED_PROXY_IP_2:-10.0.0.0/8}"
    upsert_env "OAUTH2_PROXY_TRUSTED_PROXY_IP_3" "${OAUTH2_PROXY_TRUSTED_PROXY_IP_3:-192.168.0.0/16}"
    log_ok "Variáveis de rede configuradas."
}

# ---------------------------------------------------------------------------
# Certificado SSL auto-assinado para o hostname público
# ---------------------------------------------------------------------------
ensure_ssl_certificate() {
    mkdir -p "$CERTS_DIR"

    if [[ -f "$CERT_FILE" && -f "$KEY_FILE" ]]; then
        # Verifica se o cert existente já foi emitido para o hostname atual
        local cert_cn
        cert_cn="$(openssl x509 -noout -subject -in "$CERT_FILE" 2>/dev/null | sed 's/.*CN\s*=\s*//' | tr -d ' ')"

        if [[ "$cert_cn" == "$VPS_HOSTNAME" ]]; then
            log_info "Certificado SSL já existe para ${VPS_HOSTNAME}."
            return 0
        else
            log_warn "Certificado existente (CN=${cert_cn}) difere do hostname atual (${VPS_HOSTNAME}). Regenerando..."
            rm -f "$CERT_FILE" "$KEY_FILE"
        fi
    fi

    log_info "Gerando certificado SSL para ${VPS_HOSTNAME}..."

    local san_cfg
    san_cfg="$(mktemp)"

    # Monta a seção alt_names corretamente: IP ou DNS conforme o valor do hostname
    local alt_names_block
    if is_ip_address "$VPS_HOSTNAME"; then
        alt_names_block="IP.1  = ${VPS_HOSTNAME}
IP.2  = 127.0.0.1"
    else
        alt_names_block="DNS.1 = ${VPS_HOSTNAME}
DNS.2 = localhost"
    fi

    cat > "$san_cfg" <<CFG
[req]
distinguished_name = req_distinguished_name
x509_extensions    = v3_req
prompt             = no

[req_distinguished_name]
CN = ${VPS_HOSTNAME}

[v3_req]
subjectAltName = @alt_names
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[alt_names]
${alt_names_block}
CFG

    openssl req -x509 -nodes -days "$CERT_DAYS" -newkey rsa:2048 \
        -keyout "$KEY_FILE" \
        -out   "$CERT_FILE" \
        -config "$san_cfg" \
        -extensions v3_req >/dev/null 2>&1

    rm -f "$san_cfg"
    log_ok "Certificado SSL gerado para ${VPS_HOSTNAME}."
}

# ---------------------------------------------------------------------------
# Gera docker-compose.override.yaml substituindo agentk.local pelo hostname real
# Apenas keycloak e oauth2-proxy têm referências hardcoded ao hostname.
# O nginx.conf já usa server_name com catch-all (_), não precisa de override.
# ---------------------------------------------------------------------------
generate_compose_override() {
    log_info "Gerando ${COMPOSE_OVERRIDE_FILE}..."

    local trusted_ip1="${OAUTH2_PROXY_TRUSTED_PROXY_IP_1:-172.16.0.0/12}"
    local trusted_ip2="${OAUTH2_PROXY_TRUSTED_PROXY_IP_2:-10.0.0.0/8}"
    local trusted_ip3="${OAUTH2_PROXY_TRUSTED_PROXY_IP_3:-192.168.0.0/16}"
    local client_id="${OAUTH2_PROXY_CLIENT_ID:-oauth2-proxy}"
    local client_secret="${OAUTH2_PROXY_CLIENT_SECRET:-oauth2-proxy-secret}"
    local cookie_secret="${OAUTH2_PROXY_COOKIE_SECRET:-agentktccsecretkey1234567890abcd}"

    cat > "$COMPOSE_OVERRIDE_FILE" <<OVERRIDE
# =============================================================================
# docker-compose.override.yaml — gerado automaticamente por setup2.sh
# Substitui referências ao hostname agentk.local pelo hostname real da VPS.
# NÃO edite manualmente; execute setup2.sh novamente para regenerar.
# Hostname configurado: ${VPS_HOSTNAME}
# Gerado em: $(date -u '+%Y-%m-%dT%H:%M:%SZ')
# =============================================================================

services:

  # ---------------------------------------------------------------------------
  # Keycloak: KC_HOSTNAME deve refletir o hostname público para que o Keycloak
  # construa URLs de redirect corretas (Google OAuth2, OIDC discovery, etc.).
  # ---------------------------------------------------------------------------
  keycloak:
    environment:
      - KC_BOOTSTRAP_ADMIN_USERNAME=\${KEYCLOAK_ADMIN:-admin}
      - KC_BOOTSTRAP_ADMIN_PASSWORD=\${KEYCLOAK_ADMIN_PASSWORD:-admin}
      - KC_DB=dev-file
      - KC_HOSTNAME=${VPS_HOSTNAME}
      - KC_HOSTNAME_STRICT=false
      - KC_HTTP_RELATIVE_PATH=/keycloak
      - KC_PROXY_HEADERS=xforwarded
      - KC_HTTP_ENABLED=true
      - KC_HEALTH_ENABLED=true
      - KC_IMPORT=/tmp/realm-agentk.json
      - GOOGLE_CLIENT_ID=\${GOOGLE_CLIENT_ID:-CHANGE_ME}
      - GOOGLE_CLIENT_SECRET=\${GOOGLE_CLIENT_SECRET:-CHANGE_ME}

  # ---------------------------------------------------------------------------
  # OAuth2 Proxy: todas as URLs públicas substituídas pelo hostname real.
  # As URLs internas (keycloak:8080) permanecem iguais — tráfego container-a-container.
  # ---------------------------------------------------------------------------
  oauth2-proxy:
    command:
      - --http-address=0.0.0.0:4180
      - --upstream=http://agentk-client:8501
      - --provider=oidc
      - --client-id=${client_id}
      - --client-secret=${client_secret}
      - --oidc-issuer-url=https://${VPS_HOSTNAME}/keycloak/realms/agentk
      - --skip-oidc-discovery=true
      - --login-url=https://${VPS_HOSTNAME}/keycloak/realms/agentk/protocol/openid-connect/auth
      - --redeem-url=http://keycloak:8080/keycloak/realms/agentk/protocol/openid-connect/token
      - --oidc-jwks-url=http://keycloak:8080/keycloak/realms/agentk/protocol/openid-connect/certs
      - --redirect-url=https://${VPS_HOSTNAME}/oauth2/callback
      - --reverse-proxy=true
      - --trusted-proxy-ip=${trusted_ip1}
      - --trusted-proxy-ip=${trusted_ip2}
      - --trusted-proxy-ip=${trusted_ip3}
      - --cookie-secret=${cookie_secret}
      - --cookie-secure=true
      - --cookie-samesite=lax
      - --session-cookie-minimal=true
      - --insecure-oidc-allow-unverified-email=true
      - --skip-auth-route=GET=^/favicon\.ico\$
      - --email-domain=*
      - --skip-provider-button=true
      - --oidc-extra-audience=${client_id}
      - --whitelist-domain=${VPS_HOSTNAME}
      - --backend-logout-url=http://keycloak:8080/keycloak/realms/agentk/protocol/openid-connect/logout
OVERRIDE

    log_ok "${COMPOSE_OVERRIDE_FILE} gerado."
}

# ---------------------------------------------------------------------------
# Diretório de logs do cliente
# ---------------------------------------------------------------------------
ensure_logs_dir() {
    mkdir -p "./Agentk-Sugest/logs"
    chmod 777 "./Agentk-Sugest/logs" || true
}

# ---------------------------------------------------------------------------
# Sobe a stack (docker compose mescla automaticamente o override)
# ---------------------------------------------------------------------------
start_stack() {
    log_info "Subindo todos os serviços..."
    docker compose up -d --build
    log_ok "Stack iniciada."
}

# ---------------------------------------------------------------------------
# Resumo final
# ---------------------------------------------------------------------------
print_summary() {
    local proto="https"
    local base_url="${proto}://${VPS_HOSTNAME}"

    echo ""
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo -e "${GREEN}|              STACK AGENTK PRONTA — VPS/CLOUD                |${NC}"
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo ""
    echo -e "Aplicação:      ${BOLD}${base_url}/${NC}"
    echo -e "Keycloak Admin: ${BOLD}${base_url}/keycloak/admin/${NC}"
    echo ""
    echo -e "${YELLOW}ATENÇÃO — Certificado auto-assinado:${NC}"
    echo -e "  O navegador exibirá aviso de segurança. Para produção, configure"
    echo -e "  um certificado válido (ex: Let's Encrypt com Certbot)."
    echo ""
    echo -e "Credenciais admin Keycloak:"
    echo -e "  Usuário: ${BOLD}${KEYCLOAK_ADMIN:-admin}${NC}"
    echo -e "  Senha:   ${BOLD}${KEYCLOAK_ADMIN_PASSWORD:-admin}${NC}"
    echo ""
    echo -e "Regras de firewall necessárias (portas TCP inbound):"
    echo -e "  ${BOLD}443${NC}  — HTTPS (aplicação + Keycloak via Nginx)"
    echo -e "  ${BOLD}80${NC}   — HTTP  (redirect automático para HTTPS)"
    echo -e "  ${BOLD}8082${NC} — Keycloak direto (opcional, para debug)"
    echo ""
    echo -e "Parar tudo: ${BOLD}docker compose down${NC}"
    echo ""
    echo -e "Arquivo de override gerado: ${BOLD}${COMPOSE_OVERRIDE_FILE}${NC}"
    echo -e "  (mesclado automaticamente pelo 'docker compose')"
    echo ""
}

# ---------------------------------------------------------------------------
# Fluxo principal
# ---------------------------------------------------------------------------
main() {
    require_tools
    ensure_env_file
    load_env
    configure_keycloak_credentials
    load_env
    configure_vps_hostname
    load_env
    ensure_runtime_env
    load_env
    ensure_ssl_certificate
    generate_compose_override
    ensure_logs_dir
    start_stack
    print_summary
}

main "$@"
