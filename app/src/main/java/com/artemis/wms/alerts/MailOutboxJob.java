package com.artemis.wms.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains email_outbox through Mailgun (shared Squires Enterprises sending
 * domain). Runs outside any request, so it operates as platform tier: the
 * outbox holds no tenant column and system_alert rows are joined for context
 * only. A dead Mailgun key never touches warehouse operations — rows just
 * accumulate attempts and last_error.
 */
@Component
public class MailOutboxJob {
    private static final Logger log = LoggerFactory.getLogger(MailOutboxJob.class);
    private final JdbcTemplate jdbc;
    private final RestClient http = RestClient.create();
    private final String apiKey, domain, from;

    public MailOutboxJob(JdbcTemplate jdbc, @Value("${artemis.mail.mailgun-api-key:}") String apiKey,
                         @Value("${artemis.mail.mailgun-domain:}") String domain, @Value("${artemis.mail.from}") String from) {
        this.jdbc = jdbc; this.apiKey = apiKey; this.domain = domain; this.from = from;
    }

    @Scheduled(fixedDelayString = "${artemis.mail.drain-fixed-delay-ms:30000}", initialDelay = 20000)
    public void drain() {
        if (apiKey.isBlank() || domain.isBlank()) return;
        List<Map<String, Object>> pending = jdbc.queryForList(
            "SELECT outbox_id, recipient::text AS recipient, subject, body FROM email_outbox WHERE sent_at IS NULL AND attempts < 5 ORDER BY created_at LIMIT 25");
        for (Map<String, Object> m : pending) {
            UUID id = (UUID) m.get("outbox_id");
            try {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("from", from); form.add("to", (String) m.get("recipient"));
                form.add("subject", (String) m.get("subject")); form.add("text", (String) m.get("body"));
                http.post().uri("https://api.mailgun.net/v3/" + domain + "/messages")
                    .headers(h -> h.setBasicAuth("api", apiKey)).body(form).retrieve().toBodilessEntity();
                jdbc.update("UPDATE email_outbox SET sent_at = now(), attempts = attempts + 1, last_error = NULL WHERE outbox_id = ?", id);
            } catch (Exception e) {
                log.warn("Mail send failed for {}: {}", id, e.getMessage());
                jdbc.update("UPDATE email_outbox SET attempts = attempts + 1, last_error = ? WHERE outbox_id = ?", e.getMessage(), id);
            }
        }
    }
}
