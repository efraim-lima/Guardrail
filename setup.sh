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
AGENTK_HOST_IP="${AGENTK_HOST_IP:-auto}"
SKIP_HOSTS_ENTRY="${SKIP_HOSTS_ENTRY:-0}"
HOSTS_ENTRY=""
HOSTS_FILE="/etc/hosts"

resolve_agentk_host_ip() {
    if [[ "$AGENTK_HOST_IP" != "auto" && -n "$AGENTK_HOST_IP" ]]; then
        echo "$AGENTK_HOST_IP"
        return 0
    fi

    local detected_ip=""

    if command -v ip >/dev/null 2>&1; then
        detected_ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}')"
    fi

    if [[ -z "$detected_ip" ]] && command -v hostname >/dev/null 2>&1; then
        detected_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi

    if [[ -z "$detected_ip" ]]; then
        detected_ip="127.0.0.1"
    fi

    echo "$detected_ip"
}

upsert_hosts_entry() {
    local entry="$1"

    if [[ $EUID -ne 0 ]]; then
        log_info "Permissão de root necessária para editar $HOSTS_FILE"
        log_info "Executando atualização via sudo"
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

    AGENTK_HOST_IP="$(resolve_agentk_host_ip)"
    HOSTS_ENTRY="${AGENTK_HOST_IP} agentk.local"

    log_info "Configurando entrada DNS local em $HOSTS_FILE..."

    if grep -qF "$HOSTS_ENTRY" "$HOSTS_FILE" 2>/dev/null; then
        log_success "Entrada já existe: $HOSTS_ENTRY"
        return 0
    fi

    upsert_hosts_entry "$HOSTS_ENTRY"

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
