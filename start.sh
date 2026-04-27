#!/bin/bash

#############################################################################
# start.sh
#
# Orquestrador de startup em fases para a stack AgentK.
#
# O Docker Compose e declarativo e nao suporta pausas interativas.
# Este script encapsula o compose em 4 fases separadas e pausa entre
# as fases 3 e 4 para garantir que o Client Secret esteja configurado
# e que o usuario tenha criado ao menos uma conta no Keycloak antes
# de fechar o acesso via nginx + oauth2-proxy.
#
# FASE 1 -- Infraestrutura base (gateway, server, client, ollama)
# FASE 2 -- Keycloak (aguarda healthy + importa realm 'agentk' automaticamente)
# FASE 3 -- Pausa interativa: instrucoes + coleta do Client Secret
# FASE 4 -- Camada de autenticacao (oauth2-proxy + nginx)
#############################################################################

set -e

# ---------------------------------------------------------------------------
# Cores e helpers de log
# ---------------------------------------------------------------------------
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

ENV_FILE=".env"
DEFAULT_CLIENT_SECRET="oauth2-proxy-secret"

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
# Upsert de variavel no .env
# ---------------------------------------------------------------------------
upsert_env() {
    local key="$1" value="$2"
    if grep -qE "^${key}=" "$ENV_FILE" 2>/dev/null; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
    else
        echo "${key}=${value}" >> "$ENV_FILE"
    fi
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
    docker compose up -d keycloak
    wait_for_healthy "keycloak" 180
    log_success "Realm 'agentk' e client 'oauth2-proxy' importados automaticamente."
}

# ---------------------------------------------------------------------------
# FASE 3: Pausa interativa -- instrucoes ao usuario + coleta do Client Secret
# ---------------------------------------------------------------------------
phase3_interactive_setup() {
    log_step "FASE 3 -- Configuracao Interativa (acao necessaria)"

    local current_secret="${OAUTH2_PROXY_CLIENT_SECRET:-}"

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
    echo -e "   ${BOLD}http://localhost:8082/keycloak/admin/${NC}"
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
    if [[ -z "$current_secret" || "$current_secret" == "SEU_CLIENT_SECRET" ]]; then
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
    log_step "FASE 4 -- Camada de Autenticacao (oauth2-proxy + nginx)"
    log_info "Iniciando oauth2-proxy e nginx..."
    docker compose up -d oauth2-proxy nginx
    log_success "Nginx e oauth2-proxy ativos."
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
    echo -e "   Aplicacao AgentK : ${BOLD}https://agentk.local/${NC}"
    echo -e "   Keycloak Admin   : ${BOLD}https://agentk.local/keycloak/admin/${NC}"
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

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    echo ""
    echo -e "${BOLD}${BLUE}=================================================${NC}"
    echo -e "${BOLD}${BLUE}  Inicializador de Infraestrutura AgentK${NC}"
    echo -e "${BOLD}${BLUE}=================================================${NC}"
    echo ""

    log_step "PRE-REQUISITOS"
    if ! command -v docker &>/dev/null; then
        log_error "Docker nao encontrado. Instale antes de continuar."
        exit 1
    fi
    if ! docker compose version &>/dev/null; then
        log_error "Docker Compose plugin nao encontrado."
        exit 1
    fi
    log_success "Docker e Docker Compose disponiveis"

    ensure_env

    if [[ -f ./setup.sh ]]; then
        bash ./setup.sh
    else
        log_warn "setup.sh nao encontrado. Verifique se os certificados SSL existem em ./nginx/certs/"
    fi

    phase1_infrastructure
    phase2_keycloak
    phase3_interactive_setup
    phase4_auth_layer
    print_summary
}

main "$@"
