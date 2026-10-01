package com.fashion.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${MAIL_FROM:}")
    private String mailFrom;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public boolean isConfigured() {
        return mailUsername != null && !mailUsername.isBlank();
    }

    public void sendPasswordResetEmail(String to, String resetLink) {
        if (!isConfigured()) {
            log.error("SMTP is not configured. Password reset email could not be sent to {}", to);
            return;
        }
        try {
            var message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom != null && !mailFrom.isBlank() ? mailFrom : mailUsername);
            helper.setTo(to);
            helper.setSubject("ATELIER ONE — Reset Your Password");
            helper.setText(buildHtmlBody(resetLink), true);
            mailSender.send(message);
            log.info("Password reset email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", to, e.getMessage());
        }
    }

    private String buildHtmlBody(String resetLink) {
        return "<div style=\"font-family:'DM Sans',sans-serif;max-width:600px;margin:0 auto;padding:40px 20px;color:#211f1b;\">"
            + "<h2 style=\"font-weight:500;letter-spacing:-.05em;\">Reset Your Password</h2>"
            + "<p style=\"line-height:1.7;\">We received a request to reset your ATELIER ONE account password. "
            + "Click the button below to set a new password. This link expires in 15 minutes.</p>"
            + "<a href=\"" + resetLink + "\" style=\"display:inline-block;background:#174c48;color:#fff8ef;"
            + "padding:14px 28px;text-decoration:none;font-size:12px;letter-spacing:.1em;margin:20px 0;\">"
            + "RESET PASSWORD</a>"
            + "<p style=\"line-height:1.7;font-size:13px;color:#746f66;\">If you did not request a password reset, "
            + "you can safely ignore this email. Your password will not be changed.</p>"
            + "<p style=\"line-height:1.7;font-size:13px;color:#746f66;\">If the button does not work, copy and paste "
            + "this link into your browser:<br/>" + resetLink + "</p>"
            + "</div>";
    }
}
