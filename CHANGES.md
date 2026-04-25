# Registro de Alterações (Changelog)

## 25 de Abril de 2026 - Evolução de Empacotamento, Orquestração e Documentação

### Documentação Arquitetural
- **Criação do arquivo `ARCHITECTURE.md`**: Elaboração de documentação analítica descrevendo a topologia estrutural do Gateway. O texto abrange o modelo operacional, detalhando os módulos de entrada (`Main.java`), interfaces HTTP (`PromptValidator.java`) e a mecânica de classificação através de Inteligência Artificial (`SecurityClassifier.java`), bem como as metodologias aplicadas na tomada de decisão sobre a categorização de *prompts*.

### Otimização da Engenharia de Construção (Build)
- **Modificação do `Dockerfile`**: Implementação da abordagem de *Multi-stage Build*. O processo de compilação agora utiliza o contêiner `gradle:8.7-jdk21` no estágio inicial (builder) para gerar e ofuscar o código binário nativamente no ambiente Docker. O artefato produzido é então injetado no ambiente de execução (`eclipse-temurin:21-jre-jammy`), mitigando definitivamente o acoplamento sistêmico e suprimindo a necessidade de dependências de compilação (Gradle, JDK) no *host* do usuário.

### Evolução na Orquestração de Contêineres
- **Atualização do `docker-compose.yaml` e `docker-compose.merged.yaml`**: 
  - Integração nativa do serviço **Ollama** (`ollama/ollama:latest`) com persistência em disco assegurada via volumes montados.
  - Modificação do *entrypoint* do serviço do motor de IA visando promover a automação total do provisionamento do modelo adotado. A configuração introduz o comando `ollama serve & sleep 5 && ollama pull qwen2.5:1.5b && wait`, garantindo o trânsito assíncrono para a obtenção do modelo sem intervenção humana.
  - Supressão de comentários e instruções obsoletas que demandavam compilação local manual, alinhando a documentação *inline* à nova estrutura hermética de containers.
