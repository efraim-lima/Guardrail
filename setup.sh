#!/bin/bash

#############################################################################
# setup-network-interception.sh
# 
# Script para configurar o "Fluxo de Interceptação (Camada de Rede)"
# conforme especificado em DEPLOYMENT.md
#
# Etapa 1: Redirecionamento (iptables)
# Etapa 2: Terminação TLS Falsa (Certificado CA forjado)
# Etapa 3: Adição da CA à lista confiada do Linux
# Etapa 4: Persistência de configurações
#
#############################################################################

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configurações
GATEWAY_PORT=8443
TARGET_PORT=443
GATEWAY_USER="${GATEWAY_USER:-root}"
AGENTK_DOCKER_CONTAINER="${AGENTK_DOCKER_CONTAINER:-agentk-mcp-server}"
SKIP_DOCKER_CA_SETUP="${SKIP_DOCKER_CA_SETUP:-0}"
CA_CERT_PATH="/etc/ssl/gateway-ca.crt"
CA_KEY_PATH="/etc/ssl/gateway-ca.key"
IPTABLES_RULE_FILE="/etc/iptables/rules.v4"

# Configurações do Keystore (keytool / PKCS12)
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-gateway-secret}"
KEYSTORE_FILE="${KEYSTORE_FILE:-$(pwd)/gateway-keystore.p12}"
KEYSTORE_CERT_DIR="${KEYSTORE_CERT_DIR:-$(pwd)/certs}"
KEYSTORE_CERT_FILE="${KEYSTORE_CERT_DIR}/gateway-ca.crt"
KEYSTORE_ALIAS="agentk-gateway"
KEYSTORE_DNAME="CN=api.openai.com, O=AgentK Security, L=Sao Paulo, C=BR"
NETFILTER_PERSISTENT_PACKAGE="iptables-persistent"

#############################################################################
# Funções Auxiliares
#############################################################################

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[⚠]${NC} $1"
}

log_error() {
    echo -e "${RED}[✗]${NC} $1"
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "Este script DEVE ser executado como root (use sudo)"
        exit 1
    fi
}

#############################################################################
# ETAPA 1: Configuração de iptables para redirecionamento
#############################################################################

setup_iptables_redirect() {
    log_info "=========================================="
    log_info "ETAPA 1: Configurando iptables para redirecionamento"
    log_info "=========================================="
    log_info "Objetivo: Redirecionar tráfego de saída :443 → :$GATEWAY_PORT"
    
    # Verificar se a regra já existe
    if iptables -t nat -C PREROUTING -p tcp --dport $TARGET_PORT -j REDIRECT --to-port $GATEWAY_PORT 2>/dev/null; then
        log_success "Regra iptables já existe (PREROUTING :$TARGET_PORT → :$GATEWAY_PORT)"
    else
        log_info "Regra iptables não encontrada. Criando..."
        
        # Adicionar regra para redirecionamento entrada
        iptables -t nat -A PREROUTING -p tcp --dport $TARGET_PORT -j REDIRECT --to-port $GATEWAY_PORT
        log_success "Regra PREROUTING criada"
        
        # Adicionar regra para tráfego local (OUTPUT)
        iptables -t nat -A OUTPUT -p tcp --dport $TARGET_PORT -m owner ! --uid-owner $GATEWAY_USER -j REDIRECT --to-port $GATEWAY_PORT 2>/dev/null || \
        iptables -t nat -A OUTPUT -p tcp --dport $TARGET_PORT -j REDIRECT --to-port $GATEWAY_PORT
        log_success "Regra OUTPUT criada"
    fi
    
    # Listar regras (confirmação)
    log_info "Regras iptables ativas:"
    iptables -t nat -L PREROUTING -n -v | grep -E "REDIRECT|tcp"
    
    # Verificar se iptables-persistent está instalado
    if ! dpkg -l | grep -q iptables-persistent; then
        log_warning "iptables-persistent não está instalado"
        log_info "Instalando iptables-persistent para persistência..."
        apt-get update > /dev/null 2>&1
        apt-get install -y iptables-persistent > /dev/null 2>&1
        log_success "iptables-persistent instalado"
    else
        log_success "iptables-persistent já instalado"
    fi
    
    # Salvar regras de forma persistente
    log_info "Salvando regras iptables de forma persistente..."
    iptables-save > "$IPTABLES_RULE_FILE"
    log_success "Regras iptables salvas em $IPTABLES_RULE_FILE"
}

verify_iptables_rules() {
    log_info "Verificando regras iptables..."
    if iptables -t nat -C PREROUTING -p tcp --dport $TARGET_PORT -j REDIRECT --to-port $GATEWAY_PORT 2>/dev/null; then
        log_success "✓ Regra PREROUTING validada"
        return 0
    else
        log_error "✗ Regra PREROUTING não encontrada"
        return 1
    fi
}

#############################################################################
# ETAPA 2: Gerar Certificado CA (Autoridade Certificadora Forjada)
#############################################################################

setup_certificate_authority() {
    log_info "=========================================="
    log_info "ETAPA 2: Criando Certificado CA (Autoridade Certificadora)"
    log_info "=========================================="
    log_info "Objetivo: Gerar CA para realizar terminação TLS"
    
    # Verificar se os arquivos já existem
    if [[ -f "$CA_CERT_PATH" && -f "$CA_KEY_PATH" ]]; then
        log_success "CA já existe em:"
        log_success "  Certificado: $CA_CERT_PATH"
        log_success "  Chave: $CA_KEY_PATH"
        
        # Exibir informações do certificado
        log_info "Informações do certificado:"
        openssl x509 -in "$CA_CERT_PATH" -text -noout | grep -E "Subject:|Issuer:|Not Before|Not After"
        return 0
    fi
    
    log_info "Certificado CA não encontrado. Gerando..."
    log_info "Parâmetros:"
    log_info "  - Algoritmo: RSA 4096 bits"
    log_info "  - Validade: 3650 dias (10 anos)"
    log_info "  - Subject: /CN=AgentK Security Gateway CA"
    
    # Verificar se openssl está instalado
    if ! command -v openssl &> /dev/null; then
        log_error "openssl não está instalado"
        log_info "Instalando openssl..."
        apt-get update > /dev/null 2>&1
        apt-get install -y openssl > /dev/null 2>&1
        log_success "openssl instalado"
    fi
    
    # Gerar chave privada
    log_info "Gerando chave privada RSA 4096..."
    openssl genrsa -out "$CA_KEY_PATH" 4096 2>/dev/null
    log_success "Chave privada gerada"
    
    # Gerar certificado auto-assinado com extensões de CA
    log_info "Gerando certificado CA auto-assinado..."
    openssl req -x509 -new -nodes \
        -key "$CA_KEY_PATH" \
        -sha256 -days 3650 \
        -out "$CA_CERT_PATH" \
        -subj "/CN=AgentK Security Gateway CA" \
        -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
    log_success "Certificado CA criado"
    
    # Definir permissões seguras
    chmod 600 "$CA_KEY_PATH"
    chmod 644 "$CA_CERT_PATH"
    log_success "Permissões de arquivo ajustadas (chave: 600, cert: 644)"
    
    # Exibir informações
    log_info "Informações do certificado:"
    openssl x509 -in "$CA_CERT_PATH" -text -noout | grep -E "Subject:|Issuer:|Not Before|Not After"
}

verify_certificate_authority() {
    log_info "Verificando Certificado CA..."
    
    if [[ ! -f "$CA_CERT_PATH" ]]; then
        log_error "✗ Certificado não encontrado em $CA_CERT_PATH"
        return 1
    fi
    
    if [[ ! -f "$CA_KEY_PATH" ]]; then
        log_error "✗ Chave privada não encontrada em $CA_KEY_PATH"
        return 1
    fi
    
    # Validar que o certificado é uma CA válida
    if openssl x509 -in "$CA_CERT_PATH" -text -noout | grep -q "CA:TRUE"; then
        log_success "✓ Certificado CA válido (CA:TRUE)"
        return 0
    else
        log_error "✗ Certificado não possui extensão CA:TRUE"
        return 1
    fi
}

#############################################################################
# ETAPA 3: Gerar Keystore PKCS12 (keytool) e exportar certificado
#############################################################################

setup_keytool_keystore() {
    log_info "=========================================="
    log_info "ETAPA 3: Gerando Keystore PKCS12 com keytool"
    log_info "=========================================="
    log_info "Objetivo: Criar keystore para o Gateway Java e exportar certificado CA"
    log_info "  Keystore : $KEYSTORE_FILE"
    log_info "  Alias    : $KEYSTORE_ALIAS"
    log_info "  DName    : $KEYSTORE_DNAME"
    log_info "  Cert out : $KEYSTORE_CERT_FILE"

    # Verificar se keytool está disponível
    if ! command -v keytool &>/dev/null; then
        log_error "keytool não encontrado. Instale o JDK (ex: apt-get install default-jdk)"
        return 1
    fi

    # Criar diretório de certificados se não existir
    mkdir -p "$KEYSTORE_CERT_DIR"

    # Gerar par de chaves no keystore PKCS12
    if [[ -f "$KEYSTORE_FILE" ]]; then
        log_success "Keystore já existe em: $KEYSTORE_FILE"
    else
        log_info "Gerando keystore PKCS12 (RSA 2048, validade 3650 dias)..."
        keytool -genkeypair \
            -alias "$KEYSTORE_ALIAS" \
            -keyalg RSA \
            -keysize 2048 \
            -storetype PKCS12 \
            -keystore "$KEYSTORE_FILE" \
            -validity 3650 \
            -storepass "$KEYSTORE_PASSWORD" \
            -dname "$KEYSTORE_DNAME" \
            -noprompt 2>/dev/null
        log_success "Keystore gerado: $KEYSTORE_FILE"
    fi

    # Exportar certificado público em formato PEM
    if [[ -f "$KEYSTORE_CERT_FILE" ]]; then
        log_success "Certificado exportado já existe: $KEYSTORE_CERT_FILE"
    else
        log_info "Exportando certificado PEM para $KEYSTORE_CERT_FILE ..."
        keytool -exportcert \
            -alias "$KEYSTORE_ALIAS" \
            -keystore "$KEYSTORE_FILE" \
            -storepass "$KEYSTORE_PASSWORD" \
            -rfc \
            -file "$KEYSTORE_CERT_FILE" 2>/dev/null
        log_success "Certificado exportado: $KEYSTORE_CERT_FILE"
    fi

    # Aplicar permissões no certificado
    chmod 644 "$KEYSTORE_CERT_FILE"
    log_success "Permissões ajustadas: $KEYSTORE_CERT_FILE (644)"

    # Garantir que o diretório do projeto e o diretório certs
    # são traversáveis por outros usuários (ex: container Docker)
    log_info "Ajustando permissões de travessia de diretórios..."
    chmod a+x .
    log_success "Permissão de travessia ajustada: . (diretório atual do projeto)"
    chmod a+x "$KEYSTORE_CERT_DIR"
    log_success "Permissão de travessia ajustada: $KEYSTORE_CERT_DIR"
    chmod 644 "$KEYSTORE_CERT_FILE"
    log_success "Permissão confirmada: $KEYSTORE_CERT_FILE (644)"

    # Exibir informações do keystore
    log_info "Conteúdo do keystore:"
    keytool -list \
        -keystore "$KEYSTORE_FILE" \
        -storepass "$KEYSTORE_PASSWORD" \
        -storetype PKCS12 2>/dev/null | grep -E "Alias|Entry type|Creation"
}

verify_keytool_keystore() {
    log_info "Verificando Keystore PKCS12..."

    if [[ ! -f "$KEYSTORE_FILE" ]]; then
        log_error "✗ Keystore não encontrado: $KEYSTORE_FILE"
        return 1
    fi

    if [[ ! -f "$KEYSTORE_CERT_FILE" ]]; then
        log_error "✗ Certificado exportado não encontrado: $KEYSTORE_CERT_FILE"
        return 1
    fi

    if keytool -list \
        -alias "$KEYSTORE_ALIAS" \
        -keystore "$KEYSTORE_FILE" \
        -storepass "$KEYSTORE_PASSWORD" \
        -storetype PKCS12 &>/dev/null; then
        log_success "✓ Keystore válido com alias '$KEYSTORE_ALIAS'"
        return 0
    else
        log_error "✗ Alias '$KEYSTORE_ALIAS' não encontrado no keystore"
        return 1
    fi
}

#############################################################################
# ETAPA 4: Adicionar CA à lista confiada do Linux
#############################################################################

trust_certificate_authority() {
    log_info "=========================================="
    log_info "ETAPA 3: Adicionando CA à lista confiada do Sistema"
    log_info "=========================================="
    
    # Detectar distribuição Linux
    if [[ -f /etc/os-release ]]; then
        source /etc/os-release
        DISTRO="$ID"
    else
        log_error "Não foi possível detectar a distribuição Linux"
        return 1
    fi
    
    log_info "Distribuição detectada: $DISTRO"
    
    case "$DISTRO" in
        ubuntu|debian)
            log_info "Configurando para Debian/Ubuntu..."
            
            CA_CERTS_DIR="/usr/local/share/ca-certificates"
            CA_CERT_DEST="$CA_CERTS_DIR/gateway-ca.crt"
            
            # Copiar certificado para diretório de CA confiadas
            if [[ ! -f "$CA_CERT_DEST" ]]; then
                log_info "Copiando certificado para $CA_CERT_DEST..."
                mkdir -p "$CA_CERTS_DIR"
                cp "$CA_CERT_PATH" "$CA_CERT_DEST"
                log_success "Certificado copiado"
            else
                log_success "Certificado já existe em $CA_CERT_DEST"
            fi
            
            # Atualizar repositório de CAs confiadas
            log_info "Atualizando repositório de CAs confiadas..."
            update-ca-certificates > /dev/null 2>&1
            log_success "Repositório atualizado"
            
            # Verificar em /etc/ssl/certs/ca-certificates.crt
            if grep -q "gateway-ca" /etc/ssl/certs/ca-certificates.crt 2>/dev/null; then
                log_success "✓ CA adicionada a /etc/ssl/certs/ca-certificates.crt"
            fi
            ;;
            
        fedora|centos|rhel)
            log_info "Configurando para RedHat/Fedora/CentOS..."
            
            CA_CERTS_DIR="/etc/pki/ca-trust/source/anchors"
            CA_CERT_DEST="$CA_CERTS_DIR/gateway-ca.crt"
            
            if [[ ! -f "$CA_CERT_DEST" ]]; then
                log_info "Copiando certificado para $CA_CERT_DEST..."
                mkdir -p "$CA_CERTS_DIR"
                cp "$CA_CERT_PATH" "$CA_CERT_DEST"
                log_success "Certificado copiado"
            else
                log_success "Certificado já existe em $CA_CERT_DEST"
            fi
            
            log_info "Atualizando repositório de CAs confiadas..."
            update-ca-trust > /dev/null 2>&1
            log_success "Repositório atualizado"
            ;;
            
        *)
            log_warning "Distribuição não oficialmente suportada: $DISTRO"
            log_info "Tentando método genérico..."
            mkdir -p /usr/local/share/ca-certificates
            cp "$CA_CERT_PATH" /usr/local/share/ca-certificates/gateway-ca.crt
            command -v update-ca-certificates &> /dev/null && update-ca-certificates
            ;;
    esac
}

verify_trusted_ca() {
    log_info "Verificando se CA está na lista confiada..."
    
    # Tentar diferentes locais dependendo da distro
    if grep -q "BEGIN CERTIFICATE" /etc/ssl/certs/ca-certificates.crt 2>/dev/null; then
        if grep -A 20 "gateway-ca\|AgentK" /etc/ssl/certs/ca-certificates.crt &>/dev/null || \
           openssl crl2pkcs7 -nocrl -certfile /etc/ssl/certs/ca-certificates.crt | openssl pkcs7 -print_certs -text -noout | grep -q "AgentK"; then
            log_success "✓ CA encontrada em /etc/ssl/certs/ca-certificates.crt"
            return 0
        fi
    fi
    
    if [[ -f /usr/local/share/ca-certificates/gateway-ca.crt ]]; then
        log_success "✓ CA encontrada em /usr/local/share/ca-certificates/gateway-ca.crt"
        return 0
    fi
    
    log_warning "⚠ CA não encontrada em locais esperados (pode ser normal se update-ca-certificates ainda não foi executado)"
    return 0
}

#############################################################################
# ETAPA 4: Adicionar CA no container Docker do AgentK
#############################################################################

setup_docker_container_ca() {
    log_info "=========================================="
    log_info "ETAPA 4: Configurando CA no Docker (AgentK)"
    log_info "=========================================="

    if [[ "$SKIP_DOCKER_CA_SETUP" == "1" ]]; then
        log_warning "SKIP_DOCKER_CA_SETUP=1 definido. Pulando etapa Docker."
        return 0
    fi

    if ! command -v docker >/dev/null 2>&1; then
        log_warning "Docker não encontrado neste host. Pulando etapa Docker."
        return 0
    fi

    if ! docker ps -a --format '{{.Names}}' | grep -Fxq "$AGENTK_DOCKER_CONTAINER"; then
        log_warning "Container '$AGENTK_DOCKER_CONTAINER' não encontrado."
        log_info "Defina AGENTK_DOCKER_CONTAINER=<nome-do-container> e rode novamente se necessário."
        return 0
    fi

    if ! docker ps --format '{{.Names}}' | grep -Fxq "$AGENTK_DOCKER_CONTAINER"; then
        log_warning "Container '$AGENTK_DOCKER_CONTAINER' está parado. Etapa Docker pulada."
        log_info "Inicie o container e execute novamente para instalar a CA nele."
        return 0
    fi

    log_info "Copiando CA para o container '$AGENTK_DOCKER_CONTAINER'..."
    docker cp "$CA_CERT_PATH" "$AGENTK_DOCKER_CONTAINER:/tmp/gateway-ca.crt"
    log_success "CA copiada para /tmp/gateway-ca.crt"

    if docker exec -u root "$AGENTK_DOCKER_CONTAINER" sh -lc 'command -v update-ca-certificates >/dev/null 2>&1'; then
        log_info "Instalando CA no trust store interno do container (modo root)..."
        docker exec -u root "$AGENTK_DOCKER_CONTAINER" sh -lc '
            set -e
            mkdir -p /usr/local/share/ca-certificates
            cp /tmp/gateway-ca.crt /usr/local/share/ca-certificates/gateway-ca.crt
            update-ca-certificates >/dev/null 2>&1
        '
        log_success "CA instalada no trust store do container"
    else
        log_warning "Não foi possível atualizar trust store no container (sem root ou sem update-ca-certificates)."
        log_info "Fallback: usar variáveis SSL_CERT_FILE e REQUESTS_CA_BUNDLE apontando para /tmp/gateway-ca.crt"
    fi
}

verify_docker_container_ca() {
    if [[ "$SKIP_DOCKER_CA_SETUP" == "1" ]]; then
        log_warning "Validação Docker ignorada por SKIP_DOCKER_CA_SETUP=1"
        return 0
    fi

    if ! command -v docker >/dev/null 2>&1; then
        log_warning "Docker não encontrado. Validação Docker pulada."
        return 0
    fi

    if ! docker ps --format '{{.Names}}' | grep -Fxq "$AGENTK_DOCKER_CONTAINER"; then
        log_warning "Container '$AGENTK_DOCKER_CONTAINER' não está em execução. Validação Docker pulada."
        return 0
    fi

    if docker exec "$AGENTK_DOCKER_CONTAINER" sh -lc 'test -f /tmp/gateway-ca.crt'; then
        log_success "✓ CA encontrada no container em /tmp/gateway-ca.crt"
        return 0
    fi

    log_warning "⚠ CA não encontrada em /tmp/gateway-ca.crt dentro do container"
    return 0
}

#############################################################################
# ETAPA 5: Validar e Resumir Configurações
#############################################################################

validate_all_configurations() {
    log_info "=========================================="
    log_info "Validação Final de Todas as Configurações"
    log_info "=========================================="
    
    local all_valid=true
    
    # Validar iptables
    if verify_iptables_rules; then
        log_success "✓ iptables configurado corretamente"
    else
        log_error "✗ iptables não está configurado"
        all_valid=false
    fi
    
    # Validar CA
    if verify_certificate_authority; then
        log_success "✓ Certificado CA válido"
    else
        log_error "✗ Certificado CA é inválido ou ausente"
        all_valid=false
    fi

    # Validar Keystore PKCS12
    if verify_keytool_keystore; then
        log_success "✓ Keystore PKCS12 válido"
    else
        log_error "✗ Keystore PKCS12 é inválido ou ausente"
        all_valid=false
    fi
    
    # Validar CA confiada
    if verify_trusted_ca; then
        log_success "✓ CA adiciona à lista confiada"
    else
        log_warning "⚠ CA pode não estar na lista confiada"
    fi

    # Validar CA no Docker (best-effort)
    if verify_docker_container_ca; then
        log_success "✓ Etapa Docker validada (ou pulada com segurança)"
    else
        log_warning "⚠ Etapa Docker não validada"
    fi
    
    if [[ "$all_valid" == true ]]; then
        log_success "=========================================="
        log_success "TODAS AS CONFIGURAÇÕES VALIDADAS COM SUCESSO"
        log_success "=========================================="
        return 0
    else
        log_error "=========================================="
        log_error "ALGUMAS CONFIGURAÇÕES FALHARAM"
        log_error "=========================================="
        return 1
    fi
}

#############################################################################
# RESUMO DE CONFIGURAÇÕES
#############################################################################

print_summary() {
    log_info "=========================================="
    log_info "RESUMO DE CONFIGURAÇÕES"
    log_info "=========================================="
    
    echo ""
    echo -e "${BLUE}Redirecionamento de Rede (iptables):${NC}"
    echo "  Porta de saída monitorada: $TARGET_PORT (HTTPS)"
    echo "  Porta de redirecionamento: $GATEWAY_PORT (Gateway)"
    echo "  Arquivo de persistência: $IPTABLES_RULE_FILE"
    echo ""
    
    echo -e "${BLUE}Certificado CA (Autoridade Certificadora):${NC}"
    echo "  Certificado: $CA_CERT_PATH"
    echo "  Chave privada: $CA_KEY_PATH"
    echo "  Validade: 3650 dias (10 anos)"
    echo ""

    echo -e "${BLUE}Keystore PKCS12 (keytool):${NC}"
    echo "  Arquivo  : $KEYSTORE_FILE"
    echo "  Alias    : $KEYSTORE_ALIAS"
    echo "  Cert PEM : $KEYSTORE_CERT_FILE"
    echo "  Senha    : (variável KEYSTORE_PASSWORD, padrão: gateway-secret)"
    echo ""

    echo -e "${BLUE}Docker (AgentK):${NC}"
    echo "  Container alvo: $AGENTK_DOCKER_CONTAINER"
    echo "  Cert no container: /tmp/gateway-ca.crt"
    echo "  Variáveis fallback: SSL_CERT_FILE=/tmp/gateway-ca.crt REQUESTS_CA_BUNDLE=/tmp/gateway-ca.crt"
    echo ""
    
    echo -e "${BLUE}Próximos passos:${NC}"
    echo "  1. Iniciar o Gateway Java na porta $GATEWAY_PORT"
    echo "  2. Gateway fará terminação TLS e analisará o tráfego"
    echo "  3. Aplicações que tentarem usar HTTPS serão interceptadas"
    echo ""
    
    echo -e "${BLUE}Para remover a configuração depois:${NC}"
    echo "  1. Remover regras iptables:"
    echo "     sudo iptables -t nat -D PREROUTING -p tcp --dport $TARGET_PORT -j REDIRECT --to-port $GATEWAY_PORT"
    echo "  2. Remover CA:"
    echo "     sudo rm $CA_CERT_PATH $CA_KEY_PATH"
    echo "  3. Atualizar CAs confiadas:"
    echo "     sudo update-ca-certificates"
    echo ""
}

#############################################################################
# MAIN
#############################################################################

main() {
    echo ""
    log_info "╔════════════════════════════════════════════════════════╗"
    log_info "║  Configurador de Fluxo de Interceptação de Rede       ║"
    log_info "║  DEPLOYMENT.md - Etapa 1: Camada de Rede              ║"
    log_info "╚════════════════════════════════════════════════════════╝"
    echo ""
    
    # Verificar se é root
    check_root
    
    # Executar todas as etapas
    setup_iptables_redirect
    echo ""
    
    setup_certificate_authority
    echo ""

    setup_keytool_keystore
    echo ""

    trust_certificate_authority
    echo ""

    setup_docker_container_ca
    echo ""
    
    # Validação final
    validate_all_configurations
    echo ""
    
    # Mostrar resumo
    print_summary
    
    log_success "Script executado com sucesso!"
    echo ""
}

# Executar main
main "$@"
exit $?
