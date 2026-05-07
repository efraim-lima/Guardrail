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

    // Mínimo de tokens do prompt que devem coincidir com um exemplo do BASE.json
    private static final int KEYWORD_MATCH_MIN = 2;
    // Prompts com mais tokens que esse valor usam Jaccard (varredura heurística completa)
    private static final int LONG_PROMPT_TOKEN_THRESHOLD = 15;

    // --- Integração Qwen / Ollama ---
    private static final String DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434/api/generate";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:3b";
    private static final int OLLAMA_CONNECT_TIMEOUT_MS = 5000;
    // 90s acomoda inferência em CPU para qwen2.5:3b
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

    private final List<PromptExample> database = Collections.synchronizedList(new ArrayList<>());
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
        boolean isLongPrompt = promptTokenList.size() > LONG_PROMPT_TOKEN_THRESHOLD;
        log("Analisando | tokens=" + promptTokenList.size()
                + (isLongPrompt ? " [longo — varredura Jaccard]" : " [curto — interseção direta]"));

        // ETAPA 1: cruzamento com BASE.json por coincidência de palavras-chave
        KeywordMatchResult matchResult = isLongPrompt
                ? fullHeuristicScan(promptTokens, dbCopy)
                : keywordIntersectionScan(promptTokens, dbCopy);

        if (matchResult == null || matchResult.matchCount < KEYWORD_MATCH_MIN) {
            log("Sem coincidência ≥ " + KEYWORD_MATCH_MIN + " palavras em BASE.json → UNCERTAIN");
            cache.put(normalizedPrompt, "UNCERTAIN");
            return "UNCERTAIN";
        }

        log("Match BASE.json: categoria=" + matchResult.category
                + " | palavras_coincidentes=" + matchResult.matchCount
                + " | exemplo=[" + matchResult.bestExample.text + "]");

        // ETAPA 2: double-check semântico com Qwen (confirmação leve da categoria detectada)
        String result = matchResult.category;
        if (qwenEnabled) {
            String qwenVerdict = qwenDoubleCheck(normalizedPrompt, matchResult.category,
                    matchResult.bestExample.text);
            if (qwenVerdict != null) {
                log("Qwen double-check: " + matchResult.category + " → " + qwenVerdict);
                result = qwenVerdict;
            } else {
                log("Qwen indisponível, mantendo resultado do match local: " + result);
            }
        }

        cache.put(normalizedPrompt, result);
        return result;
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

            synchronized (database) {
                database.clear();
                database.addAll(loadedExamples);
            }
            log("Base de conhecimento carregada: " + loadedExamples.size() + " exemplos.");
        } catch (Exception e) {
            logError("Erro ao carregar base de conhecimento: " + e.getMessage());
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
            list.add(new PromptExample(text, category, tokens));
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

    // =========================================================================
    // Integração Qwen / Ollama
    // =========================================================================

    /**
     * Interseção direta: conta quantos tokens do prompt existem em cada exemplo do BASE.json.
     * Retorna o exemplo com maior contagem de palavras coincidentes.
     */
    private static KeywordMatchResult keywordIntersectionScan(Set<String> promptTokens, List<PromptExample> db) {
        PromptExample bestExample = null;
        int bestCount = 0;
        for (PromptExample example : db) {
            int count = 0;
            for (String token : promptTokens) {
                if (example.tokens.contains(token)) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestExample = example;
            }
        }
        if (bestExample == null) {
            return null;
        }
        return new KeywordMatchResult(bestExample.category, bestCount, bestExample);
    }

    /**
     * Varredura heurística completa (prompts longos): usa Jaccard para normalizar a interseção
     * pelo tamanho dos conjuntos, evitando bias por exemplos com muitos tokens.
     */
    private static KeywordMatchResult fullHeuristicScan(Set<String> promptTokens, List<PromptExample> db) {
        PromptExample bestExample = null;
        double bestScore = 0.0;
        int bestCount = 0;
        for (PromptExample example : db) {
            int count = 0;
            for (String token : promptTokens) {
                if (example.tokens.contains(token)) {
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }
            int union = promptTokens.size() + example.tokens.size() - count;
            double jaccard = union > 0 ? (double) count / union : 0.0;
            if (jaccard > bestScore) {
                bestScore = jaccard;
                bestExample = example;
                bestCount = count;
            }
        }
        if (bestExample == null) {
            return null;
        }
        return new KeywordMatchResult(bestExample.category, bestCount, bestExample);
    }

    /**
     * Double-check Qwen: envia prompt mínimo pedindo confirmação da categoria já detectada.
     * Retorna null se o Ollama estiver indisponível ou a resposta for inválida.
     */
    private String qwenDoubleCheck(String userPrompt, String candidateCategory, String matchedExampleText) {
        String prompt = buildDoubleCheckPrompt(userPrompt, candidateCategory, matchedExampleText);
        String rawResponse = callOllamaGenerate(prompt);
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }
        return extractCategoryFromText(rawResponse);
    }

    /**
     * Monta prompt mínimo para o Qwen: apenas referência, categoria candidata e o prompt recebido.
     * Mantém o payload curto para reduzir latência de inferência em CPU.
     */
    private static String buildDoubleCheckPrompt(String userPrompt, String candidateCategory, String matchedExampleText) {
        return "Classifique em UMA palavra: SAFE, SUSPECT, RISKY, UNSAFE ou UNCERTAIN.\n"
                + "Referência: \"" + matchedExampleText + "\" → " + candidateCategory + "\n"
                + "Comando: \"" + userPrompt + "\"\n"
                + "Categoria:";
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

        PromptExample(String text, String category, Set<String> tokens) {
            this.text = text;
            this.category = category;
            this.tokens = tokens;
        }
    }

    private static class KeywordMatchResult {
        final String category;
        final int matchCount;
        final PromptExample bestExample;

        KeywordMatchResult(String category, int matchCount, PromptExample bestExample) {
            this.category = category;
            this.matchCount = matchCount;
            this.bestExample = bestExample;
        }
    }
}
