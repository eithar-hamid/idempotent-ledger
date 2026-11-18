package io.github.eitharhamid.ledger.ledger;

import java.util.UUID;

/** Holder for the ledger's domain exceptions; each maps to one HTTP status in ApiExceptionHandler. */
public final class LedgerExceptions {

    private LedgerExceptions() {
    }

    /** The idempotency key was already used for a different request. */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) {
            super("Idempotency key '" + key + "' was already used with a different request");
        }
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(UUID accountId) {
            super("Insufficient funds in account " + accountId);
        }
    }

    public static class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(UUID accountId, String currency) {
            super("Account " + accountId + " does not hold " + currency);
        }
    }

    public static class InvalidTransferException extends RuntimeException {
        public InvalidTransferException(String message) {
            super(message);
        }
    }
}
