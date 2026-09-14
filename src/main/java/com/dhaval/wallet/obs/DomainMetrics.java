package com.dhaval.wallet.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The domain counters the exercise asks for, on top of the HTTP rate/latency/
 * error metrics Micrometer already records for every request.
 *
 * <p>Counters are resolved once at construction rather than looked up per call:
 * these fire on the money path and a registry lookup per transfer is wasted work.
 */
@Component
public class DomainMetrics {

    public static final String SUCCEEDED = "succeeded";
    public static final String INSUFFICIENT = "declined_insufficient_funds";
    public static final String REPLAY = "idempotent_replay";
    public static final String CONFLICT = "idempotency_key_conflict";
    public static final String INVALID = "rejected_invalid";

    private final MeterRegistry registry;

    private final Counter succeeded;
    private final Counter insufficient;
    private final Counter replay;
    private final Counter conflict;
    private final Counter invalid;
    private final Counter transferredPaise;
    private final Counter walletsCreated;
    private final Counter walletsReused;
    private final Counter mintedPaise;

    /** Invariant totals, refreshed by the sampler and by every /invariants call. */
    private final AtomicLong totalBalancePaise = new AtomicLong();
    private final AtomicLong ledgerSumPaise = new AtomicLong();

    public DomainMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.succeeded = transferCounter(SUCCEEDED);
        this.insufficient = transferCounter(INSUFFICIENT);
        this.replay = transferCounter(REPLAY);
        this.conflict = transferCounter(CONFLICT);
        this.invalid = transferCounter(INVALID);

        this.transferredPaise = Counter.builder("wallet.transferred.paise")
                .description("Total paise moved by succeeded transfers")
                .register(registry);
        // NOT "wallet.wallets.created": the Prometheus client treats a trailing
        // _created as the reserved OpenMetrics companion series and strips it,
        // which silently renamed this to wallet_wallets_total -- a name that reads
        // like a gauge of how many wallets exist. "provisioned" says the same
        // thing and survives the naming convention intact.
        this.walletsCreated = Counter.builder("wallet.wallets.provisioned")
                .description("Wallets actually created by get-or-create")
                .register(registry);
        this.walletsReused = Counter.builder("wallet.wallets.reused")
                .description("Get-or-create calls that returned an existing wallet")
                .register(registry);
        this.mintedPaise = Counter.builder("wallet.minted.paise")
                .description("Total paise minted into the system from outside")
                .register(registry);

        registry.gauge("wallet.total.balance.paise", totalBalancePaise, AtomicLong::doubleValue);
        registry.gauge("wallet.ledger.sum.paise", ledgerSumPaise, AtomicLong::doubleValue);
    }

    private Counter transferCounter(String outcome) {
        return Counter.builder("wallet.transfers")
                .description("Transfer attempts by terminal outcome")
                .tags(Tags.of("outcome", outcome))
                .register(registry);
    }

    public void transferSucceeded(long paise) {
        succeeded.increment();
        transferredPaise.increment(paise);
    }

    public void transferDeclinedInsufficientFunds() {
        insufficient.increment();
    }

    public void idempotentReplay() {
        replay.increment();
    }

    public void idempotencyConflict() {
        conflict.increment();
    }

    public void rejectedInvalid() {
        invalid.increment();
    }

    public void walletCreated() {
        walletsCreated.increment();
    }

    public void walletReused() {
        walletsReused.increment();
    }

    public void minted(long paise) {
        mintedPaise.increment(paise);
    }

    /**
     * Counts a transaction retried after a transient Postgres error. Staying at
     * zero is the standing evidence that the sorted-lock discipline is preventing
     * deadlocks rather than the retry loop papering over them.
     */
    public void dbRetry(String sqlState) {
        registry.counter("wallet.db.retries", "sqlstate", sqlState == null ? "unknown" : sqlState)
                .increment();
    }

    /**
     * Carries the counters that survive in Postgres across a process restart up
     * to their durable value. Called once at startup, before the app serves
     * anything, so nothing is double-counted: every later increment is a genuinely
     * new event on top of this floor.
     *
     * <p>A counter that steps up at startup rather than resetting to zero is
     * deliberate. The usual Prometheus contract is the opposite -- a reset is the
     * signal a scraper uses to detect a restart -- but nothing scrapes this
     * instance on a free tier; the counters are read straight off {@code /metrics}
     * by a human. Matching the ledger is worth more here than preserving a
     * rate() calculation nobody is running.
     */
    public void seedFromLedger(long walletCount, long succeededCount, long declinedCount,
                               long transferredTotal, long mintedTotal) {
        increment(walletsCreated, walletCount);
        increment(succeeded, succeededCount);
        increment(insufficient, declinedCount);
        increment(transferredPaise, transferredTotal);
        increment(mintedPaise, mintedTotal);
    }

    private static void increment(Counter counter, long amount) {
        if (amount > 0) {
            counter.increment(amount);
        }
    }

    public void recordInvariants(long totalBalance, long ledgerSum) {
        totalBalancePaise.set(totalBalance);
        ledgerSumPaise.set(ledgerSum);
    }
}
