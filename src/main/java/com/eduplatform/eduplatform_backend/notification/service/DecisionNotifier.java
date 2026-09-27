package com.eduplatform.eduplatform_backend.notification.service;

import com.eduplatform.eduplatform_backend.common.enums.NotificationChannel;
import com.eduplatform.eduplatform_backend.common.mail.MailService;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Tells experts and course authors what the moderators decided. Approving or rejecting used to
 * change a status and store the note, and nothing else: the note the dashboard said was "sent"
 * reached nobody, and an applicant — who holds only the USER role and so cannot even open the
 * dashboard — learned about the decision only by trying to sign in.
 *
 * <p>Two channels: an in-app notification, which the dashboard bell and its live WebSocket show,
 * and an email with the note and the dashboard address, which is the one that reaches an
 * applicant. With {@code app.mail.enabled=false} the email is logged instead, like every other.
 * Both are written in the recipient's language (see {@link Text}).
 *
 * <p>Runs after the decision has committed, in a transaction of its own. A failure here is logged
 * and swallowed: the decision stands whether or not the message could be delivered, and the
 * moderator's request has already succeeded.
 */
@Component
public class DecisionNotifier {

    private static final Logger log = LoggerFactory.getLogger(DecisionNotifier.class);

    private final UserRepository users;
    private final NotificationDispatcher dispatcher;
    private final MailService mail;
    private final String portalUrl;

    public DecisionNotifier(UserRepository users, NotificationDispatcher dispatcher, MailService mail,
                            @Value("${app.frontend.portal-url:http://localhost:3001}") String portalUrl) {
        this.users = users;
        this.dispatcher = dispatcher;
        this.mail = mail;
        this.portalUrl = portalUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTutorDecided(DecisionEvents.TutorDecided ev) {
        try {
            User user = users.findById(ev.userId()).orElse(null);
            if (user == null) return;
            Text text = Text.of(user);
            String template;
            String title;
            String body;
            if (ev.approved()) {
                template = "tutor.approved";
                title = text.pick("Your expert application was approved",
                        "Ekspert müraciətiniz təsdiqləndi");
                body = text.pick("You can now sign in to the dashboard and create trainings.",
                        "İndi ekspert portalına daxil olub kurslar yarada bilərsiniz.");
            } else if (ev.revoked()) {
                template = "tutor.rejected";
                title = text.pick("Your expert approval was withdrawn",
                        "Ekspert təsdiqiniz geri götürüldü");
                body = text.pick("You can no longer create or edit trainings.",
                        "Artıq kurs yarada və ya redaktə edə bilməzsiniz.");
            } else {
                template = "tutor.rejected";
                title = text.pick("Your expert application was not approved",
                        "Ekspert müraciətiniz təsdiqlənmədi");
                body = text.pick("You can update your profile and submit it again from the dashboard.",
                        "Profilinizi yeniləyib ekspert portalından yenidən göndərə bilərsiniz.");
            }
            deliver(user, text, template, title, body, ev.note(), Map.of("approved", ev.approved()));
        } catch (Exception ex) {
            log.warn("Could not notify user {} about an expert decision", ev.userId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCourseDecided(DecisionEvents.CourseDecided ev) {
        try {
            User user = users.findById(ev.ownerUserId()).orElse(null);
            if (user == null) return;
            Text text = Text.of(user);
            String template = ev.published() ? "course.published" : "course.rejected";
            String title = ev.published()
                    ? text.pick("Your training \"" + ev.courseTitle() + "\" is published",
                            "“" + ev.courseTitle() + "” kursunuz dərc olundu")
                    : text.pick("Your training \"" + ev.courseTitle() + "\" needs changes",
                            "“" + ev.courseTitle() + "” kursunuza düzəlişlər lazımdır");
            String body = ev.published()
                    ? text.pick("It is now listed in the public catalogue.",
                            "Kurs artıq açıq kataloqda göstərilir.")
                    : text.pick("It was sent back for changes. Edit it and submit it for review again.",
                            "Kurs düzəliş üçün geri qaytarıldı. Onu redaktə edib yenidən yoxlamaya göndərin.");
            deliver(user, text, template, title, body, ev.note(), Map.of("courseId", ev.courseId().toString()));
        } catch (Exception ex) {
            log.warn("Could not notify user {} about a course decision", ev.ownerUserId(), ex);
        }
    }

    private void deliver(User user, Text text, String template, String title, String body, String note,
                         Map<String, Object> payload) {
        String withNote = note == null || note.isBlank() ? body
                : body + "\n\n" + text.pick("Note from the reviewer: ", "Administratorun qeydi: ") + note.trim();
        Map<String, Object> data = new HashMap<>(payload);
        if (note != null && !note.isBlank()) data.put("note", note.trim());
        // The locale travels with the notification, so a client can tell which language the
        // stored title and body are in.
        data.put("locale", text.locale());
        dispatcher.dispatch(user, template, title, withNote, data, NotificationChannel.IN_APP);
        mail.send(user.getEmail(), title,
                text.pick("Hello ", "Salam, ") + user.getFirstName() + ",\n\n" + withNote
                        + "\n\n" + text.pick("Sign in to the dashboard: ", "Ekspert portalına daxil olun: ")
                        + portalUrl
                        + "\n\nAzTU EduPlatform");
    }

    /**
     * The recipient's language: Azerbaijani for an account whose locale is az, English for every
     * other (en, and ru, which nothing is translated into yet). The message is written once and
     * stored as sent, so it is chosen here, by whom it is for: the dashboard bell and the
     * Notifications page show the stored title as it is, and these used to be English for
     * everyone — English text in the Azerbaijani interface, and English mail to az applicants.
     */
    private record Text(boolean az) {

        static Text of(User user) {
            return new Text(user.getLocale() != null && "az".equalsIgnoreCase(user.getLocale().trim()));
        }

        String pick(String english, String azerbaijani) {
            return az ? azerbaijani : english;
        }

        String locale() {
            return az ? "az" : "en";
        }
    }
}
