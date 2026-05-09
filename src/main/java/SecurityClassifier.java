import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SecurityClassifier.java - Classificador de Segurança AgentK
 * 
 * Versão otimizada com suporte a Gson e JSON Lines.
 */
public class SecurityClassifier {
    private static final String LOG_PREFIX = "[SecurityClassifier]";

    private static final String DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434/api/generate";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:1.5b";
    private static final int DEFAULT_OLLAMA_TIMEOUT = 120;

    private final HttpClient httpClient;
    private final String ollamaUrl;
    private final String model;
    private final String referencePrompts;
    private final int ollamaTimeout;
    private final List<PromptExample> database;
    private final Gson gson = new Gson();

    public SecurityClassifier() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.ollamaUrl = envOr("OLLAMA_ENDPOINT", DEFAULT_OLLAMA_URL);
        this.model = envOr("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL);
        this.ollamaTimeout = Integer.parseInt(envOr("OLLAMA_TIMEOUT", String.valueOf(DEFAULT_OLLAMA_TIMEOUT)));
        this.referencePrompts = loadReferencePrompts();
        this.database = parseDatabase(this.referencePrompts);
    }

    /**
     * Pipeline principal de classificação.
     */
    public String classify(String userPrompt) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) {
            return "Reprovado";
        }

        String normalized = userPrompt.trim();
        
        try {
            // 1. Abordagem Zero-Shot (Rápida)
            String aiPrompt = buildZeroShotPrompt(normalized);
            String llmResponse = queryLocalLLM(aiPrompt, 0.0, 10);
            String verdict = evaluateResponseStrict(llmResponse);
            
            if ("Aprovado".equals(verdict) || "Reprovado".equals(verdict)) {
                return verdict;
            }
            
            log("Validação Zero-Shot inconclusiva. Iniciando Fallback Few-Shot...");
            
            // 2. Abordagem Few-Shot (Contextual)
            List<PromptExample> similarExamples = getSimilarExamples(normalized, 3);
            String fewShotPrompt = buildFewShotPrompt(normalized, similarExamples);
            String fewShotResponse = queryLocalLLM(fewShotPrompt, 0.0, 10);
            String finalVerdict = evaluateResponseStrict(fewShotResponse);
            
            return ("Aprovado".equals(finalVerdict)) ? "Aprovado" : "Reprovado";

        } catch (Exception e) {
            logError("Falha na classificação: " + e.getMessage());
            return "Reprovado"; // Fail-safe por segurança
        }
    }

    private String buildZeroShotPrompt(String userPrompt) {
        return "Você é um classificador de segurança estrito. Sua ÚNICA função é analisar se o texto de entrada é malicioso.\n" +
               "Responda EXCLUSIVAMENTE com a palavra \"Aprovado\" ou a palavra \"Reprovado\".\n\n" +
               "Entrada: " + userPrompt;
    }

    private String buildFewShotPrompt(String userPrompt, List<PromptExample> similarExamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é um classificador de segurança estrito. Responda EXCLUSIVAMENTE com \"Aprovado\" ou \"Reprovado\".\n\n");
        for (PromptExample ex : similarExamples) {
            sb.append("Exemplo: ").append(ex.text).append("\nResposta: ").append(mapCategoryToBinary(ex.category)).append("\n\n");
        }
        sb.append("Agora classifique:\nEntrada: ").append(userPrompt).append("\nResposta:");
        return sb.toString();
    }

    private List<PromptExample> getSimilarExamples(String text, int limit) {
        return database.stream()
                .sorted((e1, e2) -> Double.compare(calculateJaccard(text, e2.text), calculateJaccard(text, e1.text)))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private double calculateJaccard(String s1, String s2) {
        Set<String> set1 = new HashSet<>(Arrays.asList(s1.toLowerCase().split("\\W+")));
        Set<String> set2 = new HashSet<>(Arrays.asList(s2.toLowerCase().split("\\W+")));
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private String mapCategoryToBinary(String category) {
        return "SAFE".equalsIgnoreCase(category) ? "Aprovado" : "Reprovado";
    }

    private String loadReferencePrompts() {
        String path = envOr("REFERENCE_PROMPTS_PATH", "BASE.jsonl");
        try {
            return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private List<PromptExample> parseDatabase(String content) {
        List<PromptExample> examples = new ArrayList<>();
        if (content == null || content.isBlank()) return examples;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue;
            try {
                JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
                if (json.has("category") && json.has("text")) {
                    examples.add(new PromptExample(json.get("text").getAsString(), json.get("category").getAsString()));
                }
            } catch (Exception ignored) {}
        }
        return examples;
    }

    private String queryLocalLLM(String aiPrompt, double temperature, int numPredict) throws IOException, InterruptedException {
        String body = gson.toJson(Map.of(
                "model", model,
                "prompt", aiPrompt,
                "stream", false,
                "options", Map.of("temperature", temperature, "num_predict", numPredict)
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl))
                .timeout(Duration.ofSeconds(ollamaTimeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            if (ollamaUrl.contains("127.0.0.1") || ollamaUrl.contains("localhost")) {
                String fallbackUrl = ollamaUrl.replace("127.0.0.1", "host.docker.internal").replace("localhost", "host.docker.internal");
                HttpRequest fallbackRequest = HttpRequest.newBuilder()
                        .uri(URI.create(fallbackUrl))
                        .timeout(Duration.ofSeconds(ollamaTimeout))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                return httpClient.send(fallbackRequest, HttpResponse.BodyHandlers.ofString()).body();
            }
            throw e;
        }
    }

    private String evaluateResponseStrict(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "INVALID";
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!json.has("response")) return "INVALID";
            String res = json.get("response").getAsString().trim().replaceAll("[^a-zA-ZáéíóúÁÉÍÓÚ]", "");
            if (res.equalsIgnoreCase("Aprovado")) return "Aprovado";
            if (res.equalsIgnoreCase("Reprovado")) return "Reprovado";
        } catch (Exception e) {}
        return "INVALID";
    }

    private static String envOr(String key, String fallback) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? fallback : val;
    }

    private void log(String msg) { System.out.println(LOG_PREFIX + " " + msg); }
    private void logError(String msg) { System.err.println(LOG_PREFIX + " [ERROR] " + msg); }

    private static class PromptExample {
        final String text;
        final String category;
        PromptExample(String text, String category) { this.text = text; this.category = category; }
    }
}
