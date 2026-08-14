package com.eduplatform.eduplatform_backend.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Outbound email. When {@code app.mail.enabled=true} and a {@link JavaMailSender} is configured,
 * mail is sent over SMTP; otherwise the message is logged (dev/test convenience) so OTP flows
 * remain usable without a live mail provider.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> senderProvider;
    private final boolean enabled;
    private final String from;

    public MailService(ObjectProvider<JavaMailSender> senderProvider,
                       @Value("${app.mail.enabled:false}") boolean enabled,
                       @Value("${app.mail.from:no-reply@eduplatform.local}") String from) {
        this.senderProvider = senderProvider;
        this.enabled = enabled;
        this.from = from;
    }

    public void send(String to, String subject, String body) {
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (!enabled || sender == null) {
            log.info("[mail:disabled] To <{}> | {} | {}", to, subject, body);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
            log.debug("Sent email to {} ({})", to, subject);
        } catch (Exception ex) {
            log.warn("Failed to send email to {} ({}); falling back to log", to, subject, ex);
            log.info("[mail:failed] To <{}> | {} | {}", to, subject, body);
        }
    }

    /** Send a one-time passcode email for a signup/verification flow. */
    public void sendOtp(String to, String code, String purpose, long ttlMinutes) {
        String subject = "Your AzTU EduPlatform verification code";
        String body = """
                Your %s verification code is: %s

                It is valid for %d minutes. If you did not request this, you can ignore this email.
                """.formatted(purpose, code, ttlMinutes);
        send(to, subject, body);
    }
}
