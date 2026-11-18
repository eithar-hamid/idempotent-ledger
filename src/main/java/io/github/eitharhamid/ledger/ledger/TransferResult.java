package io.github.eitharhamid.ledger.ledger;

/**
 * @param entry    the journal entry for this idempotency key
 * @param replayed true if the entry already existed and nothing was applied by this call
 */
public record TransferResult(JournalEntry entry, boolean replayed) {
}
