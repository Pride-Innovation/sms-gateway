package com.sms.gateway.users;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class ApiClientEmailService {

    private static final Logger log = LoggerFactory.getLogger(ApiClientEmailService.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String destinationEmail;

    public ApiClientEmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.sender.from:${spring.mail.username:no-reply@smsgateway.local}}") String from,
            @Value("${app.security.client.password.email:}") String destinationEmail
    ) {
        this.mailSender = mailSender;
        this.from = from;
        this.destinationEmail = destinationEmail;
    }

    public boolean sendAccountCreatedEmail(ApiClient client, String rawPassword) {
        return sendCredentialsEmail(client, rawPassword, "created");
    }

    public boolean sendPasswordRegeneratedEmail(ApiClient client, String rawPassword) {
        return sendCredentialsEmail(client, rawPassword, "regenerated");
    }

    private boolean sendCredentialsEmail(ApiClient client, String rawPassword, String action) {
        if (destinationEmail == null || destinationEmail.isBlank()) {
            log.warn("Skipping API client credential email: app.client.password.email is not configured");
            return false;
        }

        if (client == null || client.getUsername() == null || client.getUsername().isBlank()) {
            throw new IllegalArgumentException("ApiClient username is required to send credential email");
        }

        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Raw password is required to send credential email");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(from);
            helper.setTo(destinationEmail.trim());
            helper.setSubject("SMS Gateway - API Client Credentials");

            String html = buildHtml(client, rawPassword, action);
            String text = buildText(client, rawPassword, action);
            helper.setText(text, html);

            ClassPathResource logo = new ClassPathResource("static/images/pride_logo_vertical.png");
            if (logo.exists()) {
                helper.addInline("prideLogo", logo, "image/png");
            }

            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send API client credentials email for username {}: {}", client.getUsername(), e.getMessage());
            return false;
        }
    }

    private String buildHtml(ApiClient client, String rawPassword, String action) {
        String actionText = "regenerated".equalsIgnoreCase(action)
                ? "API client password has been regenerated"
                : "API client account has been created";

        return """
                <html>
                <body style="margin:0;padding:0;background:#f4f6f8;font-family:Arial,Helvetica,sans-serif;color:#1f2937;">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f6f8;padding:24px 0;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="680" cellspacing="0" cellpadding="0" style="max-width:680px;background:#ffffff;border-radius:12px;overflow:hidden;border:1px solid #e5e7eb;">
                          <tr>
                            <td style="background:#0f172a;padding:18px 24px;text-align:center;">
                              <img src="cid:prideLogo" alt="Pride" style="max-height:54px;display:block;margin:0 auto 10px auto;" />
                              <div style="color:#ffffff;font-size:18px;font-weight:700;letter-spacing:0.2px;">SMS Gateway Application</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:26px 28px;">
                              <p style="margin:0 0 12px 0;font-size:16px;">Hello Team,</p>
                              <p style="margin:0 0 16px 0;font-size:14px;line-height:1.6;">The %s. The credentials are below:</p>

                              <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="border:1px solid #e5e7eb;border-radius:8px;background:#f9fafb;">
                                <tr>
                                  <td style="padding:12px 14px;border-bottom:1px solid #e5e7eb;font-size:14px;"><strong>Username:</strong> %s</td>
                                </tr>
                                <tr>
                                  <td style="padding:12px 14px;font-size:14px;"><strong>Password:</strong> %s</td>
                                </tr>
                              </table>

                              <p style="margin:16px 0 0 0;font-size:13px;line-height:1.6;color:#b91c1c;"><strong>Security Notice:</strong> Handle these credentials securely and rotate immediately if exposure is suspected.</p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:14px 24px;background:#f9fafb;border-top:1px solid #e5e7eb;font-size:12px;color:#6b7280;text-align:center;">
                              This is an automated message from SMS Gateway Application. Please do not reply directly to this email.
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(escape(actionText), escape(client.getUsername()), escape(rawPassword));
    }

    private String buildText(ApiClient client, String rawPassword, String action) {
        String actionText = "regenerated".equalsIgnoreCase(action)
                ? "API client password has been regenerated"
                : "API client account has been created";

        return """
                Hello Team,

                The %s.

                Username: %s
                Password: %s

                Security Notice: Handle these credentials securely and rotate immediately if exposure is suspected.

                This is an automated message from SMS Gateway Application.
                """.formatted(actionText, client.getUsername(), rawPassword);
    }

    private String escape(String value) {
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