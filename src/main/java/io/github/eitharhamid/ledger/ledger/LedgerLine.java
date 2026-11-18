package io.github.eitharhamid.ledger.ledger;

import java.util.UUID;

/** One side of a journal entry. Positive amounts increase the balance, negative decrease it. */
public record LedgerLine(UUID entryId, UUID accountId, long amountMinor, String currency) {
}
