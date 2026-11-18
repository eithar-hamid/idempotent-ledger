package io.github.eitharhamid.ledger.ledger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.eitharhamid.ledger.TestcontainersConfiguration;
import io.github.eitharhamid.ledger.account.Account;
import io.github.eitharhamid.ledger.account.AccountRepository;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.CurrencyMismatchException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.IdempotencyConflictException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.InsufficientFundsException;
import io.github.eitharhamid.ledger.ledger.LedgerExceptions.InvalidTransferException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferServiceTest {

    private static final String AED = "AED";

    @Autowired TransferService transfers;
    @Autowired AccountRepository accounts;
    @Autowired LedgerRepository ledger;

    @AfterEach
    void ledgerStaysConsistent() {
        assertThat(ledger.findAccountsWithBalanceDrift()).as("balance = sum of lines").isEmpty();
        assertThat(ledger.findUnbalancedEntries()).as("every entry sums to zero").isEmpty();
    }

    @Test
    void transferMovesMoneyAsOneBalancedEntry() {
        UUID alice = fundedAccount(1_000);
        UUID bob = account(AED, false);

        TransferResult result = transfers.transfer(key(), request(alice, bob, 250));

        assertThat(result.replayed()).isFalse();
        assertThat(result.entry().lines()).extracting(LedgerLine::amountMinor).containsExactlyInAnyOrder(-250L, 250L);
        assertThat(balance(alice)).isEqualTo(750);
        assertThat(balance(bob)).isEqualTo(250);
    }

    @Test
    void retryWithSameKeyReturnsOriginalResultAndMovesNothing() {
        UUID alice = fundedAccount(1_000);
        UUID bob = account(AED, false);
        String key = key();

        TransferResult first = transfers.transfer(key, request(alice, bob, 100));
        TransferResult retry = transfers.transfer(key, request(alice, bob, 100));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.entry().id()).isEqualTo(first.entry().id());
        assertThat(retry.entry().lines()).hasSize(2);
        assertThat(balance(alice)).isEqualTo(900);
        assertThat(balance(bob)).isEqualTo(100);
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        UUID alice = fundedAccount(1_000);
        UUID bob = account(AED, false);
        String key = key();
        transfers.transfer(key, request(alice, bob, 100));

        assertThatThrownBy(() -> transfers.transfer(key, request(alice, bob, 999)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(balance(alice)).isEqualTo(900);
    }

    @Test
    void failedTransferReleasesKeySoTheClientCanRetry() {
        UUID alice = fundedAccount(50);
        UUID bob = account(AED, false);
        String key = key();

        assertThatThrownBy(() -> transfers.transfer(key, request(alice, bob, 100)))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(ledger.findEntryByKey(key)).as("rolled back with the transfer").isEmpty();
        assertThat(balance(alice)).isEqualTo(50);

        fund(alice, 100);
        TransferResult retry = transfers.transfer(key, request(alice, bob, 100));

        assertThat(retry.replayed()).isFalse();
        assertThat(balance(alice)).isEqualTo(50);
        assertThat(balance(bob)).isEqualTo(100);
    }

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        UUID alice = fundedAccount(100);
        UUID bob = account(AED, false);

        // 20 concurrent debits of 10 against a balance of 100: exactly 10 can succeed.
        List<Future<TransferResult>> results = runConcurrently(20,
                i -> () -> transfers.transfer(key(), request(alice, bob, 10)));

        long succeeded = 0;
        long rejected = 0;
        for (Future<TransferResult> f : results) {
            try {
                f.get();
                succeeded++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(InsufficientFundsException.class);
                rejected++;
            }
        }
        assertThat(succeeded).isEqualTo(10);
        assertThat(rejected).isEqualTo(10);
        assertThat(balance(alice)).isZero();
        assertThat(balance(bob)).isEqualTo(100);
    }

    @Test
    void concurrentRetriesOfOneKeyApplyExactlyOnce() throws Exception {
        UUID alice = fundedAccount(1_000);
        UUID bob = account(AED, false);
        String key = key();

        // A client that times out and retries aggressively: 10 copies of one request at once.
        List<Future<TransferResult>> results = runConcurrently(10,
                i -> () -> transfers.transfer(key, request(alice, bob, 100)));

        List<TransferResult> done = new ArrayList<>();
        for (Future<TransferResult> f : results) {
            done.add(f.get());
        }
        assertThat(done).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(done).extracting(r -> r.entry().id()).containsOnly(done.get(0).entry().id());
        assertThat(balance(alice)).isEqualTo(900);
        assertThat(balance(bob)).isEqualTo(100);
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        UUID alice = fundedAccount(10_000);
        UUID bob = fundedAccount(10_000);

        // Half the tasks move alice -> bob, half bob -> alice. Without a fixed lock order,
        // two such transactions can each hold one row and wait for the other's.
        List<Future<TransferResult>> results = runConcurrently(40, i -> () -> i % 2 == 0
                ? transfers.transfer(key(), request(alice, bob, 7))
                : transfers.transfer(key(), request(bob, alice, 7)));

        for (Future<TransferResult> f : results) {
            f.get(); // throws if any transaction was aborted as a deadlock victim
        }
        assertThat(balance(alice) + balance(bob)).isEqualTo(20_000);
        assertThat(balance(alice)).isEqualTo(10_000);
    }

    @Test
    void rejectsCurrencyMismatchAndSelfTransfer() {
        UUID alice = fundedAccount(1_000);
        UUID usdAccount = account("USD", false);

        assertThatThrownBy(() -> transfers.transfer(key(), request(alice, usdAccount, 10)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> transfers.transfer(key(), request(alice, alice, 10)))
                .isInstanceOf(InvalidTransferException.class);
        assertThat(balance(alice)).isEqualTo(1_000);
    }

    @Test
    void rejectsUnknownAccount() {
        UUID alice = fundedAccount(1_000);

        assertThatThrownBy(() -> transfers.transfer(key(), request(alice, UUID.randomUUID(), 10)))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(balance(alice)).isEqualTo(1_000);
    }

    // --- helpers ---------------------------------------------------------------------------

    private interface TaskFactory {
        Callable<TransferResult> task(int index);
    }

    /** Starts all tasks at the same instant to maximise contention. */
    private static List<Future<TransferResult>> runConcurrently(int count, TaskFactory factory)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferResult>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Callable<TransferResult> task = factory.task(i);
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("all transfers finished").isTrue();
        return futures;
    }

    private UUID account(String currency, boolean allowNegative) {
        UUID id = UUID.randomUUID();
        accounts.insert(new Account(id, "test-" + id, currency, 0, allowNegative, OffsetDateTime.now(ZoneOffset.UTC)));
        return id;
    }

    /** A customer account funded from a fresh "external funding" account that may go negative. */
    private UUID fundedAccount(long amountMinor) {
        UUID id = account(AED, false);
        fund(id, amountMinor);
        return id;
    }

    private void fund(UUID accountId, long amountMinor) {
        UUID funding = account(AED, true);
        transfers.transfer(key(), request(funding, accountId, amountMinor));
    }

    private long balance(UUID accountId) {
        return accounts.findById(accountId).orElseThrow().balanceMinor();
    }

    private static TransferRequest request(UUID from, UUID to, long amountMinor) {
        return new TransferRequest(from, to, amountMinor, AED, "test transfer");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
