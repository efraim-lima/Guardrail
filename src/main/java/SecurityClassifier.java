import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * SecurityClassifier.java - Classificador de Segurança AgentK
 *
 * Foco: Alta Precisão em Classificação de Intenção Kubernetes.
 * Otimizações: Batching, Cache Incremental e Resiliência em Carga.
 */
public class SecurityClassifier {
    private static final String LOG_PREFIX = "[SecurityClassifier]";

    private static final String DEFAULT_REFERENCE_PATH = "BASE.json";
    private static final String DEFAULT_REFERENCE_FALLBACK_PATH = "src/main/java/BASE.json";
    private static final int MAX_CACHE_SIZE = 100;
    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("\\W+");
    
    // Limiares para aceitação do match híbrido (RAG local)
    private static final double SEMANTIC_THRESHOLD = 0.28;
    private static final double HEURISTIC_THRESHOLD = 0.18;
    private static final double HYBRID_THRESHOLD = 0.24;

    private final Gson gson = new Gson();
    private final List<PromptExample> database = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Double> idfByToken = Collections.synchronizedMap(new HashMap<>());
    private volatile boolean isIndexing = true;
    
    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    });

    public SecurityClassifier() {
        CompletableFuture.runAsync(this::initializeDatabase);
    }

    private void initializeDatabase() {
        try {
            String pathStr = envOr("REFERENCE_PROMPTS_PATH", DEFAULT_REFERENCE_PATH);
            Path path = Paths.get(pathStr);
            if (!Files.exists(path)) {
                path = Paths.get(DEFAULT_REFERENCE_FALLBACK_PATH);
            }
            
            if (!Files.exists(path)) {
                logError("Base de conhecimento não encontrada.");
                this.isIndexing = false;
                return;
            }

            List<PromptExample> loadedExamples = parseBaseJson(path);
            if (loadedExamples.isEmpty()) {
                logError("Base de conhecimento vazia ou inválida.");
                this.isIndexing = false;
                return;
            }

            Map<String, Double> localIdf = computeIdf(loadedExamples);
            for (PromptExample example : loadedExamples) {
                example.vector = buildTfIdfVector(example.termFrequency, localIdf);
                example.vectorNorm = calculateVectorNorm(example.vector);
            }

            synchronized (database) {
                database.clear();
                database.addAll(loadedExamples);
            }
            synchronized (idfByToken) {
                idfByToken.clear();
                idfByToken.putAll(localIdf);
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

        while (isIndexing) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "UNCERTAIN";
            }
        }

        // 0. Cache Check
        String cached = cache.get(normalized);
        if (cached != null) return cached;

        try {
            List<PromptExample> dbCopy;
            synchronized (database) { dbCopy = new ArrayList<>(database); }
            if (dbCopy.isEmpty()) {
                return "UNCERTAIN";
            }

            List<String> promptTokenList = tokenize(normalized);
            if (promptTokenList.isEmpty()) {
                return "UNCERTAIN";
            }
            Set<String> promptTokens = new HashSet<>(promptTokenList);

            Map<String, Integer> promptTf = buildTermFrequency(promptTokenList);
            Map<String, Double> promptVector;
            synchronized (idfByToken) {
                promptVector = buildTfIdfVector(promptTf, idfByToken);
            }
            double promptNorm = calculateVectorNorm(promptVector);

            ScoredExample best = null;
            for (PromptExample example : dbCopy) {
                double heuristicScore = calculateJaccard(promptTokens, example.tokens);
                double semanticScore = calculateCosineSimilarity(promptVector, promptNorm, example.vector, example.vectorNorm);
                double hybridScore = (0.60 * semanticScore) + (0.40 * heuristicScore);

                ScoredExample current = new ScoredExample(example, heuristicScore, semanticScore, hybridScore);
                if (best == null || current.hybridScore > best.hybridScore) {
                    best = current;
                }
            }

            if (best == null) {
                return "UNCERTAIN";
            }

            boolean hasHeuristicMatch = best.heuristicScore >= HEURISTIC_THRESHOLD;
            boolean hasSemanticMatch = best.semanticScore >= SEMANTIC_THRESHOLD;
            boolean hasHybridMatch = best.hybridScore >= HYBRID_THRESHOLD;

            if (!hasHeuristicMatch && !hasSemanticMatch && !hasHybridMatch) {
                log((isTestFlow ? "[TEST_FLOW] " : "")
                        + "Sem match relevante no RAG local (heurístico="
                        + String.format("%.3f", best.heuristicScore)
                        + ", semântico=" + String.format("%.3f", best.semanticScore)
                        + ", híbrido=" + String.format("%.3f", best.hybridScore)
                        + "). Retornando UNCERTAIN.");
                cache.put(normalized, "UNCERTAIN");
                return "UNCERTAIN";
            }

            String verdict = best.example.category;
            log((isTestFlow ? "[TEST_FLOW] " : "")
                    + "RAG local → " + verdict
                    + " (heurístico=" + String.format("%.3f", best.heuristicScore)
                    + ", semântico=" + String.format("%.3f", best.semanticScore)
                    + ", híbrido=" + String.format("%.3f", best.hybridScore) + ")");
            cache.put(normalized, verdict);
            return verdict;

        } catch (Exception e) {
            logError("Falha no pipeline de segurança sequencial: " + e.getMessage());
            return "UNCERTAIN";
        }
    }

    private List<PromptExample> parseBaseJson(Path path) throws IOException {
        String content = Files.readString(path);
        JsonArray array = gson.fromJson(content, JsonArray.class);
        if (array == null) {
            return Collections.emptyList();
        }

        List<PromptExample> list = new ArrayList<>();
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }

            JsonObject obj = item.getAsJsonObject();
            String text = getStringOrNull(obj, "content");
            if (text == null || text.isBlank()) {
                continue;
            }

            String category = extractCategory(obj);
            List<String> tokenList = tokenize(text);
            if (tokenList.isEmpty()) {
                continue;
            }
            Set<String> tokens = new HashSet<>(tokenList);
            Map<String, Integer> termFrequency = buildTermFrequency(tokenList);

            list.add(new PromptExample(text, category, tokens, termFrequency));
        }
        return list;
    }

    private String extractCategory(JsonObject obj) {
        String classification = null;
        if (obj.has("metadata") && obj.get("metadata").isJsonObject()) {
            JsonObject metadata = obj.getAsJsonObject("metadata");
            classification = getStringOrNull(metadata, "classification");
        }

        if (classification == null || classification.isBlank()) {
            classification = "UNCERTAIN";
        }

        String normalized = classification.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "SAFE":
            case "SUSPECT":
            case "RISKY":
            case "UNSAFE":
            case "UNCERTAIN":
                return normalized;
            default:
                return "UNCERTAIN";
        }
    }

    private static String getStringOrNull(JsonObject obj, String fieldName) {
        if (obj == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        if (!obj.has(fieldName)) {
            return null;
        }
        JsonElement value = obj.get(fieldName);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsString();
    }

    private static Map<String, Double> computeIdf(List<PromptExample> examples) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (PromptExample example : examples) {
            for (String token : example.tokens) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }

        int documentCount = examples.size();
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            double value = Math.log((documentCount + 1.0) / (entry.getValue() + 1.0)) + 1.0;
            idf.put(entry.getKey(), value);
        }
        return idf;
    }

    private static Map<String, Double> buildTfIdfVector(Map<String, Integer> termFrequency, Map<String, Double> idfMap) {
        Map<String, Double> vector = new HashMap<>();
        if (termFrequency == null || termFrequency.isEmpty()) {
            return vector;
        }

        for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
            String token = entry.getKey();
            int tf = entry.getValue();
            if (tf <= 0) {
                continue;
            }

            double idf = idfMap.getOrDefault(token, 0.0);
            if (idf <= 0.0) {
                continue;
            }

            double weightedTf = 1.0 + Math.log(tf);
            vector.put(token, weightedTf * idf);
        }
        return vector;
    }

    private static double calculateVectorNorm(Map<String, Double> vector) {
        if (vector == null || vector.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : vector.values()) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    private static double calculateCosineSimilarity(Map<String, Double> left, double leftNorm,
                                                    Map<String, Double> right, double rightNorm) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }

        Map<String, Double> smaller = left.size() <= right.size() ? left : right;
        Map<String, Double> larger = left.size() <= right.size() ? right : left;

        double dot = 0.0;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            dot += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0);
        }
        return dot / (leftNorm * rightNorm);
    }

    private static double calculateJaccard(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        for (String token : smaller) {
            if (larger.contains(token)) {
                intersection++;
            }
        }
        int union = left.size() + right.size() - intersection;
        if (union == 0) {
            return 0.0;
        }
        return (double) intersection / union;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        String[] parts = TOKEN_SPLIT_PATTERN.split(text.toLowerCase(Locale.ROOT));
        for (String token : parts) {
            if (token == null) {
                continue;
            }
            String normalized = token.trim();
            if (normalized.length() < 3) {
                continue;
            }
            tokens.add(normalized);
        }
        return tokens;
    }

    private static Map<String, Integer> buildTermFrequency(List<String> tokens) {
        Map<String, Integer> tf = new HashMap<>();
        if (tokens == null || tokens.isEmpty()) {
            return tf;
        }
        for (String token : tokens) {
            tf.merge(token, 1, Integer::sum);
        }
        return tf;
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static void log(String msg)      { System.out.println(LOG_PREFIX + " " + msg); }
    private static void logError(String msg) { System.err.println(LOG_PREFIX + " [ERROR] " + msg); }

    private static class PromptExample {
        final String text;
        final String category;
        final Set<String> tokens;
        final Map<String, Integer> termFrequency;
        Map<String, Double> vector;
        double vectorNorm;

        PromptExample(String text, String category, Set<String> tokens, Map<String, Integer> termFrequency) {
            this.text = text;
            this.category = category;
            this.tokens = tokens;
            this.termFrequency = termFrequency;
            this.vector = Collections.emptyMap();
            this.vectorNorm = 0.0;
        }
    }

    private static class ScoredExample {
        final PromptExample example;
        final double heuristicScore;
        final double semanticScore;
        final double hybridScore;

        ScoredExample(PromptExample example, double heuristicScore, double semanticScore, double hybridScore) {
            this.example = example;
            this.heuristicScore = heuristicScore;
            this.semanticScore = semanticScore;
            this.hybridScore = hybridScore;
        }
    }
}
