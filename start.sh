#!/bin/bash

#############################################################################
# start.sh
#
# Script wrapper que executa setup.sh e depois inicia os containers Docker
#
#############################################################################

set -e

BLUE='\033[0;34m'
GREEN='\033[0;32m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

main() {
    echo ""
    log_info "███████████████████████████████████████████████"
    log_info "  Inicializador de Infraestrutura AgentK"
    log_info "███████████████████████████████████████████████"
    echo ""
    
    # ETAPA 1: Executar setup.sh para gerar certificados
    log_info "ETAPA 1: Gerando certificados SSL..."
    if [[ -f ./setup.sh ]]; then
        bash ./setup.sh
        log_success "Certificados SSL gerados"
    else
        log_info "✗ setup.sh não encontrado. Continuando sem gerar certificados..."
    fi
    echo ""
    
    # ETAPA 2: Verificar se docker-compose está disponível
    log_info "ETAPA 2: Verificando Docker Compose..."
    if ! command -v docker &> /dev/null; then
        echo "✗ Docker não está instalado ou não está no PATH"
        exit 1
    fi
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        echo "✗ Docker Compose não está disponível"
        exit 1
    fi
    log_success "Docker Compose disponível"
    echo ""
    
    # ETAPA 3: Iniciar containers
    log_info "ETAPA 3: Iniciando containers Docker..."
    log_info "Executando: docker compose up -d --build"
    echo ""
    
    docker compose up -d --build
    
    echo ""
    log_success "███████████████████████████████████████████████"
    log_success "  Infraestrutura iniciada com sucesso!"
    log_success "███████████████████████████████████████████████"
    echo ""
    log_info "Serviços em execução:"
    docker compose ps
    echo ""
    log_info "Próximos passos:"
    echo "  - Acessar app via domínio: https://agentk.local"
    echo "  - Acessar app via IP da VM: https://<IP_DA_VM>"
    echo "  - Acessar AgentK Client direto: http://<IP_DA_VM>:8501"
    echo "  - Acessar MCP Server direto: http://<IP_DA_VM>:${AGENTK_MCP_HOST_PORT:-3334}"
    echo "  - Acessar Ollama: http://<IP_DA_VM>:${OLLAMA_HOST_PORT:-11435}"
    echo "  - Acessar Keycloak: http://<IP_DA_VM>:8082"
    echo "  - Acessar Gateway webhook: http://localhost:8081"
    echo ""
    log_info "Para parar os serviços:"
    echo "  docker compose down"
    echo ""
}

main "$@"
exit $?
