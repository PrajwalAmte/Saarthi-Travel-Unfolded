package io.destinationintel.ai;

import java.time.Duration;
import java.util.Map;

/**
 * The one way the rest of Saarthi talks to a language model. Everything that matters for trust (the faithfulness
 * checks, quoted evidence, automatic decisions, undo, versioned storage) sits outside this interface, so swapping
 * Gemini for another provider (a GPT or Claude model, for example) means writing one implementation and changing
 * configuration; nothing else moves.
 */
public interface LlmClient {

    /** Bulk work prefers the model with the most requests to spare; quality work prefers the strongest model. */
    enum Purpose { BULK, QUALITY }

    record Answer(String json, String model, int tokens) {}

    /** The provider's request allowance is spent for now; try again after {@link #untilQuotaReset()}. */
    class BudgetExhausted extends RuntimeException {
        public BudgetExhausted(String message) {
            super(message);
        }
    }

    /** Every model is briefly over its per-minute limit; try again in a few minutes. */
    class RateLimited extends RuntimeException {
        public RateLimited(String message) {
            super(message);
        }
    }

    /** The provider failed in a way that retrying now will not fix. */
    class AiUnavailable extends RuntimeException {
        public AiUnavailable(String message) {
            super(message);
        }
    }

    /** Whether a model can be called on this instance (switched on and configured). */
    boolean active();

    /**
     * Asks for a JSON answer shaped by {@code jsonSchema}, written in standard JSON Schema (lower-case types). Each
     * implementation translates it to its provider's format. The answer is never trusted as-is: callers validate it.
     */
    Answer generateJson(String system, String user, Map<String, Object> jsonSchema, Purpose purpose);

    /** How long until requests are allowed again after {@link BudgetExhausted}. */
    Duration untilQuotaReset();
}
