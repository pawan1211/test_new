
package com.fashion.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final HttpClient httpClient;

    @Value("${RESEND_API_KEY:}")
    private String resendApiKey;

    @Value("${MAIL_FROM:}")
    private String mailFrom;

    @Value("${APP_FRONTEND_URL:https://atelier-one-chi.vercel.app}")
    private String frontendUrl;

    public EmailService() {
        this.httpClient = HttpClient.newHttpClient();
    }

    public boolean isConfigured() {
        return resendApiKey != null && !resendApiKey.isBlank()
                && mailFrom != null && !mailFrom.isBlank();
    }

    public void sendPasswordResetEmail(String to, String resetLink) {

        if (!isConfigured()) {
            log.error("Resend email service is not configured. "
                    + "Check RESEND_API_KEY and MAIL_FROM.");
            return;
        }

        try {
            String htmlBody = buildHtmlBody(resetLink);

            String jsonBody = """
                    {
                      "from": "%s",
                      "to": ["%s"],
                      "subject": "ATELIER ONE — Reset Your Password",
                      "html": "%s"
                    }
                    """.formatted(
                    escapeJson(mailFrom),
                    escapeJson(to),
                    escapeJson(htmlBody)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            jsonBody,
                            StandardCharsets.UTF_8
                    ))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Password reset email sent successfully to {}", to);
            } else {
                log.error(
                        "Resend failed to send password reset email to {}. "
                        + "HTTP status: {}, response: {}",
                        to,
                        response.statusCode(),
                        response.body()
                );
            }

        } catch (Exception e) {
            log.error(
                    "Failed to send password reset email to {}: {}",
                    to,
                    e.getMessage(),
                    e
            );
        }
    }

    private String buildHtmlBody(String resetLink) {
        return "<div style=\"font-family:'DM Sans',Arial,sans-serif;"
                + "max-width:600px;margin:0 auto;padding:40px 20px;color:#211f1b;\">"

                + "<h2 style=\"font-weight:500;letter-spacing:-.05em;\">"
                + "Reset Your Password"
                + "</h2>"

                + "<p style=\"line-height:1.7;\">"
                + "We received a request to reset your ATELIER ONE account password. "
                + "Click the button below to set a new password. "
                + "This link expires in 15 minutes."
                + "</p>"

                + "<a href=\"" + escapeHtml(resetLink) + "\" "
                + "style=\"display:inline-block;background:#174c48;color:#fff8ef;"
                + "padding:14px 28px;text-decoration:none;font-size:12px;"
                + "letter-spacing:.1em;margin:20px 0;\">"
                + "RESET PASSWORD"
                + "</a>"

                + "<p style=\"line-height:1.7;font-size:13px;color:#746f66;\">"
                + "If you did not request a password reset, "
                + "you can safely ignore this email. "
                + "Your password will not be changed."
                + "</p>"

                + "<p style=\"line-height:1.7;font-size:13px;color:#746f66;\">"
                + "If the button does not work, copy and paste this link "
                + "into your browser:<br/>"
                + escapeHtml(resetLink)
                + "</p>"

                + "</div>";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

