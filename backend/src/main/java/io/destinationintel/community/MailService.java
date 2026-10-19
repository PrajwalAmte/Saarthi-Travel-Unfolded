package io.destinationintel.community;

import io.destinationintel.common.ApiException;
import io.destinationintel.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Sends transactional email. Email sign-in is reported as unavailable when SMTP is not configured. */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final boolean configured;
    private final String from;

    public MailService(ObjectProvider<JavaMailSender> sender, Environment env, AppProperties props) {
        this.sender = sender;
        this.configured = StringUtils.hasText(env.getProperty("spring.mail.host"));
        this.from = props.mail().from();
    }

    public boolean configured() {
        return configured && sender.getIfAvailable() != null;
    }

    public void send(String to, String subject, String body) {
        JavaMailSender mail = sender.getIfAvailable();
        if (!configured || mail == null) {
            throw ApiException.unavailable("email_unavailable",
                    "Email sign-in is not configured on this server. Ask the operator to set SMTP_HOST.");
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        try {
            mail.send(msg);
        } catch (MailException e) {
            log.warn("Email delivery failed: {}", e.getClass().getSimpleName());
            throw ApiException.unavailable("email_delivery_failed",
                    "We could not send the sign-in email right now. Please try again later.");
        }
    }
}
