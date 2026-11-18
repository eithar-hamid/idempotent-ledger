package io.github.eitharhamid.ledger.account;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.github.eitharhamid.ledger.ledger.LedgerLine;
import io.github.eitharhamid.ledger.ledger.LedgerRepository;
import io.github.eitharhamid.ledger.ledger.AccountNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    public record CreateAccountRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be an ISO 4217 code") String currency,
            boolean allowNegative) {
    }

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public AccountController(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @PostMapping
    public ResponseEntity<Account> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = new Account(
                UUID.randomUUID(),
                request.name(),
                request.currency().toUpperCase(Locale.ROOT),
                0,
                request.allowNegative(),
                OffsetDateTime.now(ZoneOffset.UTC));
        accounts.insert(account);
        return ResponseEntity.created(URI.create("/accounts/" + account.id())).body(account);
    }

    @GetMapping("/{id}")
    public Account get(@PathVariable UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @GetMapping("/{id}/lines")
    public List<LedgerLine> lines(@PathVariable UUID id,
                                  @RequestParam(defaultValue = "50") int limit) {
        accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        return ledger.findLinesByAccount(id, Math.max(1, Math.min(limit, 500)));
    }
}
