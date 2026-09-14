package com.dhaval.wallet;

import com.dhaval.wallet.obs.DomainMetrics;
import com.dhaval.wallet.store.Invariants;
import com.dhaval.wallet.store.TransferService;
import com.dhaval.wallet.store.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dhaval.wallet.config.DatabaseUrl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@SpringBootApplication
@EnableScheduling
public class WalletApplication {

    public static void main(String[] args) {
        // --healthcheck turns the same jar into its own health probe, so the
        // Docker HEALTHCHECK needs nothing installed in the runtime image.
        for (String arg : args) {
            if ("--healthcheck".equals(arg)) {
                System.exit(HealthProbe.run());
            }
        }
        // Managed hosts hand out a libpq URL; JDBC needs a jdbc: one plus
        // separate credentials. Translate before the DataSource is built.
        //
        // Set as system properties rather than via SpringApplicationBuilder
        // .properties(): that registers "default properties", which sit BELOW
        // application.yml in Spring's precedence order, so the yml placeholder
        // would win and the raw libpq URL would reach Hikari. The system
        // property source sits above application.yml, which is what we need.
        DatabaseUrl.fromEnvironment().forEach((key, value) ->
                System.setProperty(key, String.valueOf(value)));

        SpringApplication.run(WalletApplication.class, args);
    }

    /** Wires the retry counter into the transfer service once both beans exist. */
    @Bean
    org.springframework.boot.CommandLineRunner wireRetryMetrics(TransferService transfers,
                                                               DomainMetrics metrics) {
        Logger log = LoggerFactory.getLogger(WalletApplication.class);
        return args -> transfers.onRetry(sqlState -> {
            metrics.dbRetry(sqlState);
            log.atWarn()
                    .addKeyValue("event", "db_tx_retry")
                    .addKeyValue("sqlstate", sqlState)
                    .log("retrying transaction after transient postgres error");
        });
    }

    /**
     * Recovers the domain counters from Postgres before the first request lands.
     *
     * <p>Without this, a restart -- which on a free instance happens every time it
     * sleeps -- leaves {@code /metrics} reporting zero transfers beside an
     * {@code /invariants} reporting thousands. Same service, same second, two
     * numbers that cannot both be right.
     */
    @Bean
    org.springframework.boot.CommandLineRunner seedDomainCounters(WalletRepository wallets,
                                                                 DomainMetrics metrics) {
        Logger log = LoggerFactory.getLogger(WalletApplication.class);
        return args -> {
            try {
                WalletRepository.CounterBaseline base = wallets.counterBaseline();
                metrics.seedFromLedger(base.wallets(), base.succeeded(), base.declined(),
                        base.transferredPaise(), base.mintedPaise());
                log.atInfo()
                        .addKeyValue("event", "counters_seeded")
                        .addKeyValue("wallets", base.wallets())
                        .addKeyValue("transfers_succeeded", base.succeeded())
                        .addKeyValue("transfers_declined", base.declined())
                        .addKeyValue("transferred_paise", base.transferredPaise())
                        .addKeyValue("minted_paise", base.mintedPaise())
                        .log("domain counters recovered from the ledger");
            } catch (RuntimeException e) {
                // Metrics are observability, not correctness. Start anyway and
                // count from zero rather than refuse to serve money.
                log.atWarn().addKeyValue("event", "counter_seed_failed")
                        .log("could not seed domain counters: {}", e.getMessage());
            }
        };
    }

    /**
     * Keeps the conservation gauges fresh even when nobody is polling
     * /invariants, so a break shows up on the dashboard and in the logs rather
     * than only when someone thinks to look.
     */
    @Component
    static class InvariantSampler {

        private static final Logger log = LoggerFactory.getLogger(InvariantSampler.class);

        private final WalletRepository wallets;
        private final DomainMetrics metrics;

        InvariantSampler(WalletRepository wallets, DomainMetrics metrics) {
            this.wallets = wallets;
            this.metrics = metrics;
        }

        @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${app.invariant-sample:PT15S}")
        void sample() {
            try {
                Invariants inv = wallets.checkInvariants();
                metrics.recordInvariants(inv.totalBalancePaise(), inv.ledgerSumPaise());

                if (!inv.allInvariantsHold()) {
                    log.atError()
                            .addKeyValue("event", "invariant_violation")
                            .addKeyValue("conservation", inv.conservationHolds())
                            .addKeyValue("no_overdraft", inv.noOverdraftHolds())
                            .addKeyValue("total_balance_paise", inv.totalBalancePaise())
                            .addKeyValue("total_minted_paise", inv.totalMintedPaise())
                            .addKeyValue("ledger_sum_paise", inv.ledgerSumPaise())
                            .addKeyValue("negative_balance_wallets", inv.negativeBalanceWallets())
                            .log("INVARIANT VIOLATION");
                }
            } catch (RuntimeException e) {
                // A sampler must never take the process down.
                log.atWarn().addKeyValue("event", "invariant_sample_failed")
                        .log("could not sample invariants: {}", e.getMessage());
            }
        }
    }
}
