package com.dhaval.wallet;

import com.dhaval.wallet.config.DatabaseUrl;
import com.dhaval.wallet.store.Invariants;
import com.dhaval.wallet.store.Money.TransferRequest;
import com.dhaval.wallet.store.Money.TransferResult;
import com.dhaval.wallet.store.Money.User;
import com.dhaval.wallet.store.Money.Wallet;
import com.dhaval.wallet.store.Money;
import com.dhaval.wallet.store.StoreException;
import com.dhaval.wallet.store.TransferService;
import com.dhaval.wallet.store.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests, on purpose.
 *
 * <p>The invariants this service claims live in Postgres -- in unique indexes, row
 * locks and CHECK constraints -- so a test with a mocked database would verify
 * nothing that matters. Set {@code TEST_DATABASE_URL} to run them; {@code make
 * test} and CI both do, and without it the whole class is skipped rather than
 * failing for the wrong reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class InvariantTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        Map<String, Object> translated = DatabaseUrl.translate(System.getenv("TEST_DATABASE_URL"));
        if (translated.isEmpty()) {
            registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        } else {
            translated.forEach((key, value) -> registry.add(key, () -> value));
        }
    }

    @Autowired
    WalletRepository wallets;

    @Autowired
    TransferService transfers;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Wallet freshWallet(TestInfo info, long paise) {
        String token = "tok_" + info.getTestMethod().map(m -> m.getName()).orElse("x")
                + "_" + System.nanoTime();
        User user = wallets.upsertUser(token);
        Wallet w = wallets.getOrCreateWallet(user.id()).wallet();
        if (paise > 0) {
            w = wallets.mint(w.id(), "mint_" + w.id(), paise).wallet();
        }
        return w;
    }

    private long balance(String walletId) {
        return wallets.getWallet(walletId).balancePaise();
    }

    /** Runs {@code task} on {@code n} threads released at the same instant. */
    private void race(int n, java.util.function.IntConsumer task) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);

        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        task.accept(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "workers never became ready");
            go.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "workers did not finish in time");
        }
    }

    // ------------------------------------------------------------------
    // Invariant 4 -- race-free get-or-create
    // ------------------------------------------------------------------

    @Test
    void concurrentGetOrCreateYieldsExactlyOneWallet(TestInfo info) throws Exception {
        User user = wallets.upsertUser("tok_goc_" + System.nanoTime());

        Map<String, Boolean> ids = new ConcurrentHashMap<>();
        AtomicInteger created = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        race(50, i -> {
            try {
                WalletRepository.GetOrCreate result = wallets.getOrCreateWallet(user.id());
                ids.put(result.wallet().id(), true);
                if (result.created()) {
                    created.incrementAndGet();
                }
            } catch (RuntimeException e) {
                errors.add(e);
            }
        });

        assertTrue(errors.isEmpty(), () -> errors.size() + " calls errored, first: " + errors.get(0));
        assertEquals(1, ids.size(),
                "expected exactly one wallet across 50 concurrent creates, got " + ids.keySet());
        assertEquals(1, created.get(), "expected exactly one caller to report created=true");
    }

    // ------------------------------------------------------------------
    // Invariant 3 -- exactly-once transfer
    // ------------------------------------------------------------------

    @Test
    void idempotentRetryStormAppliesExactlyOneDebit(TestInfo info) throws Exception {
        Wallet from = freshWallet(info, 100_000);
        Wallet to = freshWallet(info, 0);

        final int storm = 30;
        final long amount = 7_777;
        String key = "storm_" + System.nanoTime();

        List<TransferResult> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        race(storm, i -> {
            try {
                results.add(transfers.transfer(new TransferRequest(
                        from.userId(), from.id(), to.id(), amount, key)));
            } catch (RuntimeException e) {
                errors.add(e);
            }
        });

        assertTrue(errors.isEmpty(), () -> errors.size() + " errored, first: " + errors.get(0));
        assertEquals(storm, results.size());

        String firstId = results.get(0).transfer().id();
        long realWork = results.stream().filter(r -> !r.replay()).count();

        for (TransferResult r : results) {
            assertEquals(firstId, r.transfer().id(),
                    "a response carried a different transfer id -- the key applied more than once");
            assertEquals(results.get(0).transfer().status(), r.transfer().status());
        }
        assertEquals(1, realWork, "exactly one caller should have done the work");

        assertEquals(100_000 - amount, balance(from.id()),
                "source balance wrong -- more than one debit was applied");
        assertEquals(amount, balance(to.id()));
    }

    @Test
    void sameKeyDifferentBodyIsConflict(TestInfo info) {
        Wallet from = freshWallet(info, 50_000);
        Wallet to = freshWallet(info, 0);
        String key = "conflict_" + System.nanoTime();

        transfers.transfer(new TransferRequest(from.userId(), from.id(), to.id(), 1_000, key));

        StoreException e = assertThrows(StoreException.class, () ->
                transfers.transfer(new TransferRequest(from.userId(), from.id(), to.id(), 2_000, key)));

        assertEquals(StoreException.Kind.IDEMPOTENCY_CONFLICT, e.kind());
        assertEquals(49_000, balance(from.id()), "the conflicting replay moved money");
    }

    @Test
    void declineIsAlsoIdempotent(TestInfo info) {
        Wallet from = freshWallet(info, 100);
        Wallet to = freshWallet(info, 0);
        String key = "decline_" + System.nanoTime();
        TransferRequest req = new TransferRequest(from.userId(), from.id(), to.id(), 999_999, key);

        TransferResult first = transfers.transfer(req);
        assertEquals(Money.STATUS_DECLINED, first.transfer().status());

        // The retry must return the identical stored decision, not re-evaluate it.
        TransferResult second = transfers.transfer(req);
        assertTrue(second.replay(), "retry of a decline was not reported as a replay");
        assertEquals(first.transfer().id(), second.transfer().id());
        assertEquals(Money.STATUS_DECLINED, second.transfer().status());
        assertEquals(100, balance(from.id()), "a declined transfer moved money");
    }

    // ------------------------------------------------------------------
    // Invariants 1 and 2 -- conservation and no overdraft, under contention
    // ------------------------------------------------------------------

    @Test
    void conservationAndNoOverdraftUnderContention(TestInfo info) throws Exception {
        final int nWallets = 6;
        final long seed = 200_000;
        final int nMoves = 600;

        Wallet[] set = new Wallet[nWallets];
        for (int i = 0; i < nWallets; i++) {
            set[i] = freshWallet(info, seed);
        }
        long totalBefore = 0;
        for (Wallet w : set) {
            totalBefore += balance(w.id());
        }

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicInteger cursor = new AtomicInteger();

        race(40, worker -> {
            int n;
            while ((n = cursor.getAndIncrement()) < nMoves) {
                int a = n % nWallets;
                int b = (n + 1) % nWallets;
                // Every fourth job runs the pair backwards, so A->B and B->A are in
                // flight at once -- the case that deadlocks an unordered locker.
                if (n % 4 == 0) {
                    int swap = a;
                    a = b;
                    b = swap;
                }
                // Every seventh tries to move more than exists anywhere: must decline.
                long amount = (n % 7 == 0) ? seed * nWallets * 2 : (n * 13L) % 900 + 100;

                try {
                    TransferResult r = transfers.transfer(new TransferRequest(
                            set[a].userId(), set[a].id(), set[b].id(), amount,
                            "contend_" + System.nanoTime() + "_" + n));
                    if (Money.STATUS_SUCCEEDED.equals(r.transfer().status())) {
                        succeeded.incrementAndGet();
                    } else {
                        declined.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    errors.add(e);
                }
            }
        });

        // A deadlock that escapes the retry budget surfaces here. The point of the
        // sorted FOR NO KEY UPDATE is that this list stays empty.
        assertTrue(errors.isEmpty(),
                () -> errors.size() + "/" + nMoves + " transfers errored, first: " + errors.get(0));
        assertNotEquals(0, declined.get(), "expected some overdraft declines, got none");

        long totalAfter = 0;
        for (Wallet w : set) {
            long b = balance(w.id());
            assertTrue(b >= 0, "wallet " + w.id() + " went negative: " + b);
            totalAfter += b;
        }
        assertEquals(totalBefore, totalAfter,
                "CONSERVATION BROKEN across " + succeeded.get() + " successful transfers");

        Invariants inv = wallets.checkInvariants();
        assertTrue(inv.allInvariantsHold(), "server-side invariant audit failed: " + inv);
    }

    /**
     * The sharpest no-overdraft check: 40 concurrent claimants against a balance
     * that can fund exactly 10. The naive read-modify-write implementation passes
     * every other test here and fails this one.
     */
    @Test
    void drainRaceNeverOverdraws(TestInfo info) throws Exception {
        final long start = 10_000;
        final long amount = 1_000;
        final int racers = 40;
        final int expectedWinners = (int) (start / amount);

        Wallet from = freshWallet(info, start);
        Wallet to = freshWallet(info, 0);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        race(racers, i -> {
            try {
                TransferResult r = transfers.transfer(new TransferRequest(
                        from.userId(), from.id(), to.id(), amount,
                        "drain_" + System.nanoTime() + "_" + i));
                if (Money.STATUS_SUCCEEDED.equals(r.transfer().status())) {
                    succeeded.incrementAndGet();
                } else {
                    declined.incrementAndGet();
                }
            } catch (RuntimeException e) {
                errors.add(e);
            }
        });

        assertTrue(errors.isEmpty(), () -> errors.size() + " errored, first: " + errors.get(0));
        assertEquals(expectedWinners, succeeded.get(),
                "exactly " + expectedWinners + " transfers should succeed against a "
                        + start + "-paise balance");
        assertEquals(0, balance(from.id()));
        assertEquals(expectedWinners * amount, balance(to.id()));
    }

    /** The degenerate case that would otherwise lock the same row twice. */
    @Test
    void selfTransferRejected(TestInfo info) {
        Wallet w = freshWallet(info, 1_000);

        StoreException e = assertThrows(StoreException.class, () ->
                transfers.transfer(new TransferRequest(
                        w.userId(), w.id(), w.id(), 100, "self_" + System.nanoTime())));

        assertEquals(StoreException.Kind.SAME_WALLET, e.kind());
        assertEquals(1_000, balance(w.id()), "self-transfer changed the balance");
    }

    /** The libpq -> JDBC translation that every managed Postgres host makes necessary. */
    @Test
    void translatesLibpqUrlToJdbc() {
        Map<String, Object> p = DatabaseUrl.translate(
                "postgresql://alice:s3cr%40et@db.example.com:5433/wallet?sslmode=require");

        assertEquals("jdbc:postgresql://db.example.com:5433/wallet?sslmode=require",
                p.get("spring.datasource.url"));
        assertEquals("alice", p.get("spring.datasource.username"));
        assertEquals("s3cr@et", p.get("spring.datasource.password"), "password must be URL-decoded");

        assertTrue(DatabaseUrl.translate("jdbc:postgresql://localhost/x").isEmpty(),
                "an existing JDBC url must pass through untouched");
        assertTrue(DatabaseUrl.translate(null).isEmpty());
    }
}
