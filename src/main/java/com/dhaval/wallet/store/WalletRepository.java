package com.dhaval.wallet.store;

import com.dhaval.wallet.store.Money.User;
import com.dhaval.wallet.store.Money.Wallet;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Wallet and user persistence. */
@Repository
public class WalletRepository {

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<Wallet> WALLET_MAPPER = (ResultSet rs, int n) -> new Wallet(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getLong("balance_paise"),
            rs.getString("created_at"));

    /** The only form of a bearer token this service ever persists. */
    public static byte[] tokenHash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Resolves a bearer token to a user, provisioning on first sight.
     *
     * <p>Race-free by construction. {@code ON CONFLICT ... DO UPDATE} rather than
     * {@code DO NOTHING}: DO NOTHING returns no row on conflict and forces a
     * follow-up SELECT, and that gap is exactly the window that makes concurrent
     * first-use flaky. One statement, and every loser of the race is handed the
     * winner's row.
     */
    public User upsertUser(String token) {
        String id = jdbc.queryForObject("""
                INSERT INTO users (token_hash) VALUES (?)
                ON CONFLICT (token_hash) DO UPDATE SET token_hash = EXCLUDED.token_hash
                RETURNING id::text
                """, String.class, (Object) tokenHash(token));
        return new User(id);
    }

    /**
     * Returns the caller's single wallet, creating it on first call.
     *
     * <p>Correctness rests entirely on {@code UNIQUE (user_id)} plus the same
     * {@code ON CONFLICT DO UPDATE ... RETURNING} trick: N concurrent callers each
     * run one statement, Postgres serializes them on the index, exactly one INSERT
     * wins, and everyone else is handed the winner's row. There is no
     * application-side check-then-insert, so there is no window to lose.
     *
     * <p>{@code xmax = 0} distinguishes "I inserted" from "I matched", so the two
     * can be counted apart.
     */
    public GetOrCreate getOrCreateWallet(String userId) {
        return jdbc.queryForObject("""
                INSERT INTO wallets (user_id) VALUES (?::uuid)
                ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id
                RETURNING id::text, user_id::text, balance_paise, created_at::text, (xmax = 0) AS inserted
                """,
                (rs, n) -> new GetOrCreate(WALLET_MAPPER.mapRow(rs, n), rs.getBoolean("inserted")),
                userId);
    }

    /** A get-or-create outcome: the wallet, and whether this call created it. */
    public record GetOrCreate(Wallet wallet, boolean created) {}

    public Wallet getWallet(String id) {
        if (!isUuid(id)) {
            // An id that is not even a UUID is a lookup miss, not a server error.
            throw StoreException.walletNotFound();
        }
        try {
            return jdbc.queryForObject("""
                    SELECT id::text, user_id::text, balance_paise, created_at::text
                      FROM wallets WHERE id = ?::uuid
                    """, WALLET_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw StoreException.walletNotFound();
        }
    }

    /**
     * Puts money into the system from outside, so transfers have something to
     * move. Deliberately NOT a transfer: it lives in its own table and is
     * admin-gated, which keeps "conservation across transfers" a statement that
     * can actually be checked (total balance == total minted).
     *
     * <p>Idempotent on its own key, so a retried funding step during a burst run
     * cannot silently double the float and invalidate the conservation assertion.
     * {@code ON CONFLICT DO NOTHING} reports the duplicate through the affected-row
     * count rather than an exception, so the transaction survives it and no
     * savepoint is needed.
     *
     * @return the wallet after minting, and whether this call actually applied
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MintResult mint(String walletId, String idempotencyKey, long amountPaise) {
        if (!isUuid(walletId)) {
            throw StoreException.walletNotFound();
        }
        // Fail before writing anything if the wallet does not exist, so the
        // idempotency key is not consumed by a request that could never work.
        getWallet(walletId);

        int inserted = jdbc.update("""
                INSERT INTO mints (idempotency_key, wallet_id, amount_paise)
                VALUES (?, ?::uuid, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, idempotencyKey, walletId, amountPaise);

        if (inserted == 1) {
            jdbc.update("""
                    UPDATE wallets SET balance_paise = balance_paise + ?, updated_at = now()
                     WHERE id = ?::uuid
                    """, amountPaise, walletId);
        }
        return new MintResult(getWallet(walletId), inserted == 1);
    }

    public record MintResult(Wallet wallet, boolean applied) {}

    /**
     * The durable totals behind the domain counters, read once at startup.
     *
     * <p>Micrometer counters live in process memory, so a restart -- and a free
     * instance sleeps after ~15 minutes idle -- resets them to zero while the
     * ledger they describe still holds thousands of transfers. That reads as
     * broken instrumentation: {@code /metrics} says 0 succeeded next to an
     * {@code /invariants} reporting 1291. Seeding from the base tables makes the
     * counters lifetime totals of the money, not of the current process.
     *
     * <p>Only the outcomes that leave a row are recoverable. Replays, conflicts
     * and rejects are answered without writing anything, by design -- that is what
     * makes them cheap -- so they have no durable count and stay process-local.
     */
    public CounterBaseline counterBaseline() {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM wallets)                                             AS wallets,
                  (SELECT count(*) FROM transfers WHERE status = 'succeeded')                AS succeeded,
                  (SELECT count(*) FROM transfers WHERE status = 'declined')                 AS declined,
                  (SELECT coalesce(sum(amount_paise), 0) FROM transfers
                    WHERE status = 'succeeded')                                              AS transferred_paise,
                  (SELECT coalesce(sum(amount_paise), 0) FROM mints)                         AS minted_paise
                """, (rs, n) -> new CounterBaseline(
                        rs.getLong("wallets"),
                        rs.getLong("succeeded"),
                        rs.getLong("declined"),
                        rs.getLong("transferred_paise"),
                        rs.getLong("minted_paise")));
    }

    /** Durable counter totals recovered from the base tables at startup. */
    public record CounterBaseline(long wallets, long succeeded, long declined,
                                  long transferredPaise, long mintedPaise) {}

    /** Live audit of every property this service claims, recomputed from base tables. */
    public Invariants checkInvariants() {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*)                        FROM wallets)                          AS wallet_count,
                  (SELECT coalesce(sum(balance_paise), 0) FROM wallets)                          AS total_balance,
                  (SELECT coalesce(sum(amount_paise), 0)  FROM mints)                            AS total_minted,
                  (SELECT coalesce(sum(delta_paise), 0)   FROM ledger_entries)                   AS ledger_sum,
                  (SELECT count(*) FROM wallets   WHERE balance_paise < 0)                       AS negative_balances,
                  (SELECT count(*) FROM transfers WHERE status = 'succeeded')                    AS succeeded,
                  (SELECT count(*) FROM transfers WHERE status = 'declined')                     AS declined,
                  (SELECT count(*) FROM transfers WHERE status = 'pending')                      AS pending
                """, (rs, n) -> Invariants.of(
                        rs.getLong("wallet_count"),
                        rs.getLong("total_balance"),
                        rs.getLong("total_minted"),
                        rs.getLong("ledger_sum"),
                        rs.getLong("negative_balances"),
                        rs.getLong("succeeded"),
                        rs.getLong("declined"),
                        rs.getLong("pending")));
    }

    static boolean isUuid(String s) {
        if (s == null || s.length() != 36) {
            return false;
        }
        try {
            java.util.UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Used by tests and the readiness probe. */
    public List<String> ping() {
        return jdbc.queryForList("SELECT 1", String.class);
    }
}
