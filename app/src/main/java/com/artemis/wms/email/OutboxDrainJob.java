package com.artemis.wms.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * V8 outbox pattern: alert creation never blocks on (or fails with) the mail
 * provider — this background job drains the queue. Without a Mailgun key it
 * simply leaves rows pending, which is the correct dev behavior.
 */
@Component
public class OutboxDrainJob {

    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${artemis.mailgun.api-key:}") private String apiKey;
    @Value("${artemis.mailgun.domain:}")  private String domain;
    @Value("${artemis.mailgun.from:alerts@artemis-wms.local}") private String from;

    public OutboxDrainJob(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelay = 30000)
    public void drain() {
        if (apiKey == null || apiKey.isBlank() || domain == null || domain.isBlank()) return;
        List<Map<String, Object>> pending = jdbc.queryForList("""
            SELECT outbox_id, recipient, subject, body FROM email_outbox
            WHERE sent_at IS NULL AND attempts < 5
            ORDER BY created_at LIMIT 50
            """);
        for (Map<String, Object> row : pending) {
            try {
                String form = "from=" + enc(from) + "&to=" + enc((String) row.get("recipient"))
                        + "&subject=" + enc((String) row.get("subject")) + "&text=" + enc((String) row.get("body"));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mailgun.net/v3/" + domain + "/messages"))
                        .header("Authorization", "Basic " + Base64.getEncoder()
                                .encodeToString(("api:" + apiKey).getBytes(StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) {
                    jdbc.update("UPDATE email_outbox SET sent_at = now() WHERE outbox_id = ?", row.get("outbox_id"));
                } else {
                    fail(row, "HTTP " + res.statusCode());
                }
            } catch (Exception e) {
                fail(row, e.getMessage());
            }
        }
    }

    private void fail(Map<String, Object> row, String error) {
        jdbc.update("UPDATE email_outbox SET attempts = attempts + 1, last_error = ? WHERE outbox_id = ?",
            error, row.get("outbox_id"));
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
