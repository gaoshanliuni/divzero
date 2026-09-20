package dev.mineagent.runtime.integrations.ysm;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Closes every ledger execution path with a replayable outcome.
 */
public final class AppearanceCommandTransaction {
    public static final String FAILURE_CODE = "APPEARANCE_TRANSACTION_FAILED";

    private final AppearanceRequestLedger ledger;

    public AppearanceCommandTransaction(AppearanceRequestLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public Result execute(
            UUID actorId,
            UUID requestId,
            long expectedRevision,
            long currentRevision,
            LongSupplier trustedRevisionProbe,
            Operation operation
    ) {
        return execute(actorId, requestId,
                new AppearanceRequestLedger.Fingerprint(
                        new UUID(0, 0), expectedRevision, "", "", ""),
                currentRevision, trustedRevisionProbe, operation);
    }

    public Result execute(
            UUID actorId,
            UUID requestId,
            AppearanceRequestLedger.Fingerprint fingerprint,
            long currentRevision,
            LongSupplier trustedRevisionProbe,
            Operation operation
    ) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(trustedRevisionProbe, "trustedRevisionProbe");
        Objects.requireNonNull(operation, "operation");
        var decision = ledger.begin(actorId, requestId, fingerprint, currentRevision);
        if (decision.action() != AppearanceRequestLedger.Action.EXECUTE) {
            return new Result(decision.action(), decision.outcome());
        }

        AppearanceRequestLedger.Outcome outcome;
        try {
            outcome = Objects.requireNonNull(operation.execute(), "operation outcome");
        } catch (RuntimeException | LinkageError failure) {
            long trustedRevision = currentRevision;
            try {
                trustedRevision = trustedRevisionProbe.getAsLong();
            } catch (RuntimeException ignored) {
                // The revision captured before begin remains the last trustworthy value.
            }
            outcome = new AppearanceRequestLedger.Outcome(false, FAILURE_CODE, trustedRevision);
        }
        ledger.complete(actorId, requestId, fingerprint, outcome);
        return new Result(AppearanceRequestLedger.Action.EXECUTE, outcome);
    }
    public Result executeAuthorized(UUID actorId,UUID requestId,AppearanceRequestLedger.Fingerprint fingerprint,long currentRevision,
                                    LongSupplier trustedRevisionProbe,java.util.function.BooleanSupplier authorized,Operation operation){
        if(!Objects.requireNonNull(authorized).getAsBoolean())throw new SecurityException("FORBIDDEN");
        return execute(actorId,requestId,fingerprint,currentRevision,trustedRevisionProbe,operation);
    }

    @FunctionalInterface
    public interface Operation {
        AppearanceRequestLedger.Outcome execute();
    }

    public record Result(AppearanceRequestLedger.Action action, AppearanceRequestLedger.Outcome outcome) {
        public Result {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
