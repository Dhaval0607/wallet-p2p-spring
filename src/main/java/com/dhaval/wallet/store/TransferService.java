package com.dhaval.wallet.store;

import com.dhaval.wallet.store.Money.Transfer;
import com.dhaval.wallet.store.Money.TransferRequest;
import com.dhaval.wallet.store.Money.TransferResult;
import com.dhaval.wallet.store.Money.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The transaction that is the heart of this service.
 *
 * <p>THE MECHANISM, in one place:
 *
 * <ol>
 *   <li>One READ COMMITTED transaction covers everything: the idempotency key,
 *       the debit, the credit and the ledger rows. They commit together or not
 *       at all.
 *
 *   <li>Idempotency is claimed FIRST, by inserting the transfer row with
 *       {@code ON CONFLICT (requester_user_id, idempotency_key) DO NOTHING}. That
 *       unique index is the lock: a concurrent caller with the same key blocks on
 *       it until the first transaction finishes, then finds zero rows inserted and
 *       reads the committed outcome. Reporting the duplicate through the row count
 *       rather than a {@code 23505} exception matters in JDBC -- an exception would
 *       abort the transaction and force a savepoint dance to recover.
 *
 *   <li>Both wallet rows are then locked with {@code SELECT ... FOR NO KEY UPDATE},
 *       issued in ascending wallet-id order. Two things are load-bearing here and
 *       BOTH are required:
 *       <ul>
 *         <li><b>Ascending order</b> gives a total order on the blocking locks, so
 *             the waits-for graph between two transfers cannot contain a cycle.
 *             This is what makes A&rarr;B and B&rarr;A safe at the same instant.
 *
 *         <li><b>{@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}</b>, is the
 *             correct strength. The INSERT in step 2 has already taken
 *             {@code FOR KEY SHARE} on both wallets to check its foreign keys, in
 *             an order Postgres picks rather than one I control.
 *             {@code FOR UPDATE} conflicts with {@code FOR KEY SHARE}, so two
 *             transfers would each hold a shared FK lock the other needed to
 *             upgrade past -- a deadlock no ordering can break, because it forms
 *             before the ordered section begins. {@code FOR NO KEY UPDATE} does
 *             not conflict with {@code FOR KEY SHARE} while still excluding every
 *             other writer, which is exactly the strength an update to a non-key
 *             column needs. Measured on the Go build of this same design: with
 *             {@code FOR UPDATE} a 400-transfer burst produced 428 deadlocks and
 *             133 HTTP 500s; with {@code FOR NO KEY UPDATE}, zero of each.
 *       </ul>
 *
 *   <li>The overdraft check happens while both rows are locked, so the balance
 *       read cannot move underneath it. The debit UPDATE <i>also</i> carries
 *       {@code AND balance_paise >= ?}, and the column carries
 *       {@code CHECK (balance_paise >= 0)}: three independent layers, none of
 *       which is load-bearing alone.
 *
 *   <li>A decline writes no money at all -- it is discovered before any UPDATE --
 *       so there is no partial application to undo.
 * </ol>
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    /**
     * Deadlock and serialization failures are the only two aborts a plain retry
     * can fix; both mean "your transaction did nothing, try again". The sorted
     * lock discipline above is what <i>prevents</i> them. This is the safety net,
     * and the counter it feeds staying at zero is the evidence that the
     * prevention, not the retry, is doing the work.
     */
    private static final int MAX_TX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WalletRepository wallets;
    private Consumer<String> onRetry = sqlState -> {};

    public TransferService(JdbcTemplate jdbc, TransactionTemplate txTemplate, WalletRepository wallets) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.tx = txTemplate;
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Installs the retry observer, so retries can be counted. */
    public void onRetry(Consumer<String> observer) {
        if (observer != null) {
            this.onRetry = observer;
        }
    }

    /**
     * The canonical hash of a transfer's <i>meaning</i>, used to detect a reused
     * idempotency key carrying a different request. It hashes the normalized
     * fields rather than the raw bytes, so reformatted JSON -- different
     * whitespace, different key order -- is a retry, not a spurious conflict.
     */
    public static byte[] fingerprint(String from, String to, long amountPaise) {
        try {
            String canonical = "v1|" + from + "|" + to + "|" + amountPaise;
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Moves money between two wallets exactly once. */
    public TransferResult transfer(TransferRequest req) {
        if (req.fromWalletId().equals(req.toWalletId())) {
            throw StoreException.sameWallet();
        }
        byte[] fingerprint = fingerprint(req.fromWalletId(), req.toWalletId(), req.amountPaise());

        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_TX_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                onRetry.accept(sqlState(last));
                backoff(attempt);
            }
            try {
                return tx.execute(status -> attempt(req, fingerprint));
            } catch (ConcurrencyFailureException e) {
                // Spring maps SQLSTATE class 40 -- deadlock_detected (40P01) and
                // serialization_failure (40001) -- onto this hierarchy, and its
                // subclasses CannotAcquireLockException and
                // DeadlockLoserDataAccessException are caught here too.
                last = e;
            }
        }
        throw new IllegalStateException(
                "transfer failed after " + MAX_TX_ATTEMPTS + " attempts", last);
    }

    private TransferResult attempt(TransferRequest req, byte[] fingerprint) {
        // --- Step 1: claim the idempotency key inside the money transaction. ---
        List<TransferRow> claimed = jdbc.query("""
                INSERT INTO transfers (
                    idempotency_key, requester_user_id, from_wallet_id,
                    to_wallet_id, amount_paise, status, request_fingerprint
                ) VALUES (?, ?::uuid, ?::uuid, ?::uuid, ?, 'pending', ?)
                ON CONFLICT (requester_user_id, idempotency_key) DO NOTHING
                RETURNING id::text, created_at::text
                """,
                (rs, n) -> new TransferRow(rs.getString(1), rs.getString(2)),
                req.idempotencyKey(), req.requesterUserId(), req.fromWalletId(),
                req.toWalletId(), req.amountPaise(), fingerprint);

        if (claimed.isEmpty()) {
            // We lost the race for this key. The winner has necessarily COMMITTED:
            // an in-flight duplicate would have made us block, and an aborted one
            // would have let our INSERT through. Each statement in READ COMMITTED
            // takes a fresh snapshot, so its terminal row is visible to us now,
            // in this same transaction.
            return replay(req, fingerprint);
        }

        String transferId = claimed.get(0).id();
        String createdAt = claimed.get(0).createdAt();

        // --- Step 2: lock both wallets, ascending id order, no exceptions. ---
        String lo = req.fromWalletId();
        String hi = req.toWalletId();
        if (lo.compareTo(hi) > 0) {
            String swap = lo;
            lo = hi;
            hi = swap;
        }
        long loBalance = lockWallet(lo);
        long hiBalance = lockWallet(hi);
        long fromBalance = req.fromWalletId().equals(lo) ? loBalance : hiBalance;

        // --- Step 3: decide, while both rows are still locked. ---
        if (fromBalance < req.amountPaise()) {
            String completedAt = jdbc.queryForObject("""
                    UPDATE transfers
                       SET status = 'declined', decline_reason = ?, completed_at = now()
                     WHERE id = ?::uuid
                    RETURNING completed_at::text
                    """, String.class, Money.REASON_INSUFFICIENT_FUNDS, transferId);

            return new TransferResult(new Transfer(transferId, Money.STATUS_DECLINED,
                    Money.REASON_INSUFFICIENT_FUNDS, req.fromWalletId(), req.toWalletId(),
                    req.amountPaise(), req.idempotencyKey(), createdAt, completedAt), false);
        }

        // --- Step 4: apply. ---
        // The `AND balance_paise >= ?` predicate is redundant while the lock above
        // stands. It is kept so the debit remains atomically safe if someone later
        // removes the lock, with the CHECK constraint as a third layer under both.
        int debited = jdbc.update("""
                UPDATE wallets SET balance_paise = balance_paise - ?, updated_at = now()
                 WHERE id = ?::uuid AND balance_paise >= ?
                """, req.amountPaise(), req.fromWalletId(), req.amountPaise());

        if (debited != 1) {
            // Unreachable while the row lock above stands. If it ever fires,
            // something has broken the locking discipline; abort loudly rather
            // than credit money we failed to debit.
            throw new StoreException(StoreException.Kind.INVARIANT_VIOLATION,
                    "conditional debit affected " + debited + " rows while holding the row lock");
        }

        int credited = jdbc.update("""
                UPDATE wallets SET balance_paise = balance_paise + ?, updated_at = now()
                 WHERE id = ?::uuid
                """, req.amountPaise(), req.toWalletId());

        if (credited != 1) {
            throw StoreException.walletNotFound();
        }

        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, wallet_id, delta_paise)
                VALUES (?::uuid, ?::uuid, ?), (?::uuid, ?::uuid, ?)
                """,
                transferId, req.fromWalletId(), -req.amountPaise(),
                transferId, req.toWalletId(), req.amountPaise());

        String completedAt = jdbc.queryForObject("""
                UPDATE transfers SET status = 'succeeded', completed_at = now()
                 WHERE id = ?::uuid
                RETURNING completed_at::text
                """, String.class, transferId);

        return new TransferResult(new Transfer(transferId, Money.STATUS_SUCCEEDED, null,
                req.fromWalletId(), req.toWalletId(), req.amountPaise(),
                req.idempotencyKey(), createdAt, completedAt), false);
    }

    /** Locks one wallet row and returns its balance. */
    private long lockWallet(String walletId) {
        try {
            Long balance = jdbc.queryForObject("""
                    SELECT balance_paise FROM wallets WHERE id = ?::uuid FOR NO KEY UPDATE
                    """, Long.class, walletId);
            return balance == null ? 0L : balance;
        } catch (EmptyResultDataAccessException e) {
            throw StoreException.walletNotFound();
        }
    }

    /** Returns the stored outcome for an already-used key, or reports a conflict. */
    private TransferResult replay(TransferRequest req, byte[] fingerprint) {
        StoredTransfer existing = jdbc.queryForObject("""
                SELECT id::text, status, decline_reason, from_wallet_id::text, to_wallet_id::text,
                       amount_paise, idempotency_key, created_at::text, completed_at::text,
                       request_fingerprint
                  FROM transfers
                 WHERE requester_user_id = ?::uuid AND idempotency_key = ?
                """, STORED_MAPPER, req.requesterUserId(), req.idempotencyKey());

        if (existing == null) {
            throw StoreException.transferNotFound();
        }
        if (!Arrays.equals(existing.fingerprint(), fingerprint)) {
            throw StoreException.idempotencyConflict();
        }
        return new TransferResult(existing.transfer(), true);
    }

    public Transfer getTransfer(String id) {
        if (!WalletRepository.isUuid(id)) {
            throw StoreException.transferNotFound();
        }
        try {
            StoredTransfer t = jdbc.queryForObject("""
                    SELECT id::text, status, decline_reason, from_wallet_id::text, to_wallet_id::text,
                           amount_paise, idempotency_key, created_at::text, completed_at::text,
                           request_fingerprint
                      FROM transfers WHERE id = ?::uuid
                    """, STORED_MAPPER, id);
            return t.transfer();
        } catch (EmptyResultDataAccessException e) {
            throw StoreException.transferNotFound();
        }
    }

    public Wallet getWallet(String id) {
        return wallets.getWallet(id);
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private record TransferRow(String id, String createdAt) {}

    private record StoredTransfer(Transfer transfer, byte[] fingerprint) {}

    private static final org.springframework.jdbc.core.RowMapper<StoredTransfer> STORED_MAPPER =
            (rs, n) -> new StoredTransfer(
                    new Transfer(
                            rs.getString("id"),
                            rs.getString("status"),
                            rs.getString("decline_reason"),
                            rs.getString("from_wallet_id"),
                            rs.getString("to_wallet_id"),
                            rs.getLong("amount_paise"),
                            rs.getString("idempotency_key"),
                            rs.getString("created_at"),
                            rs.getString("completed_at")),
                    rs.getBytes("request_fingerprint"));

    private static String sqlState(RuntimeException e) {
        if (e instanceof org.springframework.dao.DataAccessException dae
                && dae.getRootCause() instanceof java.sql.SQLException sql) {
            return sql.getSQLState() == null ? "unknown" : sql.getSQLState();
        }
        return "unknown";
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(2L * attempt * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", ie);
        }
    }
}
