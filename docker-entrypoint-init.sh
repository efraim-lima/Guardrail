#!/bin/bash

# =============================================================================
# docker-entrypoint-init.sh
#
# Script de inicialização que executa setup.sh e depois passa para o próximo
# comando. Usado como entrypoint de um serviço "init" no docker-compose.
#
# =============================================================================

set -e

echo "[Init] Executando setup.sh..."
SKIP_HOSTS_ENTRY=1 AGENTK_HOST_IP=auto bash /workspace/setup.sh
echo "[Init] Setup concluído. Infraestrutura pronta."
