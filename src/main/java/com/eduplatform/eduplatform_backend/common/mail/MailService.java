package com.eduplatform.eduplatform_backend.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Outbound email.
 *
 * <p>With {@code app.mail.enabled=false} nothing is sent and each message is logged in full at
 * INFO instead. This is not only a dev convenience. It is the documented way an operator reads
 * the admin-bootstrap OTP before SMTP is configured (docs/operations/deployment.md, "Create the
 * first admin"), so the body has to be in that line.
 *
 * <p>With mail enabled, a body is never logged, not even when the send fails. Bodies carry live
 * password-reset links and signup OTPs, and a failure log is exactly what piles up in the
 * container's retained logs while SMTP is down. A failure is logged with recipient and subject
 * only, and is otherwise swallowed. Callers depend on that: a password-reset request answers the
 * same way whether or not the account exists, and an exception here would make the two
 * observably different.
 *
 * <p>Sends run synchronously on the caller's thread, and some callers are inside a
 * {@code @Transactional} method ({@code AccountRecoveryService.requestPasswordReset}, for one),
 * so a slow SMTP server holds that transaction's pooled DB connection for as long as the send
 * takes. The {@code spring.mail.properties.mail.smtp.*timeout} settings in application.properties
 * cap each blocking SMTP step (5 s to connect, 10 s per read or write), so a stalled server now
 * costs about ten seconds rather than forever. Sending after commit
 * ({@code @TransactionalEventListener(phase = AFTER_COMMIT)}) is the next improvement: it
 * releases the connection before the SMTP round trip, and a rolled-back transaction would no
 * longer mail out a token that was never stored.
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
        if (!enabled) {
            log.info("[mail:disabled] To <{}> | {} | {}", to, subject, body);
            return;
        }
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            // Mail is switched on but Spring built no sender (spring.mail.host unset). This counts
            // as a failed send, not as "disabled": the operator asked for real delivery, so the
            // body, and the token in it, stays out of the log.
            log.warn("[mail:failed] To <{}> | {} | app.mail.enabled=true but no mail sender is configured "
                    + "(spring.mail.host); message not sent, body not logged", to, subject);
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
            // The exception is safe to log: Spring's MailException messages carry the SMTP
            // server's reply and the transport cause, never the message text.
            log.warn("[mail:failed] To <{}> | {} | message not sent, body not logged", to, subject, ex);
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
