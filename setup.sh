#!/bin/bash

#############################################################################
# setup.sh
# 
# Script para:
#   1. Gerar certificado SSL auto-assinado para o Nginx
#   2. Registrar entrada DNS local (127.0.0.1 agentk.local) em /etc/hosts
#
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

# Configurações
CERTS_DIR="./nginx/certs"
CERT_FILE="${CERTS_DIR}/agentk.crt"
KEY_FILE="${CERTS_DIR}/agentk.key"
CERT_DAYS=365
CERT_CN="agentk.local"
AGENTK_HOST_IP="${AGENTK_HOST_IP:-127.0.0.1}"
HOSTS_ENTRY="${AGENTK_HOST_IP} agentk.local"
HOSTS_FILE="/etc/hosts"

setup_hosts_entry() {
    log_info "Configurando entrada DNS local em $HOSTS_FILE..."

    if grep -qF "$HOSTS_ENTRY" "$HOSTS_FILE" 2>/dev/null; then
        log_success "Entrada já existe: $HOSTS_ENTRY"
        return 0
    fi

    if [[ $EUID -ne 0 ]]; then
        log_info "Permissão de root necessária para editar $HOSTS_FILE"
        log_info "Executando: sudo tee -a $HOSTS_FILE"
        echo "$HOSTS_ENTRY" | sudo tee -a "$HOSTS_FILE" > /dev/null
    else
        echo "$HOSTS_ENTRY" >> "$HOSTS_FILE"
    fi

    log_success "Entrada adicionada: $HOSTS_ENTRY"
}

main() {
    echo ""
    log_info "╔═════════════════════════════════════════════╗"
    log_info "║  Gerador de Certificado SSL Auto-assinado  ║"
    log_info "║  para Nginx                                 ║"
    log_info "╚═════════════════════════════════════════════╝"
    echo ""
    
    # Criar diretório para certificados
    if [[ ! -d "$CERTS_DIR" ]]; then
        log_info "Criando diretório de certificados: $CERTS_DIR"
        mkdir -p "$CERTS_DIR"
        log_success "Diretório criado"
    else
        log_success "Diretório $CERTS_DIR já existe"
    fi
    
    # Verificar se certificado já existe
    if [[ -f "$CERT_FILE" && -f "$KEY_FILE" ]]; then
        log_success "Certificado já existe:"
        log_success "  Certificado: $CERT_FILE"
        log_success "  Chave privada: $KEY_FILE"
        setup_hosts_entry
        echo ""
        return 0
    fi
    
    # Gerar certificado auto-assinado
    log_info "Gerando certificado SSL auto-assinado..."
    log_info "  CN (Common Name): $CERT_CN"
    log_info "  Validade: $CERT_DAYS dias"
    log_info "  Algoritmo: RSA 2048 bits"
    echo ""
    
    openssl req -x509 -nodes -days "$CERT_DAYS" -newkey rsa:2048 \
        -keyout "$KEY_FILE" \
        -out "$CERT_FILE" \
        -subj "/CN=$CERT_CN"
    
    log_success "Certificado gerado com sucesso"
    log_success "  Certificado: $CERT_FILE"
    log_success "  Chave privada: $KEY_FILE"
    echo ""
    
    # Exibir informações do certificado
    log_info "Informações do certificado:"
    openssl x509 -in "$CERT_FILE" -text -noout | grep -E "Subject:|Issuer:|Not Before|Not After|Public-Key:"
    echo ""
    
    # Registrar entrada DNS local no /etc/hosts
    setup_hosts_entry
    echo ""

    log_success "Setup concluído!"
    echo ""
}

main "$@"
exit $?
