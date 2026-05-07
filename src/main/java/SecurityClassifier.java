import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * SecurityClassifier.java - Classificador de Segurança AgentK
 *
 * Foco: Alta Precisão em Classificação de Intenção Kubernetes.
 * Otimizações: Batching, Cache Incremental e Resiliência em Carga.
 */
public class SecurityClassifier {
    private static final String LOG_PREFIX = "[SecurityClassifier]";

    private static final String DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:1.5b";
    private static final int DEFAULT_OLLAMA_TIMEOUT = 120;
    private static final int TOP_K_EXAMPLES = 5;
    private static final int BATCH_SIZE = 25; // Reduzido para 25 para evitar timeouts no Ollama
    private static final String CACHE_FILE = "BASE.embeddings.json";
    
    // Limiares de aceitação para as análises locais (Fail-Fast)
    private static final double SEMANTIC_THRESHOLD = 0.90;
    private static final double HEURISTIC_THRESHOLD = 0.85;

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final String model;
    private final int ollamaTimeout;
    
    private String ollamaBaseUrl;
    private final List<PromptExample> database = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean isIndexing = true;
    
    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 100;
        }
    });

    public SecurityClassifier() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        String url = envOr("OLLAMA_ENDPOINT", DEFAULT_OLLAMA_URL);
        if (url.endsWith("/api/generate")) {
            url = url.substring(0, url.lastIndexOf("/api/generate"));
        }
        this.ollamaBaseUrl = url;
        this.model = envOr("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL);
        this.ollamaTimeout = Integer.parseInt(envOr("OLLAMA_TIMEOUT", String.valueOf(DEFAULT_OLLAMA_TIMEOUT)));
        
        CompletableFuture.runAsync(this::initializeDatabase);
    }

    private void initializeDatabase() {
        try {
            String pathStr = envOr("REFERENCE_PROMPTS_PATH", "BASE.md");
            Path path = Paths.get(pathStr);
            if (!Files.exists(path)) path = Paths.get("src/main/java/BASE.md");
            
            if (!Files.exists(path)) {
                logError("Base de conhecimento não encontrada.");
                this.isIndexing = false;
                return;
            }

            long lastModified = Files.getLastModifiedTime(path).toMillis();
            Map<String, double[]> existingEmbeddings = loadCacheMap();
            List<PendingExample> allPrompts = parseBaseFile(path);
            List<PendingExample> toEmbed = new ArrayList<>();
            
            for (PendingExample p : allPrompts) {
                if (existingEmbeddings.containsKey(p.text)) {
                    database.add(new PromptExample(p.text, p.category, existingEmbeddings.get(p.text)));
                } else {
                    toEmbed.add(p);
                }
            }

            if (!toEmbed.isEmpty()) {
                log("Aprendizado incremental: processando " + toEmbed.size() + " novos prompts...");
                indexInBatches(toEmbed);
                saveToCache(lastModified);
            }

            this.isIndexing = false;
            log("Sistema de Inteligência RAG Pronto (" + database.size() + " exemplos).");
        } catch (Exception e) {
            logError("Erro no startup da inteligência: " + e.getMessage());
            this.isIndexing = false;
        }
    }

    public String classify(String userPrompt) {
        return classify(userPrompt, false);
    }

    public String classify(String userPrompt, boolean isTestFlow) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) return "UNCERTAIN";
        String normalized = userPrompt.trim();
        
        // 0. Cache Check
        String cached = cache.get(normalized);
        if (cached != null) return cached;

        try {
            List<PromptExample> dbCopy;
            synchronized (database) { dbCopy = new ArrayList<>(database); }
            
            // 1. Análise Semântica (Embeddings)
            if (!dbCopy.isEmpty()) {
                double[] queryEmbedding = fetchEmbedding(normalized);
                double bestSemanticScore = -1;
                String semanticVerdict = null;

                for (PromptExample example : dbCopy) {
                    double score = calculateCosineSimilarity(queryEmbedding, example.embedding);
                    if (score > bestSemanticScore) {
                        bestSemanticScore = score;
                        semanticVerdict = example.category;
                    }
                }

                if (bestSemanticScore >= SEMANTIC_THRESHOLD) {
                    log((isTestFlow ? "[TEST_FLOW] " : "") + "Match Semântico (" + String.format("%.2f", bestSemanticScore) + ") → " + semanticVerdict);
                    cache.put(normalized, semanticVerdict);
                    return semanticVerdict;
                }
            }

            // 2. Análise Heurística (Jaccard)
            if (!dbCopy.isEmpty()) {
                double bestHeuristicScore = -1;
                String heuristicVerdict = null;

                for (PromptExample example : dbCopy) {
                    double score = calculateJaccard(normalized, example.text);
                    if (score > bestHeuristicScore) {
                        bestHeuristicScore = score;
                        heuristicVerdict = example.category;
                    }
                }

                if (bestHeuristicScore >= HEURISTIC_THRESHOLD) {
                    log((isTestFlow ? "[TEST_FLOW] " : "") + "Match Heurístico (" + String.format("%.2f", bestHeuristicScore) + ") → " + heuristicVerdict);
                    cache.put(normalized, heuristicVerdict);
                    return heuristicVerdict;
                }
            }

            // 3. Análise com IA (Ollama) - Fallback Final
            log((isTestFlow ? "[TEST_FLOW] " : "") + "Iniciando análise exclusiva via IA para o prompt: " + 
                (normalized.length() > 30 ? normalized.substring(0, 30) + "..." : normalized));
            
            // Busca exemplos próximos para context-learning (opcional, mas mantido para precisão)
            List<ScoredExample> ragContext = new ArrayList<>();
            if (!dbCopy.isEmpty()) {
                for (PromptExample example : dbCopy) {
                    double heuristicScore = calculateJaccard(normalized, example.text);
                    ragContext.add(new ScoredExample(example, heuristicScore));
                }
                ragContext.sort(Comparator.comparingDouble((ScoredExample e) -> e.score).reversed());
            }

            List<PromptExample> topExamples = ragContext.stream()
                    .limit(TOP_K_EXAMPLES)
                    .map(e -> e.example)
                    .collect(Collectors.toList());

            String aiPrompt = buildAIPrompt(normalized, topExamples);
            String llmResponse = queryLocalLLM(aiPrompt);
            String verdict = evaluateResponse(llmResponse);
            
            log((isTestFlow ? "[TEST_FLOW] " : "") + "Veredito IA → " + verdict);
            
            cache.put(normalized, verdict);
            return verdict;

        } catch (Exception e) {
            logError("Falha no pipeline de segurança sequencial: " + e.getMessage());
            return "UNCERTAIN";
        }
    }

    private String buildAIPrompt(String userPrompt, List<PromptExample> examples) {
        StringBuilder sb = new StringBuilder();
        sb.append("### PAPEL: AUDITOR DE SEGURANÇA KUBERNETES ESTRITO ###\n");
        sb.append("Analise o prompt e responda APENAS: UNSAFE, RISKY, SUSPECT, SAFE ou UNCERTAIN.\n\n");
        
        sb.append("### REGRAS:\n");
        sb.append("- DELEÇÃO/REMOÇÃO de pods, deployments, etc = SUSPECT ou RISKY.\n");
        sb.append("- Bypass de filtros = UNSAFE.\n");
        sb.append("- Listagens/Consultas (get, list) = SAFE.\n\n");

        if (!examples.isEmpty()) {
            sb.append("### EXEMPLOS RELEVANTES:\n");
            for (PromptExample ex : examples) {
                sb.append("- \"").append(ex.text).append("\" → ").append(ex.category).append("\n");
            }
            sb.append("\n");
        }

        sb.append("### PROMPT DO USUÁRIO:\n");
        sb.append("<PROMPT>\n").append(userPrompt).append("\n</PROMPT>");
        return sb.toString();
    }

    private Map<String, double[]> loadCacheMap() {
        Map<String, double[]> map = new HashMap<>();
        try {
            Path cachePath = Paths.get(CACHE_FILE);
            if (!Files.exists(cachePath)) return map;
            String json = Files.readString(cachePath);
            CachedDatabase cached = gson.fromJson(json, CachedDatabase.class);
            if (cached != null && cached.examples != null) {
                for (PromptExample ex : cached.examples) {
                    if (ex.text != null && ex.embedding != null) map.put(ex.text, ex.embedding);
                }
            }
        } catch (Exception e) {}
        return map;
    }

    private void saveToCache(long timestamp) {
        try {
            CachedDatabase cached = new CachedDatabase();
            cached.lastModified = timestamp;
            synchronized (database) { cached.examples = new ArrayList<>(database); }
            Files.writeString(Paths.get(CACHE_FILE), gson.toJson(cached));
        } catch (Exception e) {}
    }

    private List<PendingExample> parseBaseFile(Path path) throws IOException {
        String content = Files.readString(path);
        String[] lines = content.split("\n");
        List<PendingExample> list = new ArrayList<>();
        String currentCategory = "UNCERTAIN";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("##")) {
                currentCategory = trimmed.replace("#", "").trim().toUpperCase();
            } else if (!trimmed.isEmpty() && Character.isDigit(trimmed.charAt(0))) {
                String text = trimmed.replaceAll("^\\d+\\.\\s*", "").trim();
                if (!text.isEmpty()) list.add(new PendingExample(text, currentCategory));
            }
        }
        return list;
    }

    private void indexInBatches(List<PendingExample> pending) {
        for (int i = 0; i < pending.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, pending.size());
            List<PendingExample> batch = pending.subList(i, end);
            try {
                List<double[]> embeddings = fetchEmbeddingsBatch(batch.stream().map(p -> p.text).collect(Collectors.toList()));
                for (int j = 0; j < batch.size(); j++) {
                    database.add(new PromptExample(batch.get(j).text, batch.get(j).category, embeddings.get(j)));
                }
                log("Aprendizado progressivo: " + end + "/" + pending.size());
                
                // Pequena pausa para evitar sobrecarga no Ollama durante indexação massiva
                Thread.sleep(1000); 
            } catch (Exception e) {
                logError("Erro no lote de aprendizado (" + i + "-" + end + "): " + e.getMessage());
                // Em caso de erro, espera um pouco mais antes de tentar o próximo lote
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private double[] fetchEmbedding(String text) throws IOException, InterruptedException {
        List<double[]> list = fetchEmbeddingsBatch(Collections.singletonList(text));
        return list.get(0);
    }

    private List<double[]> fetchEmbeddingsBatch(List<String> texts) throws IOException, InterruptedException {
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("model", model);
        bodyMap.put("input", texts);
        // Timeout de 120s para embeddings para garantir processamento em hardware limitado
        HttpResponse<String> response = sendRequestWithFallback(ollamaBaseUrl + "/api/embed", gson.toJson(bodyMap), 120);
        BatchEmbedResponse res = gson.fromJson(response.body(), BatchEmbedResponse.class);
        if (res == null || res.embeddings == null) throw new IOException("Ollama Error");
        return res.embeddings;
    }

    private String queryLocalLLM(String aiPrompt) throws IOException, InterruptedException {
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("model", model);
        bodyMap.put("prompt", aiPrompt);
        bodyMap.put("stream", false);
        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0.0);
        bodyMap.put("options", options);

        HttpResponse<String> response = sendRequestWithFallback(ollamaBaseUrl + "/api/generate", gson.toJson(bodyMap), ollamaTimeout);
        GenerateResponse res = gson.fromJson(response.body(), GenerateResponse.class);
        return res != null ? res.response : "UNCERTAIN";
    }

    private HttpResponse<String> sendRequestWithFallback(String url, String jsonBody, int timeoutSec) throws IOException, InterruptedException {
        try { return executeHttpRequest(url, jsonBody, timeoutSec); }
        catch (IOException e) {
            if (url.contains("127.0.0.1")) return executeHttpRequest(url.replace("127.0.0.1", "host.docker.internal"), jsonBody, timeoutSec);
            throw e;
        }
    }

    private HttpResponse<String> executeHttpRequest(String url, String jsonBody, int timeoutSec) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String evaluateResponse(String res) {
        if (res == null) return "UNCERTAIN";
        String u = res.toUpperCase();
        if (u.contains("UNSAFE")) return "UNSAFE";
        if (u.contains("SUSPECT")) return "SUSPECT";
        if (u.contains("RISKY")) return "RISKY";
        if (u.contains("SAFE")) return "SAFE";
        return "UNCERTAIN";
    }

    private double calculateCosineSimilarity(double[] v1, double[] v2) {
        if (v1 == null || v2 == null || v1.length == 0 || v1.length != v2.length) return 0;
        double dot = 0, n1 = 0, n2 = 0;
        for (int i = 0; i < v1.length; i++) { dot += v1[i] * v2[i]; n1 += v1[i] * v1[i]; n2 += v2[i] * v2[i]; }
        double div = Math.sqrt(n1) * Math.sqrt(n2);
        return (div == 0) ? 0 : dot / div;
    }

    private double calculateJaccard(String s1, String s2) {
        Set<String> h1 = tokenize(s1), h2 = tokenize(s2);
        if (h1.isEmpty() || h2.isEmpty()) return 0.0;
        int size1 = h1.size(), size2 = h2.size();
        h1.retainAll(h2);
        return (double) h1.size() / (size1 + size2 - h1.size());
    }

    private Set<String> tokenize(String s) {
        if (s == null) return new HashSet<>();
        return Arrays.stream(s.toLowerCase().split("\\W+")).filter(t -> t.length() > 2).collect(Collectors.toSet());
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static void log(String msg)      { System.out.println(LOG_PREFIX + " " + msg); }
    private static void logError(String msg) { System.err.println(LOG_PREFIX + " [ERROR] " + msg); }

    private static class BatchEmbedResponse { List<double[]> embeddings; }
    private static class GenerateResponse { String response; }
    private static class CachedDatabase { long lastModified; List<PromptExample> examples; }
    private static class PromptExample {
        String text, category; double[] embedding;
        PromptExample() {}
        PromptExample(String t, String c, double[] e) { this.text = t; this.category = c; this.embedding = e; }
    }
    private static class PendingExample { final String text, category; PendingExample(String t, String c) { this.text = t; this.category = c; } }
    private static class ScoredExample { final PromptExample example; final double score; ScoredExample(PromptExample e, double s) { this.example = e; this.score = s; } }
}
