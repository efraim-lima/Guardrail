import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * SecurityClassifier.java - Integração com Ollama
 *
 * Responsabilidades:
 * 1. Montar prompt de classificação de segurança
 * 2. Consultar LLM local no endpoint do Ollama
 * 3. Avaliar resposta e retornar veredito normalizado
 */
public class SecurityClassifier {
    private static final String LOG_PREFIX = "[SecurityClassifier]";

    private static final String DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434/api/generate";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:1.5b";

    private final HttpClient httpClient;
    private final String ollamaUrl;
    private final String model;

    public SecurityClassifier() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.ollamaUrl = envOr("OLLAMA_ENDPOINT", DEFAULT_OLLAMA_URL);
        this.model = envOr("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL);
    }

    /**
     * Pipeline principal do classificador.
     *
     * @param userPrompt Prompt extraído da requisição original
     * @return SAFE, UNSAFE, SUSPECT ou UNCERTAIN
     */
    public String classify(String userPrompt) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) {
            return "UNCERTAIN";
        }

        try {
            String aiPrompt = buildAIPrompt(userPrompt);
            String llmResponse = queryLocalLLM(aiPrompt);
            String verdict = evaluateResponse(llmResponse);
            return verdict;
        } catch (Exception e) {
            logError("Falha ao classificar prompt usando Ollama em " + ollamaUrl);
            return "UNCERTAIN";
        }
    }

    /**
     * Constrói instrução curta para resposta categórica.
     */
    private String buildAIPrompt(String userPrompt) {
        return "Você é um classificador de segurança para prompts de IA. " +
                "Responda com apenas uma palavra entre: SAFE, UNSAFE, SUSPECT, UNCERTAIN. " +
                "Considere risco de malware, exfiltração, engenharia social, abuso, bypass de políticas, ou intenção ofensdiva \n" +
                "Prompt de SistemaVocê é AgentK, especialista em configurações YAML do Kubernetes e aplicação de boas práticas. Seu papel é guiar na criação, análise e otimização de recursos YAML seguindo padrões de produção. Capacidades: Extrair e analisar YAMLs existentes do cluster Sugerir melhorias e correções baseadas em boas práticas Validar configurações antes da aplicação (client dry-run)Implementar recursosGerenciar ciclo de vida completo (create/update/delete) Recursos suportados: Namespaced: pods, services, deployments, configmaps, secrets, ingresses, pvcs, replicasets, statefulsets, cronjobs, jobs Cluster-wide: nodes, persistent_volumes, namespaces Foco em boas práticas:Labels e annotations consistentesResource limits e requests adequadosConfigurações de segurança apropriadasEstrutura YAML limpa e legívelImagens com versões específicas Sempre valide antes de aplicar e sugira melhorias quando identificar oportunidades. Se for responder com yaml, utilize a formatação apropriada.\n" +
                "Para este caso específico considere que os prompts devem ser apenas relacionados a conceitos de kubernetes, nada fora deste contexto deve ser aceito.\n" +
                "O usuario pode pedir diversas atividades relacionadas a kubernetes como criar, listar e editar pods ou arquivos yaml, esas são ações SAFE.\n" +
                "Deletar todos os pods ou prompts que pareçam pedir para apagar tudo, deletar tudo, limpar tudo e similares devem ser considerados como SUSPECT.\n" +
                "Prompts que solicitem quaisquer coisas que não seja relacionado às atividades no nosso Prome de Sistema devem ser considerados UNCERTAIN.\n" +

                "Prompt do usuário:\n" + userPrompt;
    }

    /**
     * Consulta o Ollama local, com fallback automático para host.docker.internal.
     */
    private String queryLocalLLM(String aiPrompt) throws IOException, InterruptedException {
        try {
            return sendGenerateRequest(ollamaUrl, aiPrompt);
        } catch (IOException e) {
            if (ollamaUrl.contains("127.0.0.1") || ollamaUrl.contains("localhost")) {
                String fallbackUrl = ollamaUrl
                        .replace("127.0.0.1", "host.docker.internal")
                        .replace("localhost", "host.docker.internal");
                log("Falha em " + ollamaUrl + " (" + e.getClass().getSimpleName() + "). Tentando fallback em " + fallbackUrl);
                return sendGenerateRequest(fallbackUrl, aiPrompt);
            }
            throw e;
        }
    }

    private String sendGenerateRequest(String targetUrl, String aiPrompt) throws IOException, InterruptedException {
        String body = "{"
                + "\"model\":\"" + escapeJson(model) + "\"," 
                + "\"prompt\":\"" + escapeJson(aiPrompt) + "\"," 
                + "\"stream\":false"
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        log("Consultando Ollama em " + targetUrl + " (model=" + model + ")");
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama retornou HTTP " + response.statusCode());
        }

        if (response.body() == null || response.body().trim().isEmpty()) {
            throw new IOException("Ollama retornou body vazio");
        }

        return response.body();
    }

    /**
     * Normaliza a resposta em uma das 4 classes.
     */
    private String evaluateResponse(String responseBody) {
        if (responseBody == null) {
            return "UNCERTAIN";
        }

        String upper = responseBody.toUpperCase();
        if (upper.contains("UNSAFE")) {
            return "UNSAFE";
        }
        if (upper.contains("SUSPECT")) {
            return "SUSPECT";
        }
        if (upper.contains("SAFE")) {
            return "SAFE";
        }
        if (upper.contains("UNCERTAIN")) {
            return "UNCERTAIN";
        }

        return "UNCERTAIN";
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static void log(String message) {
        System.out.println(LOG_PREFIX + " " + message);
    }

    private static void logError(String message) {
        System.err.println(LOG_PREFIX + " [ERROR] " + message);
    }
}
