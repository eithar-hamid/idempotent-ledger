package io.github.eitharhamid.ledger.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    /** The stored entry for an idempotency key, with the hash of the request that created it. */
    public record StoredEntry(UUID id, String idempotencyKey, String requestHash,
                              String description, OffsetDateTime createdAt) {
    }

    private static final RowMapper<LedgerLine> LINE_MAPPER = (rs, rowNum) -> new LedgerLine(
            rs.getObject("entry_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getLong("amount_minor"),
            rs.getString("currency"));

    private static final RowMapper<StoredEntry> ENTRY_MAPPER = (rs, rowNum) -> new StoredEntry(
            rs.getObject("id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getString("description"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the idempotency key. This is the first write of every transfer.
     *
     * <p>If another transaction holds an uncommitted row with the same key, PostgreSQL blocks this
     * statement until that transaction ends: on commit this returns false, on rollback the insert
     * goes ahead. So two concurrent requests with one key can never both proceed.
     *
     * @return true if this call created the entry; false if the key already exists.
     */
    public boolean tryInsertEntry(UUID id, String idempotencyKey, String requestHash,
                                  String description, OffsetDateTime createdAt) {
        int inserted = jdbc.sql("""
                INSERT INTO journal_entry (id, idempotency_key, request_hash, description, created_at)
                VALUES (:id, :key, :hash, :description, :createdAt)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", id)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("description", description)
                .param("createdAt", createdAt)
                .update();
        return inserted == 1;
    }

    public Optional<StoredEntry> findEntryByKey(String idempotencyKey) {
        return jdbc.sql("SELECT * FROM journal_entry WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(ENTRY_MAPPER)
                .optional();
    }

    public void insertLine(LedgerLine line) {
        jdbc.sql("""
                INSERT INTO ledger_line (entry_id, account_id, amount_minor, currency)
                VALUES (:entryId, :accountId, :amount, :currency)
                """)
                .param("entryId", line.entryId())
                .param("accountId", line.accountId())
                .param("amount", line.amountMinor())
                .param("currency", line.currency())
                .update();
    }

    public List<LedgerLine> findLinesByEntry(UUID entryId) {
        return jdbc.sql("SELECT * FROM ledger_line WHERE entry_id = :entryId ORDER BY id")
                .param("entryId", entryId)
                .query(LINE_MAPPER)
                .list();
    }

    public List<LedgerLine> findLinesByAccount(UUID accountId, int limit) {
        return jdbc.sql("SELECT * FROM ledger_line WHERE account_id = :accountId ORDER BY id DESC LIMIT :limit")
                .param("accountId", accountId)
                .param("limit", limit)
                .query(LINE_MAPPER)
                .list();
    }

    /** Accounts whose stored balance differs from the sum of their ledger lines. Should be empty. */
    public List<UUID> findAccountsWithBalanceDrift() {
        return jdbc.sql("""
                SELECT a.id
                  FROM account a
                  LEFT JOIN ledger_line l ON l.account_id = a.id
                 GROUP BY a.id, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(l.amount_minor), 0)
                """)
                .query(UUID.class)
                .list();
    }

    /** Journal entries whose lines do not sum to zero per currency. Should be empty. */
    public List<UUID> findUnbalancedEntries() {
        return jdbc.sql("""
                SELECT DISTINCT entry_id
                  FROM ledger_line
                 GROUP BY entry_id, currency
                HAVING SUM(amount_minor) <> 0
                """)
                .query(UUID.class)
                .list();
    }

    public long countEntries() {
        return jdbc.sql("SELECT count(*) FROM journal_entry").query(Long.class).single();
    }
}
