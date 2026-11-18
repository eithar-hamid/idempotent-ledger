package io.github.eitharhamid.ledger.ledger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import io.github.eitharhamid.ledger.account.Account;
import io.github.eitharhamid.ledger.account.AccountRepository;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.CurrencyMismatchException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.IdempotencyConflictException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.InsufficientFundsException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.InvalidTransferException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves money between two accounts as one balanced journal entry.
 *
 * <p>Order of operations inside one database transaction:
 * <ol>
 *   <li><b>Claim the idempotency key</b> (insert-first). If the key exists, return the stored
 *       result instead of applying anything again.</li>
 *   <li><b>Apply balance changes</b> with guarded atomic updates, always in account-id order so
 *       two opposite transfers cannot deadlock.</li>
 *   <li><b>Write the ledger lines</b>, which sum to zero.</li>
 * </ol>
 * Any failure rolls the whole transaction back, including the key claim, so a request that
 * failed (for example on insufficient funds) can be retried with the same key.
 */
@Service
public class TransferService {

    private static final int MAX_KEY_LENGTH = 100;

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public TransferService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional
    public TransferResult transfer(String idempotencyKey, TransferRequest request) {
        validate(idempotencyKey, request);
        String requestHash = RequestHasher.hash(request);
        UUID entryId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (!ledger.tryInsertEntry(entryId, idempotencyKey, requestHash, request.description(), now)) {
            return replay(idempotencyKey, requestHash);
        }

        List<LedgerLine> lines = List.of(
                new LedgerLine(entryId, request.fromAccountId(), -request.amountMinor(), request.currency()),
                new LedgerLine(entryId, request.toAccountId(), request.amountMinor(), request.currency()));

        lines.stream()
                .sorted(Comparator.comparing(LedgerLine::accountId))
                .forEach(this::applyToBalance);
        lines.forEach(ledger::insertLine);

        return new TransferResult(
                new JournalEntry(entryId, idempotencyKey, request.description(), now, lines), false);
    }

    private TransferResult replay(String idempotencyKey, String requestHash) {
        LedgerRepository.StoredEntry stored = ledger.findEntryByKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Key " + idempotencyKey + " conflicted on insert but no entry was found"));
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        JournalEntry entry = new JournalEntry(stored.id(), stored.idempotencyKey(), stored.description(),
                stored.createdAt(), ledger.findLinesByEntry(stored.id()));
        return new TransferResult(entry, true);
    }

    private void applyToBalance(LedgerLine line) {
        if (accounts.applyDelta(line.accountId(), line.currency(), line.amountMinor())) {
            return;
        }
        // The guarded update matched no row. Work out why, for a precise error.
        Account account = accounts.findById(line.accountId())
                .orElseThrow(() -> new AccountNotFoundException(line.accountId()));
        if (!account.currency().equals(line.currency())) {
            throw new CurrencyMismatchException(line.accountId(), line.currency());
        }
        throw new InsufficientFundsException(line.accountId());
    }

    private static void validate(String idempotencyKey, TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidTransferException("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new InvalidTransferException("Source and destination accounts must differ");
        }
    }
}
