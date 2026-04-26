# Registro de Alterações (Changelog)

## 26 de Abril de 2026 - Correção de Integridade do Projeto Docker Compose Após Desacoplamento do AgentK

### Eliminação de Referência a Serviço Indefinido
- **Ajuste no `docker-compose.yaml`**: Foi removida, no serviço `oauth2-proxy`, a entrada `agentk-client` da diretiva `depends_on`, uma vez que o respectivo serviço havia sido previamente comentado para viabilizar a execução segregada dos componentes do ecossistema AgentK. A correção restaura a validade estrutural do manifesto Docker Compose, eliminando o erro de carregamento `depends on undefined service` e preservando a inicialização dos serviços remanescentes de forma consistente.

## 26 de Abril de 2026 - Desacoplamento Operacional do Compose Principal em Relação ao Compose do AgentK

### Isolamento de Ferramentas para Execução Independente
- **Comentário seletivo no `docker-compose.yaml`**: Foi executado o comentário integral dos blocos de serviço `agentk-server` e `agentk-client`, bem como das declarações correlatas `minikube` (rede externa) e `agentk-logs` (volume local), preservando-se inalteradas as demais definições de infraestrutura não pertencentes ao manifesto específico do AgentK. A intervenção teve como fundamento a separação de responsabilidades entre orquestrações, permitindo que os componentes nativos do `Agentk-Sugest/docker-compose.yml` passem a ser inicializados por meio de fluxo autônomo e dedicado, sem acoplamento direto ao compose consolidado da raiz do projeto.

## 25 de Abril de 2026 - Evolução do Guardrail: Controle de Acesso Baseado em Risco

### Autenticação em Escopo de Risco (Keycloak)
- **Implementação do Fluxo de Autorização para Vereditos RISKY**: Foi introduzido um mecanismo de interrupção e autorização no serviço de chat (`Agentk-Sugest/client/app/services/chat_service.py`). A partir desta atualização, prompts classificados pela Inteligência Artificial do Gateway como `RISKY` (arriscados) são interceptados e não enviados de imediato ao modelo LLM primário. Em vez de sofrerem um bloqueio permanente como ações `UNSAFE`, estes requisitam credenciais de administrador via interface gráfica baseada em *modals* (implementada através do decorador `@st.dialog` do Streamlit).
- **Integração com Servidor de Identidade**: A validação das credenciais fornecidas durante o cenário de risco é processada diretamente contra o Identity Provider Keycloak (`http://keycloak:8080/realms/agentk/protocol/openid-connect/token`), garantindo conformidade com a arquitetura de acesso do ecossistema. O processo utiliza o tipo de concessão `password` e, em caso de êxito na validação do *token*, a execução da aplicação é reinvocada para prosseguir com o fluxo original da requisição mitigada.
- **Adaptação de Ciclo de Vida da UI (`Agentk-Sugest/client/app/main.py`)**: Para suportar as características assíncronas do Streamlit durante a abertura do painel de autorização, a lógica de evento principal foi modificada para registrar a sessão autorizada transacionalmente e recarregar a requisição validada sem que o *prompt* do usuário seja perdido ou duplicado.## 25 de Abril de 2026 - Evolução de Empacotamento, Orquestração e Documentação

### Documentação Arquitetural
- **Criação do arquivo `ARCHITECTURE.md`**: Elaboração de documentação analítica descrevendo a topologia estrutural do Gateway. O texto abrange o modelo operacional, detalhando os módulos de entrada (`Main.java`), interfaces HTTP (`PromptValidator.java`) e a mecânica de classificação através de Inteligência Artificial (`SecurityClassifier.java`), bem como as metodologias aplicadas na tomada de decisão sobre a categorização de *prompts*.

### Otimização da Engenharia de Construção (Build)
- **Modificação do `Dockerfile`**: Implementação da abordagem de *Multi-stage Build*. O processo de compilação agora utiliza o contêiner `gradle:8.7-jdk21` no estágio inicial (builder) para gerar e ofuscar o código binário nativamente no ambiente Docker. O artefato produzido é então injetado no ambiente de execução (`eclipse-temurin:21-jre-jammy`), mitigando definitivamente o acoplamento sistêmico e suprimindo a necessidade de dependências de compilação (Gradle, JDK) no *host* do usuário.

### Evolução na Orquestração de Contêineres
- **Atualização do `docker-compose.yaml` e `docker-compose.merged.yaml`**: 
  - Integração nativa do serviço **Ollama** (`ollama/ollama:latest`) com persistência em disco assegurada via volumes montados.
  - Modificação do *entrypoint* do serviço do motor de IA visando promover a automação total do provisionamento do modelo adotado. A configuração introduz o comando `ollama serve & sleep 5 && ollama pull qwen2.5:1.5b && wait`, garantindo o trânsito assíncrono para a obtenção do modelo sem intervenção humana.
  - Supressão de comentários e instruções obsoletas que demandavam compilação local manual, alinhando a documentação *inline* à nova estrutura hermética de containers.

### Inclusão de Proxy Reverso (Nginx)
- **Atualização do `docker-compose.merged.yaml`**: Adicionado o serviço do **Nginx** (`nginx:alpine`) atuando como proxy reverso com suporte a HTTPS. A configuração estabelece a montagem de volumes em modo leitura (*read-only*) para o arquivo de configuração e certificados digitais, expõe as portas 80 e 443 estritamente para o laço local (`127.0.0.1`) e define dependência explícita (`depends_on`) em relação ao serviço `oauth2-proxy`, integrando o proxy de borda à rede `agentk-network`.

### Otimização e Segurança na Inicialização do Gateway
- **Limpeza Estrutural do `docker-compose.merged.yaml`**: Reestruturação do serviço `gateway` para iniciar estritamente o que é essencial. Foi definido o `entrypoint: ["java", "-jar", "/app/app.jar"]`, contornando lógicas desnecessárias do script original (como regras de `iptables`). Com isso, foram removidos os privilégios elevados de rede (`cap_add: NET_ADMIN`, `network_mode: "host"`), isolando-o de maneira segura na rede `agentk-network`.
- **Remoção de Dependências Obsoletas**: Como o módulo de interceptação TLS (MITM) não está ativo na arquitetura atual, eliminou-se o mapeamento de volumes referentes à geração de Certificados e Keystores (`gateway-certs` e `gateway-keys`), bem como as variáveis de ambiente que forçavam os demais serviços (`agentk-server` e `agentk-client`) a confiar nessa Autoridade Certificadora inativa, resultando em uma topologia mais leve e coerente com as operações restritas ao Webhook e ao modelo de IA local.

### Configuração de Infraestrutura em Produção
- **Criação do `docker-compose.final.yaml`**: Adicionado um arquivo Docker Compose de orquestração definitiva. Diferente das versões anteriores, este manifesto ignora a compilação local (Multi-stage build) da imagem e invoca nativamente a imagem `eclipse-temurin:21-jre-jammy`, mapeando via volume o artefato pré-compilado pelo Gradle (`./build/libs/gateway-sensor-1.0.0-obf.jar`). Essa alteração garante a integridade e separação estrita do ciclo de build e deploy em ambientes provisionados.

### Correção de Topologia em Orquestração Local
- **Ajuste de Contextos no `docker-compose.final.yaml`**: Corrigidos os caminhos para as declarações de compilação (*build context*) dos serviços `agentk-server` e `agentk-client`, que antes apontavam equivocadamente para a raiz (`./server` e `./client`) e agora endereçam precisamente as subpastas em `./Agentk-Sugest/`.
- **Refinamento na Mapeamento do Nginx**: O bloco do serviço de roteamento de borda (Nginx) teve sua diretiva de volumes corrigida. A tentativa de carregar uma configuração inexistente em `./nginx/nginx.conf` foi suprimida e o mapeamento de certificados TLS foi direcionado à pasta correta preexistente na raiz da infraestrutura (`./certs:/etc/nginx/certs:ro`), assegurando a correta funcionalidade sintática na partida do orquestrador.

## 24 de Abril de 2026 - Centralização e Padronização de Logs no Ambiente AgentK

### Configuração Global de Telemetria
- **Criação do módulo `logs/logging_config.py`**: Estabelecimento de uma infraestrutura centralizada para emissão de logs em todo o ecossistema. A solução implementa a captura de variáveis de ambiente (`AGENTK_LOG_LEVEL`, `AGENTK_LOG_DIR`, `AGENTK_LOG_MAX_MB`, `AGENTK_LOG_BACKUPS`) para parametrizar a rotação, o diretório e a severidade dos registros. Inclui um mecanismo de resolução de caminhos com *fallback* automático (priorizando o diretório do sistema `/var/log/agentk` em relação ao diretório local `logs/`) e introduz o manipulador `RotatingFileHandler` para mitigar o consumo de disco frente a altos volumes de dados em paralelo à emissão contínua em *stdout*.

### Wrappers de Telemetria nos Microsserviços
- **Refatoração no Servidor (`server/app/utils/logger.py`)**: Integração com a configuração global de logs. Foi suprimida a criação estática e isolada do arquivo local em favor da nova política, emitindo artefatos no arquivo definido por `AGENTK_SERVER_LOG_FILE` (padrão: `agentk-server.log`) sob o *namespace* específico `agentk.server`.
- **Refatoração no Cliente (`client/app/utils/logger.py`)**: Adequação da interface de log do cliente aos padrões do projeto, assegurando a exportação das trilhas de auditoria para o arquivo `AGENTK_CLIENT_LOG_FILE` (padrão: `agentk-client.log`) sob o *namespace* `agentk.client`.

### Refatoração de Instrumentação no Código Base
- **Adaptação no Ponto de Entrada do Servidor (`server/app/main.py`)**: Remoção de chamadas legadas à API `logging.basicConfig` e da instância dedicada de `FileHandler`, migrando o controle de severidade e formatação de saída de forma exclusiva para as definições orquestradas externamente via variáveis de ambiente.
- **Substituição de Mecanismos de Debug no Cliente (`client/app/classes/mcp_client.py`)**: Eliminação de rotinas manuais de depuração (ex: `_debug_log`) e de *flags* puramente locais de controle (`_debug`). A classe `MCPClient` passou a adotar o novo padrão estruturado para instrumentar adequadamente as operações críticas de ciclo de vida (inicialização `stdio` e `http`), processos de listagem e requisição de recursos (`get_tools`, `get_resources`, `get_prompts`, `get_resource`, `invoke_prompt`), execução das ferramentas disponíveis (`call_tool`) e encerramentos assíncronos (`cleanup`), capturando anomalias em níveis semânticos apropriados (como `logger.error`).

### Orquestração de Volumes e Persistência de Logs
- **Atualização do Manifesto Docker (`docker-compose.yml`)**: Incorporação de definições de rastreabilidade unificada para os serviços `agentk-server` e `agentk-client`. Foram injetadas as novas variáveis de ambiente limitadoras e definidoras da arquitetura de telemetria. Introduziu-se o mapeamento de volume compartilhado `agentk-logs:/var/log/agentk`, assegurando que ambos os componentes preservem e persistam seus registros de eventos diagnósticos na mesma partição do contêiner e no *host*.

## 17 de Abril de 2026 - Implementação de Validação de Segurança (Guardrail) no Fluxo de Chat

### Interceptação e Inspeção de Prompts
- **Refatoração do Serviço de Chat (`Agentk-Sugest/client/app/services/chat_service.py`)**: Alteração substancial no método `process_llm_request()` para instituir um ponto de controle (middleware) obrigatório. A submissão de requisições ao modelo principal (ex: ChatGPT) foi condicionada a uma pré-validação de segurança. O sistema agora extrai a última instrução (prompt) emitida pelo usuário na interface e a submete via HTTP POST ao Gateway local (`http://host.docker.internal:8080/validar`).

### Mecanismo de Bloqueio Baseado em Inteligência Artificial Local
- **Integração de Lógica de Veredito Semântico**: O fluxo de execução foi enriquecido com a etapa de análise e verificação de integridade do pacote JSON retornado pelo Gateway. A estrutura valida se o modelo de IA primário local compreendeu o mesmo texto submetido e toma decisões baseadas no estado de `veredito`. Quaisquer classificações diferentes de `SAFE` (como `SUSPECT`, `UNCERTAIN`, `RISKY` ou `UNSAFE`) desencadeiam o bloqueio automático da requisição, apresentando mensagens descritivas de *warning* ou *error* diretamente na interface gráfica, abortando a chamada externa e preservando a segurança do ambiente.

### Reforço na Segurança e Criptografia (Guardrail)
- **Mitigação de Prompt Injection (`SecurityClassifier.java`)**: Implementada blindagem na formatação de contexto submetida ao modelo de IA local (Ollama). A entrada do usuário (`userPrompt`) foi rigorosamente isolada através de sintaxe de marcação (`<USER_PROMPT>...</USER_PROMPT>`). Em conjunto, foi introduzida uma instrução forte forçando o modelo a tratar exclusivamente esse fragmento como dados inertes a serem classificados, prevenindo ataques onde um payload malicioso poderia sobrescrever o comportamento (role) e as instruções base do Guardrail.
- **Implementação de Transporte Seguro TLS/HTTPS (`PromptValidator.java` & Orquestração)**: O `Gateway` foi refatorado para habilitar suporte nativo ao tráfego HTTPS quando o certificado for mapeado via variável de ambiente (`KEYSTORE_PATH`). Para esta transição:
  - O código Java migrou condicionalmente da classe base `HttpServer` para `HttpsServer`, incorporando classes do pacote de segurança do Java para leitura do certificado PKCS12 (gerado anteriormente).
  - No `docker-compose.final.yaml`, mapeou-se o volume para injetar o arquivo `gateway-keystore.p12` no serviço `gateway`.
  - No módulo cliente (`chat_service.py`), a `gateway_url` foi migrada de `http://` para `https://` (com inibição das validações estritas de certificados locais com `verify=False`), assegurando que a transferência do pacote (incluindo o prompt original) não sofra interceptação ou "sniffing" não autorizado através da rede Docker interna.

## 25 de Abril de 2026 - Elaboração de Documentação de Segurança e Implantação

### Consolidação do Repositório
- **Criação do arquivo `README.md`**: Elaboração de documentação abrangente descrevendo as propostas de arquitetura de segurança do ecossistema AgentK. O documento detalha o papel fundamental do **Nginx** no roteamento de borda, a adoção de certificados SSL/TLS para criptografia em trânsito, a infraestrutura centralizada de captura de eventos e telemetria (Logs), os mecanismos de controle de acesso gerenciados pelo **Keycloak** em conjunto com fluxos de autorização baseados em risco, e as competências do Gateway Java como validador (Guardrail) contra *Prompt Injection*. Adicionalmente, foi incluído um guia de orquestração via contêineres (`docker-compose`) e instruções de configurações iniciais de *Realms* e *Clients* necessários no provisionamento do provedor de identidade.

## 26 de Abril de 2026 - Correção de Contexto de Build no Agentk-Sugest

### Resolução de Falha de Cálculo de Cache no Docker BuildKit (`/logs: not found`)
- **Correção do `Agentk-Sugest/docker-compose.yml`**: Identificado e resolvido um erro de referência de caminho que impedia a conclusão do *build* dos serviços `agentk-server` e `agentk-client`. O `BuildKit` relatava `failed to compute cache key: "/logs": not found` porque o `context` de ambos os serviços era definido como `./server` e `./client`, restringindo a visibilidade do daemon Docker exclusivamente ao respectivo subdiretório. Contudo, os manifestos `Dockerfile` de ambos os serviços empregavam instruções `COPY` com caminhos prefixados relativos à raiz do projeto (e.g., `COPY server/app/`, `COPY client/app/`, `COPY logs/`), evidenciando a incompatibilidade entre o escopo do contexto e os caminhos referenciados. A solução consistiu em elevar o `context` de ambos os serviços para `.` (raiz do diretório `Agentk-Sugest/`) e ajustar a diretiva `dockerfile` para referenciar explicitamente o caminho completo relativo à nova raiz de contexto (`server/Dockerfile` e `client/Dockerfile`). Essa alteração restabelece a coerência arquitetural entre o orquestrador e os manifestos de construção de imagem, permitindo o acesso irrestrito ao módulo compartilhado de telemetria (`logs/`).

## 26 de Abril de 2026 - Resolução de Conflitos na Orquestração de Contêineres

### Mitigação de Colisão de Portas de Rede
- **Modificação do `docker-compose.yaml`**: Ajuste no mapeamento de portas do serviço `gateway` para o hospedeiro (host). A diretiva de exposição foi alterada de `"8080:8080"` para `"8081:8080"`. Esta intervenção foi necessária para resolver uma colisão direta de alocação de portas (Binding) na interface de rede local (`127.0.0.1`), visto que o serviço `keycloak` já detinha reserva para o porto `8080` de forma nativa. O ajuste mantém a integridade da malha interna (rede do Docker), assegurando que o roteamento interno (proxy) entre a aplicação cliente e o Guardrail continue operacional.

### Correção de Módulos Compartilhados no Build de Contêineres
- **Atualização de Contexto e Mapeamento (`docker-compose.yaml` e `Dockerfile`)**: Resolvido o problema de importação circular ausente (`ModuleNotFoundError: No module named 'logs'`). Anteriormente, os ambientes isolados do cliente e do servidor limitavam-se ao seus subdiretórios de origem durante o *build*. O escopo de contexto (*build context*) do orquestrador Docker Compose foi elevado para a raiz do repositório `./Agentk-Sugest`, permitindo que tanto o cliente (`client/Dockerfile`) quanto o servidor (`server/Dockerfile`) acessem o módulo unificado de rastreamento de eventos em `logs/`. Os manifestos Dockerfile correspondentes foram retificados com a injeção apropriada das pastas compartilhadas (via `COPY logs/ ./app/logs/`), estabelecendo um paradigma arquitetural coerente onde microsserviços consomem bibliotecas locais de maneira centralizada sem duplicação de artefatos. Adicionalmente, foi criado o descritor de inicialização de módulo (`__init__.py`) no diretório `logs/` para formalizar a construção do pacote Python.

