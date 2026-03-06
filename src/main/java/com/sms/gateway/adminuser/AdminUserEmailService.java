package com.sms.gateway.adminuser;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class AdminUserEmailService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserEmailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public AdminUserEmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.sender.from:${spring.mail.username:no-reply@smsgateway.local}}") String from
    ) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public boolean sendWelcomeEmail(AdminUser adminUser, String rawPassword) {
        if (adminUser.getEmail() == null || adminUser.getEmail().isBlank()) {
            throw new IllegalArgumentException("Admin email is required to send welcome email");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(from);
            helper.setTo(adminUser.getEmail());
            helper.setSubject("Welcome to SMS Gateway - Admin Account Created");

            String html = buildHtml(adminUser, rawPassword);
            String text = buildText(adminUser, rawPassword);
            helper.setText(text, html);

            ClassPathResource logo = new ClassPathResource("static/images/pride_logo_vertical.png");
            if (logo.exists()) {
                helper.addInline("prideLogo", logo, "image/png");
            }

            mailSender.send(message);
      return true;
        } catch (Exception e) {
            log.warn("Failed to send admin welcome email to {}: {}", adminUser.getEmail(), e.getMessage());
      return false;
        }
    }

    public boolean sendPasswordResetEmail(AdminUser adminUser, String resetLink) {
      if (adminUser.getEmail() == null || adminUser.getEmail().isBlank()) {
        throw new IllegalArgumentException("Admin email is required to send password reset email");
      }

      try {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(from);
        helper.setTo(adminUser.getEmail());
        helper.setSubject("SMS Gateway - Password Reset Request");

        String html = buildResetHtml(adminUser, resetLink);
        String text = buildResetText(adminUser, resetLink);
        helper.setText(text, html);

        ClassPathResource logo = new ClassPathResource("static/images/pride_logo_vertical.png");
        if (logo.exists()) {
          helper.addInline("prideLogo", logo, "image/png");
        }

        mailSender.send(message);
        return true;
      } catch (Exception e) {
        log.warn("Failed to send password reset email to {}: {}", adminUser.getEmail(), e.getMessage());
        return false;
      }
    }

    public boolean sendLoginOtpEmail(AdminUser adminUser, String otp, int ttlMinutes) {
      if (adminUser.getEmail() == null || adminUser.getEmail().isBlank()) {
        throw new IllegalArgumentException("Admin email is required to send login OTP email");
      }

      try {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(from);
        helper.setTo(adminUser.getEmail());
        helper.setSubject("SMS Gateway - Your Login OTP");

        String html = buildLoginOtpHtml(adminUser, otp, ttlMinutes);
        String text = buildLoginOtpText(adminUser, otp, ttlMinutes);
        helper.setText(text, html);

        ClassPathResource logo = new ClassPathResource("static/images/pride_logo_vertical.png");
        if (logo.exists()) {
          helper.addInline("prideLogo", logo, "image/png");
        }

        mailSender.send(message);
        return true;
      } catch (Exception e) {
        log.warn("Failed to send login OTP email to {}: {}", adminUser.getEmail(), e.getMessage());
        return false;
      }
    }

    private String buildHtml(AdminUser adminUser, String rawPassword) {
        String fullName = safe(adminUser.getFirstName()) + (safe(adminUser.getLastName()).isBlank() ? "" : " " + safe(adminUser.getLastName()));
        String displayName = fullName.isBlank() ? adminUser.getUsername() : fullName.trim();

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
                              <p style="margin:0 0 12px 0;font-size:16px;">Hello %s,</p>
                              <p style="margin:0 0 16px 0;font-size:14px;line-height:1.6;">Your admin account has been created successfully in the SMS Gateway application. Use the credentials below to log in:</p>

                              <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="border:1px solid #e5e7eb;border-radius:8px;background:#f9fafb;">
                                <tr>
                                  <td style="padding:12px 14px;border-bottom:1px solid #e5e7eb;font-size:14px;"><strong>Username:</strong> %s</td>
                                </tr>
                                <tr>
                                  <td style="padding:12px 14px;font-size:14px;"><strong>Temporary Password:</strong> %s</td>
                                </tr>
                              </table>

                              <p style="margin:16px 0 0 0;font-size:13px;line-height:1.6;color:#b91c1c;"><strong>Security Notice:</strong> Please change your password immediately after your first login.</p>
                              <p style="margin:14px 0 0 0;font-size:13px;line-height:1.6;color:#4b5563;">If you did not expect this account, contact the SMS Gateway support team right away.</p>
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
                """.formatted(escape(displayName), escape(adminUser.getUsername()), escape(rawPassword));
    }

          private String buildText(AdminUser adminUser, String rawPassword) {
            String fullName = safe(adminUser.getFirstName()) + (safe(adminUser.getLastName()).isBlank() ? "" : " " + safe(adminUser.getLastName()));
            String displayName = fullName.isBlank() ? adminUser.getUsername() : fullName.trim();

            return """
                Hello %s,

                Your admin account has been created successfully in the SMS Gateway application.

                Username: %s
                Temporary Password: %s

                Security Notice: Please change your password immediately after your first login.

                This is an automated message from SMS Gateway Application.
                """.formatted(displayName, adminUser.getUsername(), rawPassword);
          }

    private String buildResetHtml(AdminUser adminUser, String resetLink) {
        String fullName = safe(adminUser.getFirstName()) + (safe(adminUser.getLastName()).isBlank() ? "" : " " + safe(adminUser.getLastName()));
        String displayName = fullName.isBlank() ? adminUser.getUsername() : fullName.trim();
        String escapedLink = escape(resetLink);

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
                              <p style="margin:0 0 12px 0;font-size:16px;">Hello %s,</p>
                              <p style="margin:0 0 16px 0;font-size:14px;line-height:1.6;">We received a request to reset your SMS Gateway account password.</p>

                              <p style="margin:0 0 18px 0;">
                                <a href="%s" style="display:inline-block;background:#0f172a;color:#ffffff;text-decoration:none;padding:12px 18px;border-radius:8px;font-size:14px;font-weight:600;">
                                  Reset Password
                                </a>
                              </p>

                              <p style="margin:0 0 8px 0;font-size:13px;color:#6b7280;">If the button does not work, copy and paste this URL into your browser:</p>
                              <p style="margin:0 0 0 0;font-size:12px;word-break:break-all;color:#111827;">%s</p>

                              <p style="margin:16px 0 0 0;font-size:13px;line-height:1.6;color:#b91c1c;"><strong>Security Notice:</strong> This link expires shortly and can only be used once.</p>
                              <p style="margin:12px 0 0 0;font-size:13px;line-height:1.6;color:#4b5563;">If you did not request this reset, you can ignore this email.</p>
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
                """.formatted(escape(displayName), escapedLink, escapedLink);
    }

    private String buildResetText(AdminUser adminUser, String resetLink) {
        String fullName = safe(adminUser.getFirstName()) + (safe(adminUser.getLastName()).isBlank() ? "" : " " + safe(adminUser.getLastName()));
        String displayName = fullName.isBlank() ? adminUser.getUsername() : fullName.trim();

        return """
                Hello %s,

                We received a request to reset your SMS Gateway account password.

                Use this reset link:
                %s

                Security Notice: This link expires shortly and can only be used once.
                If you did not request this reset, you can ignore this email.
                """.formatted(displayName, resetLink);
    }

    private String buildLoginOtpHtml(AdminUser adminUser, String otp, int ttlMinutes) {
        String fullName = safe(adminUser.getFirstName()) + (safe(adminUser.getLastName()).isBlank() ? "" : " " + safe(adminUser.getLastName()));
        String displayName = fullName.isBlank() ? adminUser.getUsername() : fullName.trim();

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
                              <p style="margin:0 0 12px 0;font-size:16px;">Hello %s,</p>
                              <p style="margin:0 0 16px 0;font-size:14px;line-height:1.6;">Use the one-time passcode below to complete your login:</p>

                              <div style="margin:0 0 18px 0;padding:14px 16px;border:1px solid #e5e7eb;border-radius:8px;background:#f9fafb;font-size:26px;font-weight:700;letter-spacing:4px;text-align:center;">
                                %s
                              </div>

                              <p style="margin:0 0 0 0;font-size:13px;line-height:1.6;color:#b91c1c;"><strong>Security Notice:</strong> This OTP expires in %d minutes and can only be used once.</p>
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
                """.formatted(escape(displayName), escape(otp), ttlMinutes);
    }

    private String buildLoginOtpText(AdminUser adminUser, String otp, int ttlMinutes) {
        String fullName = safe(adminUser.getFirstName()) + (safe(adminUser.getLastName()).isBlank() ? "" : " " + safe(adminUser.getLastName()));
        String displayName = fullName.isBlank() ? adminUser.getUsername() : fullName.trim();

        return """
                Hello %s,

                Use this OTP to complete your SMS Gateway login:
                %s

                This OTP expires in %d minutes and can only be used once.
                """.formatted(displayName, otp, ttlMinutes);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String escape(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
