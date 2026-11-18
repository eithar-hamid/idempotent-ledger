package io.github.eitharhamid.ledger.ledger;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @Positive long amountMinor,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an upper-case ISO 4217 code") String currency,
        @Size(max = 255) String description) {
}
