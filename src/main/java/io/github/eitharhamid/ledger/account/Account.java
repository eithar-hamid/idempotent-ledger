package io.github.eitharhamid.ledger.account;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(
        UUID id,
        String name,
        String currency,
        long balanceMinor,
        boolean allowNegative,
        OffsetDateTime createdAt) {
}
