#!/bin/bash

#############################################################################
# setup.sh
# 
# Script para:
#   1. Detectar IP atual da maquina e gravar no .env automaticamente
#   2. Gerar certificado SSL auto-assinado para o Nginx (com SAN de IP)
#   3. Registrar entrada DNS local em /etc/hosts
#
# O IP e detectado a cada execucao. Se mudar, o certificado e regenerado
# automaticamente com o novo IP nos SANs.
#############################################################################

set -e

# Cores para output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

# Configuracoes
CERTS_DIR="./nginx/certs"
CERT_FILE="${CERTS_DIR}/agentk.crt"
KEY_FILE="${CERTS_DIR}/agentk.key"
CERT_DAYS=365
CERT_CN="agentk.local"
SKIP_HOSTS_ENTRY="${SKIP_HOSTS_ENTRY:-0}"
HOSTS_FILE="/etc/hosts"
ENV_FILE=".env"
DEFAULT_CLIENT_SECRET="oauth2-proxy-secret"

# Cores e helpers de log
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
log_error()   { echo -e "${RED}[ERRO]${NC} $1"; }
log_step() {
    echo ""
    echo -e "${BOLD}${CYAN}=================================================${NC}"
    echo -e "${BOLD}${CYAN}  $1${NC}"
    echo -e "${BOLD}${CYAN}=================================================${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Detecta o IP da maquina pela rota padrao
# ---------------------------------------------------------------------------
resolve_agentk_host_ip() {
    local detected_ip=""

    if command -v ip >/dev/null 2>&1; then
        detected_ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}')"
    fi

    if [[ -z "$detected_ip" ]] && command -v hostname >/dev/null 2>&1; then
        detected_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi

    echo "${detected_ip:-127.0.0.1}"
}

# ---------------------------------------------------------------------------
# Upsert de chave=valor no .env (cria o arquivo se nao existir)
# ---------------------------------------------------------------------------
upsert_env() {
    local key="$1" value="$2"
    if [[ ! -f "$ENV_FILE" ]]; then
        [[ -f env.example ]] && cp env.example "$ENV_FILE" || touch "$ENV_FILE"
    fi
    # Remove a entrada antiga se existir e adiciona a nova ao final
    # Isso evita problemas com sed -i e regex complexos
    grep -vE "^${key}=" "$ENV_FILE" > "${ENV_FILE}.tmp" || true
    echo "${key}=${value}" >> "${ENV_FILE}.tmp"
    mv "${ENV_FILE}.tmp" "$ENV_FILE"
}

# ---------------------------------------------------------------------------
# Deteccao de IP e sincronizacao com .env
# Invalida o certificado se o IP mudou desde a ultima geracao.
# ---------------------------------------------------------------------------
sync_env_ip() {
    local detected_ip
    detected_ip="$(resolve_agentk_host_ip)"

    # Le o IP anterior gravado no .env (se existir)
    local previous_ip=""
    if [[ -f "$ENV_FILE" ]]; then
        previous_ip="$(grep -E '^AGENTK_HOST_IP=' "$ENV_FILE" 2>/dev/null | cut -d= -f2 | tr -d '"' || true)"
    fi

    # Grava o IP detectado no .env
    upsert_env "AGENTK_HOST_IP" "$detected_ip"
    # Documentacao Keycloak 24: KC_HOSTNAME_URL nao deve incluir o path
    upsert_env "KC_HOSTNAME_URL" "https://${detected_ip}"
    upsert_env "KC_HOSTNAME_ADMIN_URL" "https://${detected_ip}"

    log_info "IP detectado automaticamente: ${detected_ip}"
    log_success "AGENTK_HOST_IP=${detected_ip} gravado em ${ENV_FILE}"
    log_success "KC_HOSTNAME_URL=https://${detected_ip} gravado em ${ENV_FILE}"
    log_success "KC_HOSTNAME_ADMIN_URL=https://${detected_ip} gravado em ${ENV_FILE}"

    # Se o IP mudou e o certificado ja existe, remove para forcar regeracao
    # (o cert antigo nao teria o novo IP nos SANs)
    if [[ -n "$previous_ip" && "$previous_ip" != "$detected_ip" && -f "$CERT_FILE" ]]; then
        log_warn "IP mudou (${previous_ip} -> ${detected_ip}): removendo certificado antigo para regeracao..."
        rm -f "$CERT_FILE" "$KEY_FILE"
        log_warn "Certificado removido. Sera regerado com o novo IP nos SANs."
    fi

    # Exporta para uso no restante do script
    export AGENTK_HOST_IP="$detected_ip"
}

upsert_hosts_entry() {
    local entry="$1"

    if [[ $EUID -ne 0 ]]; then
        log_info "Permissao de root necessaria para editar $HOSTS_FILE"
        log_info "Executando atualizacao via sudo"
        sudo sed -i '/[[:space:]]agentk\.local\([[:space:]]\|$\)/d' "$HOSTS_FILE"
        echo "$entry" | sudo tee -a "$HOSTS_FILE" > /dev/null
    else
        sed -i '/[[:space:]]agentk\.local\([[:space:]]\|$\)/d' "$HOSTS_FILE"
        echo "$entry" >> "$HOSTS_FILE"
    fi
}

setup_hosts_entry() {
    if [[ "$SKIP_HOSTS_ENTRY" == "1" ]]; then
        log_info "SKIP_HOSTS_ENTRY=1 detectado: etapa de /etc/hosts foi ignorada."
        return 0
    fi

    local hosts_entry="${AGENTK_HOST_IP} agentk.local"

    log_info "Configurando entrada DNS local em $HOSTS_FILE..."

    if grep -qF "$hosts_entry" "$HOSTS_FILE" 2>/dev/null; then
        log_success "Entrada ja existe: $hosts_entry"
        return 0
    fi

    upsert_hosts_entry "$hosts_entry"
    log_success "Entrada adicionada: $hosts_entry"
}

# ---------------------------------------------------------------------------
# Garante que .env existe e carrega as variaveis no ambiente atual
# ---------------------------------------------------------------------------
ensure_env() {
    if [[ ! -f "$ENV_FILE" ]]; then
        log_warn "Arquivo .env nao encontrado."
        if [[ -f "env.example" ]]; then
            cp env.example "$ENV_FILE"
            log_success ".env criado a partir de env.example"
        else
            touch "$ENV_FILE"
            log_warn ".env vazio criado"
        fi
    fi
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE" 2>/dev/null || true
    set +a
}

# ---------------------------------------------------------------------------
# Aguarda o healthcheck de um container Docker ficar 'healthy'
# ---------------------------------------------------------------------------
wait_for_healthy() {
    local container="$1"
    local max_wait="${2:-180}"
    local elapsed=0
    log_info "Aguardando ${container} ficar healthy (max ${max_wait}s)..."
    while true; do
        local status
        status=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "absent")
        case "$status" in
            healthy)
                echo ""
                log_success "${container} esta healthy"
                return 0
                ;;
            unhealthy)
                echo ""
                log_error "${container} ficou unhealthy."
                log_error "Verifique os logs: docker logs ${container}"
                exit 1
                ;;
        esac
        if [[ $elapsed -ge $max_wait ]]; then
            echo ""
            log_error "Timeout (${max_wait}s) aguardando ${container}."
            log_error "Verifique: docker logs ${container}"
            exit 1
        fi
        printf "."
        sleep 5
        elapsed=$((elapsed + 5))
    done
}

# ---------------------------------------------------------------------------
# FASE 1: Infraestrutura base -- sem camada de autenticacao
# ---------------------------------------------------------------------------
phase1_infrastructure() {
    log_step "FASE 1 -- Infraestrutura Base"
    log_info "Servicos: agentk-gateway, agentk-server, agentk-client, ollama"
    log_warn "A camada de autenticacao ainda NAO esta ativa nesta fase."
    echo ""
    docker compose up -d --build agentk-gateway agentk-server agentk-client ollama
    log_success "Fase 1 concluida"
}

# ---------------------------------------------------------------------------
# FASE 2: Keycloak -- aguarda healthy e importacao automatica do realm
# ---------------------------------------------------------------------------
phase2_keycloak() {
    log_step "FASE 2 -- Keycloak (Identity Provider)"
    # Iniciamos nginx junto com keycloak para permitir acesso HTTPS imediato
    docker compose up -d keycloak nginx
    wait_for_healthy "keycloak" 180
    log_success "Keycloak e Nginx prontos para configuracao."
}

# ---------------------------------------------------------------------------
# FASE 3: Pausa interativa -- instrucoes ao usuario + coleta do Client Secret
# ---------------------------------------------------------------------------
phase3_interactive_setup() {
    log_step "FASE 3 -- Configuracao Interativa (acao necessaria)"

    local current_secret="${OAUTH2_PROXY_CLIENT_SECRET:-}"
    local detected_ip="${AGENTK_HOST_IP:-localhost}"

    echo -e "${YELLOW}+-------------------------------------------------------------+${NC}"
    echo -e "${YELLOW}|        ACAO NECESSARIA ANTES DE CONTINUAR                   |${NC}"
    echo -e "${YELLOW}+-------------------------------------------------------------+${NC}"
    echo ""
    echo -e " O Keycloak esta pronto. Antes de ativar o nginx e o oauth2-proxy,"
    echo -e " voce PRECISA criar ao menos ${BOLD}um usuario${NC} no Keycloak."
    echo -e " Sem isso, ninguem conseguira fazer login na aplicacao."
    echo ""
    echo -e "${CYAN}-------------------------------------------------------------${NC}"
    echo -e " ${BOLD}PASSO 1 -- Acesse o Keycloak Admin Console:${NC}"
    echo ""
    echo -e "   ${BOLD}https://${detected_ip}/keycloak/admin/${NC}"
    echo ""
    echo -e "   Login: ${BOLD}${KEYCLOAK_ADMIN:-admin}${NC}  /  Senha: ${BOLD}${KEYCLOAK_ADMIN_PASSWORD:-admin}${NC}"
    echo ""
    echo -e "${CYAN}-------------------------------------------------------------${NC}"
    echo -e " ${BOLD}PASSO 2 -- Crie um usuario (OBRIGATORIO):${NC}"
    echo ""
    echo -e "   Realm 'agentk' -> Users -> Add user"
    echo -e "   Defina nome, email e ative o usuario."
    echo -e "   Na aba ${BOLD}Credentials${NC}: defina uma senha (desative 'Temporary')."
    echo ""
    echo -e "${CYAN}-------------------------------------------------------------${NC}"
    echo -e " ${BOLD}PASSO 3 -- Client Secret (oauth2-proxy):${NC}"
    echo ""
    echo -e "   Secret padrao ja configurado: ${BOLD}${GREEN}${DEFAULT_CLIENT_SECRET}${NC}"
    echo ""
    echo -e "   Para usar um secret personalizado:"
    echo -e "   Clients -> oauth2-proxy -> Credentials -> Regenerate"
    echo -e "   Cole o novo valor quando solicitado abaixo."
    echo ""
    echo -e "${CYAN}-------------------------------------------------------------${NC}"
    echo ""

    local is_placeholder=false
    if [[ -z "$current_secret" || "$current_secret" == "SEU_CLIENT_SECRET" || "$current_secret" == "oauth2-proxy-secret" ]]; then
        is_placeholder=true
    fi

    if [[ "$is_placeholder" == false ]]; then
        echo -e " Client Secret atual no .env: ${BOLD}${current_secret}${NC}"
        echo -e " Pressione ${BOLD}[ENTER]${NC} para manter, ou cole um novo secret:"
    else
        echo -e " Pressione ${BOLD}[ENTER]${NC} para usar o secret padrao ${BOLD}(${DEFAULT_CLIENT_SECRET})${NC},"
        echo -e " ou cole um secret personalizado copiado do Keycloak:"
    fi
    echo ""
    read -r -p " -> Client Secret: " input_secret
    echo ""

    if [[ -n "$input_secret" ]]; then
        upsert_env "OAUTH2_PROXY_CLIENT_SECRET" "$input_secret"
        export OAUTH2_PROXY_CLIENT_SECRET="$input_secret"
        log_success "Client Secret atualizado no .env"
    elif [[ "$is_placeholder" == true ]]; then
        upsert_env "OAUTH2_PROXY_CLIENT_SECRET" "$DEFAULT_CLIENT_SECRET"
        export OAUTH2_PROXY_CLIENT_SECRET="$DEFAULT_CLIENT_SECRET"
        log_success "Client Secret padrao definido no .env: ${DEFAULT_CLIENT_SECRET}"
    else
        log_success "Mantendo Client Secret existente: ${current_secret}"
    fi

    echo ""
    echo -e " Pressione ${BOLD}[ENTER]${NC} quando tiver concluido a criacao do usuario"
    echo -e " para ativar o nginx e fechar o acesso publico..."
    read -r -p " -> Pronto? [ENTER para continuar] " _
    echo ""
}

# ---------------------------------------------------------------------------
# FASE 4: Camada de autenticacao -- oauth2-proxy + nginx
# ---------------------------------------------------------------------------
phase4_auth_layer() {
    log_step "FASE 4 -- Camada de Autenticacao (oauth2-proxy)"
    log_info "Iniciando oauth2-proxy..."
    docker compose up -d oauth2-proxy
    log_success "oauth2-proxy ativo."
    log_warn "TODO acesso a aplicacao agora exige autenticacao via Keycloak."
}

# ---------------------------------------------------------------------------
# Sumario final
# ---------------------------------------------------------------------------
print_summary() {
    echo ""
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo -e "${GREEN}|         STACK AGENTK INICIADA COM SUCESSO                   |${NC}"
    echo -e "${GREEN}+-------------------------------------------------------------+${NC}"
    echo ""
    echo -e " ${BOLD}Acesso principal (requer autenticacao Keycloak):${NC}"
    echo -e "   Aplicacao AgentK : ${BOLD}https://${AGENTK_HOST_IP}/ ${NC}"
    echo -e "   Keycloak Admin   : ${BOLD}https://${AGENTK_HOST_IP}/keycloak/admin/${NC}"
    echo ""
    echo -e " ${YELLOW}NOTA:${NC} Se estiver acessando de fora da VM, certifique-se que o IP ${BOLD}${AGENTK_HOST_IP}${NC}"
    echo -e " e alcancavel e que o seu navegador aceite o certificado auto-assinado."
    echo ""
    echo -e " ${BOLD}Endpoints de debug (localhost apenas):${NC}"
    echo -e "   Keycloak direto  : ${BOLD}http://localhost:8082/keycloak/${NC}"
    echo -e "   oauth2-proxy     : ${BOLD}http://localhost:4180/ping${NC}"
    echo -e "   Ollama           : ${BOLD}http://localhost:${OLLAMA_HOST_PORT:-11435}/${NC}"
    echo -e "   MCP Server       : ${BOLD}http://localhost:${AGENTK_MCP_HOST_PORT:-3334}/${NC}"
    echo ""
    echo -e " ${BOLD}Para parar:${NC}  docker compose down"
    echo ""
}

main() {
    echo ""
    echo -e "${BOLD}${BLUE}=================================================${NC}"
    echo -e "${BOLD}${BLUE}  Orquestrador de Infraestrutura Guardrail/AgentK${NC}"
    echo -e "${BOLD}${BLUE}=================================================${NC}"
    echo ""

    # Verificacoes basicas (Fail-Fast)
    if ! command -v docker &>/dev/null; then
        log_error "Docker nao encontrado. Instale antes de continuar."
        exit 1
    fi
    if ! docker compose version &>/dev/null; then
        log_error "Docker Compose plugin nao encontrado."
        exit 1
    fi

    ensure_env
    sync_env_ip

    # Criar diretorio para certificados
    if [[ ! -d "$CERTS_DIR" ]]; then
        log_info "Criando diretório de certificados: $CERTS_DIR"
        mkdir -p "$CERTS_DIR"
    fi
    
    # Gerar certificado auto-assinado se nao existir ou se IP mudou
    if [[ ! -f "$CERT_FILE" || ! -f "$KEY_FILE" ]]; then
        log_info "Gerando certificado SSL auto-assinado para IP: ${AGENTK_HOST_IP}..."
        
        local san_cfg
        san_cfg="$(mktemp)"
        cat > "$san_cfg" <<EOF
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
EOF

        openssl req -x509 -nodes -days "$CERT_DAYS" -newkey rsa:2048 \
            -keyout "$KEY_FILE" \
            -out "$CERT_FILE" \
            -config "$san_cfg" \
            -extensions v3_req 2>/dev/null

        rm -f "$san_cfg"
        log_success "Certificado gerado com sucesso."
    fi
    
    setup_hosts_entry

    # Execucao em Fases
    phase1_infrastructure
    phase2_keycloak
    phase3_interactive_setup
    phase4_auth_layer
    print_summary
}

main "$@"
exit $?
