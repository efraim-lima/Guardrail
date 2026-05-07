import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    // --- Thresholds de classificação local (fallback sem Qwen) ---
    private static final double SEMANTIC_THRESHOLD = 0.09;
    private static final double HEURISTIC_THRESHOLD = 0.06;
    private static final double HYBRID_THRESHOLD = 0.08;
    private static final double SUSPICIOUS_INTENT_BOOST = 0.12;
    // Score a partir do qual o RAG local é considerado de alta confiança (pula o Qwen)
    private static final double HIGH_CONFIDENCE_LOCAL_THRESHOLD = 0.45;
    private static final int APPROX_TOKEN_MIN_LENGTH = 4;
    private static final int APPROX_MAX_EDIT_DISTANCE = 1;

    // --- Limiares híbridos por categoria (RAG local) ---
    // Categorias de maior risco usam limiar mais baixo (err on the side of caution)
    private static final Map<String, Double> CATEGORY_HYBRID_THRESHOLDS = buildCategoryThresholds();

    // --- Integração Qwen / Ollama ---
    private static final String DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434/api/generate";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:3b";
    private static final int QWEN_CONTEXT_EXAMPLES_PER_CLASS = 2;
    private static final int OLLAMA_CONNECT_TIMEOUT_MS = 5000;
    // Inferência em CPU pode ser significativamente mais lenta; 90s comporta qwen2.5:1.5b em hardware modesto
    private static final int OLLAMA_READ_TIMEOUT_MS = 90000;

    private static final Pattern OLLAMA_RESPONSE_FIELD = Pattern.compile(
            "\"response\"\\s*:\\s*\"((?:\\\\\\.|[^\\\\\"])*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "SAFE", "SUSPECT", "RISKY", "UNSAFE", "UNCERTAIN");

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

    private final boolean qwenEnabled;
    private final String ollamaEndpoint;
    private final String ollamaModel;

    public SecurityClassifier() {
        this.qwenEnabled = Boolean.parseBoolean(envOr("QWEN_CLASSIFICATION_ENABLED", "true"));
        this.ollamaEndpoint = envOr("OLLAMA_ENDPOINT", DEFAULT_OLLAMA_ENDPOINT);
        this.ollamaModel = envOr("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL);
        initializeDatabase();
        if (qwenEnabled) {
            log("Classificação via Qwen ativada. Endpoint=" + ollamaEndpoint + ", modelo=" + ollamaModel);
        }
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

        // Coleta todos os exemplos pontuados para selecionar contexto few-shot ao Qwen
        List<ScoredExample> allScored = qwenEnabled ? new ArrayList<>(dbCopy.size()) : null;
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
            if (allScored != null) {
                allScored.add(current);
            }
        }

        if (best == null) {
            cache.put(normalizedPrompt, "UNCERTAIN");
            return "UNCERTAIN";
        }

        // --- Caminho rápido: confiança local alta, pula o Qwen ---
        if (best.hybridScore >= HIGH_CONFIDENCE_LOCAL_THRESHOLD) {
            String fastResult = best.example.category;
            cache.put(normalizedPrompt, fastResult);
            return fastResult;
        }

        // --- Classificação via Qwen com contexto do BASE.json ---
        if (qwenEnabled && allScored != null) {
            List<ScoredExample> contextExamples = selectTopExamplesPerCategory(allScored, QWEN_CONTEXT_EXAMPLES_PER_CLASS);
            String qwenResult = classifyWithQwen(normalizedPrompt, contextExamples);
            if (qwenResult != null) {
                log("Qwen classificou: [" + qwenResult + "] para prompt: " + normalizedPrompt);
                cache.put(normalizedPrompt, qwenResult);
                return qwenResult;
            }
            log("Qwen indisponível ou sem resposta válida, usando fallback RAG local.");
        }

        // --- Fallback: RAG local com limiares por categoria ---
        double categoryThreshold = CATEGORY_HYBRID_THRESHOLDS.getOrDefault(
                best.example.category, HYBRID_THRESHOLD);
        boolean hasHeuristicMatch = best.heuristicScore >= HEURISTIC_THRESHOLD;
        boolean hasSemanticMatch  = best.semanticScore  >= SEMANTIC_THRESHOLD;
        boolean hasHybridMatch    = best.hybridScore    >= categoryThreshold;

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

    // =========================================================================
    // Integração Qwen / Ollama
    // =========================================================================

    /**
     * Seleciona os top-K exemplos por categoria para compor o contexto few-shot enviado ao Qwen.
     * Ordena cada lista de categoria por hybridScore decrescente e retém os K melhores.
     */
    private static List<ScoredExample> selectTopExamplesPerCategory(List<ScoredExample> all, int maxPerCategory) {
        Map<String, List<ScoredExample>> byCategory = new HashMap<>();
        for (ScoredExample se : all) {
            byCategory.computeIfAbsent(se.example.category, k -> new ArrayList<>()).add(se);
        }
        List<ScoredExample> result = new ArrayList<>();
        for (List<ScoredExample> catList : byCategory.values()) {
            catList.sort((a, b) -> Double.compare(b.hybridScore, a.hybridScore));
            int take = Math.min(maxPerCategory, catList.size());
            result.addAll(catList.subList(0, take));
        }
        return result;
    }

    /**
     * Monta o prompt few-shot para o Qwen usando exemplos recuperados do BASE.json como contexto.
     */
    private static String buildQwenPrompt(String userPrompt, List<ScoredExample> examples) {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é um classificador de segurança para operações de infraestrutura Kubernetes.\n");
        sb.append("Classifique a solicitação do usuário em UMA das categorias abaixo.\n");
        sb.append("Responda APENAS com o nome exato da categoria, sem explicação adicional.\n\n");
        sb.append("Categorias:\n");
        sb.append("SAFE      - consulta somente-leitura (listar, descrever, obter status, verificar logs)\n");
        sb.append("SUSPECT   - ação que modifica ou remove recursos (deletar, remover, aplicar, escalar para zero)\n");
        sb.append("RISKY     - ação que afeta recursos críticos, produção, acesso privilegiado ou mudanças em massa\n");
        sb.append("UNSAFE    - operação claramente destrutiva, maliciosa ou que viola política de segurança\n");
        sb.append("UNCERTAIN - intenção ambígua ou informação insuficiente para classificar\n\n");
        sb.append("Exemplos de referência extraídos da base de conhecimento:\n");
        for (ScoredExample se : examples) {
            sb.append("[")
              .append(se.example.category)
              .append("] ")
              .append(se.example.text)
              .append("\n");
        }
        sb.append("\nSolicitação a classificar: ").append(userPrompt).append("\nCategoria:");
        return sb.toString();
    }

    /**
     * Envia prompt com contexto few-shot para o Qwen via API do Ollama e retorna a categoria.
     * Retorna null se o Ollama estiver indisponível ou a resposta for inválida.
     */
    private String classifyWithQwen(String userPrompt, List<ScoredExample> contextExamples) {
        if (contextExamples == null || contextExamples.isEmpty()) {
            return null;
        }
        String prompt = buildQwenPrompt(userPrompt, contextExamples);
        String rawResponse = callOllamaGenerate(prompt);
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }
        return extractCategoryFromText(rawResponse);
    }

    /**
     * Realiza HTTP POST à API /api/generate do Ollama.
     * Retorna o conteúdo do campo "response" ou null em caso de falha.
     */
    private String callOllamaGenerate(String promptText) {
        long startMs = System.currentTimeMillis();
        log("Iniciando chamada Ollama | endpoint=" + ollamaEndpoint + " | modelo=" + ollamaModel);
        try {
            URL url = new URL(ollamaEndpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(OLLAMA_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(OLLAMA_READ_TIMEOUT_MS);

            // stream:true → Ollama começa a inferir e enviar tokens imediatamente,
            // tornando o uso de CPU/RAM visível e permitindo log de progresso em tempo real.
            String body = "{\"model\":\"" + ollamaModel
                    + "\",\"prompt\":\"" + escapeForJson(promptText)
                    + "\",\"stream\":true}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            log("Payload enviado ao Ollama, aguardando tokens de resposta...");

            int status = conn.getResponseCode();
            if (status != 200) {
                logError("Ollama retornou HTTP " + status + " | endpoint=" + ollamaEndpoint);
                return null;
            }

            // Lê a resposta linha a linha (cada linha é um JSON de streaming)
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

                StringBuilder accumulated = new StringBuilder();
                int tokenCount = 0;
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }

                    // Extrai o token do campo "response" da linha de streaming
                    String token = extractStreamingResponseToken(line);
                    if (token != null && !token.isEmpty()) {
                        accumulated.append(token);
                        tokenCount++;
                        // Loga a cada 20 tokens para mostrar progresso sem spam
                        if (tokenCount % 20 == 0) {
                            log("Tokens recebidos: " + tokenCount
                                    + " | parcial=[" + accumulated.toString().trim() + "]"
                                    + " | elapsed=" + ((System.currentTimeMillis() - startMs) / 1000) + "s");
                        }
                    }

                    // Linha final: {"done":true,...}
                    if (line.contains("\"done\":true") || line.contains("\"done\": true")) {
                        break;
                    }
                }

                String result = accumulated.toString().trim();
                log("Ollama concluiu: tokens=" + tokenCount
                        + " | resposta=[" + result + "]"
                        + " | elapsed=" + ((System.currentTimeMillis() - startMs) / 1000) + "s");
                return result.isEmpty() ? null : result;
            }
        } catch (Exception e) {
            logError("Falha ao chamar Ollama (" + ((System.currentTimeMillis() - startMs) / 1000) + "s): " + e.getMessage());
            return null;
        }
    }

    /**
     * Extrai o valor do campo "response" de uma linha de streaming do Ollama.
     * Linha de exemplo: {"model":"qwen2.5:3b","response":"token","done":false}
     */
    private static String extractStreamingResponseToken(String line) {
        Matcher m = OLLAMA_RESPONSE_FIELD.matcher(line);
        if (m.find()) {
            return unescapeJsonString(m.group(1));
        }
        return null;
    }

    /**
     * Extrai o campo "response" do JSON retornado pelo Ollama.
     */
    private static String extractOllamaResponseField(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Matcher m = OLLAMA_RESPONSE_FIELD.matcher(json);
        if (m.find()) {
            return unescapeJsonString(m.group(1)).trim();
        }
        // Fallback: retorna o JSON bruto para tentativa de extração direta
        return json.trim();
    }

    /**
     * Examina o texto de resposta do Qwen e extrai a primeira categoria válida encontrada.
     */
    private static String extractCategoryFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        // Resposta direta (mais comum): o modelo responde apenas a categoria
        if (VALID_CATEGORIES.contains(upper)) {
            return upper;
        }
        // Localiza a primeira ocorrência de qualquer categoria na resposta
        int bestPos = Integer.MAX_VALUE;
        String bestCat = null;
        for (String cat : VALID_CATEGORIES) {
            int pos = upper.indexOf(cat);
            if (pos >= 0 && pos < bestPos) {
                bestPos = pos;
                bestCat = cat;
            }
        }
        return bestCat;
    }

    /**
     * Escapa uma string para uso seguro dentro de um valor JSON (campo de string).
     */
    private static String escapeForJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Constrói limiares híbridos por categoria.
     * Categorias de maior risco usam valor mais baixo (mais sensíveis à evidência).
     */
    private static Map<String, Double> buildCategoryThresholds() {
        Map<String, Double> m = new HashMap<>();
        m.put("UNSAFE",    0.05);  // máxima sensibilidade: qualquer evidência é relevante
        m.put("RISKY",     0.06);
        m.put("SUSPECT",   0.06);
        m.put("SAFE",      0.09);  // requer evidência mais sólida para declarar seguro
        m.put("UNCERTAIN", 1.0);   // não é retornado por limiar
        return Collections.unmodifiableMap(m);
    }

    // =========================================================================
    // Mapa canônico de tokens
    // =========================================================================

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
