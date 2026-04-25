# Gateway - Sensor de Validação de Prompts (MCP)

## Visão Geral
O projeto **Gateway** é uma aplicação desenvolvida em Java 21 que atua como um sensor e validador de prompts. O objetivo final é que esta aplicação funcione como um middleware capaz de analisar as intenções (prompts) emitidas por usuários através de um cliente MCP específico, determinando se a ação solicitada é segura e pertinente ao escopo do projeto antes de prosseguir.

## Arquitetura do Projeto

A aplicação é enxuta, não dependendo de frameworks web pesados (como Spring Boot), e utiliza componentes nativos do Java e integração via requisições HTTP locais.

### 1. Ponto de Entrada (`Main.java`)
Localizado na raiz do pacote de código-fonte (`/src/main/java`), o `Main.java` é o entrypoint da aplicação. Ele é responsável por:
- Inicializar a configuração do Gateway (por variáveis de ambiente como `WEBHOOK_PORT` e `WEBHOOK_PATH`).
- Instanciar um *Thread Pool* para lidar com múltiplas requisições concorrentes.
- Inicializar o modo atual de operação: o **Modo Webhook**.
- Implementar o *Graceful Shutdown* para desligar as threads de forma segura quando receber sinais do sistema (SIGTERM).

*Nota sobre Arquitetura Futura:* O `Main` prevê suporte futuro para interceptação direta de tráfego TLS (Man-In-The-Middle / *TransparentTLSInterceptor*), o que explica a presença de bibliotecas como `pcap4j` e `bouncycastle` no `build.gradle`.

### 2. Validador HTTP (`PromptValidator.java`)
É a camada de interface e rede da aplicação, rodando em um servidor HTTP nativo (`com.sun.net.httpserver.HttpServer`).
- **Endpoints:**
  - `POST /validar`: Recebe o prompt do usuário. Consegue lidar tanto com payload em formato JSON (extraindo campos como `prompt`, `input` ou `message`) quanto com texto plano (raw).
  - `GET /health`: Endpoint de verificação de integridade (Healthcheck) do serviço.
- **Fluxo:** Recebe a requisição, extrai a string do prompt com segurança e a repassa para o classificador.

### 3. Classificador de Segurança (`SecurityClassifier.java`)
É a camada que detém a inteligência e as regras de negócio.
- **Integração com Ollama**: Comunica-se localmente (via API HTTP) com um modelo LLM hospedado no Ollama (`http://127.0.0.1:11434/api/generate`). O modelo padrão utilizado é o `qwen2.5:1.5b`.
- **System Prompt Enriquecido**: Envolve o prompt do usuário com um contexto altamente específico (System Prompt). Neste contexto, a IA assume o papel de **"AgentK"**, um especialista em Kubernetes.

## Regras de Classificação
O `SecurityClassifier` força o LLM a avaliar a requisição contra critérios rígidos e responder com exatamente uma destas 4 categorias:

1. **SAFE (Seguro)**
   - O prompt solicita ações benignas estritamente relacionadas ao contexto do Kubernetes (ex: criar, listar e editar pods ou arquivos yaml).
2. **SUSPECT (Suspeito)**
   - Ação de risco no domínio de Kubernetes, que pode ser destrutiva.
   - *Regra explícita*: Pedidos para "deletar todos os pods", "apagar tudo" ou "limpar tudo" recaem aqui.
3. **UNSAFE (Inseguro)**
   - O prompt apresenta risco claro (tentativas de malware, engenharia social, bypass de políticas, ou intenções ofensivas).
4. **UNCERTAIN (Incerteza/Fora do Escopo)**
   - O prompt solicita tarefas que fogem completamente ao escopo estabelecido pelo AgentK (não tem a ver com Kubernetes).
   - Também utilizado como "fallback" genérico, caso a aplicação encontre problemas de conectividade com o Ollama ou a requisição venha vazia.

## Empacotamento e Implantação
O arquivo `build.gradle` evidencia a robustez de distribuição da aplicação:
- Gera um **Fat JAR** (um único executável com todas as dependências embutidas).
- Aplica o **ProGuard** para gerar uma versão ofuscada do JAR (`gateway-sensor-1.0.0-obf.jar`), ocultando o código e protegendo a propriedade intelectual e as lógicas de segurança da aplicação, antes de ser distribuído via imagem Docker.
