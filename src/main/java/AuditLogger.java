import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utilitário para padronização de logs de auditoria conforme requisitos de segurança.
 */
public class AuditLogger {

    public static void log(String actor, String action, String resource, String outcome, String sourceIp, String contextData) {
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        
        String message = String.format(
            "Timestamp: %s Actor/User Identification: %s Action/Event Type: %s Object/Resource: %s Outcome: %s Source IP Address: %s Contextual Data: %s",
            timestamp,
            (actor == null || actor.isEmpty()) ? "system" : actor,
            (action == null || action.isEmpty()) ? "unknown" : action,
            (resource == null || resource.isEmpty()) ? "none" : resource,
            (outcome == null || outcome.isEmpty()) ? "not_specified" : outcome,
            (sourceIp == null || sourceIp.isEmpty()) ? "unknown" : sourceIp,
            (contextData == null || contextData.isEmpty()) ? "N/A" : contextData
        );
        
        System.out.println(message);
    }

    /**
     * Log específico para o fluxo secundário de testes estatísticos.
     */
    public static void logTestVerdict(String prompt, String verdict, String sourceIp) {
        log("test-agent", "SECONDARY_FLOW_VERDICT", "statistical-analysis", verdict, sourceIp, 
            "prompt_snippet=" + (prompt.length() > 50 ? prompt.substring(0, 50) : prompt));
    }
}
