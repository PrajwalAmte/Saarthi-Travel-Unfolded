package io.destinationintel.it;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

/** Test-only mail sender that records messages instead of delivering them. */
public class CapturingMailSender implements JavaMailSender {

    public final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(SimpleMailMessage... messages) {
        sent.addAll(List.of(messages));
    }

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void send(MimeMessagePreparator... mimeMessagePreparators) {
        throw new UnsupportedOperationException();
    }

    public String lastTokenFor(String email) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            SimpleMailMessage m = sent.get(i);
            if (m.getTo() != null && m.getTo()[0].equals(email)) {
                var matcher = java.util.regex.Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(m.getText());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        throw new IllegalStateException("No sign-in email for " + email);
    }
}
