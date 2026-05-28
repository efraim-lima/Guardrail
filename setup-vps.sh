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

    if [[ "$missing" -eq 1 ]]; then exit 1; fi
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
# Senha do usuário k8s-admin (autentica prompts RISKY)
# ---------------------------------------------------------------------------
configure_k8s_admin() {
    local current_pass="${K8S_ADMIN_PASSWORD:-changeme}"
    local input_pass

    echo ""
    echo -e "${BOLD}Senha do administrador K8s (autorização de prompts RISKY)${NC}"
    echo -e "Esta senha será solicitada quando o Gateway classificar um prompt como RISKY."
    echo -e "Não é a mesma senha do painel Keycloak. Pressione ENTER para manter."
    echo ""

    read -r -s -p "Senha k8s-admin [${current_pass:0:3}***]: " input_pass || true
    echo ""
    [[ -n "${input_pass:-}" ]] && current_pass="$input_pass"

    upsert_env "K8S_ADMIN_PASSWORD" "$current_pass"
    upsert_env "K8S_ADMIN_USERNAME" "${K8S_ADMIN_USERNAME:-k8s-admin}"
    log_ok "Senha k8s-admin configurada."
}

# ---------------------------------------------------------------------------
# Detecta / solicita o hostname público da VPS (IP ou domínio)
# ---------------------------------------------------------------------------
is_ip_address() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

# Remove https:// ou http:// caso o usuário cole a URL completa
strip_scheme() {
    echo "$1" | sed -E 's|^https?://||' | sed 's|/.*||'
}

configure_vps_hostname() {
    local detected_ip
    detected_ip="$(resolve_public_ip)"

    echo ""
    echo -e "${BOLD}Configuração de hostname da VPS${NC}"
    echo ""

    if [[ -n "$detected_ip" ]]; then
        log_info "IP público detectado: ${detected_ip}"
    else
        log_warn "Não foi possível detectar o IP público automaticamente."
    fi

    # --- Domínio customizado (DuckDNS, Cloudflare, etc.) ---
    local current_domain
    current_domain="$(strip_scheme "${CUSTOM_DOMAIN:-}")"

    echo ""
    echo -e "  Domínio personalizado (DuckDNS, Cloudflare, etc.)"
    echo -e "  Exemplo: agentk-guardrail.duckdns.org"
    echo -e "  Deixe em branco para usar somente o IP."
    local input_domain
    read -r -p "Domínio [${current_domain:-nenhum}]: " input_domain || true
    [[ -n "${input_domain:-}" ]] && current_domain="$(strip_scheme "$input_domain")"

    # --- IP público ---
    local current_ip
    current_ip="${VPS_HOST_IP:-${detected_ip}}"
    local input_ip
    read -r -p "IP público [${current_ip:-<obrigatório se sem domínio>}]: " input_ip || true
    [[ -n "${input_ip:-}" ]] && current_ip="$input_ip"

    # Domínio tem precedência; IP é fallback e também fica salvo como VPS_HOST_IP
    if [[ -n "${current_domain:-}" ]]; then
        VPS_HOSTNAME="$current_domain"
        VPS_HOST_IP="${current_ip:-}"
    elif [[ -n "${current_ip:-}" ]]; then
        VPS_HOSTNAME="$current_ip"
        VPS_HOST_IP="$current_ip"
    else
        log_error "Informe ao menos o IP público ou um domínio. Abortando."
        exit 1
    fi

    upsert_env "VPS_HOSTNAME"  "$VPS_HOSTNAME"
    upsert_env "CUSTOM_DOMAIN" "${current_domain:-}"
    upsert_env "VPS_HOST_IP"   "${VPS_HOST_IP:-}"
    upsert_env "APP_PUBLIC_URL" "https://${VPS_HOSTNAME}"
    log_ok "Hostname principal: ${VPS_HOSTNAME}"
    [[ -n "${current_domain:-}" && -n "${VPS_HOST_IP:-}" ]] && \
        log_info "IP associado ao domínio: ${VPS_HOST_IP}"
}

# ---------------------------------------------------------------------------
# Corrige validPostLogoutRedirectUris do cliente oauth2-proxy no Keycloak
# em execução via kcadm.sh (Admin CLI embutido na imagem do Keycloak).
# Necessário quando o realm já foi importado com URIs antigas (agentk.local)
# e o hostname público mudou para VPS/domínio personalizado.
# ---------------------------------------------------------------------------
fix_keycloak_post_logout_uris() {
    local admin_user admin_pass token client_uuid
    admin_user="${KEYCLOAK_ADMIN:-admin}"
    admin_pass="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

    log_info "Sincronizando cliente oauth2-proxy no Keycloak com hostname: ${VPS_HOSTNAME}..."

    # Obtém token admin via REST (mais confiável que kcadm para operações de array)
    token=$(docker exec keycloak sh -c "
        curl -s -X POST http://localhost:8080/keycloak/realms/master/protocol/openid-connect/token \
          -d 'client_id=admin-cli&grant_type=password&username=${admin_user}&password=${admin_pass}' \
        | python3 -c \"import sys,json; print(json.load(sys.stdin).get('access_token',''))\"
    " 2>/dev/null || true)

    if [[ -z "$token" ]]; then
        log_warn "Não foi possível autenticar no Keycloak. Keycloak ainda não está saudável?"
        return 0
    fi

    # Autentica kcadm para as operações posteriores (agentk-internal, k8s-admin)
    docker exec keycloak \
        /opt/keycloak/bin/kcadm.sh config credentials \
        --server "http://localhost:8080/keycloak" \
        --realm master \
        --user "$admin_user" \
        --password "$admin_pass" 2>/dev/null || true

    # UUID do cliente oauth2-proxy
    client_uuid=$(docker exec keycloak sh -c "
        curl -s http://localhost:8080/keycloak/admin/realms/agentk/clients \
          -H 'Authorization: Bearer ${token}' \
        | python3 -c \"
import sys,json
cs=json.load(sys.stdin)
print(next((c['id'] for c in cs if c.get('clientId')=='oauth2-proxy'),''))
\"
    " 2>/dev/null || true)

    if [[ -z "$client_uuid" ]]; then
        log_warn "Cliente oauth2-proxy não encontrado. Realm ainda não importado?"
        return 0
    fi

    # Atualiza redirectUris, webOrigins e post.logout.redirect.uris com o hostname real
    docker exec keycloak sh -c "
        curl -s -X PUT http://localhost:8080/keycloak/admin/realms/agentk/clients/${client_uuid} \
          -H 'Authorization: Bearer ${token}' \
          -H 'Content-Type: application/json' \
          -d '{
            \"redirectUris\": [
              \"https://${VPS_HOSTNAME}/oauth2/callback\",
              \"https://agentk.local/oauth2/callback\"
            ],
            \"webOrigins\": [
              \"https://${VPS_HOSTNAME}\",
              \"https://agentk.local\"
            ],
            \"attributes\": {
              \"post.logout.redirect.uris\": \"*\"
            }
          }'
    " 2>/dev/null && log_ok "Cliente oauth2-proxy atualizado (redirectUri, webOrigin, post_logout)." \
                  || log_warn "Falha ao atualizar oauth2-proxy."

    # Garante que agentk-internal exista (realm pode ter sido importado sem ele)
    local existing
    existing=$(docker exec keycloak \
        /opt/keycloak/bin/kcadm.sh get clients -r agentk \
        --fields id,clientId 2>/dev/null \
        | python3 -c "
import sys, json
try:
    clients = json.load(sys.stdin)
    print(next(c['id'] for c in clients if c.get('clientId') == 'agentk-internal'))
except StopIteration:
    pass
" 2>/dev/null || true)

    if [[ -z "$existing" ]]; then
        log_info "Criando cliente agentk-internal no Keycloak..."
        docker exec keycloak \
            /opt/keycloak/bin/kcadm.sh create clients -r agentk \
            -s clientId=agentk-internal \
            -s 'name=AgentK Internal Validator' \
            -s enabled=true \
            -s publicClient=true \
            -s standardFlowEnabled=false \
            -s directAccessGrantsEnabled=true \
            -s protocol=openid-connect 2>/dev/null \
        && log_ok "Cliente agentk-internal criado." \
        || log_warn "Falha ao criar agentk-internal."
    else
        log_info "Cliente agentk-internal já existe."
    fi

    # Garante role k8s-admin
    docker exec keycloak \
        /opt/keycloak/bin/kcadm.sh create roles -r agentk \
        -s name=k8s-admin \
        -s 'description=Autoriza prompts RISKY' 2>/dev/null || true

    # Garante usuário k8s-admin com senha atualizada
    local k8s_pass="${K8S_ADMIN_PASSWORD:-changeme}"
    local user_id
    user_id=$(docker exec keycloak \
        /opt/keycloak/bin/kcadm.sh get users -r agentk -q username=k8s-admin \
        2>/dev/null \
        | python3 -c "
import sys, json
try:
    users = json.load(sys.stdin)
    print(users[0]['id']) if users else None
except Exception:
    pass
" 2>/dev/null || true)

    if [[ -z "$user_id" ]]; then
        log_info "Criando usuário k8s-admin..."
        docker exec keycloak \
            /opt/keycloak/bin/kcadm.sh create users -r agentk \
            -s username=k8s-admin \
            -s email=k8sadmin@agentk.internal \
            -s enabled=true \
            -s emailVerified=true \
            -s 'requiredActions=[]' 2>/dev/null || true
        user_id=$(docker exec keycloak \
            /opt/keycloak/bin/kcadm.sh get users -r agentk -q username=k8s-admin \
            2>/dev/null \
            | python3 -c "
import sys, json
try:
    users = json.load(sys.stdin)
    print(users[0]['id']) if users else None
except Exception:
    pass
" 2>/dev/null || true)
    fi

    if [[ -n "$user_id" ]]; then
        local k8s_admin_token
        # Usa REST API com JSON real — kcadm não serializa arrays vazios corretamente.
        # Sem isso, requiredActions persiste e o Direct Access Grant falha com
        # "Account is not fully set up" mesmo com senha correta.
        k8s_admin_token=$(docker exec keycloak sh -c "
          curl -s -X POST http://localhost:8080/keycloak/realms/master/protocol/openid-connect/token \\
            -d \"client_id=admin-cli&grant_type=password&username=${KEYCLOAK_ADMIN:-admin}&password=${KEYCLOAK_ADMIN_PASSWORD:-admin}\" \\
          | python3 -c \"import sys,json; print(json.load(sys.stdin).get('access_token',''))\"
        " 2>/dev/null || true)

        if [[ -n "$k8s_admin_token" ]]; then
            # Limpa required actions via PUT com JSON real
            docker exec keycloak sh -c "
              curl -s -X PUT http://localhost:8080/keycloak/admin/realms/agentk/users/${user_id} \\
                -H 'Authorization: Bearer ${k8s_admin_token}' \\
                -H 'Content-Type: application/json' \\
                -d '{\"requiredActions\":[],\"enabled\":true,\"emailVerified\":true}'
            " 2>/dev/null || true
            # Define senha permanente via REST API
            docker exec keycloak sh -c "
              curl -s -X PUT http://localhost:8080/keycloak/admin/realms/agentk/users/${user_id}/reset-password \\
                -H 'Authorization: Bearer ${k8s_admin_token}' \\
                -H 'Content-Type: application/json' \\
                -d '{\"type\":\"password\",\"value\":\"${k8s_pass}\",\"temporary\":false}'
            " 2>/dev/null || true
        else
            log_warn "Não foi possível obter token admin para limpar requiredActions via REST API."
        fi

        docker exec keycloak \
            /opt/keycloak/bin/kcadm.sh add-roles -r agentk \
            --uusername k8s-admin --rolename k8s-admin 2>/dev/null || true
        log_ok "Usuário k8s-admin configurado."
    else
        log_warn "Não foi possível criar/localizar usuário k8s-admin."
    fi
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

    # Monta alt_names cobrindo: domínio + IP (quando ambos disponíveis)
    local alt_names_block
    if is_ip_address "$VPS_HOSTNAME"; then
        # Modo IP-only
        alt_names_block="IP.1  = ${VPS_HOSTNAME}
IP.2  = 127.0.0.1"
    else
        # Modo domínio — inclui também o IP associado no SAN, se disponível
        alt_names_block="DNS.1 = ${VPS_HOSTNAME}
DNS.2 = localhost"
        if [[ -n "${VPS_HOST_IP:-}" ]] && ! is_ip_address "$VPS_HOSTNAME"; then
            alt_names_block+="
IP.1  = ${VPS_HOST_IP}
IP.2  = 127.0.0.1"
        fi
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
      - K8S_ADMIN_PASSWORD=\${K8S_ADMIN_PASSWORD:-changeme}

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
      - --cookie-samesite=none
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
# Gera gateway-keystore.p12 via openssl (sem necessidade de Java/keytool)
# ---------------------------------------------------------------------------
ensure_gateway_keystore() {
    local keystore="./gateway-keystore.p12"
    local ks_pass="${KEYSTORE_PASSWORD:-gateway-secret}"

    if [[ -f "$keystore" ]]; then
        log_info "gateway-keystore.p12 já existe."
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

    # Recarrega a configuração do nginx para forçar re-resolução de DNS.
    # Necessário quando o nginx sobe antes do Keycloak/oauth2-proxy estar prontos.
    log_info "Recarregando nginx (flush DNS)..."
    sleep 3
    docker exec nginx nginx -s reload 2>/dev/null && log_ok "nginx recarregado." || log_warn "nginx reload falhou (ignorado)."
}

# ---------------------------------------------------------------------------
# Resumo final
# ---------------------------------------------------------------------------
print_summary() {
    local base_url="https://${VPS_HOSTNAME}"

    echo ""
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo -e "${GREEN}|              STACK AGENTK PRONTA — VPS/CLOUD                |${NC}"
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo ""
    echo -e "Aplicação:      ${BOLD}${base_url}/${NC}"
    echo -e "Keycloak Admin: ${BOLD}${base_url}/keycloak/admin/${NC}"
    if [[ -n "${CUSTOM_DOMAIN:-}" && -n "${VPS_HOST_IP:-}" ]]; then
        echo ""
        echo -e "Acesso direto por IP também disponível:"
        echo -e "  https://${VPS_HOST_IP}/  (certificado válido apenas para ${CUSTOM_DOMAIN})"
    fi
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
    configure_k8s_admin
    load_env
    configure_vps_hostname
    load_env
    ensure_runtime_env
    load_env
    ensure_ssl_certificate
    generate_compose_override
    ensure_gateway_keystore
    ensure_logs_dir
    start_stack
    fix_keycloak_post_logout_uris
    print_summary
}

main "$@"
