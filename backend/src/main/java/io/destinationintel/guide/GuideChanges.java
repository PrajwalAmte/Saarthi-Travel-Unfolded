package io.destinationintel.guide;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Invalidates cached guides and recomputes coverage after a change to a destination has
 * been committed (so a concurrent reader cannot re-cache the pre-commit state).
 */
@Component
public class GuideChanges {

    private static final Logger log = LoggerFactory.getLogger(GuideChanges.class);

    private final GuideBaseLoader loader;
    private final CoverageService coverage;

    public GuideChanges(GuideBaseLoader loader, CoverageService coverage) {
        this.loader = loader;
        this.coverage = coverage;
    }

    public void destinationChanged(UUID destinationId) {
        if (destinationId == null) {
            return;
        }
        Runnable action = () -> {
            loader.evict(destinationId);
            try {
                coverage.refresh(destinationId);
            } catch (RuntimeException e) {
                log.warn("Coverage refresh failed for {}: {}", destinationId, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
