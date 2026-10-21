package io.destinationintel.request;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends "your guide is ready" emails. Runs on the web-facing instance only (REQUEST_NOTIFICATIONS_ENABLED),
 * so a developer machine pointed at the production database cannot send, or swallow, readers' emails.
 */
@Component
public class RequestNotifier {

    private static final Logger log = LoggerFactory.getLogger(RequestNotifier.class);

    private final RequestNotifications notifications;
    private final boolean enabled;

    public RequestNotifier(RequestNotifications notifications,
                           @Value("${app.notifications.enabled:true}") boolean enabled) {
        this.notifications = notifications;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT1M")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            int sent = notifications.deliverDue();
            if (sent > 0) {
                log.info("Sent {} request notification emails", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Request notification sweep failed: {}", e.toString());
        }
    }
}
