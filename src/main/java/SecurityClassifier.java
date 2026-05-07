import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SecurityClassifier {
    private static final String LOG_PREFIX = "[SecurityClassifier]";
    private static final String DEFAULT_REFERENCE_PATH = "BASE.json";
    private static final String DEFAULT_REFERENCE_FALLBACK_PATH = "src/main/java/BASE.json";
    private static final int MAX_CACHE_SIZE = 100;

    private static final double SEMANTIC_THRESHOLD = 0.09;
    private static final double HEURISTIC_THRESHOLD = 0.06;
    private static final double HYBRID_THRESHOLD = 0.08;
    private static final double SUSPICIOUS_INTENT_BOOST = 0.12;
    private static final int APPROX_TOKEN_MIN_LENGTH = 4;
    private static final int APPROX_MAX_EDIT_DISTANCE = 1;

    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("\\W+");
    private static final Pattern NON_ASCII_MARKS_PATTERN = Pattern.compile("\\p{M}+");
    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "\\\"content\\\"\\s*:\\s*\\\"((?:\\\\\\.|[^\\\\\"])*)\\\"[\\s\\S]*?"
                    + "\\\"classification\\\"\\s*:\\s*\\\"((?:\\\\\\.|[^\\\\\"])*)\\\"",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> TOKEN_CANONICAL_MAP = buildTokenCanonicalMap();
    private static final Set<String> DELETE_INTENT_TOKENS = Set.of(
            "delete_action", "remove_action", "destroy_action");
    private static final Set<String> K8S_RESOURCE_TOKENS = Set.of(
            "pod", "deployment", "namespace", "configmap", "secret", "service", "ingress",
            "statefulset", "replicaset", "daemonset", "pvc", "persistentvolume", "cluster");

    private final List<PromptExample> database = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Double> idfByToken = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    });

    public SecurityClassifier() {
        initializeDatabase();
    }

    public String classify(String userPrompt) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) {
            return "UNCERTAIN";
        }

        String normalizedPrompt = userPrompt.trim();
        String cached = cache.get(normalizedPrompt);
        if (cached != null) {
            return cached;
        }

        List<PromptExample> dbCopy;
        synchronized (database) {
            dbCopy = new ArrayList<>(database);
        }
        if (dbCopy.isEmpty()) {
            cache.put(normalizedPrompt, "UNCERTAIN");
            return "UNCERTAIN";
        }

        List<String> promptTokenList = tokenize(normalizedPrompt);
        if (promptTokenList.isEmpty()) {
            cache.put(normalizedPrompt, "UNCERTAIN");
            return "UNCERTAIN";
        }

        Set<String> promptTokens = new HashSet<>(promptTokenList);
        Map<String, Integer> promptTf = buildTermFrequency(promptTokenList);
        Map<String, Double> promptVector;
        synchronized (idfByToken) {
            promptVector = buildTfIdfVector(promptTf, idfByToken);
        }
        double promptNorm = calculateVectorNorm(promptVector);
        boolean promptHasDeleteIntent = hasAnyToken(promptTokens, DELETE_INTENT_TOKENS);
        boolean promptHasK8sResource = hasAnyToken(promptTokens, K8S_RESOURCE_TOKENS);

        ScoredExample best = null;
        for (PromptExample example : dbCopy) {
            double exactJaccard = calculateJaccard(promptTokens, example.tokens);
            double softOverlap = calculateSoftTokenOverlap(promptTokens, example.tokens);
            double heuristicScore = Math.max(exactJaccard, softOverlap * 0.92);

            double cosineSimilarity = calculateCosineSimilarity(promptVector, promptNorm, example.vector, example.vectorNorm);
            double semanticScore = (0.78 * cosineSimilarity) + (0.22 * softOverlap);

            double hybridScore = (0.62 * semanticScore) + (0.38 * heuristicScore);

            if (promptHasDeleteIntent && promptHasK8sResource && "SUSPECT".equals(example.category)) {
                hybridScore += SUSPICIOUS_INTENT_BOOST;
            }

            ScoredExample current = new ScoredExample(example, heuristicScore, semanticScore, hybridScore);
            if (best == null || current.hybridScore > best.hybridScore) {
                best = current;
            }
        }

        if (best == null) {
            cache.put(normalizedPrompt, "UNCERTAIN");
            return "UNCERTAIN";
        }

        boolean hasHeuristicMatch = best.heuristicScore >= HEURISTIC_THRESHOLD;
        boolean hasSemanticMatch = best.semanticScore >= SEMANTIC_THRESHOLD;
        boolean hasHybridMatch = best.hybridScore >= HYBRID_THRESHOLD;

        if (!hasHeuristicMatch && !hasSemanticMatch && !hasHybridMatch) {
            cache.put(normalizedPrompt, "UNCERTAIN");
            return "UNCERTAIN";
        }

        cache.put(normalizedPrompt, best.example.category);
        return best.example.category;
    }

    private void initializeDatabase() {
        try {
            Path path = resolveReferencePath();
            if (path == null) {
                logError("Base de conhecimento não encontrada.");
                return;
            }

            List<PromptExample> loadedExamples = parseBaseJson(path);
            if (loadedExamples.isEmpty()) {
                logError("Base de conhecimento vazia ou inválida.");
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
            log("RAG local inicializado com " + loadedExamples.size() + " exemplos de BASE.json.");
        } catch (Exception e) {
            logError("Erro no startup da inteligência RAG: " + e.getMessage());
        }
    }

    private Path resolveReferencePath() {
        String pathStr = envOr("REFERENCE_PROMPTS_PATH", DEFAULT_REFERENCE_PATH);
        Path path = Paths.get(pathStr);
        if (Files.exists(path)) {
            return path;
        }

        Path fallbackPath = Paths.get(DEFAULT_REFERENCE_FALLBACK_PATH);
        if (Files.exists(fallbackPath)) {
            return fallbackPath;
        }

        return null;
    }

    private List<PromptExample> parseBaseJson(Path path) throws IOException {
        String content = Files.readString(path);
        Matcher matcher = ENTRY_PATTERN.matcher(content);

        List<PromptExample> list = new ArrayList<>();
        while (matcher.find()) {
            String text = unescapeJsonString(matcher.group(1));
            String classification = unescapeJsonString(matcher.group(2));

            if (text == null || text.isBlank()) {
                continue;
            }

            String category = normalizeCategory(classification);
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

    private static String normalizeCategory(String classification) {
        if (classification == null || classification.isBlank()) {
            return "UNCERTAIN";
        }

        String normalized = classification.trim().toUpperCase(Locale.ROOT);
        if ("SAFE".equals(normalized)
                || "SUSPECT".equals(normalized)
                || "RISKY".equals(normalized)
                || "UNSAFE".equals(normalized)
                || "UNCERTAIN".equals(normalized)) {
            return normalized;
        }
        return "UNCERTAIN";
    }

    private static String unescapeJsonString(String value) {
        if (value == null) {
            return null;
        }

        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }

            if (i + 1 >= value.length()) {
                out.append('\\');
                break;
            }

            char next = value.charAt(++i);
            switch (next) {
                case '"':
                    out.append('"');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case '/':
                    out.append('/');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (i + 4 < value.length()) {
                        String hex = value.substring(i + 1, i + 5);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            out.append('u');
                        }
                    } else {
                        out.append('u');
                    }
                    break;
                default:
                    out.append(next);
                    break;
            }
        }
        return out.toString();
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

    private static double calculateSoftTokenOverlap(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }

        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        int matches = 0;

        for (String leftToken : smaller) {
            for (String rightToken : larger) {
                if (tokensApproximatelyMatch(leftToken, rightToken)) {
                    matches++;
                    break;
                }
            }
        }

        int denominator = Math.max(left.size(), right.size());
        if (denominator == 0) {
            return 0.0;
        }
        return (double) matches / denominator;
    }

    private static boolean tokensApproximatelyMatch(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }

        if (left.length() >= APPROX_TOKEN_MIN_LENGTH && right.length() >= APPROX_TOKEN_MIN_LENGTH) {
            if (left.startsWith(right) || right.startsWith(left)) {
                return true;
            }

            return levenshteinDistanceWithinLimit(left, right, APPROX_MAX_EDIT_DISTANCE);
        }
        return false;
    }

    private static boolean levenshteinDistanceWithinLimit(String left, String right, int maxDistance) {
        if (left == null || right == null) {
            return false;
        }
        int leftLength = left.length();
        int rightLength = right.length();

        if (Math.abs(leftLength - rightLength) > maxDistance) {
            return false;
        }

        int[] previous = new int[rightLength + 1];
        int[] current = new int[rightLength + 1];

        for (int j = 0; j <= rightLength; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= leftLength; i++) {
            current[0] = i;
            int rowMin = current[0];

            for (int j = 1; j <= rightLength; j++) {
                int substitutionCost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                int substitution = previous[j - 1] + substitutionCost;
                int best = Math.min(Math.min(deletion, insertion), substitution);
                current[j] = best;

                if (best < rowMin) {
                    rowMin = best;
                }
            }

            if (rowMin > maxDistance) {
                return false;
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[rightLength] <= maxDistance;
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

            String normalizedBase = normalizeBaseToken(token);
            if (normalizedBase.isEmpty()) {
                continue;
            }

            String canonical = TOKEN_CANONICAL_MAP.getOrDefault(normalizedBase, normalizedBase);
            boolean wasCanonicalMapped = !canonical.equals(normalizedBase);
            if (canonical.length() < 3 && !wasCanonicalMapped) {
                continue;
            }
            tokens.add(canonical);
        }
        return tokens;
    }

    private static String normalizeBaseToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }

        String normalized = token.trim().toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD);
        normalized = NON_ASCII_MARKS_PATTERN.matcher(normalized).replaceAll("");

        if (normalized.length() > 4 && normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean hasAnyToken(Set<String> tokens, Set<String> expected) {
        if (tokens == null || tokens.isEmpty() || expected == null || expected.isEmpty()) {
            return false;
        }

        for (String token : tokens) {
            if (expected.contains(token)) {
                return true;
            }
        }
        return false;
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

    private static Map<String, String> buildTokenCanonicalMap() {
        Map<String, String> map = new HashMap<>();

        map.put("delete", "delete_action");
        map.put("deletar", "delete_action");
        map.put("deleta", "delete_action");
        map.put("deletando", "delete_action");
        map.put("delecao", "delete_action");
        map.put("deletado", "delete_action");
        map.put("deletada", "delete_action");
        map.put("apaga", "delete_action");
        map.put("apagar", "delete_action");
        map.put("apague", "delete_action");
        map.put("elimina", "delete_action");
        map.put("eliminar", "delete_action");
        map.put("elimine", "delete_action");
        map.put("exclui", "delete_action");
        map.put("excluir", "delete_action");
        map.put("exclua", "delete_action");
        map.put("limpa", "delete_action");
        map.put("limpar", "delete_action");
        map.put("limpe", "delete_action");
        map.put("drop", "delete_action");
        map.put("dropa", "delete_action");

        map.put("remove", "remove_action");
        map.put("remova", "remove_action");
        map.put("remover", "remove_action");
        map.put("remocao", "remove_action");
        map.put("derruba", "remove_action");
        map.put("derrubar", "remove_action");
        map.put("desfaca", "remove_action");
        map.put("desfazer", "remove_action");

        map.put("destrua", "destroy_action");
        map.put("destruir", "destroy_action");
        map.put("destruicao", "destroy_action");
        map.put("extinguir", "destroy_action");
        map.put("extingue", "destroy_action");
        map.put("mate", "destroy_action");
        map.put("matar", "destroy_action");

        map.put("ns", "namespace");
        map.put("deploy", "deployment");
        map.put("deployament", "deployment");
        map.put("sts", "statefulset");
        map.put("rs", "replicaset");
        map.put("ds", "daemonset");
        map.put("cm", "configmap");
        map.put("svc", "service");
        map.put("pv", "persistentvolume");
        map.put("pvc", "pvc");

        return map;
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }

    private static void log(String message) {
        System.out.println(LOG_PREFIX + " " + message);
    }

    private static void logError(String message) {
        System.err.println(LOG_PREFIX + " [ERROR] " + message);
    }

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
