package com.dhaval.wallet.store;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A live audit of the properties this service claims to hold, recomputed from
 * the base tables. Exposed over HTTP so a reviewer can check them against the
 * running system rather than trusting the README.
 */
public record Invariants(
        @JsonProperty("wallet_count") long walletCount,
        @JsonProperty("total_balance_paise") long totalBalancePaise,
        @JsonProperty("total_minted_paise") long totalMintedPaise,
        @JsonProperty("ledger_sum_paise") long ledgerSumPaise,
        @JsonProperty("negative_balance_wallets") long negativeBalanceWallets,
        @JsonProperty("transfers_succeeded") long transfersSucceeded,
        @JsonProperty("transfers_declined") long transfersDeclined,
        @JsonProperty("transfers_stuck_pending") long transfersStuckPending,
        @JsonProperty("conservation_holds") boolean conservationHolds,
        @JsonProperty("no_overdraft_holds") boolean noOverdraftHolds,
        @JsonProperty("all_invariants_hold") boolean allInvariantsHold) {

    public static Invariants of(long wallets, long totalBalance, long totalMinted, long ledgerSum,
                                long negatives, long succeeded, long declined, long pending) {

        // Conservation holds when balances equal what was minted -- transfers moved
        // money without creating it -- and the double-entry ledger nets to zero.
        boolean conservation = totalBalance == totalMinted && ledgerSum == 0;
        boolean noOverdraft = negatives == 0;

        return new Invariants(wallets, totalBalance, totalMinted, ledgerSum, negatives,
                succeeded, declined, pending,
                conservation, noOverdraft,
                conservation && noOverdraft && pending == 0);
    }
}
