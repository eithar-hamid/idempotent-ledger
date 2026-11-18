package io.github.eitharhamid.ledger.account;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> ROW_MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("currency"),
            rs.getLong("balance_minor"),
            rs.getBoolean("allow_negative"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account account) {
        jdbc.sql("""
                INSERT INTO account (id, name, currency, balance_minor, allow_negative, created_at)
                VALUES (:id, :name, :currency, 0, :allowNegative, :createdAt)
                """)
                .param("id", account.id())
                .param("name", account.name())
                .param("currency", account.currency())
                .param("allowNegative", account.allowNegative())
                .param("createdAt", account.createdAt())
                .update();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT * FROM account WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * Applies a balance change in one atomic statement. The guard in the WHERE clause is what
     * prevents overdrafts under concurrency: there is no read-then-write window to race through.
     *
     * @return true if the row was updated; false if the account does not exist, has a different
     *         currency, or would go negative.
     */
    public boolean applyDelta(UUID accountId, String currency, long deltaMinor) {
        int updated = jdbc.sql("""
                UPDATE account
                   SET balance_minor = balance_minor + :delta
                 WHERE id = :id
                   AND currency = :currency
                   AND (allow_negative OR balance_minor + :delta >= 0)
                """)
                .param("delta", deltaMinor)
                .param("id", accountId)
                .param("currency", currency)
                .update();
        return updated == 1;
    }
}
