package io.github.eitharhamid.ledger.ledger;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";

    /** Result of the ledger's self-checks. Both lists are empty when the ledger is consistent. */
    public record IntegrityReport(long journalEntries, List<UUID> accountsWithBalanceDrift,
                                  List<UUID> unbalancedEntries, boolean consistent) {
    }

    private final TransferService transfers;
    private final LedgerRepository ledger;

    public TransferController(TransferService transfers, LedgerRepository ledger) {
        this.transfers = transfers;
        this.ledger = ledger;
    }

    /**
     * 201 when the transfer is applied; 200 with {@code Idempotent-Replayed: true} when the same
     * key and request were already applied (nothing moves twice).
     */
    @PostMapping("/transfers")
    public ResponseEntity<JournalEntry> transfer(@RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                                 @Valid @RequestBody TransferRequest request) {
        TransferResult result = transfers.transfer(idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(result.entry());
        }
        return ResponseEntity.created(URI.create("/transfers/" + result.entry().id()))
                .header(REPLAYED, "false")
                .body(result.entry());
    }

    @GetMapping("/ledger/integrity")
    public IntegrityReport integrity() {
        List<UUID> drift = ledger.findAccountsWithBalanceDrift();
        List<UUID> unbalanced = ledger.findUnbalancedEntries();
        return new IntegrityReport(ledger.countEntries(), drift, unbalanced,
                drift.isEmpty() && unbalanced.isEmpty());
    }
}
