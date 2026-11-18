package io.github.eitharhamid.ledger.ledger;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID accountId) {
        super("Account " + accountId + " not found");
    }
}
