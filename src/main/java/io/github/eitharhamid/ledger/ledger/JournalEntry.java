package io.github.eitharhamid.ledger.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record JournalEntry(
        UUID id,
        String idempotencyKey,
        String description,
        OffsetDateTime createdAt,
        List<LedgerLine> lines) {
}
